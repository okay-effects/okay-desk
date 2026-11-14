package okay.desk

/**
 * THE EXACT HANDLER of a relational query: checked against the tables first (every column named must exist —
 * the problems told in words, for the model to fix), then run: join, where, group (a column, or a date's month,
 * week or year) and aggregate (each over its own rows where it has conditions), computed values, having, select,
 * order, limit. Numbers come from here and from nowhere else.
 */
object Exact:
  def run(tables: Map[String, Table], asked: Query.Relational): Either[String, Table] =
    // a group's date part named as it was grouped ("month(date)") where its column is meant (order, select, having)
    def named(n: String) = if part(n).nonEmpty then keyName(n) else n
    def renamed(c: Cond): Cond = c match
      case Cond.Cmp(col, op, v) => Cond.Cmp(named(col), op, v)
      case Cond.Col(col, op, other) => Cond.Col(named(col), op, named(other))
      case other => other
    val q = asked.copy(select = asked.select.map(named), order = asked.order.map(o => o.copy(column = named(o.column))),
      having = asked.having.map(renamed))
    for
      base <- tables.get(q.from).toRight(s"no table ${q.from}; the tables: ${tables.keys.toVector.sorted.mkString(", ")}")
      joined <- q.join.fold[Either[String, Table]](Right(base))(j => join(base, tables, j))
      _ <- check(joined, q)
      filtered = joined.rows.filter(r => q.where.forall(holds(joined, r, _)))
      grouped =
        if q.group.isEmpty && q.aggs.isEmpty then Table(q.from, joined.columns, filtered)
        else aggregate(joined, filtered, q.group, q.aggs)
      computed <- q.compute.foldLeft[Either[String, Table]](Right(grouped)) { (acc, c) => acc.flatMap { t =>
        Expr.parse(c.expr).flatMap { e =>
          val missing = e.names.filterNot(n => t.column(n).isDefined)
          if missing.nonEmpty then
            Left(s"compute ${c.as}: no column ${missing.mkString(", ")}; " +
              s"the result's columns: ${t.columns.mkString(", ")}")
          else Right(Table(t.name, t.columns :+ c.as, t.rows.map(r =>
            r :+ e.value(n => r(t.column(n).get).num).fold(Value.Empty)(Value.Num(_)))))
        } } }
      _ <- missingIn(computed, q.having.flatMap(columnsOf), "having")
      _ <- missingIn(computed, q.select ++ q.order.map(_.column), "select or order")
    yield
      val kept = computed.rows.filter(r => q.having.forall(holds(computed, r, _)))
      val ordered = q.order.foldRight(kept) { (o, rows) =>
        computed.column(o.column).fold(rows) { i =>
          val sorted = rows.sortBy(_(i))(using summon[Ordering[Value]])
          if o.desc then sorted.reverse else sorted
        }
      }
      val selected = if q.select.isEmpty then computed.columns else q.select
      val idx = selected.map(c => computed.column(c).get)
      val limited = q.limit.fold(ordered)(ordered.take)
      Table("result", selected, limited.map(r => idx.map(r)))

  private def columnsOf(c: Cond): Vector[String] = c match
    case Cond.Cmp(c, _, _) => Vector(c)
    case Cond.Has(c, _) => Vector(c)
    case Cond.Empty(c, _) => Vector(c)
    case Cond.Col(c, _, other) => Vector(c, other)
    case Cond.In(c, _) => Vector(c)

  private def missingIn(t: Table, names: Vector[String], where: String): Either[String, Unit] =
    val missing = names.filterNot(c => t.column(c).isDefined).distinct
    if missing.isEmpty then Right(())
    else Left(s"no column ${missing.mkString(", ")} for $where; the result's columns: ${t.columns.mkString(", ")}")

  /** A DATE'S PART AS A GROUP: "month(date)" (yyyy-mm), "week(date)" (the Monday it begins), "year(date)"; else the
   * column itself. Its name in the result: month, week, year — or the column's */
  private def part(name: String): Option[(String, String)] =
    "(month|week|year)\\((.+)\\)".r.findFirstMatchIn(name.trim).map(m => (m.group(1), m.group(2).trim))
  private def grouping(name: String): String = part(name).fold(name)(_._2)
  private def keyName(name: String): String = part(name).fold(name.split('.').last)(_._1)
  private def keyOf(kind: String, v: Value): Value =
    val d = v.text
    kind match
      case "month" => if d.length >= 7 then Value.Str(d.take(7)) else v
      case "year" => if d.length >= 4 then Value.Str(d.take(4)) else v
      case _ =>
        "(\\d{4})-(\\d{2})-(\\d{2})".r.findPrefixMatchOf(d).fold(v) { m =>
          // the Monday the date's week begins (by the civil calendar's day numbers: no java.time in the page)
          val day = days(m.group(1).toInt, m.group(2).toInt, m.group(3).toInt)
          Value.Str(date(day - Math.floorMod(day + 3, 7)))
        }

  /** days since 1970-01-01 of a civil date, and back (H. Hinnant's algorithms) */
  private def days(y0: Int, m: Int, d: Int): Long =
    val y = if m <= 2 then y0 - 1 else y0
    val era = Math.floorDiv(y, 400)
    val yoe = y - era * 400
    val doy = (153 * (if m > 2 then m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era.toLong * 146097 + doe - 719468
  private def date(z0: Long): String =
    val z = z0 + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if mp < 10 then mp + 3 else mp - 9
    val y = yoe + era * 400 + (if m <= 2 then 1 else 0)
    f"$y%04d-$m%02d-$d%02d"

  private def check(t: Table, q: Query.Relational): Either[String, Unit] =
    val named = (q.where ++ q.aggs.flatMap(_.where)).flatMap(columnsOf) ++ q.group.map(grouping) ++
      q.aggs.map(_.column).filter(_ != "*") ++ q.aggs.flatMap(_.by)
    val missing = named.filterNot(c => t.column(c).isDefined).distinct
    val ops = Set("=", "!=", "<", "<=", ">", ">=")
    val badOps = (q.where ++ q.aggs.flatMap(_.where) ++ q.having).collect {
      case Cond.Cmp(_, op, _) if !ops(op) => op
      case Cond.Col(_, op, _) if !ops(op) => op }
    val badFns = q.aggs.map(_.fn).filterNot(Set("count", "sum", "avg", "min", "max", "growth", "change").contains)
    val noBy = q.aggs.filter(a => (a.fn == "growth" || a.fn == "change") && a.by.isEmpty).map(_.fn)
    if missing.nonEmpty then Left(s"no column ${missing.mkString(", ")} in ${t.name}; its columns: ${t.columns.mkString(", ")}")
    else if badOps.nonEmpty then Left(s"unknown op ${badOps.mkString(", ")}: use = != < <= > >= contains empty month")
    else if badFns.nonEmpty then Left(s"unknown aggregate ${badFns.mkString(", ")}: use count sum avg min max growth change")
    else if noBy.nonEmpty then Left(s"${noBy.mkString(", ")} needs \"by\": the column that orders the values in time")
    else Right(())

  private def compare(x: Value, v: Value): Int = (x.num, v.num) match
    case (Some(a), Some(b)) => java.lang.Double.compare(a, b)
    case _ => x.text.compareToIgnoreCase(v.text)
  private def test(op: String, cmp: Int): Boolean = op match
    case "=" => cmp == 0
    case "!=" => cmp != 0
    case "<" => cmp < 0
    case "<=" => cmp <= 0
    case ">" => cmp > 0
    case _ => cmp >= 0

  private def holds(t: Table, r: Vector[Value], c: Cond): Boolean = c match
    case Cond.Cmp(col, op, v) => test(op, compare(r(t.column(col).get), v))
    case Cond.Col(col, op, other) =>
      val (x, y) = (r(t.column(col).get), r(t.column(other).get))
      x != Value.Empty && y != Value.Empty && test(op, compare(x, y))
    case Cond.In(col, vs) => vs.exists(v => compare(r(t.column(col).get), v) == 0)
    case Cond.Has(col, text) => r(t.column(col).get).text.toLowerCase.contains(text.toLowerCase)
    case Cond.Empty(col, is) => (r(t.column(col).get) == Value.Empty) == is

  /** an inner join; the other table's columns prefixed `table.` where they clash */
  private def join(base: Table, tables: Map[String, Table], j: Join): Either[String, Table] =
    tables.get(j.table).toRight(s"no table ${j.table}").flatMap { other =>
      val keys = j.on.map { (a, b) =>
        val l = a.stripPrefix(base.name + "."); val r = b.stripPrefix(other.name + ".")
        (base.column(l).orElse(base.column(a)), other.column(r).orElse(other.column(b)), s"$a = $b")
      }
      val bad = keys.collect { case (None, _, s) => s } ++ keys.collect { case (_, None, s) => s }
      if j.on.isEmpty then Left("a join needs \"on\": [[\"left column\", \"right column\"]]")
      else if bad.nonEmpty then Left(s"join columns not found: ${bad.mkString(", ")}")
      else
        val li = keys.map(_._1.get); val ri = keys.map(_._2.get)
        val index = other.rows.groupBy(r => ri.map(r))
        val cols = base.columns ++ other.columns.map(c => if base.columns.contains(c) then s"${other.name}.$c" else c)
        Right(Table(base.name, cols, base.rows.flatMap(r => index.getOrElse(li.map(r), Vector.empty).map(r ++ _))))
    }

  private def aggregate(t: Table, rows: Vector[Vector[Value]], group: Vector[String], aggs: Vector[Agg]): Table =
    val keys = group.map(g => (t.column(grouping(g)).get, part(g).map(_._1)))
    def key(r: Vector[Value]) = keys.map((i, kind) => kind.fold(r(i))(keyOf(_, r(i))))
    val groups = rows.groupBy(key).toVector.sortBy(_._1)(using Ordering.Implicits.seqOrdering)
    val cols = group.map(keyName) ++ aggs.map(_.as)
    Table("result", cols, groups.map { (k, all) =>
      k ++ aggs.map { a =>
        val rs = all.filter(r => a.where.forall(holds(t, r, _)))
        val vals = if a.column == "*" then rs.map(_ => Value.Num(1)) else rs.map(_(t.column(a.column).get))
        val nums = vals.flatMap(_.num)
        a.fn match
          case "count" => Value.Num(if a.column == "*" then rs.size else vals.count(_ != Value.Empty))
          case "sum" => Value.Num(nums.sum)
          case "avg" => if nums.isEmpty then Value.Empty else Value.Num(nums.sum / nums.size)
          case "min" => vals.filter(_ != Value.Empty).minOption.getOrElse(Value.Empty)
          case "growth" | "change" =>
            // the rows in the order of `by`, the first and the last values that are numbers
            val bi = t.column(a.by.get).get; val ci = t.column(a.column).get
            val series = rs.sortBy(_(bi))(using summon[Ordering[Value]]).flatMap(_(ci).num)
            if series.size < 2 then Value.Empty
            else if a.fn == "change" then Value.Num(series.last - series.head)
            else if series.head == 0 then Value.Empty
            else Value.Num((series.last - series.head) / series.head.abs)
          case _ => vals.filter(_ != Value.Empty).maxOption.getOrElse(Value.Empty)
      }
    })

/** AN ARITHMETIC EXPRESSION over a row's numbers: names (columns), numbers, + - * / and parentheses. A name with
 * no number, or a division by zero, gives no value. */
enum Expr:
  case Num(n: Double)
  case Name(n: String)
  case Op(op: Char, a: Expr, b: Expr)
  case Neg(a: Expr)

  def names: Vector[String] = this match
    case Num(_) => Vector.empty
    case Name(n) => Vector(n)
    case Op(_, a, b) => a.names ++ b.names
    case Neg(a) => a.names

  def value(of: String => Option[Double]): Option[Double] = this match
    case Num(n) => Some(n)
    case Name(n) => of(n)
    case Neg(a) => a.value(of).map(-_)
    case Op(op, a, b) => for x <- a.value(of); y <- b.value(of); r <- op match
        case '+' => Some(x + y)
        case '-' => Some(x - y)
        case '*' => Some(x * y)
        case _ => Option.when(y != 0)(x / y)
      yield r

object Expr:
  def parse(text: String): Either[String, Expr] =
    val tokens = "\\s*([A-Za-z_\\p{L}][\\w.\\p{L}]*|\\d+(?:\\.\\d+)?|[-+*/()])".r.findAllMatchIn(text).map(_.group(1)).toVector
    if tokens.mkString.replaceAll("\\s", "") != text.replaceAll("\\s", "") then Left(s"cannot read the expression $text")
    else
      var at = 0
      def peek = tokens.lift(at)
      def sum(): Either[String, Expr] = product().flatMap { first =>
        var acc: Either[String, Expr] = Right(first)
        while acc.isRight && (peek.contains("+") || peek.contains("-")) do
          val op = tokens(at).head; at += 1
          acc = for a <- acc; b <- product() yield Expr.Op(op, a, b)
        acc }
      def product(): Either[String, Expr] = unary().flatMap { first =>
        var acc: Either[String, Expr] = Right(first)
        while acc.isRight && (peek.contains("*") || peek.contains("/")) do
          val op = tokens(at).head; at += 1
          acc = for a <- acc; b <- unary() yield Expr.Op(op, a, b)
        acc }
      def unary(): Either[String, Expr] =
        if peek.contains("-") then { at += 1; unary().map(Expr.Neg(_)) } else atom()
      def atom(): Either[String, Expr] = peek match
        case Some("(") =>
          at += 1
          sum().flatMap(e => if peek.contains(")") then { at += 1; Right(e) } else Left(s"a ) missing in $text"))
        case Some(t) if t.head.isDigit => at += 1; Right(Expr.Num(t.toDouble))
        case Some(t) if t.head.isLetter || t.head == '_' => at += 1; Right(Expr.Name(t))
        case other => Left(s"unexpected ${other.getOrElse("end")} in $text")
      sum().flatMap(e => if at == tokens.size then Right(e) else Left(s"unexpected ${tokens(at)} in $text"))
