package okay.desk.puzzle

import okay.desk.calc.Language
import okay.desk.organ.{Organ, Template}
import okay.inlay.domain.{Domain, Kit, Pair, Slot}

/**
 * THE READING OF A CLUE (specs/puzzles.md §4): a clue is a FORM (a choice) and its VALUES (copied from the clue's
 * words, found among the sorts' values and their forms in the languages). The phrases per form in four languages
 * are the generator; the words reader is the baseline; an organ induced from the pairs the learner; `say` the
 * read-back the person confirms. An `okay.inlay.domain.Domain`, so the kit gives pairs, reading and the measure.
 */
object PuzzleDomain extends Domain:
  /** the puzzle's sorts, with the values' forms in the languages: `forms(value)` → the words a clue may write */
  final case class Schema(puzzle: Puzzle, forms: Map[String, Vector[String]] = Map.empty):
    def all(value: String): Vector[String] = (value +: forms.getOrElse(value, Vector.empty)).distinct
    lazy val values: Vector[String] = puzzle.refs.map(_.value)
  type Point = Constraint
  type Answer = Boolean

  val name = "puzzle"
  /** the forms a clue may take; `right` and `leftNext` are the mirror wordings of `left` and `rightNext` (the value
   * written first is always the clue's first) */
  val forms: Vector[String] = Vector("same", "differ", "at", "middle", "left", "right", "rightNext", "leftNext", "adjacent")

  def enumerate(s: Schema): Iterator[Point] =
    val rs = s.puzzle.refs
    val pairs = for a <- rs.iterator; b <- rs.iterator if a != b yield (a, b)
    pairs.flatMap((a, b) => (if a.sort != b.sort then Iterator(Constraint.Same(a, b), Constraint.Differ(a, b)) else Iterator.empty) ++
      Iterator(Constraint.Left(a, b), Constraint.RightNext(a, b), Constraint.Adjacent(a, b))) ++
      rs.iterator.flatMap(a => Iterator(Constraint.Middle(a)) ++ (1 to s.puzzle.positions).iterator.map(n => Constraint.At(a, n)))
  def random(rnd: scala.util.Random, s: Schema): Point =
    val rs = s.puzzle.refs
    def one = rs(rnd.nextInt(rs.size))
    def two = { val a = one; var b = one; while b.sort == a.sort do b = one; (a, b) }
    def any = { val a = one; var b = one; while b == a do b = one; (a, b) }
    Vector("same", "differ", "at", "middle", "left", "rightNext", "adjacent")(rnd.nextInt(7)) match
      case "same" => val (a, b) = two; Constraint.Same(a, b)
      case "differ" => val (a, b) = two; Constraint.Differ(a, b)
      case "at" => Constraint.At(one, 1 + rnd.nextInt(s.puzzle.positions))
      case "middle" => Constraint.Middle(one)
      case "left" => val (a, b) = any; Constraint.Left(a, b)
      case "rightNext" => val (a, b) = any; Constraint.RightNext(a, b)
      case _ => val (a, b) = any; Constraint.Adjacent(a, b)
  /** a constraint alone is always satisfiable: the engine's answer is whether it can hold at all */
  def run(p: Point, s: Schema): Either[String, Answer] = Right(true)

  // ---- the phrases, {A} {B} the values, {N} the position; the person-ness of a value is the sort's business

  val phrases: Map[String, Map[String, Vector[String]]] = Map(
    "same" -> Map(
      "en" -> Vector("{A} is in the same house as {B}.", "The one with {A} has {B}.", "{A} goes with {B}.", "{A} and {B} are together."),
      "ru" -> Vector("{A} и {B} в одном доме.", "У того, кто {A}, {B}.", "{A} вместе с {B}.", "Там, где {A}, там и {B}."),
      "uk" -> Vector("{A} і {B} в одному будинку.", "У того, хто {A}, {B}.", "{A} разом з {B}."),
      "pl" -> Vector("{A} i {B} są w tym samym domu.", "Ten, kto ma {A}, ma {B}.", "{A} jest razem z {B}.")),
    "differ" -> Map(
      "en" -> Vector("{A} is not in the same house as {B}.", "{A} and {B} are in different houses.", "The one with {A} does not have {B}."),
      "ru" -> Vector("{A} и {B} в разных домах.", "У того, кто {A}, нет {B}.", "{A} не вместе с {B}."),
      "uk" -> Vector("{A} і {B} в різних будинках.", "{A} не разом з {B}."),
      "pl" -> Vector("{A} i {B} są w różnych domach.", "{A} nie jest razem z {B}.")),
    "at" -> Map(
      "en" -> Vector("{A} is in house {N}.", "{A} lives in the house number {N}.", "House {N} has {A}."),
      "ru" -> Vector("{A} в доме номер {N}.", "{A} живёт в доме {N}.", "В доме {N} {A}."),
      "uk" -> Vector("{A} у будинку номер {N}.", "{A} живе у будинку {N}."),
      "pl" -> Vector("{A} jest w domu numer {N}.", "{A} mieszka w domu {N}.")),
    "middle" -> Map(
      "en" -> Vector("{A} is in the middle house.", "The middle house has {A}."),
      "ru" -> Vector("{A} в среднем доме.", "В среднем доме {A}."),
      "uk" -> Vector("{A} у середньому будинку."),
      "pl" -> Vector("{A} jest w środkowym domu.")),
    "left" -> Map(
      "en" -> Vector("{A} is somewhere to the left of {B}.", "{A} is left of {B}."),
      "ru" -> Vector("{A} где-то левее {B}.", "{A} левее, чем {B}."),
      "uk" -> Vector("{A} десь лівіше від {B}."),
      "pl" -> Vector("{A} jest gdzieś na lewo od {B}.")),
    "right" -> Map(
      "en" -> Vector("{A} is somewhere to the right of {B}.", "{A} is right of {B}."),
      "ru" -> Vector("{A} где-то правее {B}.", "{A} правее, чем {B}."),
      "uk" -> Vector("{A} десь правіше від {B}."),
      "pl" -> Vector("{A} jest gdzieś na prawo od {B}.")),
    "rightNext" -> Map(
      "en" -> Vector("{A} is immediately to the right of {B}.", "{A} is directly right of {B}."),
      "ru" -> Vector("{A} сразу справа от {B}.", "{A} стоит непосредственно справа от {B}."),
      "uk" -> Vector("{A} одразу праворуч від {B}."),
      "pl" -> Vector("{A} jest bezpośrednio na prawo od {B}.")),
    "leftNext" -> Map(
      "en" -> Vector("{A} is immediately to the left of {B}.", "{A} is directly left of {B}."),
      "ru" -> Vector("{A} сразу слева от {B}.", "{A} стоит непосредственно слева от {B}."),
      "uk" -> Vector("{A} одразу ліворуч від {B}."),
      "pl" -> Vector("{A} jest bezpośrednio na lewo od {B}.")),
    "adjacent" -> Map(
      "en" -> Vector("{A} is next to {B}.", "{A} lives next to {B}.", "{A} and {B} are neighbours.", "{A} is next door to {B}."),
      "ru" -> Vector("{A} рядом с {B}.", "{A} живёт по соседству с {B}.", "{A} и {B} соседи."),
      "uk" -> Vector("{A} поруч із {B}.", "{A} і {B} сусіди."),
      "pl" -> Vector("{A} jest obok {B}.", "{A} i {B} są sąsiadami.")))

  def say(rnd: scala.util.Random, p: Point, s: Schema, language: String): String =
    val ws = phrases(p.form).getOrElse(language, phrases(p.form)("en"))
    def word(r: Ref) = { val fs = s.all(r.value); fs(rnd.nextInt(fs.size)) }
    val t = ws(rnd.nextInt(ws.size))
    val filled = p match
      case Constraint.At(a, n) => t.replace("{A}", word(a)).replace("{N}", n.toString)
      case Constraint.Middle(a) => t.replace("{A}", word(a))
      case other => val Vector(a, b) = other.refs: @unchecked; t.replace("{A}", word(a)).replace("{B}", word(b))
    filled.head.toUpper +: filled.tail

  /** THE READ-BACK (the person confirms a clue by seeing it): the first phrase of the form, the values' own names */
  def readBack(p: Point, language: String, label: String => String = identity): String =
    val t = phrases(p.form).getOrElse(language, phrases(p.form)("en")).head
    p match
      case Constraint.At(a, n) => t.replace("{A}", label(a.value)).replace("{N}", n.toString)
      case Constraint.Middle(a) => t.replace("{A}", label(a.value))
      case other => val Vector(a, b) = other.refs: @unchecked; t.replace("{A}", label(a.value)).replace("{B}", label(b.value))

  def slots(s: Schema): Vector[Slot] = Vector(Slot("form", forms), Slot("a", Vector.empty, copied = true), Slot("b", Vector.empty, copied = true), Slot("n", Vector.empty, copied = true))
  def parts(p: Point): Map[String, String] = p match
    case Constraint.At(a, n) => Map("form" -> "at", "a" -> a.value, "n" -> n.toString)
    case Constraint.Middle(a) => Map("form" -> "middle", "a" -> a.value)
    case other => val Vector(a, b) = other.refs: @unchecked; Map("form" -> other.form, "a" -> a.value, "b" -> b.value)
  def assemble(s: Schema, parts: Map[String, String]): Option[Point] =
    def ref(k: String) = parts.get(k).flatMap(v => s.puzzle.sortOf(v).map(so => Ref(so.name, v)))
    parts.get("form").flatMap {
      case "at" => for a <- ref("a"); n <- parts.get("n").flatMap(_.toIntOption) if n >= 1 && n <= s.puzzle.positions yield Constraint.At(a, n)
      case "middle" => ref("a").map(Constraint.Middle(_))
      case f => for a <- ref("a"); b <- ref("b") if a != b && (a.sort != b.sort || f != "same" && f != "differ") yield f match
        case "same" => Constraint.Same(a, b)
        case "differ" => Constraint.Differ(a, b)
        case "left" => Constraint.Left(a, b)
        case "right" => Constraint.Left(b, a)
        case "rightNext" => Constraint.RightNext(a, b)
        case "leftNext" => Constraint.RightNext(b, a)
        case _ => Constraint.Adjacent(a, b)
    }

  /** THE COPIER: the values written in the clue, in the order written — `a` the first, `b` the second, `n` a number;
   * a value is found by any of its forms, the longest forms first, each occurrence once */
  object copier extends Kit.Copier[Schema]:
    def found(clue: String, s: Schema): Vector[(Int, String)] =
      val q = clue.toLowerCase.replace('ё', 'е')
      val hits = for
        v <- s.values
        f <- s.all(v)
        suffix = if f.exists(c => c >= 'А' && c <= 'я') then "\\p{L}{0,3}" else "(?:s|es)?"
        m <- s"(?U)(^|[^\\p{L}])${java.util.regex.Pattern.quote(f.toLowerCase.replace('ё', 'е'))}".concat(
          s"($suffix)([^\\p{L}]|$$)").r.findAllMatchIn(q).toVector
      yield (m.start + m.group(1).length, m.end - m.group(3).length, v, f.length)
      val kept = hits.sortBy(h => (-h._4, h._1)).foldLeft(Vector.empty[(Int, Int, String, Int)]) { (acc, hit) =>
        if acc.exists(other => hit._1 < other._2 && other._1 < hit._2) then acc else acc :+ hit
      }
      kept.groupBy(_._3).values.map(_.minBy(_._1)).map(h => h._1 -> h._3).toVector.sortBy(_._1)

    def copy(clue: String, s: Schema, slot: Slot): Option[String] = slot.name match
      case "a" => found(clue, s).headOption.map(_._2)
      case "b" => found(clue, s).lift(1).map(_._2)
      case "n" =>
        val digits = "\\b([1-9])\\b".r.findFirstMatchIn(clue).map(_.group(1))
        digits.orElse(Map("first" -> "1", "second" -> "2", "third" -> "3", "fourth" -> "4", "fifth" -> "5",
          "перв" -> "1", "втор" -> "2", "трет" -> "3", "четв" -> "4", "пят" -> "5", "перш" -> "1", "друг" -> "2", "pierwsz" -> "1", "drug" -> "2", "trzec" -> "3", "czwart" -> "4", "piąt" -> "5")
          .collectFirst { case (w, n) if clue.toLowerCase.contains(w) => n })
      case _ => None

  /** THE WORDS READER: the form by its words, the most specific first */
  object rules extends Kit.Reader[Schema]:
    val words: Vector[(String, Vector[String])] = Vector(
      "rightNext" -> Vector("immediately to the right", "directly right", "сразу справа", "непосредственно справа", "одразу праворуч", "bezpośrednio na prawo"),
      "leftNext" -> Vector("immediately to the left", "directly left", "сразу слева", "непосредственно слева", "одразу ліворуч", "bezpośrednio na lewo"),
      "adjacent" -> Vector("next to", "next door", "neighbour", "neighbor", "рядом", "сосед", "поруч", "сусід", "obok", "sąsiad"),
      "left" -> Vector("left of", "левее", "слева от", "лівіше", "na lewo"),
      "right" -> Vector("right of", "правее", "справа от", "правіше", "na prawo"),
      "middle" -> Vector("middle", "центральн", "в центре", "средн", "середн", "środkow"),
      "at" -> Vector("house number", "in house", "house 1", "house 2", "house 3", "house 4", "house 5", "house 6", "house 7", "first house", "second house", "third house", "fourth house", "fifth house", "номер", "в доме", "живе у будинку", "у будинку номер",
        "первом доме", "втором доме", "третьем доме", "четвёртом доме", "пятом доме", "першому будинку", "другому будинку",
        "третьому будинку", "четвертому будинку", "п'ятому будинку", "w domu", "numer", "pierwszym domu", "drugim domu", "trzecim domu", "czwartym domu", "piątym domu"),
      "differ" -> Vector("not in the same", "different", "does not have", "doesn't have", "разных", "нет", "не вместе", "різних", "не разом", "różnych", "nie jest razem"),
      "same" -> Vector("same house", "goes with", "together", "has ", "одном доме", "вместе", "там, где", "у того", "одному", "разом", "tym samym", "razem", "ma "))
    def read(clue: String, s: Schema, slot: Slot): Map[String, Double] =
      if slot.name != "form" then Map.empty
      else
        val q = clue.toLowerCase.replace('ё', 'е')
        if "(?iU)\\bне\\s+(?:рядом|сосед|справа|слева|сразу)".r.findFirstIn(q).nonEmpty then return Map.empty
        if copier.found(clue, s).size == 2 && q.contains("рядом с ним") then
          if q.contains("слева от") then return Map("leftNext" -> 1.0)
          if q.contains("справа от") then return Map("rightNext" -> 1.0)
        words.collectFirst { case (f, ws) if ws.exists(w => q.contains(w.replace('ё', 'е'))) => Map(f -> 1.0) }.getOrElse {
          // Only the supported plain associations. Two names alone do not license inventing a relation.
          val plain = Vector("lives in", "owns ", "is drunk", "are smoked", "smoker owns", "smoker drinks",
            "smokes ", "drinks ", "живёт в", "у ", "пьют ", "курят ", "держит ", "пьёт ", "курит ",
            "выращивает ", "разводит ")
          if copier.found(clue, s).size == 2 && plain.exists(w => q.contains(w.replace('ё', 'е'))) then Map("same" -> 1.0) else Map.empty
        }

  /** THE ORGAN'S READER: the form read by an organ induced from the phrases */
  final class ByOrgan(organ: Organ, lang: String => Language) extends Kit.Reader[Schema]:
    def read(clue: String, s: Schema, slot: Slot): Map[String, Double] =
      if slot.name != "form" then Map.empty
      else organ.forward(Organ.words(clue, lang(Language.detect(clue).head))).flatMap(_.parts.get("form")).map(f => Map(f -> 1.0)).getOrElse(Map.empty)
  def induce(pairs: Vector[Pair[Point]], lang: String => Language, name: String = "puzzle"): Organ =
    Organ.induce(name, pairs.map(p => (Organ.words(p.question, lang(p.language)), Template(Map("form" -> p.point.form)))), minCover = 2)
  def first(rs: Kit.Reader[Schema]*): Kit.Reader[Schema] = new Kit.Reader[Schema]:
    def read(clue: String, s: Schema, slot: Slot): Map[String, Double] = rs.iterator.map(_.read(clue, s, slot)).find(_.nonEmpty).getOrElse(Map.empty)
