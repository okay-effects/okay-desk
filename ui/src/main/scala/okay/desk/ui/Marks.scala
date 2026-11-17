package okay.desk.ui

/** THE PERSON'S MARKS (technology.md §12.1: people's questions are the measure): an answer marked right or wrong,
 * kept on the device a line of JSON each — the question, the reading said back, the answer, the program, the mark,
 * the model — and copied to be shared. People's questions with their verdicts are the measure and the calibration
 * no written set can be. */
object Marks:
  /** the file the marks are kept in (`Disk`); not a table: the page does not open it as data */
  val file = "~marks.jsonl"

  private def quoted(s: String): String =
    "\"" + s.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => ""
      case '\t' => "\\t"
      case c if c < ' ' => ""
      case c => c.toString
    } + "\""

  /** one mark as a line: the question's entries (the question first), right or wrong, the model */
  def line(entries: Vector[App.Entry], right: Boolean, model: String, tables: Seq[String]): String =
    val question = entries.headOption.fold("")(_.text)
    val reading = entries.find(App.understood).fold("")(_.text)
    val answer = entries.drop(1).filterNot(App.understood).map(_.text).mkString("\n")
    val program = entries.flatMap(_.program).headOption.getOrElse("")
    s"""{"question":${quoted(question)},"understood":${quoted(reading)},"answer":${quoted(answer)},""" +
      s""""program":${quoted(program)},"right":$right,"model":${quoted(model)},""" +
      s""""tables":[${tables.map(quoted).mkString(",")}]}"""

  /** the marks kept: how many */
  def count(text: String): Int = text.linesIterator.count(_.trim.nonEmpty)
