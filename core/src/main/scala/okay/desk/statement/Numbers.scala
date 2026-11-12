package okay.desk.statement

/**
 * A NUMBER AS A TEXT SAYS IT (specs/formalization.md §17): digits, or a word — "three", "third", «третьем»,
 * «трьох», «trzecim». A statement copies the words; the compiler needs the number. Words up to ten, in the four
 * languages the page reads: English whole, the others by a stem and a short ending (their endings vary with case
 * and gender) — so that "tennis" is not ten nor «Шестаков» six. Data of the languages, not a reader: it is asked of
 * words a reader marked, and of a frame's pieces to find the one that is its number.
 */
object Numbers:
  private val english = Vector("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
  private val englishOrdinal = Vector("", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth",
    "ninth", "tenth")
  private val stems: Vector[(Int, Vector[String])] = Vector(
    0 -> Vector("ноль", "нул", "zer"),
    1 -> Vector("один", "одн", "перв", "jeden", "jedn", "pierw"),
    2 -> Vector("два", "двух", "две", "втор", "дві", "двох", "dwa", "dwóch", "dwie", "drug"),
    3 -> Vector("три", "трёх", "трех", "трет", "трьох", "trzy", "trzech", "trzec"),
    4 -> Vector("четыр", "четвёрт", "четверт", "чотир", "четвер", "czter", "czwart"),
    5 -> Vector("пят", "п'ят", "пʼят", "pięć", "pięci", "piąt"),
    6 -> Vector("шест", "шіст", "шост", "sześć", "sześci", "szóst"),
    7 -> Vector("семь", "седьм", "сім", "сьом", "siedem", "siedmi", "siódm"),
    8 -> Vector("восем", "восьм", "вісім", "osiem", "ośmi", "ósm"),
    9 -> Vector("девят", "дев'ят", "девʼят", "dziewię", "dziewiąt"),
    10 -> Vector("десят", "dziesię", "dziesiąt"))

  /** the number these words say: digits as they are, an English word whole, another by its stem and ending */
  def of(words: String): Option[Int] =
    val w = words.trim.toLowerCase
    w.toIntOption.orElse("^\\d+$".r.findFirstIn(w).flatMap(_.toIntOption)).orElse {
      val first = w.takeWhile(c => c.isLetter || c == '\'' || c == 'ʼ')
      val exact = english.indexOf(first) max englishOrdinal.indexOf(first)
      if exact > 0 || first == "zero" then Some(exact max 0)
      else stems.flatMap((n, ss) => ss.filter(s => first.startsWith(s) && first.length - s.length <= 3)
        .map(s => (n, s.length))).maxByOption(_._2).map(_._1) }
