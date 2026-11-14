package okay.desk.calc

import okay.desk.*
import okay.desk.Space.{Col, Kind, Link, Program, Schema}
import Term.*

/**
 * THE DENOTATION: a closed term is a point of the space (`Space.Program`), the point compiles to the query the
 * engines run; and back — a point as a term, for the oracle fill and the laws (read ∘ phrase = id is checked on
 * terms). A term with holes has no denotation: it is a reading not finished (an abstention), a value of its own.
 */
object Den:
  private def leaf(t: Term): Option[String] = t match
    case Leaf(_, v) => Some(v)
    case Con(_, n, _) => Some(n)
    case Hole(_) => None

  private def col(s: Schema, tables: Vector[String], name: String): Either[String, Col] =
    tables.flatMap(t => s.of(t)).find(_.name == name).toRight(s"no column $name in ${tables.mkString(", ")}")

  private def cond(s: Schema, t: Term): Either[String, Option[(Col, String, Value)]] = t match
    case Leaf(_, "none") => Right(None)
    case Con(Ty.ColumnT(ts, _, _, _), c, Vector(Leaf(_, op), Leaf(_, v))) => col(s, ts, c).map(cc => Some((cc, op, Value.of(v))))
    case other => Left(s"an unfinished condition: ${other.show}")

  /** a closed term's point of the space */
  def program(s: Schema, term: Term): Either[String, Program] = term match
    case Con(Ty.Program, "Rows", Vector(tbl, link, whereOwn, whereOwn2, whereOther, group, fn, aggCol, order, limit)) =>
      for
        t <- leaf(tbl).toRight("no table")
        l = leaf(link).filter(_ != "none").map { x => val Array(c, to) = x.split("->"): @unchecked; Link(t, c, to) }
        w1 <- cond(s, whereOwn); w1b <- cond(s, whereOwn2); w2 <- cond(s, whereOther)
        g <- leaf(group).toRight("no group").flatMap(x => if x == "none" then Right(None) else col(s, t +: l.map(_.to).toVector, x).map(Some(_)))
        f <- leaf(fn).toRight("no function")
        a <- leaf(aggCol).toRight("no aggregate column").flatMap { x =>
          if f == "none" then Right(None) else if f == "count" then Right(Some(("count", None)))
          else col(s, Vector(t), x).map(c => Some((f, Some(c)))) }
        o <- order match
          case Leaf(_, "none") => Right(None)
          case Con(_, by, Vector(Leaf(_, dir))) => Right(Some((by, dir == "desc")))
          case other => Left(s"an unfinished order: ${other.show}")
        n <- leaf(limit).toRight("no limit").map(x => if x == "none" then None else Some(x.toInt))
      yield Program.Rel(t, l, w1.toVector ++ w1b.toVector ++ w2.toVector, g, a, o, n)
    case Con(Ty.Program, "Absent", Vector(tbl, link, where)) =>
      for
        t <- leaf(tbl).toRight("no table")
        l <- leaf(link).toRight("no link").map { x => val Array(from, c) = x.split("\\."): @unchecked; Link(from, c, t) }
        w <- cond(s, where)
      yield Program.Absent(t, l, w.toVector)
    case Con(Ty.Program, "Closure", Vector(dir, node)) =>
      for
        d <- leaf(dir).toRight("no direction"); n <- leaf(node).toRight("no node")
        e <- s.edgeLike.headOption.toRight("no edge-like table")
      yield Program.Closure(e, n, d == "from")
    case other => Left(s"not a closed program: ${other.show}")

  /** a point of the space as a closed term (the inverse) */
  def term(s: Schema, p: Program): Term = p match
    case Program.Rel(from, link, where, group, agg, order, limit) =>
      val other = link.map(_.to).toVector
      def condOf(ts: Vector[String], c: Option[(Col, String, Value)]): Term = c.fold(Leaf(Ty.ColumnT(ts, Role.Where, optional = true), "none")) { (cc, op, v) =>
        Con(Ty.ColumnT(ts, Role.Where, optional = true), cc.name, Vector(Leaf(Ty.OpT(cc.kind), op), Leaf(Ty.ValueT(cc.table, cc.name, op == "month"), v.text))) }
      val cols = s.tables(from).columns
      val owns = where.filter(_._1.table == from).sortBy(w => cols.indexOf(w._1.name)); val own = owns.headOption; val own2 = owns.lift(1); val oth = where.find(w => other.contains(w._1.table))
      val fn = agg.map(_._1).getOrElse("none")
      def upTo(first: Option[(Col, String, Value)]): Vector[String] = first.toVector.flatMap(w => cols.takeWhile(_ != w._1.name) :+ w._1.name)
      Con(Ty.Program, "Rows", Vector(
        Leaf(Ty.TableT, from),
        Leaf(Ty.LinkT(from), link.map(l => s"${l.column}->${l.to}").getOrElse("none")),
        condOf(Vector(from), own),
        own2.fold(Leaf(Ty.ColumnT(if own.isEmpty then Vector.empty else Vector(from), Role.Where, optional = true, except = upTo(own)), "none")) { (cc, op, v) =>
          Con(Ty.ColumnT(Vector(from), Role.Where, optional = true, except = upTo(own)), cc.name, Vector(Leaf(Ty.OpT(cc.kind), op), Leaf(Ty.ValueT(cc.table, cc.name, op == "month"), v.text))) },
        condOf(other, oth),
        Leaf(Ty.ColumnT(from +: other, Role.Group, optional = true, except = where.map(_._1.name)), group.map(_.name).getOrElse("none")),
        Leaf(Ty.FnT(required = group.isDefined, timed = s.of(from).exists(_.kind == Kind.Time)), fn),
        Leaf(Ty.ColumnT(Vector(from), Role.Agg, optional = fn == "none" || fn == "count"), agg.flatMap(_._2).map(_.name).getOrElse("none")),
        order.fold(Leaf(Ty.OrderT(from, Option.when(fn != "none")(fn), group.isDefined), "none"))((by, desc) =>
          Con(Ty.OrderT(from, Option.when(fn != "none")(fn), group.isDefined), by, Vector(Leaf(Ty.DirT, if desc then "desc" else "asc")))),
        Leaf(Ty.LimitT(ordered = order.isDefined), limit.map(_.toString).getOrElse("none"))))
    case Program.Absent(target, l, where) =>
      val w = where.headOption.fold(Leaf(Ty.ColumnT(Vector(target), Role.Where, optional = true), "none")) { (cc, op, v) =>
        Con(Ty.ColumnT(Vector(target), Role.Where, optional = true), cc.name, Vector(Leaf(Ty.OpT(cc.kind), op), Leaf(Ty.ValueT(cc.table, cc.name, op == "month"), v.text))) }
      Con(Ty.Program, "Absent", Vector(Leaf(Ty.TableT, target), Leaf(Ty.ReverseLinkT(target), s"${l.from}.${l.column}"), w))
    case Program.Closure(edges, node, forward) =>
      Con(Ty.Program, "Closure", Vector(Leaf(Ty.DirectionT, if forward then "from" else "to"), Leaf(Ty.NodeT(edges), node)))

  /** the query of a closed term, and its result on the tables */
  def run(s: Schema, term: Term): Either[String, (Query, Table)] =
    for
      p <- program(s, term)
      q <- Space.compile(p, s.tables)
      r <- q match
        case rel: Query.Relational => Exact.run(s.tables, rel)
        case l: Query.Logical => Logic.run(s.tables, l)
    yield (q, r)

