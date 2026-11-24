package okay.desk

import okay.desk.skill.Read
import okay.scala3.*

/**
 * THE PERSON, AS AN EFFECT (specs/guarantees.md §3, the operator 2026-11-15): asked where the reading is in doubt —
 * a line's best options — answering with one of them, or none. On the page a handler shows the options as buttons
 * (one press); in a measure a handler answers by the gold frame; in a test, by a list.
 */
enum Person[X]:
  /** the lines decided so far, the options of the next (best first): the one meant, or none of them */
  case Choose(decided: Vector[String], options: Vector[String]) extends Person[Option[String]]

object Person:
  def choose[R[_]](decided: Vector[String], options: Vector[String])(using In[Person, R]): Option[String] ! R =
    perform[Person](Choose(decided, options))

  /** answered by a gold frame: the option offered that is one of its lines (and not decided yet), else none */
  def byFrame[R[_], A](gold: Option[Vector[String]]): Handler[Person, R, A, A] =
    Handler.tail[Person, R, A, A](pure)([X] => (op: Person[X]) => op match
      case Choose(decided, options) => gold.flatMap(g => options.find(o => g.contains(o) && !decided.contains(o))))

  /** never asked: a reading with no dialogue */
  def silent[R[_], A]: Handler[Person, R, A, A] =
    Handler.tail[Person, R, A, A](pure)([X] => (op: Person[X]) => op match
      case Choose(_, options) => options.headOption)

/**
 * THE FRAME READ BY CHOICES, A PROGRAM (specs/frames.md §2): over `Read` (the model: the prompt, the tokens, the
 * options weighed — all of a line's in one operation) and `Person` (asked where in doubt). The same program on the
 * JVM (mlx-scala's handler) and in the page (WebGPU's); nothing in it touches a model or a screen.
 */
