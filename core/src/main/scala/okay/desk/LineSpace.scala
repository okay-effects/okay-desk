package okay.desk

import okay.desk.skill.Read
import okay.frames.{Choice, Hole, Space}
import okay.inlay.domain.Units
import okay.scala3.*

/**
 * A DOMAIN OF THE CORE MODEL (SPRINT K2): a world, the language of its lines, the options of the next line, the
 * exact run. The core model is one: it reads a question into lines, each chosen among the options the domain offers
 * there, given the domain's rule and its world in words — the same prompt shape in every domain (the rule; the world;
 * "Question: …"), so a model trained on several reads them all, and a domain it never saw is a test of the core.
 */
/** a domain's reading: its lines in order, `end` the last (the term of `LineSpace.asSpace`) */
final case class LineTerm(lines: Vector[String])

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
  /** THE DOMAIN AS AN OKAY-FRAMES SPACE (`LineSpace.asSpace`): the reader weighs its holes, the laws check it */
  lazy val space: Space[W, LineTerm] = LineSpace.asSpace(this)

object LineSpace:
  /**
   * A DOMAIN'S LINES AS A SPACE OF MEANINGS (okay-frames `Space`): each line a hole (`line 1`, `line 2`, …) of the
   * options `next` offers after the lines before; the term the lines, `end` the last; the canonical words the lines
   * themselves — already the domain's exact language — joined by "; ", and parsed back by splitting
   */
  def asSpace[W](s: LineSpace[W]): Space[W, LineTerm] = new Space[W, LineTerm]:
    def name: String = s.name
    def next(w: W, text: String, chosen: Vector[Choice]): Option[Hole] =
      val lines = chosen.map(_.option)
      Option.when(!lines.lastOption.contains(Frame.end))(Hole(s"line ${lines.size + 1}", s.next(w, text, lines)))
    def assemble(w: W, chosen: Vector[Choice]): Option[LineTerm] =
      Option.when(chosen.lastOption.exists(_.option == Frame.end))(LineTerm(chosen.map(_.option)))
    def path(w: W, t: LineTerm): Vector[Choice] = t.lines.zipWithIndex.map((l, i) => Choice(s"line ${i + 1}", l))
    def say(w: W, t: LineTerm, language: String): String = t.lines.mkString("; ")
    def parse(w: W, words: String, language: String): Option[LineTerm] =
      Option(words.split("; ").toVector).filter(_.lastOption.contains(Frame.end)).map(LineTerm(_))

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
      val renames = Map("convert" -> Map("quantity" -> "quantity", "from" -> "from", "to" -> "to"),
        "shift" -> Map("unit" -> "shift_unit", "direction" -> "direction", "n" -> "n", "date" -> "date1"),
        "between" -> Map("unit" -> "between_unit", "from" -> "date1", "to" -> "date2"))
      val slotted = kind.flatMap(renames.get).fold(Map.empty[String, String])(r =>
        parts.collect { case (k, v) if r.contains(k) => r(k) -> v } ++ kind.map("kind" -> _))
      Units.assemble(Units, slotted).toRight("not a meaning of the units").flatMap(Units.run(_, Units))
    /** a point of the domain as its lines (the gold of a drawn question) */
    def lines(p: Units.Point): Vector[String] = (p match
      case Units.Point.Convert(q, f, t) => Vector("kind: convert", s"from: $f", s"to: $t", s"quantity: $q")
      case Units.Point.Shift(d, n, u, fw) => Vector("kind: shift", s"unit: $u",
        s"direction: ${if fw then "after" else "before"}", s"n: $n", s"date: $d")
      case Units.Point.Between(a, b, u) => Vector("kind: between", s"unit: $u", s"from: $a", s"to: $b")) :+ Frame.end

/**
 * THE CORE READING (SPRINT K2): a question read into a domain's lines, each line the best of its options as the
 * model weighs them — okay-frames' `Reading` over the domain's `space`, driven here by the `Read` effect: a hole's
 * options scored after the context, the best chosen (`weighed`), a hole of one option taken with no forward
 * (`forced`); the least margin kept
 */
object CoreReading:
  final case class Reading(lines: Vector[String], margin: Double)
  private type Frames[W] = okay.frames.Reading[W, LineTerm]
  def read[W, R[_]](space: LineSpace[W], w: W, question: String, maxLines: Int = 16)(
      using In[Read, R]): Reading ! R =
    val (system, user) = space.prompt(w, question)
    for
      prompt <- Read.prompt[R](system, user)
      newline <- Read.encode[R]("\n").map(_.head)
      end <- Read.encode[R]("<|im_end|>").map(_.last)
      read <- step(maxLines, prompt, newline, end, okay.frames.Reading(space.space, w, question))
    yield Reading(read.chosen.map(_.option), read.margin)
  private def step[W, R[_]](maxLines: Int, context: Array[Int], newline: Int, end: Int, r: Frames[W])(
      using In[Read, R]): Frames[W] ! R =
    r.hole match
      case Some(h) if h.options.nonEmpty && r.chosen.size < maxLines =>
        val chosen: Frames[W] ! R = r.forced match
          case Some(only) => pure(r.chose(only))
          case None =>
            for
              tokens <- Read.each[String, Array[Int], R](h.options)(o => Read.encode[R](o))
              scores <- Read.score[R](context :+ newline,
                h.options.zip(tokens).map((o, t) => t :+ (if o == Frame.end then end else newline)))
            yield r.weighed(scores)
        for
          next <- chosen
          t <- Read.encode[R](next.chosen.last.option)
          rest <- step(maxLines, (context :+ newline) ++ t, newline, end, next)
        yield rest
      case _ => pure(r)

