package okay.desk.tasks

/**
 * AN ANSWER IN WORDS AGAINST A TASK'S EXACT ANSWER: what a person types, or what the desk says in the chat. It does
 * not solve anything and reads no task: it looks for the expected answer — as the task writes it in English or in
 * Russian, or as the solver's values — among the words of the reply. A reply that says "it cannot be decided" or
 * "it cannot be" is right exactly when the solver says so.
 */
object Judge:
  enum Mood:
    case Plain, Undecided, Impossible

  private val stop = Set("the", "a", "an", "is", "are", "was", "were", "it", "of", "in", "at", "это", "в", "на", "а")
  private val undecided = Vector("cannot be decided", "can not be decided", "cannot be determined", "not decided",
    "do not decide", "does not follow", "not follow", "more than one", "undecided", "ambiguous", "not unique",
    "not enough", "insufficient", "не следует", "определить нельзя", "нельзя определить", "нельзя сказать",
    "не определя", "не определен", "больше одно", "несколько", "неоднознач", "недостаточно")
  private val impossible = Vector("cannot be done", "can not be done", "this cannot be", "impossible", "no such",
    "no arrangement", "no solution", "contradict", "unreachable", "невозможно", "нельзя", "быть не может",
    "нет решени", "противореч", "ни одна")
  private val yes = Set("yes", "true", "proved", "да", "верно", "правда", "истина", "доказано")
  private val no = Set("no", "false", "refuted", "нет", "неверно", "неправда", "ложь", "опровергнуто")

  /** the words of a text: letters, or a number with its decimals; lower case, ё as е — a single capital Latin letter
   * is kept as it is (a name: A, B), so "A is a liar" is not read as the article */
  def words(s: String): Vector[String] = """\p{N}+(?:\.\p{N}+)?|\p{L}+""".r.findAllIn(s).toVector.map(w =>
    if w.length == 1 && w.head >= 'A' && w.head <= 'Z' then w else w.toLowerCase.replace('ё', 'е'))

  private def content(s: String): Vector[String] = words(s).filterNot(stop)

  /** a number said with fewer decimals is the same number rounded */
  private def same(expected: String, said: String): Boolean =
    if expected == said then true
    else if expected.head.isDigit && said.head.isDigit then
      scala.util.Try((BigDecimal(expected), BigDecimal(said))).toOption.exists((e, r) =>
        e == r || r.scale < e.scale && e.setScale(r.scale, BigDecimal.RoundingMode.HALF_UP) == r)
    else expected != "A" && said != "A" && expected.equalsIgnoreCase(said)

  /** where the words stand together in the reply, from `from` on */
  private def at(part: Vector[String], said: Vector[String], from: Int = 0): Option[Int] =
    (from to said.size - part.size).find(i => part.indices.forall(j => same(part(j), said(i + j))))

  def mood(reply: String): Mood =
    val text = words(reply).mkString(" ").toLowerCase
    if undecided.exists(text.contains) then Mood.Undecided
    else if impossible.exists(text.contains) then Mood.Impossible
    else Mood.Plain

  private def expected(expect: Vector[String]): Mood =
    if expect.exists(e => e.contains("|") || e == "several") then Mood.Undecided
    else if expect.exists(e => e == "none" || e == "unreachable") then Mood.Impossible
    else Mood.Plain

  /** the yes and the no of a reply, in the order said ("not true" is a no) */
  private def truths(reply: String): Vector[Boolean] =
    val said = words(reply)
    said.indices.toVector.flatMap { i =>
      val negated = i > 0 && (said(i - 1) == "not" || said(i - 1) == "не")
      if yes(said(i)) then Some(!negated) else if no(said(i)) then Some(false) else None }

  /** the items of an answer: by `;` when it has one, else by commas, "and", "or" */
  private def items(answer: String): Vector[Vector[String]] =
    (if answer.contains(";") then answer.split(";").toVector
      else answer.split("""\s*[,:]\s*|\s+(?:and|or|и|или)\s+""").toVector).map(content).filter(_.nonEmpty)

  /** the answer as the task writes it: one value whole, several values each standing together, in any order */
  private def written(answer: String, single: Boolean, said: Vector[String]): Boolean =
    val parts = if single then Vector(content(answer)).filter(_.nonEmpty) else items(answer)
    parts.nonEmpty && parts.forall(at(_, said).nonEmpty)

  /** the solver's values, in their order: a yes or a no each, or the words of each value */
  private def values(expect: Vector[String], reply: String, said: Vector[String]): Boolean =
    val flags = expect.map(e => if e == "true" || e == "yes" then Some(true)
      else if e == "false" || e == "no" then Some(false) else None)
    if flags.forall(_.nonEmpty) then truths(reply).take(flags.size) == flags.flatten
    else expect.flatMap(_.split(",").map(content).filter(_.nonEmpty)).foldLeft(Option(0))((from, part) =>
      from.flatMap(i => at(part, said, i).map(_ + part.size))).nonEmpty

  /** `alone`: the reply is to be the answer and nothing else (what a person types) — it has no more items than the
   * answer and no number that is not in it; without it the answer is looked for in a longer text (what the desk
   * says) */
  def accepts(task: Task, reply: String, alone: Boolean = false): Boolean =
    val said = content(reply)
    val single = task.expect.size == 1
    val flag = single && Set("true", "false", "yes", "no")(task.expect.head)
    val answers = Vector(task.answer.en, task.answer.ru)
    val known = (answers ++ task.expect).flatMap(content).filter(_.head.isDigit)
    def short = !alone || said.filter(_.head.isDigit).forall(n => known.exists(same(_, n))) &&
      (flag || items(reply).size <= (answers.map(items(_).size) :+ task.expect.size).max)
    expected(task.expect) match
      case Mood.Impossible => mood(reply) == Mood.Impossible
      case Mood.Undecided => mood(reply) == Mood.Undecided || (task.expect match
        case Vector(one) if one.contains("|") && one != "false|true" =>
          one.split('|').toVector.map(content).forall(at(_, said).nonEmpty)
        case _ => false)
      case Mood.Plain => said.nonEmpty && mood(reply) != Mood.Undecided && short && (written(task.answer.en, single,
        said) || written(task.answer.ru, single, said) || values(task.expect, reply, said))

  /** THE DESK'S RESULT TABLE (as `Table.render` draws it) against a task over data: as many rows as the answer has,
   * and each row of the answer in a row of its own — more columns than were asked for do not make it wrong, more
   * or fewer rows do */
  def tabled(task: Task, rendered: String): Boolean =
    val rows = rendered.linesIterator.drop(2).map(_.split('|').toVector.map(_.trim).filter(_.nonEmpty)).toVector
    val wanted = task.expect.map(_.split(',').toVector.map(_.trim).filter(_.nonEmpty))
    def place(left: Vector[Vector[String]], free: Vector[Vector[String]]): Boolean = left.headOption.forall(want =>
      free.indices.exists(i => want.forall(w => free(i).exists(same(w, _))) && place(left.tail, free.patch(i, Nil, 1))))
    rows.size == wanted.size && place(wanted, rows)
