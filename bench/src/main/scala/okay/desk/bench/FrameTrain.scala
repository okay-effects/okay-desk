package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.{Adaptable, Chat, Lora, Model, Models, Port, Session, Tokenizer}
import okay.desk.*
import okay.desk.tasks.Everyday
import okay.effects.json.Json
import scala.jdk.CollectionConverters.*

/** the frames' texts read back: each a table (the domains' by seed, or written out), a question, the lines */
object FrameTexts:
  def load(path: Path): Vector[FrameData.Item] =
    Files.readAllLines(path).asScala.toVector.filter(_.trim.nonEmpty).flatMap { l =>
      val j = Json.fromText(l)
      val lines = j.field("lines").collect { case Json.Arr(xs) => xs.collect { case Json.Str(s) => s } }
        .getOrElse(Vector.empty)
      val question = j.field("question").collect { case Json.Str(s) => s }.getOrElse("")
      val tables = j.field("tables") match
        case Some(Json.Arr(ts)) => ts.collect { case Json.Str(s) =>
          val name = s.takeWhile(_ != '\n')
          Table.parse(name + ".tsv", s.dropWhile(_ != '\n').drop(1) + "\n").toOption.map(t => name -> t.copy(name = name))
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
    val items = sys.env("FRAME_TEXTS").split(",").toVector.flatMap(f => FrameTexts.load(Path.of(f)))
    val held = items.filter(i => i.tables.keySet.exists(Domains.heldOut))
    require(held.isEmpty, s"${held.size} items over the measure's tables")
    val epochs = sys.env.get("FRAME_EPOCHS").map(_.toInt).getOrElse(1)
    println(s"$repo: ${items.size} frames; e.g.\n  " + items.take(2).map(i => s"${i.question}\n    → ${i.lines.mkString(" | ")}")
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
    val t0 = System.nanoTime()
    val questions = sys.env.get("FRAME_ONLY").fold(Everyday.questions)(n => Everyday.questions.take(n.toInt))
    for q <- questions; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
      val tables = Map(q.table -> Everyday.tables(q.table))
      val (system, user) = Frame.prompt(tables, text)
      val prompt = chat.prompt(Vector(FrameTrain.msg("system", system), FrameTrain.msg("user", user)), Nil,
        Map("enable_thinking" -> false))
      val port = FramePort(tok, tables, text, stop, newline)
      val s = Session(model, prompt, 160, chat.stops, port = port)
      try Iterator.continually(s.next()).takeWhile(_.isDefined).foreach(_ => ()) finally s.close()
      val lines = port.lines.toVector
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
    model.close()
