package okay.desk.ui

import okay.business.scala3.*

/**
 * THE ABSTENTION AS A DIALOGUE (specs/data.md §30): when the readers are not sure ("not sure: orders or customers"),
 * the page offers the readings as buttons; the person's choice is written into the question — "… — orders": a
 * table named in the question is the table, an exact fact for the rules — and the question is read again. Each
 * answered clarification is kept on the disk (one JSON line: the question as asked, the choice, the program): the
 * pairs a later training reads (§13: learning from use, the person the judge).
 */
object Clarified:
  /** the disk's name of the kept clarifications; names with this prefix are not tables */
  val prefix = "okay-desk:"
  val file: String = prefix + "clarified.jsonl"

  private def russian(q: String) = q.exists(c => c >= 'а' && c <= 'я' || c >= 'А' && c <= 'Я')

  /** the readings of a hesitation, if the words are one: "not sure: A or B" (the readers' abstention), or the
   * dialogue's ask (specs/pilot.md §3.2) — "ask: <the hole's question>" and, on the next line, the options */
  def readings(words: String): Option[Vector[String]] =
    if words.startsWith("ask: ") then words.split("\n").lift(1).map(_.split("\\|").toVector.map(_.trim).filter(_.nonEmpty)).filter(_.size >= 2)
    else "^not sure: (.+?)(?: — .*)?$".r.findFirstMatchIn(words).map(_.group(1).split(" or ").toVector.map(_.trim))
      .filter(_.size >= 2)
  /** the hole's question, where the words are the dialogue's ask */
  def askedQuestion(words: String): Option[String] = Option.when(words.startsWith("ask: "))(words.split("\n").head.drop(5))

  /** the question asked again with the choice written into it */
  def question(q: String, choice: String): String = s"${q.trim.stripSuffix("?").stripSuffix(".")} — $choice"
  /** what the person said, in the conversation */
  def saying(q: String, choice: String): String = if russian(q) then s"Я имею в виду $choice" else s"I mean $choice"
  /** what the page says to thanks, and to a yes or no with nothing pending */
  def welcome(q: String): String = if russian(q) then "Пожалуйста. Спрашивайте ещё." else "You're welcome. Ask on."
  def nothingPending(q: String): String = if russian(q) then "Ничего не ожидает ответа — задайте вопрос о данных." else "Nothing is pending — ask about the data."
  def prompt(q: String, asked: Option[String] = None): String = asked.getOrElse(
    if russian(q) then "Не уверен, о какой таблице вопрос — выберите:" else "Not sure which table the question means — choose:")
  def button(q: String, choice: String, asked: Boolean = false): String =
    if asked then choice match
      case "none" => if russian(q) then "без этого" else "none"
      case "desc" => if russian(q) then "по убыванию" else "largest first"
      case "asc" => if russian(q) then "по возрастанию" else "smallest first"
      case c => c
    else if russian(q) then s"Таблица $choice" else s"Table $choice"

  /** one kept clarification: a JSON line */
  def line(q: String, choice: String, program: String): String =
    import okay.effects.json.Json
    Json.print(Json.Obj(Vector("question" -> Json.Str(q), "choice" -> Json.Str(choice), "program" -> Json.Str(program))))

  /** the clarification appended to the disk's file */
  def keep[R[_]](q: String, choice: String, program: String)(using In[Disk, R]): Unit ! R =
    Disk.get[R](file).flatMap(was => Disk.put[R](file, was.fold("")(_ + "\n") + line(q, choice, program)))
