package okay.desk.calc

import okay.scala3.*

/**
 * THE DIALOGUE AS A LEVEL OF THE CALCULUS (specs/pilot.md §3.2, Okay!Chat's Пилот brought over): a hole no source
 * decided — or two candidates too close — is a QUESTION to the person, with the candidates as its options; the
 * person's answer is a SOURCE (`Fill.decided`), asked before every other, so the same reading, run again, refines
 * the SAME term instead of re-reading the question. Before a message is read as a question, its ACT is decided
 * (Пилот's act layer): «да», «нет», «спасибо», «не так: …» are answers, acknowledgements and corrections, not
 * questions about the data. `Turn.decide` is pure and listable: the state and the message in, the action out
 * (Okay!Chat's `Turn.decide(state, evidence): Action`); the strategy performs. Laws in `DialogueSuite`.
 */
object Dialogue:
  /** the person's answers so far: a hole's type → the candidate chosen (a hole's type says its place: a column of
   * these tables in this role, a value of this column — so the answer finds its hole when the reading runs again) */
  type Answers = Vector[(Ty, String)]

  /** THE PERSON AS A SOURCE: an answer given for this hole's type decides it; silent everywhere else */
  def decided[R[_]](answers: Answers): Fill[R] = new Fill[R]:
    def apply(ty: Ty, cs: Vector[String], ctx: Context): Dist ! R =
      // (a value hole takes what the person typed — a number the column's values do not list)
      pure(answers.collectFirst { case (t, c) if t == ty && (cs.contains(c) || t.isInstanceOf[Ty.ValueT]) => Vector((c, 0.0)) }.getOrElse(Vector.empty))

  /** what a reading ends in: a closed term, or the hole it stopped at with the candidates to offer */
  enum Outcome:
    case Closed(term: Term)
    case Open(ty: Ty, term: Term, candidates: Vector[String], steps: List[Strategy.Step])

  /** the candidates offered for an open hole: the best two or three the sources weighed, else the grammar's (a few) */
  def offered(g: Rules, ty: Ty, dist: Dist, limit: Int = 12): Vector[String] =
    val weighed = dist.filter(_._1 != Fill.refuse).sortBy(-_._2).map(_._1)
    val cs = if weighed.size >= 2 then weighed.take(3) else g.constructors(ty).take(limit)
    cs.distinct

  /** the reading's outcome in a row: greedy with the fill, and — where it stopped — the hole weighed once more for
   * the candidates to offer (a deterministic strategy: the same steps lead to the same hole) */
  def read[R[_]](g: Rules, ctx: Context, fill: Fill[Strategy.Rest[R]], margin: Double = 0.0): Outcome ! R =
    Strategy.greedyIn[R](g, ctx, fill, margin).flatMap { (steps, term) =>
      term match
        case Some(t) => pure(Outcome.Closed(t))
        case None =>
          steps.lastOption match
            case Some((ty, at)) =>
              val cs = g.constructors(ty)
              Strategy.weighOnce[R](fill, ty, cs, ctx.copy(term = at)).map(d => Outcome.Open(ty, at, offered(g, ty, d), steps))
            case None => pure(Outcome.Open(Ty.Program, ctx.term, g.constructors(Ty.Program), steps))
    }

  /** THE QUESTION OF A HOLE, in the person's language: a piece per kind of hole (`ask:<kind>`, data in the language
   * files; English and Russian built in), the candidates named */
  def question(ty: Ty, candidates: Vector[String], lang: Language): String =
    val kind = ty match
      case Ty.Program | Ty.KindT => "program"
      case Ty.TableT => "table"
      case Ty.LinkT(_) | Ty.ReverseLinkT(_) => "link"
      case Ty.ColumnT(_, Role.Where, _, _) => "where"
      case Ty.ColumnT(_, Role.Group, _, _) => "group"
      case Ty.ColumnT(_, Role.Agg, _, _) => "aggCol"
      case Ty.OpT(_) => "op"
      case Ty.ValueT(_, _) => "value"
      case Ty.FnT(_, _) => "fn"
      case Ty.OrderT(_, _, _) => "order"
      case Ty.DirT => "dir"
      case Ty.LimitT(_) => "limit"
      case Ty.NodeT(_) => "node"
      case Ty.DirectionT => "direction"
      case _ => "word"
    val names = candidates.map(c => name(ty, c, lang))
    val piece = lang.pieces.get(s"ask:$kind").flatMap(_.headOption)
      .orElse(builtInAsk(lang.code).get(kind)).getOrElse(builtInAsk("en")(kind))
    piece.replace("{column}", ty match { case Ty.ValueT(_, c) => c; case _ => "" }).replace("{options}", names.mkString(" / "))

  /** a candidate as the person sees it: `none` said in words, a constructor's name otherwise */
  def name(ty: Ty, c: String, lang: Language): String =
    if ty == Ty.DirT then lang.pieces.get(s"ask:$c").flatMap(_.headOption).getOrElse(
      if lang.code == "ru" then (if c == "desc" then "по убыванию" else "по возрастанию") else (if c == "desc" then "largest first" else "smallest first"))
    else if c == "none" then (ty match
      case Ty.LimitT(_) => lang.pieces.get("ask:all").flatMap(_.headOption).getOrElse(if lang.code == "ru" then "все" else "all")
      case _ => lang.pieces.get("ask:none").flatMap(_.headOption).getOrElse(if lang.code == "ru" then "без этого" else "none"))
    else c

  /** the built-in questions; a language file's `pieces` under `ask:<kind>` override them */
  def builtInAsk(code: String): Map[String, String] = if code == "ru" then Map(
    "program" -> "Не уверен, о чём вопрос: {options}?", "table" -> "О какой таблице вопрос: {options}?",
    "link" -> "Считать вместе с таблицей по ссылке: {options}?", "where" -> "По какому столбцу условие: {options}?",
    "group" -> "По какому столбцу группировать: {options}?", "aggCol" -> "Какой столбец считать: {options}?",
    "op" -> "Какое сравнение: {options}?", "value" -> "Какое значение {column}: {options}?",
    "fn" -> "Что посчитать: {options}?", "order" -> "По какому столбцу упорядочить: {options}?",
    "dir" -> "В каком порядке: {options}?", "limit" -> "Сколько строк показать: {options}?",
    "node" -> "О каком узле вопрос: {options}?", "direction" -> "В какую сторону: {options}?",
    "word" -> "Уточните: {options}?")
  else Map(
    "program" -> "Not sure what the question asks: {options}?", "table" -> "Which table is the question about: {options}?",
    "link" -> "Read together with the linked table: {options}?", "where" -> "Which column is the condition on: {options}?",
    "group" -> "Which column to group by: {options}?", "aggCol" -> "Which column to compute over: {options}?",
    "op" -> "Which comparison: {options}?", "value" -> "Which value of {column}: {options}?",
    "fn" -> "What to compute: {options}?", "order" -> "Which column to order by: {options}?",
    "dir" -> "In which order: {options}?", "limit" -> "How many rows to show: {options}?",
    "node" -> "Which node is the question about: {options}?", "direction" -> "In which direction: {options}?",
    "word" -> "Please choose: {options}?")

  /** THE ACT of a message (Пилот's act layer, before any reading): what the person is doing with it */
  enum Act:
    case Yes, No, Thanks
    /** «не так: по странам», "no, by country" — what follows the correction word */
    case Correction(rest: String)
    case Question

  object Act:
    /** the act by the languages' word lists (`yes`, `no`, `thanks`, `correction`); a message with more than a few words
     * beyond the act's own is a question — "no orders in Lviv?" is not a No */
    def of(message: String, languages: Vector[Language]): Act =
      val original = message.trim.stripSuffix("!").stripSuffix(".").trim
      val m = original.toLowerCase
      val words = m.split("\\s+").filter(_.nonEmpty)
      def starts(key: String): Option[String] = languages.flatMap(_.list(key)).sortBy(-_.length)
        .find(p => m == p || m.startsWith(p + " ") || m.startsWith(p + ",") || m.startsWith(p + ":"))
      starts("correction") match
        case Some(p) => Correction(original.drop(p.length).dropWhile(c => c == ',' || c == ':' || c == ' ').trim)
        case None =>
          if words.size <= 3 && starts("thanks").isDefined then Thanks
          else if words.size <= 2 && starts("yes").isDefined then Yes
          else if words.size <= 2 && starts("no").isDefined then No
          else Question

  /** THE TURN (Okay!Chat's `Turn.decide`): the state of the dialogue and the message in; the action out — pure */
  object Turn:
    /** what is pending: the question last read and the hole it stopped at, with the answers given so far */
    final case class State(question: Option[String], pending: Option[(Ty, Vector[String])], answers: Answers)
    enum Action:
      /** the pending hole answered by a candidate: the question read again with the answer as a source */
      case Answer(question: String, answers: Answers)
      /** a correction to the last question: read again with the correction appended, the answers kept */
      case Correct(question: String, answers: Answers)
      /** a new question read afresh */
      case Read(question: String)
      case Acknowledge
      /** the person said yes or no and nothing is pending: nothing to do but say so */
      case Nothing

    def decide(s: State, message: String, languages: Vector[Language]): Action =
      Act.of(message, languages) match
        case Act.Thanks => Action.Acknowledge
        case Act.Yes => s.pending.zip(s.question).map((p, q) => Action.Answer(q, s.answers :+ (p._1, p._2.head)))
          .getOrElse(Action.Nothing)
        case Act.No => s.pending.zip(s.question).flatMap((p, q) => p._2.lift(1).map(c => Action.Answer(q, s.answers :+ (p._1, c))))
          .getOrElse(Action.Nothing)
        case Act.Correction(rest) =>
          // a candidate named in the correction answers the pending hole; else the correction joins the question
          val named = s.pending.flatMap((ty, cs) => cs.find(c => c != "none" && rest.toLowerCase.contains(c.toLowerCase)).map(c => (ty, c)))
          (s.question, named) match
            case (Some(q), Some(a)) => Action.Answer(q, s.answers :+ a)
            case (Some(q), None) if rest.nonEmpty => Action.Correct(s"${q.stripSuffix("?").stripSuffix(".")} — $rest", s.answers)
            case _ => Action.Read(message)
        case Act.Question => Action.Read(message)

    /** a candidate chosen by its button: the pending hole answered */
    def chosen(s: State, choice: String): Option[Action] =
      s.pending.zip(s.question).map((p, q) => Action.Answer(q, s.answers :+ (p._1, choice)))