/** A QUERY BACK TO A POINT (the teacher's pairs carry the gold as the query's JSON; an organ is induced from the
 * gold TERM): the relational shape as it is; the Datalog shapes the space writes (`Space.compile`) by their form */
object OfQuery:
  import okay.desk.Space.{Col, Program}
  private val Reach = """^\s*(\w+)\(X, Y\) :- (\w+)\(X, Y\)\. \1\(X, Z\) :- \2\(X, Y\), \1\(Y, Z\)\.\s*$""".r
  private val GoalFrom = """^\s*\w+\(\s*"?([^",()]+?)"?\s*,\s*[A-Z]\w*\s*\)\s*$""".r
  private val GoalTo = """^\s*\w+\(\s*[A-Z]\w*\s*,\s*"?([^",()]+?)"?\s*\)\s*$""".r
  private val Absent = """(?s)^\s*q\((\w+)\) :- (\w+)\(([^)]*)\)(.*?), not has\(\w+\)\. has\(\w+\) :- (\w+)\(([^)]*)\)\.\s*$""".r
  private val Cmp = """(\w+)\s*(=|<|>|<=|>=)\s*("([^"]*)"|[^\s,]+)""".r

  def apply(s: Schema, q: Query): Option[Program] = q match
    case Query.Relational(from, join, where, group, aggs, _, order, limit) =>
      val table = s.tables.get(from)
      def col(name: String): Option[Col] = s.cols.find(c => c.table == from && c.name == name)
        .orElse(join.flatMap(j => s.cols.find(c => c.table == j.table && c.name == name)))
      for
        _ <- table
        link <- join.fold(Option(None: Option[okay.desk.Space.Link]))(j => s.links.find(l => l.from == from && l.to == j.table).map(Some(_)))
        conds <- Some(where.collect { case Cond.Cmp(c, op, v) => col(c).map(cc => (cc, op, v)) }).filter(_.forall(_.isDefined)).map(_.flatten)
        if conds.size == where.size
        g <- group.headOption.fold(Option(None: Option[Col]))(c => col(c).map(Some(_)))
        a <- aggs.headOption.fold(Option(None: Option[(String, Option[Col])]))(ag =>
          if ag.column == "*" then Some(Some((ag.fn, None))) else col(ag.column).map(c => Some((ag.fn, Some(c)))))
      yield Program.Rel(from, link, conds, g, a, order.headOption.map(o => (o.column.stripPrefix("-"), o.desc)), limit)
    case Query.Logical(rules, goal) =>
      rules match
        case Reach(_, edges) => goal match
          case GoalFrom(node) => Some(Program.Closure(edges, node, true))
          case GoalTo(node) => Some(Program.Closure(edges, node, false))
          case _ => None
        case Absent(_, target, vars, rest, other, _) =>
          val names = vars.split(",").map(_.trim).toVector
          val cols = s.tables.get(target).map(_.columns).getOrElse(Vector.empty)
          val conds = Cmp.findAllMatchIn(rest).toVector.flatMap { m =>
            val i = names.indexOf(m.group(1))
            Option.when(i >= 0 && i < cols.size)(i).flatMap(i => s.cols.find(c => c.table == target && c.name == cols(i)))
              .map(c => (c, m.group(2), Option(m.group(4)).map(Value.Str(_)).getOrElse(Value.of(m.group(3)))))
          }
          s.links.find(l => l.from == other && l.to == target).map(l => Program.Absent(target, l, conds))
        case _ => None
