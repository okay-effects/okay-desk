package okay.desk

import okay.desk.Space.{Col, Link, Program, Schema}
import okay.inlay.domain.{Domain, Slot}

/**
 * TABLES AS A DOMAIN (okay-inlay specs/domains.md §1): the desk's space of meanings (`Space`) declared to okay-inlay's
 * framework — the same points, the same exact engines, the same phrases — so the kit's pairs, reading with abstention
 * and measure by constructor apply to tables as to any other domain. The slots are the space's constructors over a
 * schema; a value and a node are copied from the question, never chosen.
 */
object TablesDomain extends Domain:
  type Schema = Space.Schema
  type Point = Space.Program
  type Answer = Table
  val name = "tables"

  def enumerate(s: Schema): Iterator[Point] = Space.enumerate(s)
  def random(rnd: scala.util.Random, s: Schema): Point = Space.random(rnd, s)
  def run(p: Point, s: Schema): Either[String, Table] = Space.compile(p, s.tables).flatMap {
    case q: Query.Relational => Exact.run(s.tables, q)
    case q: Query.Logical => Logic.run(s.tables, q)
  }
  def say(rnd: scala.util.Random, p: Point, s: Schema, language: String): String =
    Phrase.say(rnd, p, s, ru = language == "ru")
  override def useful(a: Table): Boolean = a.rows.nonEmpty

  private def col(c: Col) = s"${c.table}.${c.name}"
  private def link(l: Link) = s"${l.from}.${l.column}->${l.to}"

  def slots(s: Schema): Vector[Slot] =
    val cols = s.cols.map(col); val tables = s.tables.keys.toVector.sorted
    Vector(
      Slot("kind", Vector("rows", "absent", "closure")),
      Slot("table", tables),
      Slot("link", s.links.map(link)),
      Slot("where1", cols), Slot("op1", Vector("=", "<", ">")), Slot("value1", Vector.empty, copied = true),
      Slot("where2", cols), Slot("op2", Vector("=", "<", ">")), Slot("value2", Vector.empty, copied = true),
      Slot("group", cols),
      Slot("agg", Vector("count", "sum", "avg", "min", "max")), Slot("agg_col", cols),
      Slot("order", cols.map(_.split('.').last).distinct ++ Vector("n", "sum", "avg", "min", "max")),
      Slot("desc", Vector("true", "false")),
      Slot("limit", Vector("1", "3", "5", "10")),
      Slot("node", Vector.empty, copied = true), Slot("forward", Vector("true", "false")))

  def parts(p: Point): Map[String, String] = p match
    case Program.Rel(from, l, where, group, agg, order, limit) =>
      Map("kind" -> "rows", "table" -> from) ++ l.map(x => "link" -> link(x)) ++
        where.zipWithIndex.flatMap { case ((c, op, v), i) =>
          Vector(s"where${i + 1}" -> col(c), s"op${i + 1}" -> op, s"value${i + 1}" -> v.text) } ++
        group.map(g => "group" -> col(g)) ++
        agg.toVector.flatMap((fn, c) => Vector("agg" -> fn) ++ c.map(x => "agg_col" -> col(x))) ++
        order.toVector.flatMap((c, d) => Vector("order" -> c, "desc" -> d.toString)) ++
        limit.map(n => "limit" -> n.toString)
    case Program.Absent(target, l, where) =>
      Map("kind" -> "absent", "table" -> target, "link" -> link(l)) ++
        where.zipWithIndex.flatMap { case ((c, op, v), i) =>
          Vector(s"where${i + 1}" -> col(c), s"op${i + 1}" -> op, s"value${i + 1}" -> v.text) }
    case Program.Closure(edges, node, forward) =>
      Map("kind" -> "closure", "table" -> edges, "node" -> node, "forward" -> forward.toString)

  def assemble(s: Schema, ps: Map[String, String]): Option[Point] =
    def column(name: String): Option[Col] = ps.get(name).flatMap(n => s.cols.find(c => col(c) == n))
    def linkOf: Option[Link] = ps.get("link").flatMap(n => s.links.find(l => link(l) == n))
    def value(c: Col, text: String): Value =
      if c.kind == Space.Kind.Num then text.toDoubleOption.map(Value.Num(_)).getOrElse(Value.Str(text))
      else Value.Str(text)
    val where = (1 to 2).toVector.flatMap { i =>
      for c <- column(s"where$i"); op <- ps.get(s"op$i"); v <- ps.get(s"value$i") yield (c, op, value(c, v))
    }
    ps.get("kind") match
      case Some("rows") => ps.get("table").filter(s.tables.contains).map { t =>
          Program.Rel(t, linkOf, where, column("group"), ps.get("agg").map(fn => (fn, column("agg_col"))),
            for o <- ps.get("order"); d <- ps.get("desc").flatMap(_.toBooleanOption) yield (o, d),
            ps.get("limit").flatMap(_.toIntOption))
        }
      case Some("absent") =>
        for t <- ps.get("table") if s.tables.contains(t); l <- linkOf yield Program.Absent(t, l, where)
      case Some("closure") =>
        for t <- ps.get("table") if s.edgeLike.contains(t); n <- ps.get("node")
          f <- ps.get("forward").flatMap(_.toBooleanOption)
        yield Program.Closure(t, n, f)
      case _ => None
