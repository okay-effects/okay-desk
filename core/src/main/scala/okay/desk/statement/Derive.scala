package okay.desk.statement

import okay.datalog.Term
import okay.desk.tasks.Gold
import okay.effects.json.Json

/**
 * THE COMPILER OF `Problem.Derive` (specs/formalization.md §3.6). The SHAPE of the statement chooses the solver:
 * claims (`Fact`, `If`, `Iff`, `OneOf`) are true-or-false unknowns for the finite solver, which decides what follows,
 * what does not follow and what cannot be at all; ways (`Edge`, `Link`) are Datalog facts with the one rule of
 * reaching, and the question is the goal. A thing is named by the words that named it first.
 */
object Derive:
  private enum Goal:
    /** "is it so": the frame after `Whether` */
    case Is(f: Frame)
    case Asked(f: Frame)
    case Open

  private val asking = Set(Con.Which, Con.From, Con.To, Con.Apart, Con.Cycle, Con.Ends)
  private val claims = Set(Con.Fact, Con.If, Con.Iff, Con.OneOf, Con.Which)
  private val ways = Set(Con.Edge, Con.Link, Con.Path, Con.Round, Con.From, Con.To, Con.Apart, Con.Cycle, Con.Ends)

  private def split(frames: List[Frame]): (Vector[Frame], Vector[Goal]) = frames match
    case Nil => (Vector.empty, Vector.empty)
    case Frame(Con.Whether, _) :: f :: rest if !asking(f.con) && f.con != Con.Whether =>
      val (clues, goals) = split(rest)
      (clues, Goal.Is(f) +: goals)
    case f :: rest =>
      val (clues, goals) = split(rest)
      if f.con == Con.Whether then (clues, Goal.Open +: goals)
      else if asking(f.con) then (clues, Goal.Asked(f) +: goals)
      else (f +: clues, goals)

  def compile(s: Statement, text: String): Either[String, Gold] =
    val (clues, goals) = split(s.frames.toList)
    val cons = s.frames.map(_.con).toSet - Con.Whether
    val names = s.things.map(_.in(text))
    val twice = names.filter(a => names.count(Statement.same(a, _)) > 1)
    if s.problem != Problem.Derive then Left(s"not a derivation: ${s.problem}")
    else if names.isEmpty then Left("nothing is named")
    else if twice.nonEmpty then Left(s"named more than once: ${twice.distinct.mkString(", ")}")
    else if goals.isEmpty then Left("no question")
    else if goals.contains(Goal.Open) then Left("a question of nothing")
    else if cons.subsetOf(claims) then Right(claimed(s, text, clues, goals))
    else if cons.subsetOf(ways) then reached(s, text, clues, goals)
    else Left("claims and ways in one statement")

  private def of(a: Arg): Arg.Mention = a match
    case m: Arg.Mention => m
    case other => sys.error(s"not a name: $other")

  /** claims: every one a true-or-false unknown, the frames the conditions on them */
  private def claimed(s: Statement, text: String, clues: Vector[Frame], goals: Vector[Goal]): Gold =
    def name(thing: Span): String = thing.in(text)
    def said(a: Arg): Json =
      val m = of(a)
      if m.denied then Gold.not(Gold.v(name(m.of))) else Gold.v(name(m.of))
    def condition(f: Frame): Json = (f.con, f.args) match
      case (Con.Fact, Vector(a)) => said(a)
      case (Con.If, Vector(a, b)) => Gold.implies(said(a), said(b))
      case (Con.Iff, Vector(a, b)) => Gold.is(said(a), said(b))
      case (Con.OneOf, args) => Gold.is(Gold.op("count", args.map(said)*), Gold.n(1))
      case _ => sys.error(s"not a claim: ${f.con}")
    val listed = s.frames.filter(_.con == Con.OneOf).flatMap(_.args.map(of(_).of)).distinct
    val alternatives = if listed.isEmpty then s.things else listed
    val asked = goals.collect {
      case Goal.Is(f) => condition(f)
      case Goal.Asked(_) => Gold.which(alternatives.map(t => name(t) -> Gold.v(name(t)))*)
    }
    val keys = if asked.size == 1 then Vector("answer") else asked.indices.toVector.map(i => f"answer${i + 1}%02d")
    Gold.finite(Gold.bools(s.things.map(name)*), clues.map(condition), keys.zip(asked)*)

  /** ways: every thing a constant, every edge a fact, reaching the one rule; the question is the goal */
  private def reached(s: Statement, text: String, clues: Vector[Frame], goals: Vector[Goal]): Either[String, Gold] =
    def c(a: Arg): String = Term.show(of(a).of.in(text))
    val facts = s.things.map(t => s"thing(${Term.show(t.in(text))}).") ++ clues.flatMap(f => (f.con, f.args) match
      case (Con.Edge, Vector(a, b)) => Vector(s"edge(${c(a)}, ${c(b)}).")
      case (Con.Link, Vector(a, b)) => Vector(s"edge(${c(a)}, ${c(b)}).", s"edge(${c(b)}, ${c(a)}).")
      case _ => Vector.empty)
    val reach = "path(X, Y) :- edge(X, Y). path(X, Z) :- edge(X, Y), path(Y, Z)."
    val goal: Vector[(String, String)] = goals.collect {
      case Goal.Is(Frame(Con.Path, Vector(a, b))) => ("", s"path(${c(a)}, ${c(b)})")
      case Goal.Is(Frame(Con.Round, Vector(a))) => ("", s"path(${c(a)}, ${c(a)})")
      case Goal.Asked(Frame(Con.From, Vector(a))) => (s"asked(X) :- path(${c(a)}, X), X != ${c(a)}.", "asked(X)")
      case Goal.Asked(Frame(Con.To, Vector(a))) => (s"asked(X) :- path(X, ${c(a)}), X != ${c(a)}.", "asked(X)")
      case Goal.Asked(Frame(Con.Apart, Vector(a))) =>
        (s"asked(X) :- thing(X), not path(${c(a)}, X), X != ${c(a)}.", "asked(X)")
      case Goal.Asked(Frame(Con.Cycle, _)) => ("asked(X) :- path(X, X).", "asked(X)")
      case Goal.Asked(Frame(Con.Ends, _)) => ("leads(X) :- edge(X, Y). asked(X) :- thing(X), not leads(X).", "asked(X)")
    }
    if clues.exists(f => f.con == Con.Path || f.con == Con.Round) then Left("a way is asked for, never stated")
    else if !clues.exists(f => f.con == Con.Edge || f.con == Con.Link) then Left("nothing leads anywhere")
    else if goals.size != 1 || goal.size != 1 then Left("one question of ways at a time")
    else Right(Gold.Rules((facts :+ reach :+ goal.head._1).filter(_.nonEmpty).mkString("\n"), goal.head._2))

/** a statement to the program of the exact solver its kind and its shape choose */
object Compile:
  def of(s: Statement, text: String): Either[String, Gold] = s.problem match
    case Problem.Assign => Assign.compile(s, text)
    case Problem.Derive => Derive.compile(s, text)
    case other => Left(s"no compiler of $other yet")
