package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.{Adaptable, Chat, Lora, Model, Models, Port, Tokenizer}
import okay.desk.*
import okay.desk.tasks.Everyday
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
    val rnd = new scala.util.Random(5)
    val t0 = System.nanoTime()
    for e <- 1 to epochs do
      var sum = 0f; var k = 0
      for i <- rnd.shuffle(items) do
        val (system, user) = Frame.prompt(i.tables, i.question)
        val prompt = chat.prompt(Vector(msg("system", system), msg("user", user)), Nil, Map("enable_thinking" -> false))
        val a = tok.encode(answer(i.lines)) :+ end
        sum += lora.step(prompt ++ a, prompt.length); k += 1
        if k % 200 == 0 then
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

/** THE FRAME READER MEASURED on `Everyday`, as `CeilingBench` and `GenBench` score: right, asked, wrong */
object FrameBench:
  def main(args: Array[String]): Unit =
    val repo = sys.env.getOrElse("FRAME_MODEL", "mlx-community/Qwen3-0.6B-4bit")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true); val tok = chat.tokenizer
    sys.env.get("FRAME_ADAPTER").foreach(f => Lora.attach(model.asInstanceOf[Adaptable], f))
    println(s"$repo${sys.env.get("FRAME_ADAPTER").fold("")(" with " + _)}")
    val stop = tok.id("<|im_end|>"); val newline = tok.encode("\n").head
    final case class Count(right: Int = 0, asked: Int = 0, wrong: Int = 0)
    var counts = Map.empty[(String, String), Count].withDefaultValue(Count())
    val doubts = scala.collection.mutable.ArrayBuffer.empty[(Option[Table], Vector[String], Double, String,
      Map[String, Table])]
    val t0 = System.nanoTime()
    val questions = sys.env.get("FRAME_ONLY").fold(Everyday.questions)(n => Everyday.questions.take(n.toInt))
    for q <- questions; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
      val tables = Map(q.table -> Everyday.tables(q.table))
      val (system, user) = Frame.prompt(tables, text)
      val prompt = chat.prompt(Vector(FrameTrain.msg("system", system), FrameTrain.msg("user", user)), Nil,
        Map("enable_thinking" -> false))
      val read = FrameRead(model, prompt, tok, tables, text, newline, stop)
      val lines = read.lines
      doubts += ((q.gold.flatMap(Everyday.run(_).toOption), lines, read.margins.minOption.getOrElse(0.0), lang, tables))
      val got = Frame.query(lines, tables).flatMap(Exact.run(tables, _)).toOption
      val gold = q.gold.flatMap(Everyday.run(_).toOption)
      val outcome = (gold, got) match
        case (_, None) => if q.gold.isEmpty then "right" else "asked"
        case (None, Some(_)) => "WRONG (outside)"
        case (Some(want), Some(t)) => if Sample.same(t, want) then "right" else "WRONG"
      val c = counts((q.table, lang))
      counts = counts.updated((q.table, lang),
        if outcome == "right" then c.copy(right = c.right + 1)
        else if outcome == "asked" then c.copy(asked = c.asked + 1) else c.copy(wrong = c.wrong + 1))
      println(f"  [${q.table}%-8s $lang] $outcome%-15s $text → ${lines.dropRight(1).drop(1).mkString(" | ")}")
    println(f"the frame reader, ${(System.nanoTime() - t0) / 1e9 / (2 * Everyday.questions.size)}%.1f s a question:")
    for t <- Everyday.tables.keys.toVector.sorted do
      val (en, ru) = (counts((t, "en")), counts((t, "ru")))
      println(f"  $t%-9s en ${en.right}%2d / ${en.asked}%2d / ${en.wrong}%2d   " +
        f"ru ${ru.right}%2d / ${ru.asked}%2d / ${ru.wrong}%2d")
    for lang <- Vector("en", "ru") do
      val c = counts.collect { case ((_, l), c) if l == lang => c }
      println(s"total $lang: right ${c.map(_.right).sum}, asked ${c.map(_.asked).sum}, wrong ${c.map(_.wrong).sum} " +
        s"of ${Everyday.questions.size}")
    // THE DOUBT: a question whose smallest margin is below a bar is asked instead — right, asked, wrong by the bar
    for bar <- Vector(0.0, 0.05, 0.1, 0.2, 0.3, 0.5, 0.75, 1.0) do
      val line = Vector("en", "ru").map { lang =>
        val mine = doubts.filter(_._4 == lang)
        val (r, a, w) = mine.foldLeft((0, 0, 0)) { case ((r, a, w), (gold, lines, m, _, tables)) =>
          val got = if m < bar then None else Frame.query(lines, tables).flatMap(Exact.run(tables, _)).toOption
          (gold, got) match
            case (_, None) => if gold.isEmpty then (r + 1, a, w) else (r, a + 1, w)
            case (None, Some(_)) => (r, a, w + 1)
            case (Some(want), Some(t)) => if Sample.same(t, want) then (r + 1, a, w) else (r, a, w + 1) }
        f"$lang $r%2d / $a%2d / $w%2d" }
      println(f"  doubt below $bar%.2f: ${line.mkString("   ")}")
    model.close()

/**
 * THE FRAME READ WITH ITS DOUBT (specs/frames.md §2): the same choices as `FramePort`, each line's options scored
 * by the model from a checkpoint of its state (the mean log-probability of the option's tokens and the line's end),
 * the best fed on — and the MARGIN of each choice kept: the best's score less the second's. A small margin at any
 * line is a doubt: the page asks instead of answering.
 */
object FrameRead:
  import mlx.*
  import mlx.Mlx.*

  final case class Read(lines: Vector[String], margins: Vector[Double])

  def apply(model: Model, prompt: Array[Int], tok: Tokenizer, tables: Map[String, Table], question: String,
      newline: Int, stop: Int, maxLines: Int = 16): Read =
    val carry = new Scope
    val state = model.newState()
    try
      Scope(carry)(model.logits(ints(prompt, 1, prompt.length), state).eval())
      val lines = scala.collection.mutable.ArrayBuffer.empty[String]
      val margins = scala.collection.mutable.ArrayBuffer.empty[Double]
      def score(option: Array[Int], after: Int): Double =
        val at = state.checkpoint()
        val input = newline +: option
        val want = option :+ after
        val lp = Scope(carry) {
          val logits = astype(model.logitsAll(ints(input, 1, input.length), state), Dtype.Float32)
          val v = logits.dim(-1)
          val probs = softmax(reshape(logits, input.length, v), 1)
          takeAlong(probs, ints(want, input.length, 1), 1).toFloats.take(want.length)
        }
        state.rewind(at, 0): Unit
        at.close()
        lp.map(p => math.log(math.max(p, 1e-30f).toDouble)).sum / want.length
      while !lines.lastOption.contains(Frame.end) && lines.size < maxLines do
        val options = Frame.next(lines.toVector, tables, question)
        if options.isEmpty then lines += Frame.end
        else
          val scored = options.map(o => (o, score(tok.encode(o), if o == Frame.end then stop else newline)))
            .sortBy(-_._2)
          val (best, s) = scored.head
          margins += scored.lift(1).fold(Double.PositiveInfinity)(second => s - second._2)
          lines += best
          val fed = newline +: tok.encode(best)
          Scope(carry)(model.logits(ints(fed, 1, fed.length), state).eval())
      Read(lines.toVector, margins.toVector)
    finally
      state.close(); carry.close()
