package okay.desk

/**
 * THE SPACE OF MEANINGS OVER A SCHEMA (specs/data.md §25, bottom-up): the fragment of Datalog the desk answers, as
 * DATA over the tables. The slots are typed by the data (a key, text, a number, a time; the joins the values prove;
 * the edge-like tables), every program is well-formed by construction, and the space is ENUMERABLE up to a size — so
 * coverage is a number (which programs have phrasings, which questions reach which programs), and a reading is a
 * point of this space, never a string. `compile` takes a point to the `Query` the engines run; the relational query
 * and `Conjunctive.program` are its two targets.
 */
object Space:
  enum Kind:
    case Key, Text, Num, Time
  final case class Col(table: String, name: String, kind: Kind)
  /** a join the data proves: `column` of `from` names rows of `to` (its first column) */
  final case class Link(from: String, column: String, to: String)

  final case class Schema(tables: Map[String, Table]):
    val cols: Vector[Col] = tables.values.toVector.sortBy(_.name).flatMap(t => t.columns.map(c => Col(t.name, c, kindOf(t, c))))
    val links: Vector[Link] = tables.values.toVector.sortBy(_.name).flatMap { t =>
      tables.values.toVector.sortBy(_.name).filter(o => o.name != t.name && o.rows.nonEmpty).flatMap { o =>
        val keys = o.rows.map(_(0).text).toSet
        t.columns.zipWithIndex.collect { case (c, i) if t.rows.nonEmpty && t.rows.forall(r => keys(r(i).text)) => Link(t.name, c, o.name) }
      }
    }
    /** two text columns whose values share a domain: a graph */
    val edgeLike: Vector[String] = tables.values.toVector.filter(t => t.columns.size == 2 && t.rows.nonEmpty &&
      t.columns.forall(c => kindOf(t, c) == Kind.Text) && t.rows.map(_(0).text).toSet.intersect(t.rows.map(_(1).text).toSet).nonEmpty).map(_.name)
    def of(table: String): Vector[Col] = cols.filter(_.table == table)
    /** the months a date column's days are in (`yyyy-mm`): the values of a `month` condition */
    def months(c: Col): Vector[String] = tables(c.table).column(c.name).toVector.flatMap(i =>
      tables(c.table).rows.map(_(i).text).filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).map(_.take(7))).distinct.sorted
    /** the values a condition can name: a text or time column's distinct values; a numeric column's thresholds */
    def values(c: Col): Vector[Value] =
      val t = tables(c.table); val i = t.columns.indexOf(c.name)
      c.kind match
        case Kind.Num =>
          val vs = t.rows.flatMap(_(i).num).sorted.distinct
          if vs.size < 3 then Vector.empty else Vector(vs(vs.size / 4), vs(vs.size / 2), vs(vs.size * 3 / 4)).distinct.map(Value.Num.apply)
        case Kind.Key => Vector.empty
        case _ => t.rows.map(_(i)).filter(_ != Value.Empty).distinct.take(12)

  def kindOf(t: Table, c: String): Kind =
    val i = t.columns.indexOf(c); val vs = t.rows.map(_(i)).filter(_ != Value.Empty)
    if c.toLowerCase.matches("(?U).*\\b(year|date|day|month|quarter|week|time|год|дата|день|месяц)\\b.*") then Kind.Time
    else if c.toLowerCase.matches(".*(^|_)(id|key|uid)$") then Kind.Key
    else if vs.nonEmpty && vs.forall(_.num.isDefined) then Kind.Num
    else Kind.Text

  /** a point of the space */
  enum Program:
    /** rows of a table (joined along a link), under conditions, grouped and aggregated, ordered, limited */
    case Rel(from: String, link: Option[Link], where: Vector[(Col, String, Value)], group: Option[Col], agg: Option[(String, Option[Col])],
      order: Option[(String, Boolean)], limit: Option[Int])
    /** rows of `target` no row of `link.from` names, under conditions on the target */
    case Absent(target: String, link: Link, where: Vector[(Col, String, Value)])
    /** what a node reaches (or what reaches it) through an edge-like table */
    case Closure(edges: String, node: String, forward: Boolean)

  def compile(p: Program, tables: Map[String, Table]): Either[String, Query] = p match
    case Program.Rel(from, link, where, group, agg, order, limit) =>
      Right(Query.Relational(from, link.map(l => Join(l.to, Vector((l.column, tables(l.to).columns.head)))),
        where.flatMap {
          // a month: on or after its first day, before the next month's
          case (c, "month", v) => month(c, v.text + "-01").map((cc, op, vv) => Cond.Cmp(cc.name, op, vv))
          case (c, op, v) => Vector(Cond.Cmp(c.name, op, v))
        }, group.map(_.name).toVector,
        agg.map((fn, c) => Agg(fn, c.map(_.name).getOrElse("*"), if fn == "count" then "n" else fn,
          by = Option.when(fn == "growth" || fn == "change")(tables(from).columns.find(c => kindOf(tables(from), c) == Kind.Time).getOrElse("")))).toVector, Vector.empty,
        order.map((c, desc) => Order(c, desc)).toVector, limit))
    case Program.Absent(target, link, where) =>
      Conjunctive.program(tables, target, conds = where.map((c, op, v) => Conjunctive.Cond(c.table, c.name, op, v.text)),
        absent = Some(Conjunctive.Join(link.from, tables(target).columns.head, link.column)), select = Vector(tables(target).columns.head))
    case Program.Closure(edges, node, forward) =>
      val goal = if forward then s"reach(${Logic.constant(node)}, X)" else s"reach(X, ${Logic.constant(node)})"
      Right(Query.Logical(s"reach(X, Y) :- $edges(X, Y). reach(X, Z) :- $edges(X, Y), reach(Y, Z).", goal))

  /** THE ENUMERATION, bounded: one condition at most (two with a link: one per table), one group, one aggregate, one
   * order (by a column or the aggregate), a limit of 1, 3, 5 or 10. Lazy: the space is counted, not held. */
  def enumerate(s: Schema): Iterator[Program] =
    val limits = Vector(None, Some(1), Some(3), Some(5), Some(10))
    def conds(cs: Vector[Col]): Vector[(Col, String, Value)] = cs.flatMap { c =>
      val ops = c.kind match { case Kind.Num => Vector("<", ">"); case _ => Vector("=") }
      (for v <- s.values(c); op <- ops yield (c, op, v)) ++
        (if c.kind == Kind.Time then s.months(c).map(m => (c, "month", Value.Str(m))) else Vector.empty)
    }
    // (an edge-like table is asked through closures, not as rows; a group on a column a condition fixes says nothing;
    // an order or a limit on one aggregate without groups has nothing to order)
    val rel = for
      t <- s.tables.keys.toVector.sorted.filterNot(s.edgeLike.contains).iterator
      own = s.of(t)
      link <- None +: s.links.filter(_.from == t).map(Some(_))
      other = link.toVector.flatMap(l => s.of(l.to).filterNot(c => own.exists(_.name == c.name)))
      ownConds = conds(own)
      where <- (Vector(Vector.empty) ++ ownConds.map(Vector(_)) ++
        (for (a, i) <- ownConds.zipWithIndex; b <- ownConds.drop(i + 1) if a._1 != b._1 yield Vector(a, b)) ++
        (for a <- ownConds; b <- conds(other) yield Vector(a, b))).iterator
      group <- None +: (own ++ other).filter(c => c.kind == Kind.Text && !where.exists(_._1 == c)).map(Some(_))
      // (growth over the time column, when the table has one: the last value against the first, per group or whole)
      agg <- None +: Some(("count", None)) +: (for fn <- Vector("sum", "avg", "min", "max") ++ (if own.exists(_.kind == Kind.Time) then Vector("growth") else Vector.empty); c <- own.filter(_.kind == Kind.Num) yield Some((fn, Some(c))))
      if agg.isDefined || group.isEmpty // a group without an aggregate says nothing
      orderCols = (if agg.exists(_._1 != "count") then agg.map(a => a._1).toVector else Vector.empty) ++
        (if agg.isEmpty then own.filter(c => c.kind == Kind.Num || c.kind == Kind.Time).map(_.name) else if agg.exists(_._1 == "count") then Vector("n") else Vector.empty)
      order <- None +: (if agg.isDefined && group.isEmpty then Vector.empty else for c <- orderCols; d <- Vector(true, false) yield Some((c, d)))
      limit <- limits
      if limit.isEmpty || order.isDefined // a limit without an order picks arbitrary rows
    yield Program.Rel(t, link, where, group, agg, order, limit)
    val absent = for
      l <- s.links.iterator
      where <- (Vector(Vector.empty) ++ conds(s.of(l.to)).map(Vector(_))).iterator
    yield Program.Absent(l.to, l, where)
    val closure = for
      e <- s.edgeLike.iterator
      t = s.tables(e)
      node <- t.rows.flatten.map(_.text).distinct.iterator
      forward <- Iterator(true, false)
    yield Program.Closure(e, node, forward)
    rel ++ absent ++ closure

  /** whether a query (a gold program, a reading) is a point of the space: the enumeration reaches it */
  def contains(s: Schema, q: Query): Boolean =
    val norm = normal(q)
    enumerate(s).exists(p => compile(p, s.tables).toOption.exists(c => normal(c) == norm))
  /** a query up to what the space does not say: the aggregate's alias, the selection, and WHICH number a comparison
   * names (the space enumerates a few thresholds to count itself; a question's number is free) */
  private def normal(q: Query): Query = q match
    case Query.Relational(from, join, where, group, aggs, _, order, limit) =>
      val aliases = aggs.map(a => a.as -> (if a.fn == "count" then "n" else a.fn)).toMap
      val cmp = where.map { case Cond.Cmp(c, op, _) if op == "<" || op == ">" => Cond.Cmp(c, op, Value.Empty); case w => w }
      Query.Relational(from, join, cmp, group, aggs.map(a => a.copy(as = if a.fn == "count" then "n" else a.fn)), Vector.empty,
        order.map(o => o.copy(column = aliases.getOrElse(o.column, o.column))), limit)
    case l => l

  /** the month of a day `yyyy-mm-dd` as two conditions: on or after its first day, before the next month's */
  def month(c: Col, day: String): Vector[(Col, String, Value)] =
    val (y, m) = (day.take(4).toInt, day.slice(5, 7).toInt)
    val next = if m == 12 then f"${y + 1}-01-01" else f"$y-${m + 1}%02d-01"
    Vector((c, ">=", Value.Str(f"$y-$m%02d-01")), (c, "<", Value.Str(next)))

  /** A POINT DRAWN AT RANDOM, by the enumeration's structure (the pairs are the space × the phrases: a draw, not a
   * walk over the whole space). The weights keep the plain shapes from swamping the rare ones. */
  def random(rnd: scala.util.Random, s: Schema): Program =
    def pick[A](xs: Vector[A]): A = xs(rnd.nextInt(xs.size))
    def cond(cs: Vector[Col]): Option[(Col, String, Value)] =
      val withValues = cs.filter(c => s.values(c).nonEmpty)
      if withValues.isEmpty then None else
        val c = pick(withValues); val v = pick(s.values(c))
        // a date: the day, or the month it is in (specs/loop.md §2.3)
        if c.kind == Kind.Time && v.text.matches("\\d{4}-\\d{2}-\\d{2}") && rnd.nextInt(10) < 4 then
          Some((c, "month", Value.Str(v.text.take(7))))
        else Some((c, if c.kind == Kind.Num then pick(Vector("<", ">")) else "=", v))
    val roll = rnd.nextInt(100)
    if roll < 15 && s.links.nonEmpty then
      val l = pick(s.links)
      Program.Absent(l.to, l, if rnd.nextInt(3) == 0 then cond(s.of(l.to)).toVector else Vector.empty)
    else if roll < 30 && s.edgeLike.nonEmpty then
      val e = pick(s.edgeLike); val t = s.tables(e)
      Program.Closure(e, pick(t.rows.flatten.map(_.text).distinct), rnd.nextBoolean())
    else
      val from = pick(s.tables.keys.toVector.sorted.filterNot(s.edgeLike.contains) match { case v if v.nonEmpty => v; case _ => s.tables.keys.toVector.sorted })
      val own = s.of(from)
      val link = s.links.filter(_.from == from) match { case ls if ls.nonEmpty && rnd.nextInt(10) < 3 => Some(pick(ls)); case _ => None }
      val other = link.toVector.flatMap(l => s.of(l.to).filterNot(c => own.exists(_.name == c.name)))
      val where = rnd.nextInt(100) match
        case r if r < 40 => Vector.empty
        case r if r < 75 || link.isEmpty && r < 90 => cond(own).toVector
        case r if r < 90 => // two on the table, in the table's order of columns (the canonical order)
          val a = cond(own).toVector; (a ++ cond(own.filterNot(c => a.exists(_._1 == c))).toVector).sortBy(w => s.tables(from).columns.indexOf(w._1.name))
        case _ => cond(own).toVector ++ cond(other).toVector
      val nums = own.filter(_.kind == Kind.Num)
      val agg = rnd.nextInt(100) match
        case r if r < 40 => None
        case r if r < 65 || nums.isEmpty => Some(("count", None))
        case _ => Some((pick(Vector("sum", "avg", "min", "max") ++ (if own.exists(_.kind == Kind.Time) then Vector("growth") else Vector.empty)), Some(pick(nums))))
      val texts = (own ++ other).filter(c => c.kind == Kind.Text && !where.exists(_._1 == c))
      val group = if agg.isDefined && texts.nonEmpty && rnd.nextInt(10) < 5 then Some(pick(texts)) else None
      val orderCols = agg match
        case Some(("count", _)) => Vector("n")
        case Some((fn, _)) => Vector(fn)
        case None => own.filter(c => c.kind == Kind.Num || c.kind == Kind.Time).map(_.name)
      // a link only when the program uses the joined table (a condition or the group on its columns): a join the
      // question
      // cannot say made pairs no reader can read ("The tasks." with a join to accounts)
      val usedLink = link.filter(_ => where.exists(w => other.contains(w._1)) || group.exists(other.contains))
      val order = if orderCols.nonEmpty && !(agg.isDefined && group.isEmpty) && rnd.nextInt(10) < 4 then Some((pick(orderCols), rnd.nextInt(10) < 7)) else None
      val limit = if order.isDefined && rnd.nextBoolean() then Some(pick(Vector(1, 3, 5, 10))) else None
      Program.Rel(from, usedLink, where, group, agg, order, limit)
