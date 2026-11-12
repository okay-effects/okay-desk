//> using scala 3.3.8
//> using dep com.lihaoyi::ujson:4.0.2

import scala.util.matching.Regex

/**
 * THE GOLD OF ZEBRALOGIC (specs/formalization.md §17), for measuring only: ZebraLogic's public puzzles come without
 * their solutions, and its clues are written from a few templates. This script reads the templates EXACTLY — it is
 * the benchmark's own grammar, never a reader of the page — solves every puzzle by search and keeps a puzzle only
 * when it has exactly one solution: a misread clue nearly always leaves none or several, so the uniqueness of all
 * of them is the check of this gold. Pass the dataset's rows files (the viewer's JSON) and an output file.
 */
enum Clue:
  case Same(a: Int, b: Int)
  case At(a: Int, k: Int)
  case NotAt(a: Int, k: Int)
  case Left(a: Int, b: Int)
  case JustLeft(a: Int, b: Int)
  case Beside(a: Int, b: Int)
  case Gap(a: Int, b: Int, between: Int)

final case class Puzzle(id: String, n: Int, attributes: Vector[(String, Vector[String])], clues: Vector[Clue],
    text: String)

val ordinals = Vector("first", "second", "third", "fourth", "fifth", "sixth")

/**
 * the things a clue names, in its order: a value said whole (a plural allowed; "hip hop" said "hip-hop", "ford
 * f150" said "Ford F-150"), else by its stem ("painting" said "paints"); a word that is a value of two attributes
 * (a person's name and a child's) is the one its context names ("child is named Bella")
 */
def things(clue: String, attributes: Vector[(String, Vector[String])]): Vector[Int] =
  val values = attributes.flatMap(_._2)
  val owner = attributes.zipWithIndex.flatMap((a, g) => a._2.map(_ => g))
  val low = clue.toLowerCase
  def pieces(v: String): Vector[String] =
    "[a-z]+|[0-9]+".r.findAllIn(v.toLowerCase).toVector.map(Regex.quote)
  def whole(v: String): String = "\\b" + pieces(v).mkString("[ -]?") + "(?:s|es)?\\b"
  def stem(v: String): String =
    val w = v.toLowerCase.replaceAll("[^a-z0-9 ]", " ")
    "\\b" + Regex.quote(w.take(math.max(4, w.length - 3))) + "[a-z]*"
  def found(form: String => String): Vector[(Int, Int, Int)] = values.indices.toVector.flatMap { i =>
    form(values(i)).r.findAllMatchIn(low).map(m => (m.start, m.end, i)) }
  val exact = found(whole)
  val all = if exact.map(_._3).distinct.size >= 2 then exact else exact ++ found(stem)
  // where two values sit on the same words: the one whose attribute the words before name
  def fits(m: (Int, Int, Int)): Int =
    val before = low.substring(math.max(0, m._1 - 30), m._1)
    val about = attributes(owner(m._3))._1.toLowerCase
    if before.contains("child") && about.contains("child") then 0
    else if before.contains("mother") && about.contains("mother") then 0
    else if !before.contains("child") && !before.contains("mother") && about.contains("name") then 1
    else 2
  val kept = all.sortBy(m => (m._1, -(m._2 - m._1), fits(m))).foldLeft(Vector.empty[(Int, Int, Int)]) { (acc, m) =>
    if acc.exists(k => m._1 < k._2 && k._1 < m._2) then acc else acc :+ m }
  kept.map(_._3).distinct

def parse(id: String, text: String): Either[String, Puzzle] =
  val n = "There are (\\d+) houses".r.findFirstMatchIn(text).map(_.group(1).toInt).getOrElse(0)
  val attributes = text.linesIterator.filter(_.trim.startsWith("- ")).toVector.map { l =>
    l.takeWhile(_ != ':').trim.stripPrefix("- ") -> "`([^`]*)`".r.findAllMatchIn(l).map(_.group(1)).toVector }
  val clueLines = text.linesIterator.map(_.trim).filter(_.matches("^\\d+\\. .*")).map(_.dropWhile(_ != ' ').trim)
    .toVector
  def ordinal(c: String): Option[Int] = ordinals.indexWhere(o => c.toLowerCase.contains(s"$o house")) match
    case -1 => None
    case k => Some(k + 1)
  val clues = clueLines.map { c =>
    val t = things(c, attributes)
    val low = c.toLowerCase
    def two(f: (Int, Int) => Clue): Either[String, Clue] =
      if t.size == 2 then Right(f(t(0), t(1))) else Left(s"«$c»: ${t.size} things")
    if low.contains(" is not in the ") then
      ordinal(c).filter(_ => t.size == 1).map(k => Clue.NotAt(t(0), k)).toRight(s"«$c»")
    else if ordinal(c).nonEmpty then
      ordinal(c).filter(_ => t.size == 1).map(k => Clue.At(t(0), k)).toRight(s"«$c»: ${t.size} things")
    else if low.contains("next to each other") then two(Clue.Beside(_, _))
    else if low.contains("one house between") then two(Clue.Gap(_, _, 1))
    else if low.contains("two houses between") then two(Clue.Gap(_, _, 2))
    else if low.contains("somewhere to the left of") then two(Clue.Left(_, _))
    else if low.contains("somewhere to the right of") then two((a, b) => Clue.Left(b, a))
    else if low.contains("directly left of") then two(Clue.JustLeft(_, _))
    else if low.contains(" is ") then two(Clue.Same(_, _))
    else Left(s"«$c»: no template") }
  clues.collectFirst { case Left(e) => e }.toLeft(Puzzle(id, n, attributes, clues.collect { case Right(c) => c }, text))

