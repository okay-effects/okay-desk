package okay.desk.puzzle

/** A vocabulary for ordered-house grids, not a stored puzzle or solution. Only values present in the input are used. */
object GridText:
  final case class Word(value: String, forms: Vector[String])
  private def word(value: String, forms: String*): Word = Word(value, forms.toVector)
  val vocabulary: Vector[(String, Vector[Word])] = Vector(
    "colours" -> Vector(
      word("red", "красный дом", "красном доме", "красного дома", "красным домом", "красный"),
      word("green", "зелёный дом", "зелёном доме", "зелёного дома", "зелёным домом", "зелёный"),
      word("ivory", "белый дом", "белом доме", "белого дома", "белым домом", "white", "белого"),
      word("yellow", "жёлтый дом", "жёлтом доме", "жёлтого дома", "жёлтым домом", "жёлтый"),
      word("blue", "синий дом", "синем доме", "синего дома", "синим домом", "синий"),
      word("black", "чёрный дом", "чёрном доме")),
    "nations" -> Vector(word("Englishman", "англичанин", "англичанина"),
      word("Spaniard", "испанец", "испанца"), word("Ukrainian", "украинец", "украинца"),
      word("Norwegian", "норвежец", "норвежца"), word("Japanese", "японец", "японца"),
      word("German", "немец", "немца"), word("Dane", "датчанин", "датчанина"), word("Swede", "швед", "шведа")),
    "drinks" -> Vector(word("coffee", "кофе"), word("tea", "чай"), word("milk", "молоко"),
      word("orange juice", "апельсиновый сок"), word("water", "вода", "воду"), word("beer", "пиво")),
    "smokes" -> Vector(word("Old Gold"), word("Kools", "Kool"), word("Chesterfields", "Chesterfield"),
      word("Lucky Strike"), word("Parliaments", "Parliament"), word("Pall Mall"), word("Dunhill"),
      word("Blend"), word("Blue Master"), word("Prince")),
    "pets" -> Vector(word("dog", "собака", "собаку", "собак"), word("snails", "улитки", "улиток"),
      word("fox", "лиса", "лису", "лисой"), word("horse", "лошадь", "лошадью", "лошадей"),
      word("zebra", "зебра", "зебру"), word("fish", "рыба", "рыбу", "рыбки", "рыбок"),
      word("cat", "кошка", "кошку", "кошек"),
      word("birds", "птицы", "птиц")))
  private val numbers = Map("two" -> 2, "three" -> 3, "four" -> 4, "five" -> 5, "six" -> 6,
    "два" -> 2, "двух" -> 2, "три" -> 3, "трёх" -> 3, "четыре" -> 4, "четырёх" -> 4,
    "пять" -> 5, "пяти" -> 5, "шесть" -> 6, "шести" -> 6)
  private val count = "(?iU)\\b([2-9]|two|three|four|five|six|два|двух|три|трёх|четыре|четырёх|пять|пяти|шесть|шести)" +
    "\\s+(?:houses|домов|дома)\\b"
  def counts(text: String): Vector[Int] = count.r.findAllMatchIn(text).flatMap(m =>
    m.group(1).toIntOption.orElse(numbers.get(m.group(1).toLowerCase))).toVector.distinct
  /** A brand after the Russian smoking predicate is copied, not chosen from the stock vocabulary. */
  private def withBrands(text: String): Vector[(String, Vector[Word])] =
    val pattern = "(?iU)\\b(?:курит|курят)\\s+" +
      "([A-Za-z][A-Za-z0-9-]*(?:[ \\t]+[A-Za-z][A-Za-z0-9-]*){0,3})"
    val known = vocabulary.find(_._1 == "smokes").get._2
    val names = pattern.r.findAllMatchIn(text).map(_.group(1)).toVector
      .filterNot(n => known.exists(w => (w.value +: w.forms).exists(_.equalsIgnoreCase(n))))
      .groupBy(_.toLowerCase).values.map(_.head).toVector.sortBy(_.toLowerCase)
    vocabulary.map((sort, ws) => sort -> (if sort == "smokes" then ws ++ names.map(word(_)) else ws))

  private def fullSchema(words: Vector[(String, Vector[Word])]): PuzzleDomain.Schema =
    PuzzleDomain.Schema(Puzzle(1, words.flatMap { (sort, vs) =>
      vs.map(w => Sort(sort + w.value, Vector(w.value))) },
      Vector.empty, None), words.flatMap(_._2).map(w => w.value -> w.forms).toMap)
  private def present(text: String, words: Vector[(String, Vector[Word])]): Set[String] =
    PuzzleDomain.copier.found(text, fullSchema(words)).map(_._2).toSet
  def looksLike(text: String): Boolean =
    if counts(text).isEmpty || !text.contains("?") then false
    else
      val found = present(text, vocabulary)
      vocabulary.count((_, ws) => ws.exists(w => found(w.value))) >= 2

  def infer(text: String): Either[String, PuzzleDomain.Schema] =
    val ns = counts(text)
    if ns.size != 1 then Left("The number of houses must be stated unambiguously.")
    else
      val words = withBrands(text)
      val found = present(text, words)
      val groups = words.map((sort, ws) => sort -> ws.filter(w => found(w.value))).filter(_._2.nonEmpty)
      val missing = groups.filter(_._2.size != ns.head)
      if groups.size < 2 || missing.nonEmpty then
        Left("Please list all values in each category; values absent from the text cannot be invented. " +
          missing.map((sort, ws) => s"$sort: ${ws.size} of ${ns.head}").mkString("; "))
      else if !groups.forall((sort, _) =>
        okay.desk.know.SourceText.sentences(text).flatMap(uniqueCategories).contains(sort)) then
        Left("Please state that each category has a different value in every house, or provide category headers.")
      else Right(PuzzleDomain.Schema(Puzzle(ns.head, groups.map((sort, ws) => Sort(sort, ws.map(_.value))),
        Vector.empty, None), groups.flatMap(_._2).map(w => w.value -> w.forms).toMap))

  private def uniqueCategories(line: String): Set[String] =
    val q = line.toLowerCase
    if q.contains("all category values are different") then vocabulary.map(_._1).toSet
    else if (q.contains("кажд") || q.contains("все")) &&
      Vector("разн", "различ", "уникальн", "своего").exists(q.contains) then
      Map("colours" -> "цвет", "nations" -> "национальност", "pets" -> "животн",
        "drinks" -> "напит", "smokes" -> "сигарет").collect { case (sort, noun) if q.contains(noun) => sort }.toSet
    else Set.empty

  def declaration(line: String): Boolean =
    val q = line.trim.toLowerCase.replace('ё', 'е')
    val introductory = counts(line).nonEmpty &&
      (q.matches("на улице стоят .+ домов[.]?") || q.matches("there are .+ houses[.]?"))
    val orientation = q.startsWith("еще одно замечание:") &&
      q.endsWith("справа означает справа относительно вас.")
    introductory || uniqueCategories(line).nonEmpty || orientation ||
      Set("кроме того:", "вопрос:").contains(q)
