package okay.data

import okay.effects.json.Json

/**
 * THE PROGRAM THE MODEL WRITES — data, not code (the effects rule: the model's answer is a query; handlers run it).
 * Relational: from a table, optional join, where, group with aggregates, select, order, limit. Logical: Datalog
 * rules over the tables as relations, and a goal. Read from the model's JSON; checked against the tables' schema
 * before it runs, the problems told back to the model in words.
 */
enum Cond:
  case Cmp(column: String, op: String, value: Value)   // = != < <= > >=
  case Has(column: String, text: String)                // contains, ignoring case
  case Empty(column: String, is: Boolean)

final case class Agg(fn: String, column: String, as: String)  // count sum avg min max; count's column may be "*"
final case class Order(column: String, desc: Boolean)
final case class Join(table: String, on: Vector[(String, String)])

enum Query:
  case Relational(from: String, join: Option[Join], where: Vector[Cond], group: Vector[String], aggs: Vector[Agg],
    select: Vector[String], order: Vector[Order], limit: Option[Int])
  case Logical(rules: String, goal: String)

object Query:
  /** the model's JSON: {"from": .., "join": {"table": .., "on": [["a.x","b.y"]]}, "where": [{"column", "op",
   * "value"}], "group": [..], "aggregate": [{"fn", "column", "as"}], "select": [..], "order": [{"column", "desc"}],
   * "limit": n} or {"datalog": "rules…", "goal": "p(X, Y)"} */
  def parse(text: String): Either[String, Query] =
    (try Right(Json.fromText(text)) catch { case e: Exception => Left(s"not JSON: ${e.getMessage}") }).flatMap {
      case o: Json.Obj => of(o)
      case _ => Left("a JSON object expected")
    }

  private def str(o: Json.Obj, k: String): Option[String] = o.fields.collectFirst { case (`k`, Json.Str(s)) => s }
  private def arr(o: Json.Obj, k: String): Vector[Json] = o.fields.collectFirst { case (`k`, Json.Arr(xs)) => xs.toVector }.getOrElse(Vector.empty)
  private def num(o: Json.Obj, k: String): Option[Int] = o.fields.collectFirst { case (`k`, Json.Num(n)) => n.toInt }
  private def obj(o: Json.Obj, k: String): Option[Json.Obj] = o.fields.collectFirst { case (`k`, x: Json.Obj) => x }
  private def value(j: Json): Value = j match
    case Json.Num(n) => Value.Num(n.toDouble)
    case Json.Str(s) => Value.of(s)
    case Json.Bool(b) => Value.Str(b.toString)
    case _ => Value.Empty

  def of(o: Json.Obj): Either[String, Query] =
    (str(o, "datalog"), str(o, "goal")) match
      case (Some(rules), Some(goal)) => Right(Query.Logical(rules, goal))
      case (Some(_), None) => Left("a datalog program needs a goal")
      case _ =>
        str(o, "from").toRight("\"from\": the table").map { from =>
          val join = obj(o, "join").flatMap(j => str(j, "table").map(t => Join(t, arr(j, "on").collect {
            case Json.Arr(Vector(Json.Str(a), Json.Str(b))) => (a, b) })))
          val where = arr(o, "where").collect { case c: Json.Obj =>
            (str(c, "column"), str(c, "op")) match
              case (Some(col), Some("contains")) => Cond.Has(col, str(c, "value").getOrElse(""))
              case (Some(col), Some("empty")) => Cond.Empty(col, true)
              case (Some(col), Some("not empty")) => Cond.Empty(col, false)
              case (Some(col), Some(op)) => Cond.Cmp(col, op, c.fields.collectFirst { case ("value", v) => value(v) }.getOrElse(Value.Empty))
              case _ => Cond.Empty("?", true)
          }
          val group = arr(o, "group").collect { case Json.Str(s) => s }
          val aggs = arr(o, "aggregate").collect { case a: Json.Obj =>
            val fn = str(a, "fn").getOrElse("count"); val col = str(a, "column").getOrElse("*")
            Agg(fn, col, str(a, "as").getOrElse(if col == "*" then fn else s"${fn}_$col")) }
          val select = arr(o, "select").collect { case Json.Str(s) => s }
          val order = arr(o, "order").collect {
            case Json.Str(s) => Order(s.stripPrefix("-"), s.startsWith("-"))
            case x: Json.Obj => Order(str(x, "column").getOrElse(""), x.fields.exists { case ("desc", Json.Bool(true)) => true; case _ => false })
          }
          Query.Relational(from, join, where, group, aggs, select, order, num(o, "limit"))
        }

  /** the program as JSON text — shown with its result, so the answer is checkable */
  def print(q: Query): String = q match
    case Query.Logical(rules, goal) => Json.print(Json.obj("datalog" -> Json.Str(rules), "goal" -> Json.Str(goal)))
    case Query.Relational(from, join, where, group, aggs, select, order, limit) =>
      val parts = Vector.newBuilder[(String, Json)]
      parts += "from" -> Json.Str(from)
      join.foreach(j => parts += "join" -> Json.obj("table" -> Json.Str(j.table),
        "on" -> Json.Arr(j.on.map((a, b) => Json.Arr(Vector(Json.Str(a), Json.Str(b)))))))
      if where.nonEmpty then parts += "where" -> Json.Arr(where.map {
        case Cond.Cmp(c, op, v) => Json.obj("column" -> Json.Str(c), "op" -> Json.Str(op), "value" -> (v match
          case Value.Num(d) => Json.Num(BigDecimal(d)); case other => Json.Str(other.text)))
        case Cond.Has(c, t) => Json.obj("column" -> Json.Str(c), "op" -> Json.Str("contains"), "value" -> Json.Str(t))
        case Cond.Empty(c, is) => Json.obj("column" -> Json.Str(c), "op" -> Json.Str(if is then "empty" else "not empty"))
      })
      if group.nonEmpty then parts += "group" -> Json.Arr(group.map(Json.Str.apply))
      if aggs.nonEmpty then parts += "aggregate" -> Json.Arr(aggs.map(a => Json.obj("fn" -> Json.Str(a.fn),
        "column" -> Json.Str(a.column), "as" -> Json.Str(a.as))))
      if select.nonEmpty then parts += "select" -> Json.Arr(select.map(Json.Str.apply))
      if order.nonEmpty then parts += "order" -> Json.Arr(order.map(o => Json.Str((if o.desc then "-" else "") + o.column)))
      limit.foreach(l => parts += "limit" -> Json.Num(BigDecimal(l)))
      Json.print(Json.Obj(parts.result()))