object FrameReading:
  /** the lines read, each choice's margin, the questions asked, and whether none of the options offered was meant */
  final case class Reading(lines: Vector[String], margins: Vector[Double], asked: Int = 0,
      unresolved: Boolean = false, runnersUp: Vector[Option[String]] = Vector.empty):
    def margin: Double = margins.minOption.getOrElse(0.0)

  /** where the person is asked: a line's margin below `bar`; the best `offer` options; at most `maxAsks` questions */
  final case class Doubt(bar: Double, offer: Int = 4, maxAsks: Int = 2)

  /**
   * LINKS AS FACTS (§6.3): a line naming a value the question names (`Links`), not yet said, weighs `link` more; a
   * line naming a text value the question does not name — a guess («customer = dee» for «из Львова») — `guess` less
   */
  private def weighed(scores: Vector[Double], options: Vector[String], so: Reading, tables: Map[String, Table],
      question: String, link: Double, guess: Double): Vector[Double] =
    val table = so.lines.headOption.map(_.stripPrefix("table: ").trim).flatMap(tables.get)
    val linked = if link == 0.0 && guess == 0.0 then Set.empty[(String, String)]
      else table.fold(Set.empty)(t => Links.of(t, question).toSet)
    val said = so.lines.flatMap(Links.named).toSet
    def text(v: String) = !v.matches("-?[\\d.]+") && !v.matches("\\d{4}-\\d{2}.*") && !v.startsWith("column ")
    options.zip(scores).map((o, s) => Links.named(o) match
      case Some(n) if linked(n) && !said(n) => s + link
      case Some(n @ (_, v)) if !linked(n) && text(v) => s - guess
      case _ => s)

  /** the options but the conditions on a value the question does not name: a text value not linked (`weighed`'s
   * guesses), a number it does not write (`pruneNumbers`); a line left with none keeps all */
  var pruneNumbers: Boolean = true
  var pruneSingle: Boolean = true
  /** text values not linked pruned too — OFF since 2026-11-18: a value the question says in another language
   * («автобусом» for `bus`) is not linked, and was pruned away from 120 of 970 never-trained questions (FrameLaws);
   * kept, the merged reader's wrong 174 → 138 of 1004, 0.7 → 0.8 s a question (specs/theory.md E5) */
  var pruneText: Boolean = false
  def pruned(options: Vector[String], so: Reading, tables: Map[String, Table], question: String)
      : Vector[String] =
    val table = so.lines.headOption.map(_.stripPrefix("table: ").trim).flatMap(tables.get)
    val linked = table.fold(Set.empty[(String, String)])(t => Links.of(t, question).toSet)
    def text(v: String) = !v.matches("-?[\\d.]+") && !v.matches("\\d{4}-\\d{2}.*") && !v.startsWith("column ")
    // a number the question does not write (a column's own values, offered where they are few) — but 0 and 1
    val written = Frame.numbers(question).toSet ++ Set("0", "1")
    def number(v: String) = v.matches("-?[\\d.]+")
    // a condition's value whatever its operator ("where: amount >= 120" → 120)
    val condition = "^(?:where|part|from|to): (\\S+) (?:=|!=|<|>|<=|>=) (.+)$".r
    // a column of a few values (a term, a quarter, a mark: "the second term" is term = 2) keeps its own; a wide one
    // (amounts, ids) only the numbers the question writes
    val few = table.fold(Set.empty[String])(t => t.columns.indices.filter(i =>
      t.rows.map(_(i).text).distinct.size <= 4).map(t.columns(_)).toSet)
    val kept = options.filter {
      case condition(c, v) if number(v) => written(v) || few(c) || !pruneNumbers
      case o => Links.named(o) match
        case Some(n @ (_, v)) => linked(n) || !text(v) || !pruneText
        case None => true }
    // ONE NUMBER ASKED (an aggregate, no group yet): order, limit and keep cannot change a single row — not weighed
    // (theory E6: decide only what changes the answer); a group chosen later brings them back on its next line
    val answer = so.lines.find(_.startsWith("answer: ")).map(_.stripPrefix("answer: "))
    val single = pruneSingle && answer.exists(a => a != "rows" && a != "cannot") &&
      !so.lines.exists(_.startsWith("group: "))
    val useful = if !single then kept
      else kept.filterNot(o => o.startsWith("order: ") || o.startsWith("limit: ") || o.startsWith("keep: "))
    if useful.isEmpty then options else useful

  // (the section first and one line ahead where in doubt — both negative, theory.md §11 — removed 2026-11-23)

  /** THE CANONICAL READER (the improvements, step 1; theory: the model judges the meaning of plain clauses, the
   * program walks the formal space): the options weighed as their clauses in the question's language (`FrameSay.line`),
   * the lines chosen written so; the frame itself unchanged. Off: the lines as written (`where: city = Lviv`) */
  var canonical: Boolean = false
  private def written(line: String, question: String): String =
    if canonical then FrameSay.line(line, FrameSay.langOf(question)) else line

  /** `start`: lines decided already (a person's choice where two readers parted) — read on from them */
  def read[R[_]](tables: Map[String, Table], question: String, doubt: Option[Doubt] = None, maxLines: Int = 16,
      start: Vector[String] = Vector.empty, link: Double = 0.0,
      guess: Double = 0.0, prune: Boolean = false)(using In[Read, R], In[Person, R]): Reading ! R =
    val (rule, user) = Frame.prompt(tables, question)
    val system = if canonical then Frame.canonicalRule else rule
    for
      prompt <- Read.prompt[R](system, user)
      newline <- Read.encode[R]("\n").map(_.head)
      end <- Read.encode[R]("<|im_end|>").map(_.last)
      decided <- Read.each[String, Array[Int], R](start)(l => Read.encode[R](written(l, question)))
      context = decided.foldLeft(prompt)((c, t) => (c :+ newline) ++ t)
      read <- step(tables, question, doubt, maxLines, link, guess, prune, context, newline, end,
        Reading(start, Vector.fill(start.size)(Double.PositiveInfinity),
          runnersUp = Vector.fill(start.size)(None)))
    yield read

  private def step[R[_]](tables: Map[String, Table], question: String, doubt: Option[Doubt], maxLines: Int,
      link: Double, guess: Double, prune: Boolean, context: Array[Int], newline: Int, end: Int, so: Reading)(
      using In[Read, R], In[Person, R]): Reading ! R =
    if so.lines.lastOption.contains(Frame.end) || so.unresolved || so.lines.size >= maxLines then pure(so)
    else
      // THE OPTIONS ARE THE SPACE'S (okay-frames `Space`: `FrameSpace`, one definition for the reader and the laws);
      // PRUNED (`prune`, theory.md E5) as the page offers them: unwritten numbers are not weighed
      val options = FrameSpace.of(prune).offered(so.lines, tables, question)
      if options.isEmpty then pure(so.copy(lines = so.lines :+ Frame.end))
      else
        for
          tokens <- Read.each[String, Array[Int], R](options)(o => Read.encode[R](written(o, question)))
          // each option's tokens and the line's end after them (a newline; the turn's end after `end`)
          raw <- Read.score[R](context :+ newline,
            options.zip(tokens).map((o, t) => t :+ (if o == Frame.end then end else newline)))
          scores = weighed(raw, options, so, tables, question, link, guess)
          ranked = options.zip(scores).sortBy(-_._2)
          margin = ranked.lift(1).fold(Double.PositiveInfinity)(second => ranked.head._2 - second._2)
          chosen <- doubt match
            case Some(d) if margin < d.bar && so.asked < d.maxAsks =>
              Person.choose[R](so.lines, ranked.take(d.offer).map(_._1)).map(c => (c, so.asked + 1))
            case _ => pure[(Option[String], Int), R]((Some(ranked.head._1), so.asked))
          next <- chosen match
            case (None, asked) => pure[Reading, R](so.copy(margins = so.margins :+ margin, asked = asked,
              unresolved = true))
            case (Some(line), asked) =>
              // the line said as soon as it is chosen (a page shows the reading going on)
              Read.note[R](line).flatMap(_ => Read.encode[R](written(line, question))).flatMap(t => step(tables, question, doubt,
                maxLines, link, guess, prune,
                (context :+ newline) ++ t, newline, end,
                Reading(so.lines :+ line, so.margins :+ margin, asked, runnersUp = so.runnersUp :+
                  ranked.map(_._1).find(_ != line))))
        yield next
