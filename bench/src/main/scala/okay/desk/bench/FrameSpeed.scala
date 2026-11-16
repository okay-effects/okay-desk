package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.Chat
import okay.desk.*
import okay.desk.skill.{Read, Reading}
import okay.llm.compute.OnMlx
import okay.llm.qwen.{Qwen3Compute, Qwen3Config, Safetensors}
import okay.scala3.*

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/** a frame adapter merged into the model's weights (mlx-scala's `LoraMerge`): a model directory the page's engine
 * reads — `FrameMerge <model dir> <adapter> <out dir>` */
object FrameMerge:
  def main(args: Array[String]): Unit =
    mlx.llm.LoraMerge.qwen3(Path.of(args(0)), Path.of(args(1)), Path.of(args(2)))
    println(s"merged: ${args(2)}")

/**
 * `Read` ANSWERED BY THE PAGE'S ENGINE (okay-llm's `Qwen3Compute`) on the JVM's MLX (`OnMlx`): the same program as in
 * the browser, the tokenizer mlx-scala's. `packed`: a line's options weighed in one forward (`Qwen3Compute.score`),
 * else one option a forward (the same engine and weights: the packing's gain alone).
 */
final class QwenReading(dir: Path, packed: Boolean, within: Boolean = false, planes: Option[Path] = None)(
    using ExecutionContext) extends Reading:
  private val chat = Chat.load(dir); private val tok = chat.tokenizer
  private val config = Qwen3Config.parse(Files.readString(dir.resolve("config.json")))
  // the base's weights, and — `planes` — an adapter beside them (okay-desk's `Adapted`: the page's way), else merged
  val model =
    val st = Safetensors(Files.readAllBytes(dir.resolve("model.safetensors")))
    planes.fold(Qwen3Compute(OnMlx(), st, config)) { p =>
      val a = okay.desk.skill.Adapted(OnMlx(), st, Safetensors(Files.readAllBytes(p)))
      val m = Qwen3Compute(a, a.tensors, config); a.releaseSources(); m }
  private var kept: Option[(Array[Int], model.Cache)] = None
  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 10.minutes)
  /** the cache of `head`: from the kept one where it begins `head`, else from nothing */
  private def cacheOf(head: Array[Int]): model.Cache =
    kept match
      case Some((ids, c)) if ids.sameElements(head) => c
      case other =>
        val from = other.filter((ids, _) => head.startsWith(ids)).map(_._2)
        val (_, _, next) = await(model.run(head, 0, Nil, withLogits = false, from, keep = true))
        other.foreach(_._2.free())
        kept = Some(head -> next.get); next.get
  def answer[X](op: Read[X]): X = (op match
    case Read.Prompt(system, user) =>
      def msg(role: String, content: String) = okay.effects.json.Json.obj("role" -> okay.effects.json.Json.Str(role),
        "content" -> okay.effects.json.Json.Str(content))
      chat.prompt(Vector(msg("system", system), msg("user", user)), Nil, Map("enable_thinking" -> false))
    case Read.Encode(text) => tok.encode(text)
    case Read.Decode(ids) => tok.decode(ids)
    case Read.Score(context, options) =>
      val c = cacheOf(context.init)
      if packed then await(model.score(c, context.last, options, within))
      else options.map(o => await(model.score(c, context.last, Vector(o), within)).head)
    case Read.Note(_) => ()
    case other => sys.error(s"not answered here: $other")).asInstanceOf[X]

/**
 * THE PACKING'S GAIN, MEASURED (specs/frames.md §6): `Everyday` read into frames by the page's engine with a merged
 * frame model (`FRAME_MERGED`), its options weighed packed and one by one: the seconds a question, and whether the
 * frames are the same. `FRAME_ONLY`: the first questions only.
 */
