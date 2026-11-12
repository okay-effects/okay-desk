//> using scala 3.3.8
import scala.io.Source

// A NOVELTY GATE TRIED ON A RUN'S FILE (READ_OUT): signs with their bars at a share of Validate's answers asked;
// then, per set, what is shown and how much of it is wrong.
case class J(set: String, language: String, family: String, how: String, least: Map[String, Float]):
  def definite: Boolean = how == "exact" || how == "harmless" || how == "silent"
  def good: Boolean = how == "exact" || how == "harmless"

@main def novel(path: String): Unit =
  val all = Source.fromFile(path, "UTF-8").getLines().toVector.map { l =>
    val p = l.split("\t").toVector
    J(p(0), p(1), p(2), p(3), p.drop(4).map { kv => val Array(k, v) = kv.split("="); k -> v.toFloat }.toMap) }
  val sets = Vector("Validate", "Test", "Unseen", "Sealed", "Collection")
  def q(xs: Vector[Float], p: Double): Float = xs.sorted.apply(((xs.size - 1) * p).toInt)
  for
    signs <- Vector(Vector.empty[String], Vector("word"), Vector("pair"), Vector("near"), Vector("pair", "near"),
      Vector("pair", "near", "tag", "frame", "link", "pick"))
    share <- (if signs.isEmpty then Vector(0.0) else Vector(0.0, 0.01, 0.03))
  do
    // a bar per language: the two are read with different sureness
    val bars = (for language <- Vector("en", "ru"); s <- signs yield
      val validate = all.filter(j => j.set == "Validate" && j.language == language && j.definite)
      (language, s) -> q(validate.map(_.least.getOrElse(s, 0f)), share)).toMap
    def shown(j: J): Boolean = j.definite && signs.forall(s => j.least.getOrElse(s, 0f) >= bars((j.language, s)))
    val name = if signs.isEmpty then "no sign" else signs.mkString(" + ")
    println(s"$name, the bar at ${share * 100}% of Validate: " +
      bars.toVector.sortBy(_._1).map((k, b) => f"${k._1} ${k._2} $b%.2f").mkString(", "))
    for set <- sets; language <- Vector("en", "ru") do
      val mine = all.filter(j => j.set == set && j.language == language)
      if mine.nonEmpty then
        val (open, closed) = (mine.filter(!shown(_)), mine.filter(shown))
        println(f"  $set%-10s $language: ${mine.size}%4d texts, right ${mine.count(_.good)}%4d; " +
          f"shown ${closed.size}%4d " +
          f"(${100.0 * closed.size / mine.size}%5.1f%%), of them WRONG ${closed.count(!_.good)}%3d " +
          f"(${100.0 * closed.count(!_.good) / math.max(1, closed.size)}%4.1f%%); right but asked " +
          f"${open.count(_.good)}%4d")
