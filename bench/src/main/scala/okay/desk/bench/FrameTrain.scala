package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.{Adaptable, Chat, Lora, Model, Models, Port, Tokenizer}
import okay.desk.*
import okay.effects.json.Json
import scala.jdk.CollectionConverters.*

/** the frames' texts read back: each a table (the domains' by seed, or written out), a question, the lines */
object FrameTexts:
  /** the texts of a file; `FRAME_SYNTH_MAX`: at most so many of the synthetic forms' (template, taught), drawn at
   * random — the first night's had 40% of them and the reader took a plain sum for a share */
  def load(path: Path): Vector[FrameData.Item] =
    val all = Files.readAllLines(path).asScala.toVector.filter(_.trim.nonEmpty)
    // (the questions outside — "answer: cannot" — all kept: bounding them away lost every refusal, round 2)
    val synth = all.filter(l => (l.contains("\"source\":\"template\"") || l.contains("\"source\":\"taught\"")) &&
      !l.contains("answer: cannot"))
    val keep = sys.env.get("FRAME_SYNTH_MAX").map(_.toInt).fold(synth.toSet)(n =>
      scala.util.Random(11).shuffle(synth).take(n).toSet)
    val synthetic = synth.toSet
    all.filter(l => !synthetic(l) || keep(l)).flatMap { l =>
      val j = Json.fromText(l)
      val lines = j.field("lines").collect { case Json.Arr(xs) => xs.collect { case Json.Str(s) => s } }
        .getOrElse(Vector.empty)
      val question = j.field("question").collect { case Json.Str(s) => s }.getOrElse("")
      val tables = j.field("tables") match
        case Some(Json.Arr(ts)) => ts.collect { case Json.Str(s) =>
          val name = s.takeWhile(_ != '\n')
          Table.parse(name + ".tsv", s.dropWhile(_ != '\n').drop(1) + "\n").toOption
            .map(t => name -> t.copy(name = name))
        }.flatten.toMap
        case _ => j.field("seed").collect { case Json.Num(n) =>
          val t = Domains.any(scala.util.Random(n.toLong)); Map(t.name -> t) }.getOrElse(Map.empty)
      Option.when(lines.nonEmpty && tables.nonEmpty && question.nonEmpty)(FrameData.Item(tables, question, lines))
    }

/**
 * THE FRAME READER TRAINED (specs/frames.md §4): LoRA on a small model (the released Qwen3 0.6B by default), each
 * example the frame's prompt (`Frame.prompt`: the rule, the table with its values, the question) and the frame's
 * lines as the answer, a newline before each. `FRAME_TEXTS`: the jsonl files (comma-separated); `FRAME_OUT` the
 * adapter. `Everyday` is never among them.
 */
