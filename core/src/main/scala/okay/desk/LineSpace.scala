package okay.desk

import okay.desk.skill.Read
import okay.inlay.domain.Units
import okay.scala3.*

/**
 * A DOMAIN OF THE CORE MODEL (SPRINT K2): a world, the language of its lines, the options of the next line, the
 * exact run. The core model is one: it reads a question into lines, each chosen among the options the domain offers
 * there, given the domain's rule and its world in words — the same prompt shape in every domain (the rule; the world;
 * "Question: …"), so a model trained on several reads them all, and a domain it never saw is a test of the core.
 */
trait LineSpace[W]:
  def name: String
  /** the lines' language, the model's system prompt */
  def rule: String
  /** the world in words (the tables, the units) */
  def describe(w: W): String
  /** the options of the next line after `taken`; empty when the reading is complete */
  def next(w: W, question: String, taken: Vector[String]): Vector[String]
  /** the lines run exactly: the answer in words, or why not */
  def answer(w: W, lines: Vector[String]): Either[String, String]
  def prompt(w: W, question: String): (String, String) = (rule, s"${describe(w)}\n\nQuestion: $question")

object LineSpace:
  /** THE TABLES (the frame reader's language, unchanged: its prompt is `Frame.prompt`'s) */
  object Tables extends LineSpace[Map[String, Table]]:
    val name = "tables"
    def rule: String = Frame.rule
    def describe(w: Map[String, Table]): String = w.values.toVector.sortBy(_.name).map(_.describe).mkString("\n")
    def next(w: Map[String, Table], question: String, taken: Vector[String]): Vector[String] =
      if taken.lastOption.contains(Frame.end) then Vector.empty else FrameSpace.of(true).offered(taken, w, question)
    def answer(w: Map[String, Table], lines: Vector[String]): Either[String, String] =
      Frame.query(lines, w).flatMap(Exact.run(w, _)).map(_.toString)

  /**
   * UNITS AND THE CALENDAR (okay-inlay's `Units`): a conversion within a dimension, a date shifted by days, weeks or
   * months, the days or weeks between two dates. The numbers and dates are the question's own (copied, never
   * guessed); the units and the kind are chosen
   */
  object UnitsAndDates extends LineSpace[Unit]:
    val name = "units"
    val rule: String =
      """You read a question about units or dates into lines, one at a time, each line one of the options:
        |kind: convert | shift | between; for convert: from: UNIT, to: UNIT, quantity: N; for shift: unit: day | week |
        |month, direction: after | before, n: N, date: DATE; for between: unit: day | week, from: DATE, to: DATE;
        |end.""".stripMargin
    def describe(w: Unit): String =
      Units.units.groupBy(_.dimension).toVector.sortBy(_._1)
        .map((d, us) => s"$d: ${us.map(_.name).mkString(", ")}").mkString("\n") + "\ncalendar: day, week, month"
    private val dateRe = """\b\d{4}-\d{2}-\d{2}\b""".r
    private def dates(q: String): Vector[String] = dateRe.findAllIn(q).toVector.distinct
    private def numbers(q: String): Vector[String] =
      """\b\d+\b""".r.findAllIn(dateRe.replaceAllIn(q, " ")).toVector.distinct
    private def value(taken: Vector[String], key: String): Option[String] =
      taken.find(_.startsWith(key + ": ")).map(_.drop(key.length + 2))
    /** the slots of a kind, in order */
    private val slots = Map("convert" -> Vector("from", "to", "quantity"),
      "shift" -> Vector("unit", "direction", "n", "date"), "between" -> Vector("unit", "from", "to"))
    def next(w: Unit, question: String, taken: Vector[String]): Vector[String] =
      if taken.lastOption.contains(Frame.end) then Vector.empty
      else value(taken, "kind") match
        case None => Vector("convert", "shift", "between").map("kind: " + _)
        case Some(kind) =>
          slots(kind).find(s => value(taken, s).isEmpty) match
            case None => Vector(Frame.end)
            case Some(slot) =>
              val options = (kind, slot) match
                case ("convert", "from") => Units.units.map(_.name)
                case ("convert", "to") =>
                  val dim = value(taken, "from").flatMap(f => Units.units.find(_.name == f)).map(_.dimension)
                  Units.units.filter(u => dim.forall(_ == u.dimension) && !value(taken, "from").contains(u.name))
                    .map(_.name)
                case ("shift", "unit") => Units.shiftUnits
                case ("between", "unit") => Units.betweenUnits
                case ("shift", "direction") => Vector("after", "before")
                case (_, "quantity" | "n") => numbers(question)
                case (_, "date" | "from" | "to") => dates(question)
                case _ => Vector.empty
              options.map(o => s"$slot: $o")
    def answer(w: Unit, lines: Vector[String]): Either[String, String] =
      val parts = lines.filter(_ != Frame.end).map(l => l.takeWhile(_ != ':') -> l.dropWhile(_ != ':').drop(2)).toMap
      val kind = parts.get("kind")
      // the lines' names to the domain's slots
      val slotted = kind match
        case Some("convert") => parts.collect { case ("quantity", v) => "quantity" -> v; case ("from", v) =>
          "from" -> v; case ("to", v) => "to" -> v; case ("kind", v) => "kind" -> v }
        case Some("shift") => parts.collect { case ("unit", v) => "shift_unit" -> v; case ("direction", v) =>
          "direction" -> v; case ("n", v) => "n" -> v; case ("date", v) => "date1" -> v; case ("kind", v) => "kind" -> v }
        case Some("between") => parts.collect { case ("unit", v) => "between_unit" -> v; case ("from", v) =>
          "date1" -> v; case ("to", v) => "date2" -> v; case ("kind", v) => "kind" -> v }
        case _ => Map.empty[String, String]
      Units.assemble(Units, slotted).toRight("not a meaning of the units").flatMap(Units.run(_, Units))
    /** a point of the domain as its lines (the gold of a drawn question) */
    def lines(p: Units.Point): Vector[String] = (p match
      case Units.Point.Convert(q, f, t) => Vector("kind: convert", s"from: $f", s"to: $t", s"quantity: $q")
      case Units.Point.Shift(d, n, u, fw) => Vector("kind: shift", s"unit: $u",
        s"direction: ${if fw then "after" else "before"}", s"n: $n", s"date: $d")
      case Units.Point.Between(a, b, u) => Vector("kind: between", s"unit: $u", s"from: $a", s"to: $b")) :+ Frame.end

