package okay.desk.statement

import okay.desk.puzzle.{Constraint, Puzzle, Question, Ref, Sort}
import okay.desk.tasks.Gold
import okay.effects.json.Json

/**
 * THE COMPILER OF `Problem.Assign` (specs/formalization.md §3.6): a statement to the program of the exact solver that
 * fits it. The model chose the kind; the SHAPE of what it stated chooses the solver here — declared attributes with
 * clues of places in a row and "which … is with …" questions are a `Puzzle` for the grid solver; anything else (an
 * either–or, who has what with no row at all, a line with no attributes, "who is last", "is it so") is a finite
 * constraint program, every thing a position 1..n. The things are named by the words that named them first; nothing
 * else of the text is used.
 */
object Assign:
  private enum Goal:
    case Of(attribute: Int, of: Arg)
    case End(last: Boolean)
    case Is(clue: Frame)
    /** "is it so" with nothing said after it */
    case Open

  private val asking = Set(Con.Ask, Con.First, Con.Last, Con.Whether, Con.Attribute)
  /** the clues of a place in the row: with none the row is not of the task, and what fits, fits in any order */
  private val placed = Set(Con.Before, Con.After, Con.JustBefore, Con.JustAfter, Con.Beside, Con.At, Con.Middle,
    Con.NotAt, Con.Between)

  /** a sentence's frames as clues and goals: `Whether` makes the frame after it the question */
  private def split(frames: List[Frame]): (Vector[Frame], Vector[Goal]) = frames match
    case Nil => (Vector.empty, Vector.empty)
    case Frame(Con.Whether, _) :: f :: rest if !asking(f.con) =>
      val (clues, goals) = split(rest)
      (clues, Goal.Is(f) +: goals)
    case f :: rest =>
      val (clues, goals) = split(rest)
      (f.con, f.args) match
        case (Con.Attribute, _) => (clues, goals)
        case (Con.Ask, Vector(Arg.Pick(i), of)) => (clues, Goal.Of(i, of) +: goals)
        case (Con.First, _) => (clues, Goal.End(false) +: goals)
        case (Con.Last, _) => (clues, Goal.End(true) +: goals)
        case (Con.Whether | Con.Ask, _) => (clues, Goal.Open +: goals)
        case _ => (f +: clues, goals)

  /** `whole`: no question is asked — the grid itself is the answer (a benchmark that asks for the whole grid) */
  def compile(s: Statement, text: String, whole: Boolean = false): Either[String, Gold] =
    // a frame without the pieces of its roles (a reader that took too few) is a refusal, not a failure
    try compiled(s, text, whole) catch case e: RuntimeException => Left(e.getMessage)

  private def compiled(s: Statement, text: String, whole: Boolean): Either[String, Gold] =
    val declared = s.frames.filter(_.con == Con.Attribute).map(_.args.collect { case Arg.Mention(at, _, _) => at })
    val groups = if declared.isEmpty then Vector(s.things) else declared
    val things = groups.flatten
    def name(thing: Span): String = thing.in(text)
    def thing(a: Arg): Span = a match
      case Arg.Mention(_, of, _) => of
      case other => sys.error(s"not a name: $other")
    def number(a: Arg): Int = a.span.flatMap(s => Numbers.of(s.in(text))).get
    val (clues, goals) = split(s.frames.toList)
    val stray = s.things.filterNot(things.contains)
    val twice = things.filter(t => things.count(u => Statement.same(name(t), name(u))) > 1)
    // what no arrangement can be: a thing set against itself, two things of one attribute on one position
    val pairs = clues.filter(_.con != Con.Either).map(f => f -> f.args.collect { case Arg.Mention(_, of, _) => of })
    val itself = pairs.collectFirst { case (f, Vector(a, b)) if a == b => s"${f.con}: ${name(a)} and itself" }
    val classes = pairs.collect { case (Frame(Con.Same, _), Vector(a, b)) => (a, b) }
      .foldLeft(things.map(Set(_))) { case (sofar, (a, b)) =>
        val (joined, rest) = sofar.partition(c => c(a) || c(b))
        rest :+ joined.flatten.toSet }
    val together = classes.flatMap(c => groups.map(_.filter(c)).filter(_.size > 1)).headOption.map(of =>
      s"${of.map(name).mkString(" and ")} are of one attribute: never on one position")
    if s.problem != Problem.Assign then Left(s"not an assignment: ${s.problem}")
    else if things.isEmpty then Left("nothing is named")
    else if groups.map(_.size).distinct.size > 1 then Left("the attributes have different numbers of things: " +
      groups.map(g => g.map(name).mkString(", ")).mkString("; "))
    else if stray.nonEmpty then Left(s"not among the things listed: ${stray.map(name).mkString(", ")}")
    else if twice.nonEmpty then Left(s"named more than once: ${twice.map(name).distinct.mkString(", ")}")
    else if goals.isEmpty && !whole then Left("no question")
    else if goals.contains(Goal.Open) then Left("a question of nothing")
    else if itself.orElse(together).nonEmpty then Left(itself.orElse(together).get)
    else if goals.exists(_.isInstanceOf[Goal.End]) && groups.size > 1 then
      Left("the first or the last of which attribute is not said")
    else
      val n = groups.head.size
      val grid = declared.nonEmpty && clues.forall(_.con != Con.Either) && clues.exists(f => placed(f.con)) &&
        goals.forall(_.isInstanceOf[Goal.Of]) && (goals.nonEmpty || whole)
      if grid then
        def ref(t: Span): Ref = Ref((groups.indexWhere(_.contains(t)) + 1).toString, name(t))
        val asks = goals.collect { case Goal.Of(i, of) => Question((i + 1).toString, ref(thing(of))) }
        Right(Gold.Grid(Puzzle(n, groups.zipWithIndex.map((g, i) => Sort((i + 1).toString, g.map(name))),
          clues.map(f => constraint(f, a => ref(thing(a)), number)), asks.headOption, asks.drop(1))))
      else
        def v(a: Arg): Json = Gold.v(name(thing(a)))
        def at(position: Int): Json =
          Gold.which(things.map(t => name(t) -> Gold.is(Gold.v(name(t)), Gold.n(position)))*)
        val distinct = groups.filter(_.size > 1).map(g => Gold.op("distinct", g.map(t => Gold.v(name(t)))*))
        val asked = goals.map {
          case Goal.Of(i, of) => Gold.which(groups(i).map(g => name(g) -> Gold.is(Gold.v(name(g)), v(of)))*)
          case Goal.End(last) => at(if last then n else 1)
          case Goal.Is(f) => condition(f, v, number, n)
          case Goal.Open => sys.error("a question of nothing")
        }
        val keys = if asked.size == 1 then Vector("answer") else asked.indices.toVector.map(i => f"answer${i + 1}%02d")
        Right(Gold.finite(things.map(t => name(t) -> Gold.ints(1, n)),
          distinct ++ clues.map(condition(_, v, number, n)), keys.zip(asked)*))

  private def constraint(f: Frame, ref: Arg => Ref, number: Arg => Int): Constraint = (f.con, f.args) match
    case (Con.Same, Vector(a, b)) => Constraint.Same(ref(a), ref(b))
    case (Con.Differ, Vector(a, b)) => Constraint.Differ(ref(a), ref(b))
    case (Con.Before, Vector(a, b)) => Constraint.Left(ref(a), ref(b))
    case (Con.After, Vector(a, b)) => Constraint.Left(ref(b), ref(a))
    case (Con.JustBefore, Vector(a, b)) => Constraint.RightNext(ref(b), ref(a))
    case (Con.JustAfter, Vector(a, b)) => Constraint.RightNext(ref(a), ref(b))
    case (Con.Beside, Vector(a, b)) => Constraint.Adjacent(ref(a), ref(b))
    case (Con.At, Vector(a, k)) => Constraint.At(ref(a), number(k))
    case (Con.Middle, Vector(a)) => Constraint.Middle(ref(a))
    case (Con.NotAt, Vector(a, k)) => Constraint.NotAt(ref(a), number(k))
    case (Con.Between, Vector(a, b, k)) => Constraint.Apart(ref(a), ref(b), number(k) + 1)
    case _ => sys.error(s"not a clue of a grid: ${f.con}")

  private def condition(f: Frame, v: Arg => Json, number: Arg => Int, n: Int): Json =
    import Gold.{is, op}
    (f.con, f.args) match
      case (Con.Same, Vector(a, b)) => is(v(a), v(b))
      case (Con.Differ, Vector(a, b)) => op("!=", v(a), v(b))
      case (Con.Either, Vector(a, b, c)) => op("or", is(v(a), v(b)), is(v(a), v(c)))
      case (Con.Before, Vector(a, b)) => op("<", v(a), v(b))
      case (Con.After, Vector(a, b)) => op(">", v(a), v(b))
      case (Con.JustBefore, Vector(a, b)) => is(op("+", v(a), Gold.n(1)), v(b))
      case (Con.JustAfter, Vector(a, b)) => is(v(a), op("+", v(b), Gold.n(1)))
      case (Con.Beside, Vector(a, b)) => is(op("abs", op("-", v(a), v(b))), Gold.n(1))
      case (Con.At, Vector(a, k)) => is(v(a), Gold.n(number(k)))
      case (Con.Middle, Vector(a)) => is(v(a), Gold.n((n + 1) / 2))
      case (Con.NotAt, Vector(a, k)) => op("!=", v(a), Gold.n(number(k)))
      case (Con.Between, Vector(a, b, k)) => is(op("abs", op("-", v(a), v(b))), Gold.n(number(k) + 1))
      case _ => sys.error(s"not a clue: ${f.con}")
