package okay.desk

/**
 * A MONTH NAMED IN A QUESTION (specs/loop.md §2.3): "in March", «в марте», «за березень», "w marcu" — the month's
 * number, by the stems of its names in English, Russian, Ukrainian and Polish, every case ending. A condition `month`
 * on a date column holds the dates of that month (`Space.compile`: on or after its first day, before the next
 * month's). English "may" is a month only after "in", "for", "during" or before a year: it is also a verb.
 */
object Months:
  private val stems: Vector[Vector[String]] = Vector(
    Vector("january", "jan", "январ", "січ", "stycz"),
    Vector("february", "feb", "феврал", "лют", "lut"),
    Vector("march", "mar", "март", "берез", "marz", "marc"),
    Vector("april", "apr", "апрел", "квіт", "kwie"),
    Vector("мая", "мае", "май", "трав", "maj", "maja", "maju"),
    Vector("june", "jun", "июн", "черв", "czerw"),
    Vector("july", "jul", "июл", "лип", "lip"),
    Vector("august", "aug", "август", "серп", "sierp"),
    Vector("september", "sept", "sep", "сентябр", "верес", "wrze"),
    Vector("october", "oct", "октябр", "жовт", "paźdz", "pazdz"),
    Vector("november", "nov", "ноябр", "листопад", "listopad"),
    Vector("december", "dec", "декабр", "груд", "grud"))
  private val shortOnly = Set("jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    "мая", "мае", "май", "maj", "maja", "maju")

  /** a month said in a language (`en`, `ru`, `uk`, `pl`): "in March", «в марте», «у березні», "w marcu" */
  def said(language: String, month: Int): String =
    val m = month - 1
    language match
      case "ru" => "в " + Vector("январе", "феврале", "марте", "апреле", "мае", "июне", "июле", "августе",
        "сентябре", "октябре", "ноябре", "декабре")(m)
      case "uk" => "у " + Vector("січні", "лютому", "березні", "квітні", "травні", "червні", "липні", "серпні",
        "вересні", "жовтні", "листопаді", "грудні")(m)
      case "pl" => Vector("w styczniu", "w lutym", "w marcu", "w kwietniu", "w maju", "w czerwcu", "w lipcu",
        "w sierpniu", "we wrześniu", "w październiku", "w listopadzie", "w grudniu")(m)
      case _ => "in " + Vector("January", "February", "March", "April", "May", "June", "July", "August", "September",
        "October", "November", "December")(m)

  /** the months a question names, in the order it names them (1–12) */
  def in(question: String): Vector[Int] =
    val q = question.toLowerCase
    val words = "(?U)\\b\\w+\\b".r.findAllMatchIn(q).toVector
    val found = words.flatMap { m =>
      val w = m.matched
      stems.indices.find(i => stems(i).exists(s => if shortOnly(s) then w == s else w.startsWith(s)))
        .map(i => (m.start, i + 1)) }
    val may = "(?U)\\b(in|for|during|of)\\s+may\\b|\\bmay\\s+(19|20)\\d\\d\\b".r.findAllMatchIn(q)
      .map(m => (m.start, 5))
    (found ++ may).sortBy(_._1).map(_._2).distinct

  /** `yyyy-mm` of a month in a column's dates (`yyyy-mm-dd`): the year a question writes, else the latest year the
   * column has that month in */
  def value(month: Int, question: String, dates: Iterable[String]): Option[String] =
    val mm = f"$month%02d"
    val years = dates.collect { case d if d.length >= 7 && d.slice(5, 7) == mm => d.take(4) }.toSet
    val written = "\\b(19|20)\\d\\d\\b".r.findAllIn(question).toSet.intersect(years)
    val year = if written.nonEmpty then written.toVector.sorted.lastOption else years.toVector.sorted.lastOption
    year.map(y => s"$y-$mm")