/** THE CORE READING (SPRINT K2): a question read into a domain's lines, each line the best of its options as the
 * model weighs them — `FrameReading`'s loop with no table in it; the least margin kept */
object CoreReading:
  final case class Reading(lines: Vector[String], margin: Double)
  def read[W, R[_]](space: LineSpace[W], w: W, question: String, maxLines: Int = 16)(
      using In[Read, R]): Reading ! R =
    val (system, user) = space.prompt(w, question)
    for
      prompt <- Read.prompt[R](system, user)
      newline <- Read.encode[R]("\n").map(_.head)
      end <- Read.encode[R]("<|im_end|>").map(_.last)
      read <- step(space, w, question, maxLines, prompt, newline, end, Reading(Vector.empty, Double.PositiveInfinity))
    yield read
  private def step[W, R[_]](space: LineSpace[W], w: W, question: String, maxLines: Int, context: Array[Int],
      newline: Int, end: Int, so: Reading)(using In[Read, R]): Reading ! R =
    val options = space.next(w, question, so.lines)
    if options.isEmpty || so.lines.size >= maxLines then pure(so)
    else
      for
        tokens <- Read.each[String, Array[Int], R](options)(o => Read.encode[R](o))
        scores <- Read.score[R](context :+ newline,
          options.zip(tokens).map((o, t) => t :+ (if o == Frame.end then end else newline)))
        ranked = options.zip(scores).sortBy(-_._2)
        margin = ranked.lift(1).fold(Double.PositiveInfinity)(s => ranked.head._2 - s._2)
        line = ranked.head._1
        t <- Read.encode[R](line)
        next <- step(space, w, question, maxLines, (context :+ newline) ++ t, newline, end,
          Reading(so.lines :+ line, math.min(so.margin, margin)))
      yield next