object FrameSpeed:
  type RP[X] = (Read + Person)[X]
  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global
    val dir = Path.of(sys.env("FRAME_MERGED"))
    val asked = sys.env.get("FRAME_ONLY").fold(FrameBench.everyday)(n => FrameBench.everyday.take(n.toInt))
    def readAll(r: QwenReading): (Vector[Vector[String]], Double) =
      val t0 = System.nanoTime()
      val frames = asked.map(a => Read.run(handle(FrameReading.read[RP](a.tables, a.text),
        Person.silent[Read, FrameReading.Reading]), r).lines)
      (frames, (System.nanoTime() - t0) / 1e9 / asked.size)
    // FRAME_WITHIN=1: the packed weighing over the whole vocabulary against the one within the options' tokens
    val within = sys.env.get("FRAME_WITHIN").contains("1")
    // FRAME_PLANES: the adapter beside the base (FRAME_BASE) against the merged model, the same weighing
    sys.env.get("FRAME_PLANES") match
    case Some(planes) =>
      val (merged, tm) = readAll(QwenReading(dir, packed = true, within = true))
      val (beside, tb) = readAll(QwenReading(Path.of(sys.env("FRAME_BASE")), packed = true, within = true,
        Some(Path.of(planes))))
      def right(fs: Vector[Vector[String]]) = asked.zip(fs).count((a, lines) => FrameBench.outcome(a.gold,
        Frame.query(lines, a.tables).flatMap(Exact.run(a.tables, _)).toOption) == "right")
      println(f"merged: right ${right(merged)} ($tm%.2f s); beside the base: right ${right(beside)} ($tb%.2f s); " +
        s"the same frames ${merged.zip(beside).count(_ == _)} of ${asked.size}")
    case None =>
      val (one, tOne) = readAll(QwenReading(dir, packed = !within))
      val (pack, tPack) = readAll(QwenReading(dir, packed = true, within = within))
      if within then
        val rightFull = asked.zip(one).count((a, lines) => FrameBench.outcome(a.gold,
          Frame.query(lines, a.tables).flatMap(Exact.run(a.tables, _)).toOption) == "right")
        println(s"the whole vocabulary: right $rightFull of ${asked.size}")
      val same = one.zip(pack).count(_ == _)
      val right = asked.zip(pack).count((a, lines) =>
        FrameBench.outcome(a.gold, Frame.query(lines, a.tables).flatMap(Exact.run(a.tables, _)).toOption) == "right")
      println(f"one option a forward: $tOne%.2f s a question; packed: $tPack%.2f s — ${tOne / tPack}%.1f× faster; " +
        s"the same frames: $same of ${asked.size}; right (packed): $right")

/** ONE QUESTION READ, ITS LINES AND EACH LINE'S OPTIONS WEIGHED (a check of a page's case on the JVM): the sample
 * tables of the page (`web/page/sample`), the merged frame model (`FRAME_MERGED`), the page's weighing (within the
 * options), the links at `FRAME_LINK` — `FrameAsk <question>` */
object FrameAsk:
  type RP[X] = (Read + Person)[X]
  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global
    val tables = Vector("orders", "customers").map(n => s"$n.csv" -> Table.parse(s"$n.csv",
      Files.readString(Path.of(sys.env("FRAME_SAMPLE"), s"$n.csv"))).fold(sys.error, identity)).toMap
    val r = QwenReading(Path.of(sys.env("FRAME_MERGED")), packed = true, within = true)
    val shown = new Reading:
      def answer[X](op: Read[X]): X =
        val out = r.answer(op)
        (op, out) match
          case (Read.Score(_, options), scores: Vector[?]) if options.size > 2 =>
            println(s"  ${options.size} options, the best: " + scores.asInstanceOf[Vector[Double]].zipWithIndex
              .sortBy(-_._1).take(6).map((s, i) => f"$i:$s%.2f").mkString(" "))
          case _ => ()
        out
    for link <- Vector(0.0, 2.0, 8.0) do
      val read = Read.run(handle(FrameReading.read[RP](tables, args.mkString(" "), link = link),
        Person.silent[Read, FrameReading.Reading]), shown)
      println(s"link $link: ${read.lines.mkString(" | ")}  margins ${read.margins.map(m => f"$m%.2f").mkString(" ")}")
