package okay.desk

/**
 * THE RESULT CHECKED AGAINST THE QUESTION (the ensemble of checks, 2026-11-24; execution-guided reading): an
 * independent opinion from the exact side — the frame run, its shape set beside what the question's own words ask
 * for. Where they disagree the reading is in doubt and a person is asked, never answered silently. A check names its
 * reason; none is a guess at the right frame, only a refusal of a wrong-shaped one.
 */
object FrameChecks:
  // "how many" / "how much" in the four languages: the answer is a number, not rows
  private val howMuch = "(?iU)\\b(how many|how much|сколько|скільки|ile)\\b".r
  // "for each" / "by …" / "per": the answer is grouped
  private val each = ("(?iU)\\b(for each|each|per|by (category|month|week|year|region|product|city|customer|" +
    "employee|student|subject|role|merchant|supplier|item|type|status)|по кажд\\w*|для кожн\\w*|dla każd\\w*|" +
    "na każd\\w*|в разрезе|по категори\\w*|по месяц\\w*|по регион\\w*|по товар\\w*|по город\\w*)\\b").r

  /** the reason the frame's shape does not fit the question, or none */
  def suspicious(question: String, lines: Vector[String], result: Option[Table]): Option[String] =
    val answer = lines.find(_.startsWith("answer: ")).map(_.drop(8)).getOrElse("")
    val grouped = lines.exists(_.startsWith("group: "))
    if answer == "cannot" then None
    else if howMuch.findFirstIn(question).nonEmpty && answer == "rows" then Some("asked how many, got rows")
    else if each.findFirstIn(question).nonEmpty && !grouped && answer != "rows" then Some("asked for each, not grouped")
    else if result.exists(_.rows.isEmpty) then Some("no row came out")
    else None
