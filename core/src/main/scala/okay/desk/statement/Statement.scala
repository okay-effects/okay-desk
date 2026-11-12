package okay.desk.statement

/**
 * THE STATEMENT OF A TASK (specs/formalization.md §3): what a model's reading of a task is — never a solution and
 * never a program of some solver. It is the kind of problem, then, sentence by sentence, a few FRAMES: a constructor
 * from the kind's closed set and pieces copied from that sentence. A name is a piece of the text; what it names is
 * the piece that named it first, so "the same thing as before" is a pointer into the text and nothing is invented.
 * No topic is in the constructors: an order, a "goes with", an "every" — what a sentence states, not what it is about.
 *
 * Every decision is local (one sentence and what was named before it) and closed (one of a few constructors, a piece
 * of the sentence, one of the things already named): the decisions a weak model can make, each of them checkable.
 * The compilers (`Assign`, …) take a statement to the program of an exact solver; a statement is data.
 */

/** a piece of the task's text, copied: [from, until) */
final case class Span(from: Int, until: Int):
  def in(text: String): String = text.substring(from, until)
  def within(outer: Span): Boolean = outer.from <= from && until <= outer.until

/** THE KIND OF PROBLEM, the first decision — it chooses the solver's family and the constructors a sentence may take */
enum Problem:
  /** what follows from facts and rules: okay-datalog */
  case Derive
  /** who has what, who is where: things on positions, exactly one each — the grid solver, finite constraints */
  case Assign
  /** how to get from a state to a state: planning */
  case Plan
  /** a number from the numbers given: arithmetic, finite constraints */
  case Calculate
  /** what the opened tables answer */
  case Query

/** what an argument of a constructor is */
enum Role:
  /** a name said here for the first time: a declared thing */
  case Fresh
  /** a name: of something new at its first mention, of the thing named before after that */
  case Thing
  /** a statement that is so or is not: a name as a thing's, said plainly or denied ("the street is not wet") */
  case Claim
  /** a number as the text writes it */
  case Number
  /** one of the attributes the statement declared, by its order in the text */
  case Attribute

/** an argument: a piece of the sentence and what it denotes */
enum Arg:
  /**
   * the words `at` name the thing first named by the words `of` (`of == at`: named here for the first time);
   * `denied`: a claim said not to be so
   */
  case Mention(at: Span, of: Span, denied: Boolean = false)
  case Number(at: Span)
  case Pick(index: Int)

  def span: Option[Span] = this match
    case Mention(at, _, _) => Some(at)
    case Number(at) => Some(at)
    case Pick(_) => None

/**
 * THE CONSTRUCTORS, each of one kind of problem; `many`: the last role repeats (a list).
 *
 * ASSIGN: things stand on positions 1..n, and the things of one attribute take every position once. The positions
 * may be a line (houses, floors, places in a race, a scale of heights) or no line at all (who has which hat): the
 * same statement, read by the constructors used. `Before` is the smaller position; a text's "left", "below", "ahead"
 * or "shorter" is a reader's choice between `Before` and `After`, right as long as it is the same choice throughout.
 */
