package okay.desk.bench

import java.nio.file.Path
import okay.desk.{Frame, Sample}

/** HOW MUCH A TRIE OF THE OPTIONS SAVES (theory.md §11 E7): a line's options as the scoring packs them (each option's
 * tokens and its line end, after the newline) — their tokens all together against the nodes of their trie (a shared
 * beginning counted once). `FrameTrie <model dir>` */
object FrameTrie:
  def main(args: Array[String]): Unit =
    val tok = mlx.llm.Chat.load(Path.of(args(0))).tokenizer
    val qs = Vector("How many orders are unpaid?", "Сколько заказов из Львова?", "What did ann spend in November?")
    for q <- qs; taken <- Vector(Vector("table: orders", "answer: count"),
        Vector("table: orders", "answer: count", "where: status = unpaid")) do
      val options = Frame.next(taken, Sample.tables, q)
      // as FrameReading packs a segment: the newline, then the option's tokens (its last one predicted, not fed)
      val seqs = options.map(o => tok.encode(o).toVector)
      val total = seqs.map(_.size).sum
      val nodes = seqs.flatMap(s => s.indices.map(i => s.take(i + 1))).distinct.size
      println(f"${q.take(32)}%-32s after ${taken.size} lines: ${options.size}%4d options, $total%5d tokens, " +
        f"trie $nodes%5d nodes (${total.toDouble / nodes}%.1fx)")

/** THE TRIE'S LAW (E7): the options weighed as a trie give the packed segments' numbers — on MLX, the tree ops by their
 * row-at-a-time defaults. `FrameTrieLaw <model dir> [options]` */
object FrameTrieLaw:
  def main(args: Array[String]): Unit =
    import scala.concurrent.{Await, ExecutionContext}
    import scala.concurrent.duration.*
    given ExecutionContext = ExecutionContext.global
    val dir = Path.of(args(0)); val k = args.lift(1).fold(24)(_.toInt)
    val tok = mlx.llm.Chat.load(dir).tokenizer
    val config = okay.llm.qwen.Qwen3Config.parse(java.nio.file.Files.readString(dir.resolve("config.json")))
    val st = okay.llm.qwen.Safetensors(java.nio.file.Files.readAllBytes(dir.resolve("model.safetensors")))
    val model = okay.llm.qwen.Qwen3Compute(okay.llm.compute.OnMlx(), st, config)
    val q = "How many orders are unpaid?"
    val context = tok.encode(s"Tables: orders(id, customer, amount, status, day, city)\nQuestion: $q\ntable: orders\n" +
      "answer: count\n").toArray
    val head = context.init
    val (_, _, cache) = Await.result(model.run(head, config.layers, Nil, withLogits = false, None, keep = true), 10.minutes)
    val nl = tok.encode("\n").last
    val options = Frame.next(Vector("table: orders", "answer: count"), Sample.tables, q).take(k)
      .map(o => tok.encode(o).toArray :+ nl)
    val t0 = System.nanoTime
    val seg = Await.result(model.score(cache.get, context.last, options, within = true, trie = Some(false)), 10.minutes)
    val t1 = System.nanoTime
    val tree = Await.result(model.score(cache.get, context.last, options, within = true, trie = Some(true)), 30.minutes)
    val t2 = System.nanoTime
    val diff = seg.zip(tree).map((a, b) => math.abs(a - b)).max
    println(f"${options.size} options: max |segments − trie| = $diff%.2e; best ${seg.indexOf(seg.max)} vs " +
      f"${tree.indexOf(tree.max)}; segments ${(t1 - t0) / 1e9}%.1f s, trie (row-at-a-time) ${(t2 - t1) / 1e9}%.1f s")

/** what a line's options are: by section and column (`FrameMix`) */
object FrameMix:
  def main(args: Array[String]): Unit =
    val q = args.headOption.getOrElse("How many orders are unpaid?")
    val options = Frame.next(Vector("table: orders", "answer: count"), Sample.tables, q)
    println(s"${options.size} options for: $q")
    options.groupBy(o => o.split(" ").take(2).mkString(" ")).toVector.sortBy(-_._2.size).foreach((k, v) =>
      println(f"${v.size}%4d  $k%-24s e.g. ${v.take(3).mkString("; ")}"))
