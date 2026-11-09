package okay.desk.skill

import okay.desk.Table
import okay.desk.calc.{Encode, Term, Ty}
import okay.desk.logic.{Compile, LogicGrammar}

/**
 * THE EXTRACTION CONTRACT (specs/logic.md §5.2): what a reading head must produce for the logic grammar — one of the
 * grammar's constructors at each hole, and, for a copied constant, the span of the task it was read from. A reading
 * is data: a word of constructors with evidence, replayed against the grammar. A choice the grammar does not offer, a
 * constant with no evidence, and a span whose text is not the constant are refused. A reading that stops before the
 * last hole is an ABSTENTION, not an answer: the open hole is a question for the person.
 *
 * The heads see the same questions at every step: the hole's type, the constructors it offers, and whether the choice
 * copies text (a constant) or binds something (a variable, a predicate). The stage metrics compare a reading with
 * the gold word and name the first stage where it leaves it — the goal, a relation, or a copied value.
 */
object Extract:
  /** a span of the task's text: [start, end), and the text it covers */
  final case class Span(start: Int, end: Int, text: String)

  /** where a constant came from: a span of the task, a cell of the loaded data, or the ontology's own knowledge */
  enum Evidence:
    case Text(span: Span)
    case Data(source: String)
    case Known

  final case class Choice(con: String, evidence: Option[Evidence] = None)
  final case class Reading(task: String, choices: Vector[Choice])

  /** what a head is asked at a hole: its type, the constructors offered, the stage it decides */
  final case class Ask(ty: Ty, options: Vector[String], stage: Stage)

  enum Stage:
    case Goal, Relation, Value, Binding, Structure

  /** the next hole of a partial term, as the heads see it */
  def next(g: LogicGrammar, t: Term): Option[Ask] =
    g.nextHole(t).map { case (ty, _, _) => Ask(ty, g.constructors(ty), stageOfType(ty)) }

  def stageOfType(ty: Ty): Stage = ty match
    case Ty.GoalT => Stage.Goal
    case Ty.AtomT(_, _, _) => Stage.Relation
    case Ty.ArgT(_, _, _, _) | Ty.TermT(_) => Stage.Value
    case _ => Stage.Structure

  /** the stage a choice decides: a variable is a binding, a constant a value */
  def stageOf(ty: Ty, con: String): Stage = ty match
    case Ty.ArgT(_, _, _, _) | Ty.TermT(_) if LogicGrammar.isVar(con) => Stage.Binding
    case other => stageOfType(other)

  /** whether the choice is a constant that must be copied (a variable is bound, not copied) */
  def copies(ty: Ty, con: String): Boolean = ty match
    case Ty.ArgT(_, _, _, _) | Ty.TermT(_) => !LogicGrammar.isVar(con)
    case _ => false

  /** the reading replayed against the grammar: a partial term when the reading stops, a refusal when it leaves it */
  def replay(g: LogicGrammar, r: Reading): Either[String, Term] =
    r.choices.zipWithIndex.foldLeft[Either[String, Term]](Right(Term.Hole(Ty.LogicT))) { case (acc, (c, i)) =>
      acc.flatMap(t => g.nextHole(t) match
        case None => Left(s"choice $i after the program was complete")
        case Some((ty, _, put)) =>
          if !g.constructors(ty).contains(c.con) then Left(s"choice $i: $ty offers no ${c.con}")
          else evidence(r.task, ty, c.con, c.evidence).map(_ => put(g.apply(ty, c.con))))
    }

  /** a constant is copied: its span must exist in the task and say exactly the constant */
  private def evidence(task: String, ty: Ty, con: String, ev: Option[Evidence]): Either[String, Unit] =
    if !copies(ty, con) then Right(())
    else ev match
      case None => Left(s"a constant with no evidence: $con")
      case Some(Evidence.Known) => Right(()) // the ontology offers it: the grammar already checked
      case Some(Evidence.Data(source)) =>
        if source.nonEmpty then Right(()) else Left(s"a data evidence with no source: $con")
      case Some(Evidence.Text(span)) =>
        if span.start < 0 || span.end < span.start || span.end > task.length then
          Left(s"a span outside the task: ${span.start}..${span.end}")
        // the span says the constant up to case: the text's surface form, the table's value
        else if !task.substring(span.start, span.end).equalsIgnoreCase(con) then
          Left(s"the span says '${task.substring(span.start, span.end)}', not '$con'")
        // and it is a whole word: 'Eve' inside 'Every' is not a copy of the constant eve
        else if !aligned(task, span.start, span.end) then
          Left(s"the span ${span.start}..${span.end} is part of a word, not a whole one")
        else Right(())

  private def wordChar(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '-'

  /** whether [start, end) covers whole words of the text (`Eve` in `Every` does not) */
  def aligned(text: String, start: Int, end: Int): Boolean =
    start >= 0 && end <= text.length && end > start &&
      (start == 0 || !wordChar(text(start - 1))) && (end == text.length || !wordChar(text(end)))

  /** the first whole-word occurrence of `value` at or after `from`, up to case: the span a copy cites */
  def find(task: String, value: String, from: Int = 0): Option[Span] =
    val lower = task.toLowerCase
    val v = value.toLowerCase
    var at = lower.indexOf(v, math.max(0, from))
    while at >= 0 && !aligned(task, at, at + value.length) do at = lower.indexOf(v, at + 1)
    Option.when(at >= 0)(Span(at, at + value.length, task.substring(at, at + value.length)))

  /** a data evidence names a real cell: `table.column` and a value that occurs there */
  def dataHolds(tables: Map[String, Table], source: String, value: String): Boolean =
    source.split('.').toVector match
      case Vector(table, column) =>
        tables.get(table).exists { t =>
          val i = t.columns.indexOf(column)
          i >= 0 && t.rows.exists(r => r(i).text == value)
        }
      case _ => false

  /** the word of a reading: the constructors chosen, in order */
  def word(r: Reading): Vector[String] = r.choices.map(_.con)

  /** THE GOLD READING: a gold term as a reading, its constants found in the task in order — the corpus' way in, not
   * a reader. A constant the text does not say is `Known` (the ontology's own knowledge, a threshold like 18) */
  def gold(g: LogicGrammar, task: String, term: Term): Either[String, Reading] =
    val letters = Encode.word(term)
    val choices = Vector.newBuilder[Choice]
    var cur: Term = Term.Hole(Ty.LogicT)
    var from = 0
    var error: Option[String] = None
    var i = 0
    while error.isEmpty && i < letters.size do
      val letter = letters(i)
      g.nextHole(cur) match
        case None => error = Some(s"letter $i after the program was complete")
        case Some((ty, _, put)) =>
          if !g.constructors(ty).contains(letter) then error = Some(s"letter $i: $ty offers no $letter")
          else
            val ev =
              if !copies(ty, letter) then None
              else
                find(task, letter, from).orElse(find(task, letter)) match
                  case Some(span) =>
                    from = span.end
                    Some(Evidence.Text(span))
                  case None => Some(Evidence.Known)
            choices += Choice(letter, ev)
            cur = put(g.apply(ty, letter))
      i += 1
    error.toLeft(Reading(task, choices.result()))

  /** where a reading leaves the gold word: the stage, whether it stopped there (an abstention) or chose wrongly,
   * and whether it closed at all */
  final case class Verdict(stage: Option[Stage], at: Int, closed: Boolean, stopped: Boolean):
    def exact: Boolean = stage.isEmpty && closed

  def verdict(g: LogicGrammar, gold: Reading, reading: Reading): Verdict =
    val goldWord = word(gold)
    var t: Term = Term.Hole(Ty.LogicT)
    var i = 0
    var stage: Option[Stage] = None
    var diverged = false
    while !diverged && i < goldWord.size && i < reading.choices.size do
      g.nextHole(t) match
        case None => diverged = true
        case Some((ty, _, put)) =>
          if goldWord(i) != reading.choices(i).con then
            stage = Some(stageOf(ty, goldWord(i)))
            diverged = true
          else
            t = put(g.apply(ty, goldWord(i)))
            i += 1
    val stopped = !diverged && i < goldWord.size
    if stopped then stage = stageAt(g, t)
    else if !diverged && reading.choices.size > goldWord.size then stage = Some(Stage.Structure)
    Verdict(stage, i, replay(g, reading).toOption.exists(t => g.closed(t)), stopped)

  private def stageAt(g: LogicGrammar, t: Term): Option[Stage] =
    g.nextHole(t).map { case (ty, _, _) => stageOfType(ty) }

  /** whether a reading says the gold program and goal up to a consistent renaming of variables: a literal word match
   * is `Verdict.exact`, this is the formal definition — a rename is not a wrong premise, a changed atom still is */
  def same(g: LogicGrammar, gold: Reading, reading: Reading): Boolean =
    (replay(g, gold), replay(g, reading)) match
      case (Right(a), Right(b)) =>
        (Compile.program(a), Compile.program(b)) match
          case (Right(x), Right(y)) => Compile.same(x, y)
          case _                    => false
      case _ => false

  /** the text spans a reading copied from */
  def spans(r: Reading): Set[(Int, Int)] =
    r.choices.flatMap(_.evidence).collect { case Evidence.Text(s) => (s.start, s.end) }.toSet

  /** the stage metrics over a corpus: exact (literal word), semantic (up to alpha-renaming), abstained, closed wrong,
   * wrong per stage, and the copied spans */
  final case class Summary(tasks: Int, exact: Int, abstained: Int, closedWrong: Int, goalWrong: Int,
      relationWrong: Int, valueWrong: Int, spanMatched: Int, spanPredicted: Int, spanGold: Int,
      semanticExact: Int):
    def precision: Double = if spanPredicted == 0 then 1.0 else spanMatched.toDouble / spanPredicted
    def recall: Double = if spanGold == 0 then 1.0 else spanMatched.toDouble / spanGold
    def spanF1: Double =
      if precision + recall == 0 then 0.0 else 2 * precision * recall / (precision + recall)

  object Summary:
    val empty: Summary = Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

  def summarize(cases: Vector[(LogicGrammar, Reading, Reading)]): Summary =
    cases.foldLeft(Summary.empty) { case (s, (g, gold, reading)) =>
      val v = verdict(g, gold, reading)
      val pred = spans(reading)
      val want = spans(gold)
      s.copy(
        tasks = s.tasks + 1,
        exact = s.exact + (if v.exact then 1 else 0),
        abstained = s.abstained + (if !v.closed then 1 else 0),
        closedWrong = s.closedWrong + (if v.closed && !v.exact then 1 else 0),
        // a stage is wrong when a choice was made there, not when the reading stopped before it
        goalWrong = s.goalWrong + (if !v.stopped && v.stage.contains(Stage.Goal) then 1 else 0),
        relationWrong = s.relationWrong + (if !v.stopped && v.stage.contains(Stage.Relation) then 1 else 0),
        valueWrong = s.valueWrong + (if !v.stopped && v.stage.contains(Stage.Value) then 1 else 0),
        spanMatched = s.spanMatched + (pred & want).size,
        spanPredicted = s.spanPredicted + pred.size,
        spanGold = s.spanGold + want.size,
        semanticExact = s.semanticExact + (if same(g, gold, reading) then 1 else 0))
    }
