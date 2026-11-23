package okay.desk.puzzle

/** A NAME IN A RUSSIAN CASE, for the read-back of a clue (`PuzzleDomain.readBack`): «рядом с лисой», «справа от
 * белого дома» — not «рядом с лиса». The words of the grid puzzles: an adjective and a noun («синий дом»,
 * «апельсиновый сок»), a person («норвежец», «англичанин»), an animal or a drink («лиса», «лошадь», «молоко»),
 * plurals («улитки»); a word not Russian (a brand: Kool) as written. Genitive (`gen`) and instrumental (`ins`) */
object Ru:
  private def cyr(w: String) = w.exists(c => c >= 'а' && c <= 'я' || c == 'ё')
  private val hushing = "гкхжшчщ"
  private val indeclinable = Set("кофе", "какао", "виски")

  private def adjective(w: String, c: String): String =
    val (stem, end) = (w.dropRight(2), w.takeRight(2))
    (end, c) match
      case ("ый" | "ой", "gen") => stem + "ого"
      case ("ий", "gen") => stem + (if hushing.contains(stem.last) then "ого" else "его")
      case ("ый" | "ой", _) => stem + "ым"
      case ("ий", _) => stem + "им"
      case _ => w

  private def noun(w: String, c: String): String =
    val gen = c == "gen"
    if !cyr(w) || indeclinable(w) then w
    else if w.endsWith("ец") then
      // испанец → испанца, норвежец → норвежцем
      val s = w.dropRight(2) + "ц"
      if gen then s + "а" else s + "ем"
    else if w.endsWith("ки") || w.endsWith("цы") || w.endsWith("ы") || w.endsWith("и") then
      // a plural: улитки → улиток / улитками; птицы → птиц / птицами
      val s = w.dropRight(1)
      if gen then (if s.endsWith("к") then s.dropRight(1) + "ок" else s) else s + "ами"
    else if w.endsWith("а") then
      val s = w.dropRight(1)
      if gen then s + (if hushing.contains(s.last) then "и" else "ы") else s + "ой"
    else if w.endsWith("я") then
      val s = w.dropRight(1)
      if gen then s + "и" else s + "ей"
    else if w.endsWith("ь") then
      // лошадь (feminine): лошади / лошадью
      val s = w.dropRight(1)
      if gen then s + "и" else s + "ью"
    else if w.endsWith("о") then
      val s = w.dropRight(1)
      if gen then s + "а" else s + "ом"
    else if w.endsWith("й") then
      val s = w.dropRight(1)
      if gen then s + "я" else s + "ем"
    else if gen then w + "а" else w + (if "жшчщц".contains(w.last) then "ем" else "ом")

  /** a name (one or more words) in a case: each adjective and noun declined, other words as written */
  def decline(name: String, c: String): String =
    name.split(" ").toVector.map { w =>
      if !cyr(w) then w
      else if w.endsWith("ый") || w.endsWith("ий") || w.endsWith("ой") then adjective(w, c)
      else noun(w, c)
    }.mkString(" ")