enum Con(val problem: Problem, val roles: Vector[Role], val many: Boolean = false):
  /** these things are of one attribute: different, and one on every position */
  case Attribute extends Con(Problem.Assign, Vector(Role.Fresh), many = true)
  /** on the same position: "the Englishman lives in the red house" */
  case Same extends Con(Problem.Assign, Vector(Role.Thing, Role.Thing))
  /** not on the same position */
  case Differ extends Con(Problem.Assign, Vector(Role.Thing, Role.Thing))
  /** the first is with the second or with the third */
  case Either extends Con(Problem.Assign, Vector(Role.Thing, Role.Thing, Role.Thing))
  /** somewhere before, somewhere after */
  case Before extends Con(Problem.Assign, Vector(Role.Thing, Role.Thing))
  case After extends Con(Problem.Assign, Vector(Role.Thing, Role.Thing))
  /** on the position just before, just after */
  case JustBefore extends Con(Problem.Assign, Vector(Role.Thing, Role.Thing))
  case JustAfter extends Con(Problem.Assign, Vector(Role.Thing, Role.Thing))
  /** on neighbouring positions, in either order */
  case Beside extends Con(Problem.Assign, Vector(Role.Thing, Role.Thing))
  /** on the position of this number */
  case At extends Con(Problem.Assign, Vector(Role.Thing, Role.Number))
  /** not on the position of this number */
  case NotAt extends Con(Problem.Assign, Vector(Role.Thing, Role.Number))
  /** this many positions between the two, in either order ("one house between them") */
  case Between extends Con(Problem.Assign, Vector(Role.Thing, Role.Thing, Role.Number))
  /** on the middle position */
  case Middle extends Con(Problem.Assign, Vector(Role.Thing))
  /** THE QUESTION: which thing of the attribute is with this one */
  case Ask extends Con(Problem.Assign, Vector(Role.Attribute, Role.Thing))
  /** THE QUESTION: what is on the first position, on the last */
  case First extends Con(Problem.Assign, Vector.empty)
  case Last extends Con(Problem.Assign, Vector.empty)
  /** THE QUESTION of every kind: is the next frame of this sentence so */
  case Whether extends Con(Problem.Assign, Vector.empty)

  /**
   * DERIVE, of claims: things that are so or are not, and what follows. A claim is named by the words that say it
   * ("it rains", "cats"); "every cat is a mammal" and "if it rains, the street is wet" are the same `If`.
   */
  /** this is so (denied: is not) */
  case Fact extends Con(Problem.Derive, Vector(Role.Claim))
  /** if the first, then the second; "only if" is the same with the two changed over */
  case If extends Con(Problem.Derive, Vector(Role.Claim, Role.Claim))
  /** the first exactly when the second */
  case Iff extends Con(Problem.Derive, Vector(Role.Claim, Role.Claim))
  /** exactly one of these is so: the alternatives */
  case OneOf extends Con(Problem.Derive, Vector(Role.Claim), many = true)
  /** THE QUESTION: which of the alternatives is so (of all the claims, when no alternatives are said) */
  case Which extends Con(Problem.Derive, Vector.empty)

  /**
   * DERIVE, of ways: things and what leads from one to another — a flight, a road, "needs", "imports", "is a parent
   * of" — and what is reached by any number of steps.
   */
  /** the first leads to the second */
  case Edge extends Con(Problem.Derive, Vector(Role.Thing, Role.Thing))
  /** each leads to the other */
  case Link extends Con(Problem.Derive, Vector(Role.Thing, Role.Thing))
  /** after `Whether`: there is a way from the first to the second; a way from this thing back to itself */
  case Path extends Con(Problem.Derive, Vector(Role.Thing, Role.Thing))
  case Round extends Con(Problem.Derive, Vector(Role.Thing))
  /** THE QUESTION: which other things are reached from this one; reach this one; are not reached from this one */
  case From extends Con(Problem.Derive, Vector(Role.Thing))
  case To extends Con(Problem.Derive, Vector(Role.Thing))
  case Apart extends Con(Problem.Derive, Vector(Role.Thing))
  /** THE QUESTION: which things have a way back to themselves; which lead nowhere */
  case Cycle extends Con(Problem.Derive, Vector.empty)
  case Ends extends Con(Problem.Derive, Vector.empty)

  def in(p: Problem): Boolean = this == Con.Whether || problem == p

object Con:
  def of(problem: Problem): Vector[Con] = values.toVector.filter(_.in(problem))

final case class Frame(con: Con, args: Vector[Arg])

/** a sentence of the task, and what it states (nothing, for a sentence that only sets the scene) */
final case class Sentence(at: Span, frames: Vector[Frame])