/** A CLUE'S WORLD: the positions in a row and the lists placed on them (a logic puzzle's, okay-desk `puzzle`) */
final case class ClueWorld(positions: Int, sorts: Vector[okay.desk.puzzle.Sort])

/**
 * A LOGIC PUZZLE'S CLUE (SPRINT K2, the domain held out of training): one sentence of a grid puzzle read into its
 * constraint — the relation, the things it names (values of the lists), a position or a distance where it has one.
 * The exact part is the constraint itself (the solver takes it); two readings are the same when their constraints are
 * (a symmetric relation in either order).
 */
object Clues extends LineSpace[ClueWorld]:
  import okay.desk.puzzle.{Constraint, Ref}
  val name = "clues"
  val rule: String =
    """You read one clue of a logic puzzle into lines, one at a time, each line one of the options: relation: same |
      |differ | at | not at | middle | left of | right next to | beside | apart; then the things it names, a: VALUE
      |(and b: VALUE for a relation of two); then n: a position or a distance where the relation has one;
      |end.""".stripMargin
  def describe(w: ClueWorld): String =
    s"positions: 1 to ${w.positions}\n" + w.sorts.map(s => s"${s.name}: ${s.values.mkString(", ")}").mkString("\n")
  private val two = Set("same", "differ", "left of", "right next to", "beside", "apart")
  private val counted = Set("at", "not at", "apart")
  private def value(taken: Vector[String], key: String): Option[String] =
    taken.find(_.startsWith(key + ": ")).map(_.drop(key.length + 2))
  def next(w: ClueWorld, question: String, taken: Vector[String]): Vector[String] =
    if taken.lastOption.contains(Frame.end) then Vector.empty
    else
      val values = w.sorts.flatMap(_.values)
      value(taken, "relation") match
        case None => Vector("same", "differ", "at", "not at", "middle", "left of", "right next to", "beside", "apart")
          .map("relation: " + _)
        case Some(r) =>
          if value(taken, "a").isEmpty then values.map("a: " + _)
          else if two(r) && value(taken, "b").isEmpty then values.filterNot(value(taken, "a").contains).map("b: " + _)
          else if counted(r) && value(taken, "n").isEmpty then
            (if r == "apart" then 2 to math.max(2, w.positions - 1) else 1 to w.positions).map(n => s"n: $n").toVector
          else Vector(Frame.end)
  /** the constraint of a reading's lines */
  def constraint(w: ClueWorld, lines: Vector[String]): Option[Constraint] =
    def ref(v: String) = w.sorts.find(_.values.contains(v)).map(s => Ref(s.name, v))
    for
      r <- value(lines, "relation"); a <- value(lines, "a").flatMap(ref)
      c <- r match
        case "same" => value(lines, "b").flatMap(ref).map(Constraint.Same(a, _))
        case "differ" => value(lines, "b").flatMap(ref).map(Constraint.Differ(a, _))
        case "left of" => value(lines, "b").flatMap(ref).map(Constraint.Left(a, _))
        case "right next to" => value(lines, "b").flatMap(ref).map(Constraint.RightNext(a, _))
        case "beside" => value(lines, "b").flatMap(ref).map(Constraint.Adjacent(a, _))
        case "apart" => for b <- value(lines, "b").flatMap(ref); n <- value(lines, "n").flatMap(_.toIntOption)
          yield Constraint.Apart(a, b, n)
        case "at" => value(lines, "n").flatMap(_.toIntOption).map(Constraint.At(a, _))
        case "not at" => value(lines, "n").flatMap(_.toIntOption).map(Constraint.NotAt(a, _))
        case "middle" => Some(Constraint.Middle(a))
        case _ => None
    yield c
  /** a constraint as its lines (the gold of a drawn clue) */
  def lines(c: Constraint): Vector[String] = (c match
    case Constraint.Same(a, b) => Vector("relation: same", s"a: ${a.value}", s"b: ${b.value}")
    case Constraint.Differ(a, b) => Vector("relation: differ", s"a: ${a.value}", s"b: ${b.value}")
    case Constraint.At(a, n) => Vector("relation: at", s"a: ${a.value}", s"n: $n")
    case Constraint.NotAt(a, n) => Vector("relation: not at", s"a: ${a.value}", s"n: $n")
    case Constraint.Middle(a) => Vector("relation: middle", s"a: ${a.value}")
    case Constraint.Left(a, b) => Vector("relation: left of", s"a: ${a.value}", s"b: ${b.value}")
    case Constraint.RightNext(a, b) => Vector("relation: right next to", s"a: ${a.value}", s"b: ${b.value}")
    case Constraint.Adjacent(a, b) => Vector("relation: beside", s"a: ${a.value}", s"b: ${b.value}")
    case Constraint.Apart(a, b, d) => Vector("relation: apart", s"a: ${a.value}", s"b: ${b.value}", s"n: $d")) :+
    Frame.end
  /** the constraint in a canonical form: a symmetric relation's two things in order */
  private def canonical(c: Constraint): String = c match
    case Constraint.Same(a, b) => s"same ${Vector(a.value, b.value).sorted.mkString(" ")}"
    case Constraint.Differ(a, b) => s"differ ${Vector(a.value, b.value).sorted.mkString(" ")}"
    case Constraint.Adjacent(a, b) => s"beside ${Vector(a.value, b.value).sorted.mkString(" ")}"
    case Constraint.Apart(a, b, d) => s"apart ${Vector(a.value, b.value).sorted.mkString(" ")} $d"
    case other => other.toString
  def answer(w: ClueWorld, lines: Vector[String]): Either[String, String] =
    constraint(w, lines).map(canonical).toRight("not a constraint")
