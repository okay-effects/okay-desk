package okay.desk.calc

import okay.desk.*
import okay.desk.Space.{Col, Kind, Program, Schema}

/**
 * LINEARIZATION FROM A LANGUAGE'S PIECES (docs/calculus.md §10): the other direction of the same `Language` record
 * — a point of the space said in a language whose words the record holds, so the record is a concrete syntax in
 * GF's sense: one declaration, parsing and linearization. A piece is a template with slots: `{T}` the table,
 * `{C}` a column, `{V}` a value, `{N}` a number, `{K}` a limit, `{X}` a node — a case after a dot (`{T.gen}`,
 * `{C.dat}`) picks the form (nom, gen, prep, dat for tables; nom, gen, dat for columns). The pieces: `count`,
 * `rows`, `sum`, `avg`, `min`, `max`, `top1Desc`, `top1Asc`, `by`, `condEq`, `condMore`, `condLess`, `condTime`,
 * `and`, `group`, `desc`, `asc`, `topN`, `absent`, `closureFrom`, `closureTo`; several wordings each, one drawn.
 * The law (`SayingSuite`): what a language says, the data alone reads with that language and never contradicts.
 */
object Say:
  private def pick(rnd: scala.util.Random, xs: Vector[String]): String = xs(rnd.nextInt(xs.size))
  private def form(forms: Vector[String], c: String, default: String): String =
    val i = c match { case "gen" => 1; case "prep" => 2; case "dat" => if forms.size > 3 then 3 else 2; case _ => 0 }
    forms.lift(i).getOrElse(forms.headOption.getOrElse(default))

  def say(rnd: scala.util.Random, p: Program, s: Schema, lang: Language): String =
    def piece(key: String): String = pick(rnd, lang.pieces.getOrElse(key, Vector(s"<$key>")))
    def fill(template: String, t: String = "", c: Option[Col] = None, v: String = "", n: String = "", k: String = "", x: String = ""): String =
      "\\{(T|C|V|N|K|X)(?:\\.(\\w+))?\\}".r.replaceAllIn(template, m => java.util.regex.Matcher.quoteReplacement(m.group(1) match
        case "T" => form(lang.tables.getOrElse(t, Vector.empty), Option(m.group(2)).getOrElse("nom"), t)
        case "C" => c.map(col => form(lang.columns.getOrElse(col.name, Vector.empty), Option(m.group(2)).getOrElse("nom"), col.name)).getOrElse("")
        case "V" => v; case "N" => n; case "K" => k; case _ => x))
    def value(col: Col, vv: Value): String = if col.kind == Kind.Num || col.kind == Kind.Time then vv.text else lang.values.getOrElse(vv.text, vv.text)
    def cond(w: (Col, String, Value)): String =
      val (col, op, vv) = w
      (col.kind, op) match
        case (Kind.Num, ">") => fill(piece("condMore"), c = Some(col), n = vv.text)
        case (Kind.Num, "<") => fill(piece("condLess"), c = Some(col), n = vv.text)
        case (Kind.Time, _) => fill(piece("condTime"), c = Some(col), v = vv.text)
        case _ => fill(piece("condEq"), c = Some(col), v = value(col, vv))
    p match
      case Program.Closure(_, node, forward) => fill(piece(if forward then "closureFrom" else "closureTo"), x = node)
      case Program.Absent(target, link, where) =>
        fill(piece("absent"), t = target).replace("{O}", form(lang.tables.getOrElse(link.from, Vector.empty), "gen", link.from)) +
          where.headOption.map(w => " " + cond(w)).getOrElse("") + (if rnd.nextBoolean() then "?" else ".")
      case Program.Rel(from, _, where, group, agg, order, limit) =>
        val head = (agg, order, limit) match
          case (Some(("count", _)), _, _) => fill(piece("count"), t = from)
          case (Some((fn, Some(col))), _, _) => fill(piece(fn), t = from, c = Some(col))
          case (None, Some((by, desc)), Some(1)) => fill(piece(if desc then "top1Desc" else "top1Asc"), t = from, c = s.of(from).find(_.name == by))
          case (None, Some((by, _)), _) => fill(piece("by"), t = from, c = s.of(from).find(_.name == by))
          case _ => fill(piece("rows"), t = from)
        val conds = if where.isEmpty then "" else " " + where.map(cond).mkString(" " + piece("and") + " ")
        val grouped = group.map(g => " " + fill(piece("group"), c = Some(g))).getOrElse("")
        val tail = (order, limit) match
          case (Some((_, desc)), Some(1)) if agg.isDefined => ", " + piece(if desc then "desc" else "asc") + ", " + piece("onlyFirst") // the first group
          case (Some(_), Some(1)) => ""
          case (Some((_, desc)), lim) => ", " + piece(if desc then "desc" else "asc") + lim.filter(_ != 1).map(k => ", " + fill(piece("topN"), k = k.toString)).getOrElse("")
          case _ => ""
        head + conds + grouped + tail + (if head.startsWith(piece("count").take(3)) then "?" else ".")
