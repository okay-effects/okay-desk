//> using scala 3.3.8

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** THE GATE TRIED WITHOUT READING AGAIN (specs/core-joint.md §8): a run's outcomes (`READ_OUT`, a line a task: set,
 * language, family, how, the least margin of each kind, the words and pairs never met as −counts, the answer) and its
 * bars (the run's log, "the gate's bars: …") → per set and language, for each gate: shown, right among shown, wrong
 * among shown. Gates: `novelty` (the page's: an answer, no word never met, every margin over its bar), `margins` (no
 * novelty sign), `margins×k` (bars k times higher); with a SECOND run over the same tasks (another reader), `agree`
 * (both answered the same) and `agree+margins`.
 * `scala-cli run scripts/gates.scala -- <out.tsv> <run log> [<other out.tsv> <other log>]` */
@main def gates(args: String*): Unit =
  final case class Row(set: String, lang: String, how: String, m: Map[String, Float], answer: String)
  def load(out: String, log: String): (Vector[Row], Map[String, Float]) =
    val line = Files.readAllLines(Path.of(log)).asScala.find(_.contains("the gate's bars:")).getOrElse(
      sys.error(s"no bars in $log"))
    val bars = line.substring(line.indexOf(':') + 1).split(",").map(_.trim).filter(_.nonEmpty)
      .map { b => val p = b.split(" "); s"${p(0)} ${p(1)}" -> p(2).toFloat }.toMap
    val rows = Files.readAllLines(Path.of(out)).asScala.toVector.map(_.split("\t", -1)).map { f =>
      val kv = f.drop(4).map { s => val i = s.indexOf('='); s.take(i) -> s.drop(i + 1) }.toMap
      Row(f(0), f(1), f(3), (kv - "answer").map((k, v) => k -> v.toFloat), kv.getOrElse("answer", "")) }
    (rows, bars)
  val (rows, bars) = load(args(0), args(1))
  val other = Option.when(args.size >= 4)(load(args(2), args(3)))
  other.foreach((o, _) => require(o.size == rows.size && o.zip(rows).forall((a, b) => a.set == b.set && a.lang == b.lang),
    "the two runs are not over the same tasks"))
  val kinds = Vector("tag", "frame", "link", "pick")
  def answered(r: Row) = r.how != "refused"
  def right(r: Row) = r.how == "exact" || r.how == "harmless"
  def over(r: Row, k: Float) = kinds.forall(kind => r.m.get(kind).forall(_ >= k * bars.getOrElse(s"${r.lang} $kind", 0f)))
  def novel(r: Row) = r.m.getOrElse("word", 0f) != 0f || r.m.getOrElse("pair", 0f) != 0f
  def agree(i: Int) = other.exists((o, _) => answered(rows(i)) && answered(o(i)) && rows(i).answer == o(i).answer)
  type Gate = (String, Int => Boolean)
  val gates: Vector[Gate] = Vector[Gate](
    "none" -> (i => answered(rows(i))),
    "novelty" -> (i => answered(rows(i)) && !novel(rows(i)) && over(rows(i), 1f)),
    "margins" -> (i => answered(rows(i)) && over(rows(i), 1f)),
    "margins×2" -> (i => answered(rows(i)) && over(rows(i), 2f))) ++ (if other.isEmpty then Vector.empty[Gate] else Vector[Gate](
    "agree" -> (i => agree(i)),
    "agree+marg" -> (i => agree(i) && over(rows(i), 1f)),
    "agree|novel" -> (i => agree(i) || (answered(rows(i)) && !novel(rows(i)) && over(rows(i), 1f)))))
  println(f"${"set"}%-16s ${"gate"}%-11s ${"shown"}%6s ${"right"}%6s ${"wrong"}%6s  of")
  for ((set, lang), is) <- rows.indices.groupBy(i => (rows(i).set, rows(i).lang)).toVector.sortBy(_._1)
      if set != "Validate"; (name, g) <- gates do
    val shown = is.filter(g)
    println(f"${s"$set $lang"}%-16s $name%-11s ${shown.size}%6d ${shown.count(i => right(rows(i)))}%6d " +
      f"${shown.count(i => !right(rows(i)))}%6d  ${is.size}")