object FrameTrain:
  def msg(role: String, content: String) = Json.obj("role" -> Json.Str(role), "content" -> Json.Str(content))
  def answer(lines: Vector[String]): String = lines.map("\n" + _).mkString

  def main(args: Array[String]): Unit =
    // the seed of the adapter's first values too
    sys.env.get("FRAME_SEED").foreach(s => mlx.Mlx.randomSeed(s.toLong))
    val repo = sys.env.getOrElse("FRAME_MODEL", "mlx-community/Qwen3-0.6B-4bit")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val loaded = Model.load(dir, mapped = true); val tok = chat.tokenizer
    val end = tok.id("<|im_end|>")
    // none over a table named as one of the measure's (the grammar's generic "sales" is not `Everyday`'s, but kept out)
    val all = sys.env("FRAME_TEXTS").split(",").toVector.flatMap(f => FrameTexts.load(Path.of(f)))
    val items = all.filterNot(i => i.tables.keySet.exists(Domains.heldOut))
    println(s"${all.size - items.size} texts over tables named as the measure's left out")
    val epochs = sys.env.get("FRAME_EPOCHS").map(_.toInt).getOrElse(1)
    println(s"$repo: ${items.size} frames; e.g.\n  " +
      items.take(2).map(i => s"${i.question}\n    → ${i.lines.mkString(" | ")}")
      .mkString("\n  "))
    val lora = Lora(loaded.asInstanceOf[Adaptable], rank = sys.env.get("FRAME_RANK").map(_.toInt).getOrElse(16),
      projs = Seq("q", "k", "v", "o", "gate", "up", "down"),
      lr = sys.env.get("FRAME_LR").map(_.toFloat).getOrElse(2e-4f))
    // FRAME_INIT: the adapter's first values from a file (a looped student's: LoopInit), not random
    sys.env.get("FRAME_INIT").foreach { p => lora.restoreParameters(p); println(s"the adapter started from $p") }
    // FRAME_SEED: the order the texts are seen in — the protocol's seeds
    val rnd = new scala.util.Random(sys.env.get("FRAME_SEED").map(_.toLong).getOrElse(5L))
    val t0 = System.nanoTime()
    // FRAME_BATCH: so many examples a step in one pass (mlx-scala's `Lora.stepBatch`); within a window of 16 batches
    // of the shuffled order the examples sorted by length, so a batch pads little
    val batch = sys.env.get("FRAME_BATCH").map(_.toInt).getOrElse(1)
    // FRAME_TREE: a batch as a trie (`Lora.stepTree`): the rule and a table run once for the questions over it; the
    // window's examples sorted by their tokens, so a batch holds questions over one table
    val tree = sys.env.contains("FRAME_TREE")
    given Ordering[Array[Int]] = (a, b) =>
      val i = a.indices.find(k => k >= b.length || a(k) != b(k))
      i.fold(a.length - b.length)(k => if k >= b.length then 1 else Integer.compare(a(k), b(k)))
    def tokens(i: FrameData.Item): (Array[Int], Int) =
      val (system, user) = Frame.prompt(i.tables, i.question)
      val prompt = chat.prompt(Vector(msg("system", system), msg("user", user)), Nil, Map("enable_thinking" -> false))
      (prompt ++ (tok.encode(answer(i.lines)) :+ end), prompt.length)
    // FRAME_LISTWISE=<k>: TRAINED AS IT READS (okay-inlay concept.md §5 role 2) — each line of the frame a choice
    // among the options the reading offers there (`Frame.next`, pruned as the page prunes), the meant line against
    // k others (half of them, where there are, of its own section: `where:` against `where:`), the listwise loss
    // over one trie a batch (mlx-scala `Lora.stepChoices`)
    val listwise = sys.env.get("FRAME_LISTWISE").map(_.toInt)
    val newline = tok.encode("\n").head
    def choicesOf(i: FrameData.Item, prompt: Array[Int], wide: Int): Vector[Lora.Choice] =
      var ctx = prompt
      i.lines.indices.toVector.flatMap { li =>
        val gold = i.lines(li)
        val so = FrameReading.Reading(i.lines.take(li), Vector.empty)
        val offered = FrameReading.pruned(Frame.next(so.lines, i.tables, i.question), so, i.tables, i.question)
        val c = Option.when(offered.contains(gold) && offered.size > 1) {
          val head = gold.takeWhile(_ != ':')
          val (same, other) = rnd.shuffle(offered.filterNot(_ == gold)).partition(_.takeWhile(_ != ':') == head)
          val fromOther = math.min(other.size, wide / 2)
          val negatives = (same.take(wide - fromOther) ++ other.take(fromOther)).take(wide)
          val opts = rnd.shuffle(gold +: negatives)
          Lora.Choice(ctx :+ newline, opts.map(o => tok.encode(o) :+ (if o == Frame.end then end else newline)),
            opts.indexOf(gold))
        }
        ctx = (ctx :+ newline) ++ tok.encode(gold)
        c
      }
    for e <- 1 to epochs do
      var sum = 0f; var k = 0; var shown = 0
      val order = rnd.shuffle(items).map(i => (tokens(i), i))
      val batches = if batch <= 1 then order.map(Vector(_))
        else order.grouped(batch * 16).toVector.flatMap(w =>
          (if tree || listwise.nonEmpty then w.sortBy(_._1._1) else w.sortBy(_._1._1.length)).grouped(batch).toVector)
      // (one a step: the order as before, the seed's runs reproduced)
      for b0 <- if batch <= 1 then batches else rnd.shuffle(batches) do
        val b = b0.map(_._1)
        val loss = listwise match
          case Some(wide) =>
            val cs = b0.flatMap((t, i) => choicesOf(i, t._1.take(t._2), wide))
            if cs.isEmpty then 0f else lora.stepChoices(cs)
          case None =>
            if b.size == 1 then lora.step(b.head._1, b.head._2)
            else if tree then lora.stepTree(b) else lora.stepBatch(b)
        sum += loss * b.size; k += b.size
        if k / 200 > shown then
          shown = k / 200
          println(f"epoch $e, $k/${items.size}: mean loss ${sum / k}%.4f (${(System.nanoTime() - t0) / 1e9}%.0f s)")
      println(f"epoch $e: mean loss ${sum / k}%.4f")
    val out = sys.env.getOrElse("FRAME_OUT", sys.props("user.home") + "/.cache/okay-desk/frame-lora.safetensors")
    lora.save(out)
    println(s"saved $out")
    lora.close(); loaded.close()

/**
 * THE FRAME BUILT BY CHOICES AS A DECODE PORT: before each line only a newline may come; after it the model scores
 * the next line's whole options (`Frame.next`: the table and question allow them) and takes the best (the mean
 * log-probability of its tokens); after `end`, only the stop. Nothing is written that is not an option.
 */
final class FramePort(tok: Tokenizer, tables: Map[String, Table], question: String, stop: Int, newline: Int)
    extends Port:
  val lines = scala.collection.mutable.ArrayBuffer.empty[String]
  private val pending = scala.collection.mutable.ArrayBuffer.empty[Int]
  private def settle(): Unit =
    if pending.nonEmpty then { lines += tok.decode(pending.toSeq).trim; pending.clear() }
  def allowed: Option[Array[Int]] =
    settle()
    Some(if lines.lastOption.contains(Frame.end) then Array(stop) else Array(newline))
  def took(token: Int): Unit = if token != newline && token != stop then pending += token
  override def choices: Option[Seq[Array[Int]]] =
    settle()
    if lines.lastOption.contains(Frame.end) then None
    else
      val options = Frame.next(lines.toVector, tables, question)
      Option.when(options.nonEmpty)(options.map(o => tok.encode(o)))

