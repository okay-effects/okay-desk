package okay.desk.calc

import okay.desk.*
import okay.desk.Space.{Col, Link, Program, Schema}
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
    case Con(Ty.Program, "Rows", Vector(tbl, link, whereOwn, whereOther, group, fn, aggCol, order, limit)) =>
      for
        t <- leaf(tbl).toRight("no table")
        l = leaf(link).filter(_ != "none").map { x => val Array(c, to) = x.split("->"): @unchecked; Link(t, c, to) }
        w1 <- cond(s, whereOwn); w2 <- cond(s, whereOther)
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
      yield Program.Rel(t, l, w1.toVector ++ w2.toVector, g, a, o, n)
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
  def term(@annotation.unused s: Schema, p: Program): Term = p match
    case Program.Rel(from, link, where, group, agg, order, limit) =>
      val other = link.map(_.to).toVector
      def condOf(ts: Vector[String], c: Option[(Col, String, Value)]): Term = c.fold(Leaf(Ty.ColumnT(ts, Role.Where, optional = true), "none")) { (cc, op, v) =>
        Con(Ty.ColumnT(ts, Role.Where, optional = true), cc.name, Vector(Leaf(Ty.OpT(cc.kind), op), Leaf(Ty.ValueT(cc.table, cc.name), v.text))) }
      val own = where.find(_._1.table == from); val oth = where.find(w => other.contains(w._1.table))
      val fn = agg.map(_._1).getOrElse("none")
      Con(Ty.Program, "Rows", Vector(
        Leaf(Ty.TableT, from),
        Leaf(Ty.LinkT(from), link.map(l => s"${l.column}->${l.to}").getOrElse("none")),
        condOf(Vector(from), own), condOf(other, oth),
        Leaf(Ty.ColumnT(from +: other, Role.Group, optional = true, except = where.map(_._1.name)), group.map(_.name).getOrElse("none")),
        Leaf(Ty.FnT(required = group.isDefined), fn),
        Leaf(Ty.ColumnT(Vector(from), Role.Agg, optional = fn == "none" || fn == "count"), agg.flatMap(_._2).map(_.name).getOrElse("none")),
        order.fold(Leaf(Ty.OrderT(from, Option.when(fn != "none")(fn), group.isDefined), "none"))((by, desc) =>
          Con(Ty.OrderT(from, Option.when(fn != "none")(fn), group.isDefined), by, Vector(Leaf(Ty.DirT, if desc then "desc" else "asc")))),
        Leaf(Ty.LimitT(ordered = order.isDefined), limit.map(_.toString).getOrElse("none"))))
    case Program.Absent(target, l, where) =>
      val w = where.headOption.fold(Leaf(Ty.ColumnT(Vector(target), Role.Where, optional = true), "none")) { (cc, op, v) =>
        Con(Ty.ColumnT(Vector(target), Role.Where, optional = true), cc.name, Vector(Leaf(Ty.OpT(cc.kind), op), Leaf(Ty.ValueT(cc.table, cc.name), v.text))) }
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