/**
 * the solutions, up to two: a house for every value, the values of an attribute all different — by search over
 * domains, a value's houses narrowed by every clue it is in each time another value is placed
 */
def solve(p: Puzzle): Vector[Vector[Int]] =
  val group = p.attributes.zipWithIndex.flatMap((a, g) => a._2.map(_ => g))
  val m = group.size
  val houses = (1 to p.n).toSet
  val start = Array.fill(m)(houses)
  for c <- p.clues do c match
    case Clue.At(a, k) => start(a) = start(a) & Set(k)
    case Clue.NotAt(a, k) => start(a) = start(a) - k
    case _ => ()
  def allowed(c: Clue, i: Int, h: Int, j: Int): Set[Int] = c match
    case Clue.Same(a, b) if a == i || b == i => Set(h)
    case Clue.Left(a, _) if a == i => houses.filter(_ > h)
    case Clue.Left(_, _) => houses.filter(_ < h)
    case Clue.JustLeft(a, _) if a == i => Set(h + 1)
    case Clue.JustLeft(_, _) => Set(h - 1)
    case Clue.Beside(_, _) => Set(h - 1, h + 1)
    case Clue.Gap(_, _, g) => Set(h - g - 1, h + g + 1)
    case _ => houses
  def other(c: Clue, i: Int): Option[Int] = c match
    case Clue.Same(a, b) => Option.when(a == i)(b).orElse(Option.when(b == i)(a))
    case Clue.Left(a, b) => Option.when(a == i)(b).orElse(Option.when(b == i)(a))
    case Clue.JustLeft(a, b) => Option.when(a == i)(b).orElse(Option.when(b == i)(a))
    case Clue.Beside(a, b) => Option.when(a == i)(b).orElse(Option.when(b == i)(a))
    case Clue.Gap(a, b, _) => Option.when(a == i)(b).orElse(Option.when(b == i)(a))
    case _ => None
  val touching = (0 until m).map(i => p.clues.flatMap(c => other(c, i).map(j => (c, j)))).toVector
  var found = Vector.empty[Vector[Int]]
  def go(domains: Vector[Set[Int]]): Unit =
    if found.size < 2 && domains.forall(_.nonEmpty) then
      domains.indices.filter(domains(_).size > 1).minByOption(domains(_).size) match
        case None =>
          val at = domains.map(_.head)
          val distinct = (0 until m).groupBy(group).values.forall(is => is.map(at).distinct.size == is.size)
          if distinct && p.clues.forall(holds(_, at)) then found :+= at
        case Some(i) =>
          for h <- domains(i).toVector.sorted do
            var d = domains.updated(i, Set(h))
            // the others of its attribute lose the house; every clue it is in narrows the other value
            for j <- 0 until m if j != i && group(j) == group(i) do d = d.updated(j, d(j) - h)
            for (c, j) <- touching(i) do d = d.updated(j, d(j) & allowed(c, i, h, j))
            go(d)
  def holds(c: Clue, at: Vector[Int]): Boolean = c match
    case Clue.Same(a, b) => at(a) == at(b)
    case Clue.At(a, k) => at(a) == k
    case Clue.NotAt(a, k) => at(a) != k
    case Clue.Left(a, b) => at(a) < at(b)
    case Clue.JustLeft(a, b) => at(a) + 1 == at(b)
    case Clue.Beside(a, b) => (at(a) - at(b)).abs == 1
    case Clue.Gap(a, b, g) => (at(a) - at(b)).abs == g + 1
  go(start.toVector)
  found

@main def zebralogic(out: String, files: String*): Unit =
  val rows = files.flatMap(f => ujson.read(java.nio.file.Files.readString(java.nio.file.Path.of(f)))("rows").arr)
    .map(_("row"))
  var (unique, other, unread) = (0, 0, 0)
  val lines = rows.flatMap { r =>
    val id = r("id").str
    parse(id, r("puzzle").str) match
      case Left(why) =>
        unread += 1
        if unread <= 8 then println(s"unread $id: $why")
        None
      case Right(p) =>
        val s = solve(p)
        if s.size != 1 then
          other += 1
          if other <= 8 then println(s"${s.size} solutions: $id")
          None
        else
          unique += 1
          val values = p.attributes.flatMap(_._2)
          Some(ujson.Obj("id" -> id, "size" -> r("size").str, "houses" -> p.n, "puzzle" -> p.text,
            "solution" -> ujson.Obj.from(values.zip(s.head).map((v, h) => v -> ujson.Num(h)))).render()) }
  java.nio.file.Files.writeString(java.nio.file.Path.of(out), lines.mkString("\n") + "\n")
  println(s"puzzles: ${rows.size}; one solution: $unique; none or several: $other; a clue not read: $unread")
