//> using scala 3.3.8

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** THE PROTOCOL'S VERDICT (docs/toolkit.md §4): two names of the history compared set by set — exact with no gate over
 * the seeds (range and mean), and what the page's gate shows (shown / wrong, summed) — with a word: "up" or "down"
 * when the means differ by more than either side's range, "within the spread" otherwise; and the hard limits said
 * when broken (a wrong answer shown through the page's gate, Test below by more than a point).
 * `scala-cli run scripts/compare.scala -- <old name> <new name>` */
@main def compare(old: String, next: String): Unit =
  val file = Path.of(sys.props("user.home"), "work", "my", "okay-desk-private", "history.tsv")
  val rows = Files.readAllLines(file).asScala.toVector.drop(1).map(_.split("\t", -1))
  def of(name: String) = rows.filter(_(1) == name)
  require(of(old).nonEmpty && of(next).nonEmpty, s"no rows of $old or of $next in $file")
  val keys = (of(old) ++ of(next)).map(r => (r(3), r(4))).distinct
  val order = Vector("Test", "Unseen", "Sealed", "Teacher test", "Collection")
  println(f"${"set"}%-18s ${old}%-24s ${next}%-24s verdict")
  var broken = Vector.empty[String]
  for (set, lang) <- keys.sortBy((s, l) => (order.indexOf(s), l)) do
    def cell(name: String) =
      val rs = of(name).filter(r => r(3) == set && r(4) == lang)
      val xs = rs.map(_(5).toDouble)
      val shown = rs.flatMap(r => r(7).toIntOption).sum
      val wrong = rs.flatMap(r => r(8).toIntOption).sum
      (xs, shown, wrong)
    val (a, sa, wa) = cell(old)
    val (b, sb, wb) = cell(next)
    if a.nonEmpty && b.nonEmpty then
      val (ma, mb) = (a.sum / a.size, b.sum / b.size)
      val spread = math.max(a.max - a.min, b.max - b.min)
      val verdict = if mb - ma > spread then "up" else if ma - mb > spread then "DOWN" else "within the spread"
      def show(xs: Vector[Double], s: Int, w: Int) = f"${xs.min}%.0f–${xs.max}%.0f (${xs.sum / xs.size}%.1f) $s/$w"
      println(f"${s"$set $lang"}%-18s ${show(a, sa, wa)}%-24s ${show(b, sb, wb)}%-24s $verdict")
      if wb > wa then broken :+= s"$set $lang: $wb wrong shown through the page's gate (was $wa)"
      if set == "Test" && ma - mb > 1 then broken :+= f"Test $lang: $mb%.1f against $ma%.1f"
  println(if broken.isEmpty then "hard limits: kept" else "hard limits BROKEN: " + broken.mkString("; "))
