package okay.data

/** A VALUE in a cell: text, a number, or empty. Numbers compare as numbers, text as text; empty sorts first */
enum Value:
  case Str(s: String)
  case Num(d: Double)
  case Empty
  def text: String = this match
    case Str(s) => s
    case Num(d) => if d == d.toLong.toDouble && math.abs(d) < 1e15 then d.toLong.toString else d.toString
    case Empty => ""
  def num: Option[Double] = this match
    case Num(d) => Some(d)
    case Str(s) => s.trim.toDoubleOption
    case Empty => None

object Value:
  def of(s: String): Value =
    val t = s.trim
    if t.isEmpty then Empty
    else t.toDoubleOption.filter(_ => t.matches("-?\\d+(\\.\\d+)?")).map(Num.apply).getOrElse(Str(s))
  given Ordering[Value] = (a, b) => (a, b) match
    case (Empty, Empty) => 0
    case (Empty, _) => -1
    case (_, Empty) => 1
    case (Num(x), Num(y)) => java.lang.Double.compare(x, y)
    case (x, y) => x.text.compareTo(y.text)

/** A TABLE: named columns, rows of values; from a file by its extension (CSV, TSV, JSON lines, a JSON array) */
final case class Table(name: String, columns: Vector[String], rows: Vector[Vector[Value]]):
  def column(c: String): Option[Int] = Some(columns.indexOf(c)).filter(_ >= 0)
  def size: Int = rows.size
  /** the schema as the model reads it: each column with its kind and a sample */
  def describe: String =
    val kinds = columns.indices.map { i =>
      val vs = rows.map(_(i)).filter(_ != Value.Empty)
      val numeric = vs.nonEmpty && vs.forall(_.num.isDefined)
      val sample = vs.take(3).map(_.text.take(20)).mkString(", ")
      s"  ${columns(i)}: ${if numeric then "number" else "text"}${if sample.nonEmpty then s" (e.g. $sample)" else ""}"
    }
    s"table $name (${rows.size} rows):\n${kinds.mkString("\n")}"
  def render(limit: Int = 50): String =
    val shown = rows.take(limit)
    val widths = columns.indices.map(i => (columns(i).length +: shown.map(_(i).text.length)).max.min(40))
    def row(cells: Vector[String]) = cells.zipWithIndex.map((c, i) => c.take(40).padTo(widths(i), ' ')).mkString(" | ")
    (row(columns) +: widths.map("-" * _).mkString("-+-") +: shown.map(r => row(r.map(_.text)))).mkString("\n") +
      (if rows.size > limit then s"\n… ${rows.size - limit} more rows" else "")

object Table:
  /** a file's table by its name's extension; a name without one is read as CSV */
  def parse(name: String, text: String): Either[String, Table] =
    val base = name.split("[/\\\\]").last
    val stem = base.replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("[^A-Za-z0-9_]", "_").toLowerCase
    val ext = base.split('.').lastOption.map(_.toLowerCase).getOrElse("csv")
    ext match
      case "tsv" => Right(delimited(stem, text, '\t'))
      case "json" | "jsonl" | "ndjson" => json(stem, text)
      case _ => Right(delimited(stem, text, if text.linesIterator.take(1).exists(_.count(_ == ';') > text.linesIterator.take(1).map(_.count(_ == ',')).sum) then ';' else ','))

  /** CSV/TSV: quoted fields, doubled quotes, the first line the header */
  def delimited(name: String, text: String, sep: Char): Table =
    val lines = text.split("\r?\n").toVector.filter(_.nonEmpty)
    if lines.isEmpty then Table(name, Vector.empty, Vector.empty)
    else
      def fields(l: String): Vector[String] =
        val out = Vector.newBuilder[String]; val cur = new StringBuilder; var q = false; var i = 0
        while i < l.length do
          val c = l(i)
          if q then
            if c == '"' && i + 1 < l.length && l(i + 1) == '"' then { cur += '"'; i += 1 }
            else if c == '"' then q = false
            else cur += c
          else if c == '"' then q = true
          else if c == sep then { out += cur.result(); cur.clear() }
          else cur += c
          i += 1
        out += cur.result()
        out.result()
      val header = fields(lines.head).map(_.trim)
      val rows = lines.tail.map(l => fields(l).padTo(header.size, "").take(header.size).map(Value.of))
      Table(name, header, rows)

  /** JSON lines or a JSON array of objects: the columns the union of the keys, in first-seen order */
  def json(name: String, text: String): Either[String, Table] =
    import okay.effects.json.Json
    val objs: Either[String, Vector[Json]] =
      def read(t: String): Either[String, Json] = try Right(Json.fromText(t)) catch { case e: Exception => Left(e.getMessage) }
      if text.trim.startsWith("[") then read(text).flatMap {
        case Json.Arr(items) => Right(items)
        case _ => Left("a JSON array of objects expected")
      } else text.split("\r?\n").toVector.filter(_.trim.nonEmpty).foldLeft[Either[String, Vector[Json]]](Right(Vector.empty)) {
        case (Right(acc), l) => read(l).map(acc :+ _)
        case (l, _) => l
      }
    objs.map { items =>
      val keys = items.collect { case Json.Obj(fs) => fs.map(_._1) }.flatten.distinct.toVector
      def cell(j: Json): Value = j match
        case Json.Str(s) => Value.of(s)
        case Json.Num(n) => Value.Num(n.toDouble)
        case Json.Bool(b) => Value.Str(b.toString)
        case Json.Null => Value.Empty
        case other => Value.Str(Json.print(other))
      val rows = items.map {
        case Json.Obj(fs) => keys.map(k => fs.collectFirst { case (n, v) if n == k => cell(v) }.getOrElse(Value.Empty))
        case other => Vector(cell(other)).padTo(keys.size, Value.Empty)
      }
      Table(name, keys, rows)
    }
