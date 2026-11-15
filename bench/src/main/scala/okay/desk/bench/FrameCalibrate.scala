package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.effects.json.Json
import scala.jdk.CollectionConverters.*

/**
 * THE BOUND ON MISREADINGS (specs/guarantees.md §3.1): conformal risk control. A calibration's records (`FrameBench`
 * with `FRAME_RECORDS`, questions never trained on) set the doubt's bar: λ̂ = the smallest bar with
 * (n·R̂(λ) + 1) / (n + 1) ≤ ε, R̂(λ) the share of the calibration's questions answered wrong at λ (shown: their score
 * at least λ). Then the test's records read at λ̂: right, asked, wrong — the bound holds for a test drawn as the
 * calibration is. The score: the frame's smallest margin; with two readers (a second file of each), the margin where
 * their frames agree and none where they do not.
 * `FrameCalibrate <calibration records>[,<second reader's>] <test records>[,<second reader's>]`
 */
object FrameCalibrate:
  final case class Rec(key: String, lang: String, outcome: String, margin: Double, frame: String)

  def load(path: String): Vector[Rec] = Files.readAllLines(Path.of(path)).asScala.toVector.filter(_.nonEmpty).map {
    l =>
      val j = Json.fromText(l)
      def s(k: String) = j.field(k).collect { case Json.Str(x) => x }.getOrElse("")
      Rec(s("key"), s("lang"), s("outcome"), j.field("margin").collect { case Json.Num(n) => n.toDouble }
        .getOrElse(0.0), s("frame"))
  }

  /** each question's outcome and score; with two readers the score is none (−∞) where their frames differ */
  def scored(files: String): Vector[(String, Double)] =
    files.split(",").toVector.map(load) match
      case Vector(one) => one.map(r => (r.outcome, r.margin))
      case Vector(a, b) =>
        val other = b.map(r => (r.key, r.lang) -> r).toMap
        a.flatMap(r => other.get((r.key, r.lang)).map(o =>
          (r.outcome, if o.frame == r.frame then math.min(r.margin, o.margin) else Double.NegativeInfinity)))
      case _ => sys.error("one or two readers' records")

  def risk(xs: Vector[(String, Double)], bar: Double): Double =
    xs.count((o, s) => s >= bar && o == "wrong").toDouble / xs.size

  def bar(calibration: Vector[(String, Double)], eps: Double): Double =
    val n = calibration.size
    val bars = (calibration.map(_._2).filter(!_.isInfinite) :+ 0.0).distinct.sorted
    bars.find(l => (n * risk(calibration, l) + 1) / (n + 1) <= eps).getOrElse(Double.PositiveInfinity)

  def main(args: Array[String]): Unit =
    val calibration = scored(args(0)); val test = scored(args(1))
    println(s"calibration: ${calibration.size} questions; test: ${test.size}")
    def counts(xs: Vector[(String, Double)], l: Double) =
      val shown = xs.filter(_._2 >= l)
      (shown.count(_._1 == "right"), xs.size - shown.size, shown.count(_._1 == "wrong"))
    for eps <- Vector(0.02, 0.05, 0.1, 0.15) do
      val l = bar(calibration, eps)
      val (cr, ca, cw) = counts(calibration, l); val (tr, ta, tw) = counts(test, l)
      println(f"ε $eps%.2f: λ̂ $l%.3f | calibration right $cr / asked $ca / wrong $cw | test right $tr / asked $ta / " +
        f"wrong $tw — the test's risk ${tw.toDouble / test.size}%.3f")
