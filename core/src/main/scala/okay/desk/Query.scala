package okay.desk

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
  // one column against another of the same row: quantity < reorder_level (specs/direction.md §2)
  case Col(column: String, op: String, other: String)

/** count sum avg min max (count's column may be "*"); growth and change — the last value against the first in the
 * order of `by` (a time column) within each group: growth as a fraction of the first, change as a difference */
final case class Agg(fn: String, column: String, as: String, by: Option[String] = None,
    // the rows this aggregate is over, within its group (SQL's FILTER): a part beside its whole, one month beside
    // another (specs/direction.md §2)
    where: Vector[Cond] = Vector.empty)
/** a value computed on each row of the result from its numbers: `part / total`, `march - january`, `price * units` */
final case class Compute(as: String, expr: String)
final case class Order(column: String, desc: Boolean)
final case class Join(table: String, on: Vector[(String, String)])

enum Query:
  case Relational(from: String, join: Option[Join], where: Vector[Cond], group: Vector[String], aggs: Vector[Agg],
    select: Vector[String], order: Vector[Order], limit: Option[Int],
    // after the aggregates: values computed on each row, then conditions on the result's rows (SQL's HAVING)
    compute: Vector[Compute] = Vector.empty, having: Vector[Cond] = Vector.empty)
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

  def of(o0: Json.Obj): Either[String, Query] =
    // a wrapper some models add ({"query": {...}}) unwrapped
    val o = obj(o0, "query").getOrElse(o0)
    (str(o, "datalog"), str(o, "goal")) match
      case (Some(rules), Some(goal)) => Right(Query.Logical(rules, goal))
      case (Some(_), None) => Left("a datalog program needs a goal")
      case _ =>
        str(o, "from").toRight("\"from\": the table").map { from =>
          // "join" an object, or a list of them (the first taken)
          val joinObj = obj(o, "join").orElse(arr(o, "join").collectFirst { case j: Json.Obj => j })
          val join = joinObj.flatMap(j => str(j, "table").map(t => Join(t, arr(j, "on").collect {
            case Json.Arr(Vector(Json.Str(a), Json.Str(b))) => (a, b) })))
          val where = conds(arr(o, "where"))
          val group = arr(o, "group").collect { case Json.Str(s) => s }
          val aggs = arr(o, "aggregate").collect { case a: Json.Obj =>
            val fn = str(a, "fn").getOrElse("count"); val col = str(a, "column").getOrElse("*")
            Agg(fn, col, str(a, "as").getOrElse(if col == "*" then fn else s"${fn}_$col"), str(a, "by"),
              conds(arr(a, "where"))) }
          val select = arr(o, "select").collect { case Json.Str(s) => s }
          val order = arr(o, "order").collect {
            case Json.Str(s) => Order(s.stripPrefix("-"), s.startsWith("-"))
            case x: Json.Obj => Order(str(x, "column").getOrElse(""),
              x.fields.exists { case ("desc", Json.Bool(true)) => true; case _ => false })
          }
          val compute = arr(o, "compute").collect { case c: Json.Obj =>
            (str(c, "as"), str(c, "expr")) }.collect { case (Some(as), Some(e)) => Compute(as, e) }
          Query.Relational(from, join, where, group, aggs, select, order, num(o, "limit"), compute,
            conds(arr(o, "having")))
        }

  /** conditions as the model writes them: {"column", "op", "value"} or {"column", "op", "column2"} */
  private def conds(cs: Vector[Json]): Vector[Cond] = cs.collect { case c: Json.Obj => c }.flatMap { c =>
    (str(c, "column"), str(c, "op")) match
      // a month of a date column ("yyyy-mm"): on or after its first day, before the next month's — the
      // calculus's month, the same two conditions (specs/direction.md §2)
      case (Some(col), Some("month")) if str(c, "value").exists(_.matches("\\d{4}-\\d{2}")) =>
        val m = str(c, "value").get
        val (y, mm) = (m.take(4).toInt, m.drop(5).toInt)
        val next = if mm == 12 then f"${y + 1}-01-01" else f"$y-${mm + 1}%02d-01"
        Vector(Cond.Cmp(col, ">=", Value.Str(m + "-01")), Cond.Cmp(col, "<", Value.Str(next)))
      // a shorthand some models write: {"status": "unpaid"} means status = unpaid
      case (None, None) if c.fields.size == 1 =>
        Vector(Cond.Cmp(c.fields.head._1, "=", value(c.fields.head._2)))
      case (Some(col), Some("contains")) => Vector(Cond.Has(col, str(c, "value").getOrElse("")))
      case (Some(col), Some("empty")) => Vector(Cond.Empty(col, true))
      case (Some(col), Some("not empty")) => Vector(Cond.Empty(col, false))
      // one column against another: {"column": "quantity", "op": "<", "column2": "reorder_level"}
      case (Some(col), Some(op)) if str(c, "column2").nonEmpty => Vector(Cond.Col(col, op, str(c, "column2").get))
      case (Some(col), Some(op)) =>
        Vector(Cond.Cmp(col, op, c.fields.collectFirst { case ("value", v) => value(v) }.getOrElse(Value.Empty)))
      case _ => Vector(Cond.Empty("?", true))
  }

  private def condJson(c: Cond): Json = c match
    case Cond.Cmp(c, op, v) => Json.obj("column" -> Json.Str(c), "op" -> Json.Str(op), "value" -> (v match
      case Value.Num(d) => Json.Num(BigDecimal(d)); case other => Json.Str(other.text)))
    case Cond.Has(c, t) => Json.obj("column" -> Json.Str(c), "op" -> Json.Str("contains"), "value" -> Json.Str(t))
    case Cond.Empty(c, is) => Json.obj("column" -> Json.Str(c), "op" -> Json.Str(if is then "empty" else "not empty"))
    case Cond.Col(c, op, other) => Json.obj("column" -> Json.Str(c), "op" -> Json.Str(op), "column2" -> Json.Str(other))

  /** the program as JSON text — shown with its result, so the answer is checkable */
  def print(q: Query): String = q match
    case Query.Logical(rules, goal) => Json.print(Json.obj("datalog" -> Json.Str(rules), "goal" -> Json.Str(goal)))
    case Query.Relational(from, join, where, group, aggs, select, order, limit, compute, having) =>
      val parts = Vector.newBuilder[(String, Json)]
      parts += "from" -> Json.Str(from)
      join.foreach(j => parts += "join" -> Json.obj("table" -> Json.Str(j.table),
        "on" -> Json.Arr(j.on.map((a, b) => Json.Arr(Vector(Json.Str(a), Json.Str(b)))))))
      if where.nonEmpty then parts += "where" -> Json.Arr(where.map(condJson))
      if group.nonEmpty then parts += "group" -> Json.Arr(group.map(Json.Str.apply))
      if aggs.nonEmpty then parts += "aggregate" -> Json.Arr(aggs.map(a => Json.obj((Vector("fn" -> Json.Str(a.fn),
        "column" -> Json.Str(a.column), "as" -> Json.Str(a.as)) ++ a.by.map(b => "by" -> Json.Str(b)) ++
        Option.when(a.where.nonEmpty)("where" -> Json.Arr(a.where.map(condJson))))*)))
      if compute.nonEmpty then parts += "compute" -> Json.Arr(compute.map(c =>
        Json.obj("as" -> Json.Str(c.as), "expr" -> Json.Str(c.expr))))
      if having.nonEmpty then parts += "having" -> Json.Arr(having.map(condJson))
      if select.nonEmpty then parts += "select" -> Json.Arr(select.map(Json.Str.apply))
      if order.nonEmpty then parts += "order" -> Json.Arr(order.map(o => Json.Str((if o.desc then "-" else "") + o.column)))
      limit.foreach(l => parts += "limit" -> Json.Num(BigDecimal(l)))
      Json.print(Json.Obj(parts.result()))
