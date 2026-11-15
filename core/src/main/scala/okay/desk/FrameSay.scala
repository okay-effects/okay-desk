package okay.desk

/**
 * THE FRAME IN CANONICAL WORDS, AND BACK (specs/guarantees.md §3.2–3.3): each line of a frame said as one clause, in
 * English or Russian, the clauses joined by "; ", every name and value in «»; `parse` reads the words back to the
 * same lines — exactly, on every frame of the option space (`FrameSaySuite`). What a person reads is what is
 * computed; a canonical sentence typed by a person is read with no model and no doubt.
 */
object FrameSay:
  private def q(s: String) = s"«$s»"
  private val ops = Vector("=" -> "=", "!=" -> "≠", "<" -> "<", "<=" -> "≤", ">" -> ">", ">=" -> "≥")
  private def opSaid(op: String) = ops.toMap.getOrElse(op, op)
  private def opRead(s: String) = ops.find(_._2 == s).map(_._1).getOrElse(s)

  private final case class Words(month: String, column: String, rows: String, count: String,
      fn: Map[String, String], shareOf: String, changeOf: String, cannot: String, table: String, part: String,
      from: String, to: String, where: String, each: String, parts: Map[String, String], keep: String,
      sorted: String, answer: String, desc: String, asc: String, first: String, and: String)
  private val en = Words("in month", "column", "rows", "number of rows",
    Map("sum" -> "sum of", "avg" -> "average of", "min" -> "smallest", "max" -> "largest"), "share of the",
    "change of the", "cannot be computed from the data", "table", "the part where", "from", "to", "where",
    "for each", Map("month" -> "month of", "week" -> "week of", "year" -> "year of"), "keeping answers",
    "sorted by", "the answer", "descending", "ascending", "first", "and")
  private val ru = Words("в месяце", "столбец", "строки", "число строк",
    Map("sum" -> "сумма", "avg" -> "среднее", "min" -> "наименьшее", "max" -> "наибольшее"), "доля:",
    "изменение:", "не вычисляется по данным", "таблица", "часть, где", "от", "к", "где", "по каждому",
    Map("month" -> "месяцу", "week" -> "неделе", "year" -> "году"), "оставив ответы", "упорядочив по", "ответу",
    "по убыванию", "по возрастанию", "первые", "и")
  private def words(lang: String) = if lang == "ru" then ru else en

  private def condSaid(w: Words, c: String): String =
    val p = c.split(" ", 3).toVector
    p match
      case Vector(col, "month", m) => s"${q(col)} ${w.month} ${q(m)}"
      case Vector(col, op, v) if v.startsWith("column ") => s"${q(col)} ${opSaid(op)} ${w.column} ${q(v.drop(7))}"
      case Vector(col, op, v) => s"${q(col)} ${opSaid(op)} ${q(v)}"
      case _ => c

  private def answerSaid(w: Words, a: String): String = a.split(" ").toVector match
    case Vector("rows") => w.rows
    case Vector("count") => w.count
    case Vector("cannot") => w.cannot
    case Vector(f, c) if w.fn.contains(f) => s"${w.fn(f)} ${q(c)}"
    case Vector(k @ ("share" | "change"), "count") => s"${if k == "share" then w.shareOf else w.changeOf} ${w.count}"
    case Vector(k @ ("share" | "change"), f, c) =>
      s"${if k == "share" then w.shareOf else w.changeOf} ${w.fn(f)} ${q(c)}"
    case _ => a

  /** a frame's lines said, clause by clause (the end not said) */
  def say(lines: Vector[String], lang: String): String =
    val w = words(lang)
    lines.filter(_ != Frame.end).map { line =>
      val (head, body) = (line.takeWhile(_ != ':'), line.dropWhile(_ != ':').drop(1).trim)
      head match
        case "table" => s"${w.table} ${q(body)}"
        case "answer" => answerSaid(w, body)
        case "part" => s"${w.part} ${condSaid(w, body)}"
        case "from" => s"${w.from} ${condSaid(w, body)}"
        case "to" => s"${w.to} ${condSaid(w, body)}"
        case "where" => s"${w.where} ${condSaid(w, body)}"
        case "group" =>
          "(month|week|year)\\((.+)\\)".r.findFirstMatchIn(body)
            .fold(s"${w.each} ${q(body)}")(m => s"${w.each} ${w.parts(m.group(1))} ${q(m.group(2))}")
        case "keep" => body.split(" ").toVector match
          case Vector("answer", op, n) => s"${w.keep} ${opSaid(op)} ${q(n)}"
          case _ => line
        case "order" => body.split(" ").toVector match
          case Vector(c, dir) =>
            s"${w.sorted} ${if c == "answer" then w.answer else q(c)} ${if dir == "desc" then w.desc else w.asc}"
          case _ => line
        case "limit" => s"${w.first} ${q(body)}"
        case _ => line
    }.mkString("; ")

  private val quoted = "«([^»]*)»"
  /** the words read back to the lines, or why not */
  def parse(text: String, lang: String): Either[String, Vector[String]] =
    val w = words(lang)
    def cond(s: String): Option[String] =
      val month = s"^$quoted ${w.month} $quoted$$".r
      val column = s"^$quoted (\\S+) ${w.column} $quoted$$".r
      val value = s"^$quoted (\\S+) $quoted$$".r
      s match
        case month(c, m) => Some(s"$c month $m")
        case column(c, op, o) => Some(s"$c ${opRead(op)} column $o")
        case value(c, op, v) => Some(s"$c ${opRead(op)} $v")
        case _ => None
    def answer(s: String): Option[String] =
      val fns = w.fn.map(_.swap)
      if s == w.rows then Some("rows") else if s == w.count then Some("count")
      else if s == w.cannot then Some("cannot")
      else
        def plain(t: String): Option[String] =
          if t == w.count then Some("count")
          else fns.collectFirst { case (said, f) if t.startsWith(said + " «") && t.endsWith("»") =>
            s"$f ${t.drop(said.length + 2).dropRight(1)}" }
        if s.startsWith(w.shareOf + " ") then plain(s.drop(w.shareOf.length + 1)).map("share " + _)
        else if s.startsWith(w.changeOf + " ") then plain(s.drop(w.changeOf.length + 1)).map("change " + _)
        else plain(s).filter(_ != "count")
    val clauses = text.split("; ").toVector
    val read = clauses.zipWithIndex.map { (c, i) =>
      def after(k: String) = Option.when(c.startsWith(k + " "))(c.drop(k.length + 1))
      if i == 0 then after(w.table).collect { case s"«$t»" => s"table: $t" }
      else if i == 1 then answer(c).map("answer: " + _)
      else after(w.part).flatMap(cond).map("part: " + _)
        .orElse(after(w.from).flatMap(cond).map("from: " + _))
        .orElse(after(w.to).flatMap(cond).map("to: " + _))
        .orElse(after(w.where).flatMap(cond).map("where: " + _))
        .orElse(after(w.each).flatMap { g =>
          w.parts.collectFirst { case (k, said) if g.startsWith(said + " «") => s"group: $k(${g.drop(said.length + 2)
            .dropRight(1)})" }.orElse(g match { case s"«$x»" => Some(s"group: $x"); case _ => None }) })
        .orElse(after(w.keep).flatMap(k => k.split(" ", 2).toVector match
          case Vector(op, s"«$n»") => Some(s"keep: answer ${opRead(op)} $n")
          case _ => None))
        .orElse(after(w.sorted).flatMap { o =>
          val dir = if o.endsWith(" " + w.desc) then Some("desc") else if o.endsWith(" " + w.asc) then Some("asc")
            else None
          dir.flatMap { d =>
            val x = o.dropRight((if d == "desc" then w.desc else w.asc).length + 1)
            if x == w.answer then Some(s"order: answer $d")
            else x match { case s"«$c»" => Some(s"order: $c $d"); case _ => None }
          } })
        .orElse(after(w.first).collect { case s"«$n»" => s"limit: $n" })
    }
    read.zip(clauses).collectFirst { case (None, c) => c }
      .fold[Either[String, Vector[String]]](Right(read.flatten :+ Frame.end))(c => Left(s"not canonical: $c"))