final case class Statement(problem: Problem, sentences: Vector[Sentence]):
  def frames: Vector[Frame] = sentences.flatMap(_.frames)
  def mentions: Vector[Arg.Mention] = frames.flatMap(_.args).collect { case m: Arg.Mention => m }
  /** the things named: the pieces that named them first, in the text's order */
  def things: Vector[Span] = mentions.filter(m => m.at == m.of).map(_.at).distinct

  /**
   * THE STATEMENT IS OF THIS TEXT: its sentences are the text's, every constructor is of the statement's kind and
   * has the arguments of its roles, every piece is copied from the frame's own sentence, a number reads as one, and
   * a name that names something again points at an earlier first naming.
   */
  def check(text: String): Either[String, Unit] =
    def fail(why: String): Either[String, Unit] = Left(why)
    val firsts = things.toSet
    def named(at: Span, of: Span): Boolean = at == of || firsts(of) && of.until <= at.from
    def arg(s: Sentence, f: Frame, role: Role, a: Arg): Either[String, Unit] =
      val inside = a.span.forall(p => p.from < p.until && p.within(s.at))
      if !inside then fail(s"${f.con}: a piece outside its sentence «${s.at.in(text)}»")
      else (role, a) match
        case (Role.Fresh, Arg.Mention(at, of, false)) if at == of => Right(())
        case (Role.Thing, Arg.Mention(at, of, false)) if named(at, of) => Right(())
        case (Role.Claim, Arg.Mention(at, of, _)) if named(at, of) => Right(())
        case (Role.Number, Arg.Number(at)) if Numbers.of(at.in(text)).nonEmpty => Right(())
        case (Role.Attribute, Arg.Pick(i)) if i >= 0 && i < frames.count(_.con == Con.Attribute) => Right(())
        case _ => fail(s"${f.con}: not a $role in «${s.at.in(text)}»")
    def frame(s: Sentence, f: Frame): Either[String, Unit] =
      val roles = if f.con.many then f.con.roles ++ Vector.fill(f.args.size - f.con.roles.size)(f.con.roles.last)
        else f.con.roles
      if !f.con.in(problem) then fail(s"${f.con} is not a constructor of $problem")
      else if roles.size != f.args.size then fail(s"${f.con} takes ${f.con.roles.size} pieces, not ${f.args.size}")
      else first(roles.zip(f.args))((role, a) => arg(s, f, role, a))
    if sentences.map(_.at) != Sentences.of(text) then fail("the sentences are not the text's")
    else first(sentences.flatMap(s => s.frames.map(s -> _)))(frame)

  private def first[A](xs: Vector[A])(f: A => Either[String, Unit]): Either[String, Unit] =
    xs.foldLeft[Either[String, Unit]](Right(()))((acc, x) => acc.flatMap(_ => f(x)))

object Statement:
  /** two pieces say the same name: letter for letter, a capital at the start of a sentence aside */
  def same(a: String, b: String): Boolean = a.equalsIgnoreCase(b)

/**
 * THE SENTENCES OF A TEXT, where a reader reads them: a sentence ends at a full stop, a question mark or an
 * exclamation mark (with a closing quote or bracket after it) before a space or the end, and at the end of a line.
 * The number of a list item ("3. ") begins its sentence and does not end one.
 */
object Sentences:
  private val closers = "\"»”’')"

  def of(text: String): Vector[Span] =
    val out = Vector.newBuilder[Span]
    var start = 0
    def close(end: Int): Unit =
      var (a, b) = (start, end)
      while a < b && text(a).isWhitespace do a += 1
      while b > a && text(b - 1).isWhitespace do b -= 1
      if a < b then out += Span(a, b)
      start = end
    var i = 0
    while i < text.length do
      val c = text(i)
      if c == '\n' then close(i)
      else if c == '.' || c == '?' || c == '!' then
        var end = i + 1
        while end < text.length && closers.contains(text(end)) do end += 1
        val said = text.substring(start, i).trim
        val number = c == '.' && said.nonEmpty && said.forall(_.isDigit)
        if (end == text.length || text(end).isWhitespace) && !number then
          close(end)
          i = end - 1
      i += 1
    close(text.length)
    out.result()
