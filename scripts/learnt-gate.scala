//> using scala 3.3.8

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** A GATE LEARNT FROM OUTCOMES (specs/core-joint.md §9): a logistic model of "this shown answer is wrong" over a
 * task's signs — each head's least margin over its bar (the reader with the adapter and the one without), the words
 * and pairs never met, whether the two readers agree — fitted on the unseen families and the teacher's test, its
 * threshold chosen there for at most `alpha` wrong among shown, then judged on sets it never saw: the sealed set
 * and the collection. `scala-cli run scripts/learnt-gate.scala -- a.tsv a.log b.tsv b.log [alpha]` */
@main def learntGate(args: String*): Unit =
  final case class Row(set: String, lang: String, how: String, m: Map[String, Float], answer: String)
  def load(out: String, log: String): (Vector[Row], Map[String, Float]) =
    val line = Files.readAllLines(Path.of(log)).asScala.find(_.contains("the gate's bars:")).get
    val bars = line.substring(line.indexOf(':') + 1).split(",").map(_.trim).filter(_.nonEmpty)
      .map { b => val p = b.split(" "); s"${p(0)} ${p(1)}" -> p(2).toFloat }.toMap
    val rows = Files.readAllLines(Path.of(out)).asScala.toVector.map(_.split("\t", -1)).map { f =>
      val kv = f.drop(4).map { s => val i = s.indexOf('='); s.take(i) -> s.drop(i + 1) }.toMap
      Row(f(0), f(1), f(3), (kv - "answer").map((k, v) => k -> v.toFloat), kv.getOrElse("answer", "")) }
    (rows, bars)
  val (a, barsA) = load(args(0), args(1))
  val (b, barsB) = load(args(2), args(3))
  val alpha = args.lift(4).map(_.toDouble).getOrElse(0.01)
  val kinds = Vector("tag", "frame", "link", "pick")
  def answered(r: Row) = r.how != "refused"
  def right(r: Row) = r.how == "exact" || r.how == "harmless"
  def rel(r: Row, bars: Map[String, Float], k: String): Double =
    r.m.get(k).fold(3.0)(m => math.max(-3.0, math.min(3.0, m / math.max(1e-3, bars.getOrElse(s"${r.lang} $k", 1f)).toDouble - 1)))
  def features(i: Int): Array[Double] =
    val (x, y) = (a(i), b(i))
    Array(1.0) ++ kinds.map(rel(x, barsA, _)) ++ kinds.map(rel(y, barsB, _)) ++ Array(
      math.log1p(-x.m.getOrElse("word", 0f).toDouble), math.log1p(-x.m.getOrElse("pair", 0f).toDouble),
      if answered(y) && y.answer == x.answer then 1.0 else 0.0, if x.lang == "ru" then 1.0 else 0.0)
  val fit = a.indices.filter(i => answered(a(i)) && (a(i).set == "Unseen" || a(i).set == "Teacher test"))
  val xs = fit.map(features); val ys = fit.map(i => if right(a(i)) then 0.0 else 1.0)
  val w = new Array[Double](xs.head.length)
  def p(x: Array[Double]) = 1 / (1 + math.exp(-x.indices.map(j => w(j) * x(j)).sum))
  for _ <- 1 to 3000 do
    val g = new Array[Double](w.length)
    for (x, y) <- xs.zip(ys); j <- w.indices do g(j) += (p(x) - y) * x(j) / xs.size
    for j <- w.indices do w(j) -= 0.5 * (g(j) + 1e-3 * w(j))
  // the threshold on the fitting sets: the largest share shown with wrong / shown ≤ alpha
  val scored = fit.map(i => (p(features(i)), right(a(i)))).sortBy(_._1)
  val t = scored.indices.reverse.find { n =>
    val shown = scored.take(n + 1); shown.count(!_._2).toDouble / shown.size <= alpha }.map(n => scored(n)._1)
    .getOrElse(0.0)
  println(f"fitted on ${fit.size} answers of Unseen and the teacher's test; threshold p(wrong) ≤ $t%.3f; weights " +
    w.map(v => f"$v%.2f").mkString(" "))
  println(f"${"set"}%-16s ${"shown"}%6s ${"right"}%6s ${"wrong"}%6s  of")
  for ((set, lang), is) <- a.indices.groupBy(i => (a(i).set, a(i).lang)).toVector.sortBy(_._1) if set != "Validate" do
    val shown = is.filter(i => answered(a(i)) && p(features(i)) <= t)
    val tag = if set == "Unseen" || set == "Teacher test" then " (fitted here)" else ""
    println(f"${s"$set $lang"}%-16s ${shown.size}%6d ${shown.count(i => right(a(i)))}%6d ${shown.count(i => !right(a(i)))}%6d  ${is.size}$tag")
