package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.{Adaptable, Chat, Lora, Model, Models}
import okay.desk.*
import okay.desk.tasks.Everyday
import okay.effects.json.Json
import scala.jdk.CollectionConverters.*

/**
 * THE FRAME READER MEASURED (specs/frames.md §4, specs/guarantees.md §6.1): a set of questions — `Everyday`, or the
 * teacher's natural pairs (`FRAME_SET`: a `Teacher pairs` file; their tables the domains', by seed) — each read
 * into a frame by `FrameRead`, run, scored against the gold: right, asked, wrong. `FRAME_RECORDS`: each question's
 * record written (its key, language, outcome, smallest margin, frame) for `FrameCalibrate`.
 */
object FrameBench:
  final case class Asked(key: String, lang: String, text: String, tables: Map[String, Table], gold: Option[Table])

  def everyday: Vector[Asked] = Everyday.questions.zipWithIndex.flatMap { (q, i) =>
    val tables = Map(q.table -> Everyday.tables(q.table))
    val gold = q.gold.flatMap(Everyday.run(_).toOption)
    Vector(Asked(s"${q.table}-$i", "en", q.en, tables, gold), Asked(s"${q.table}-$i", "ru", q.ru, tables, gold))
  }

  /** the teacher's pairs as questions: those whose program is relational and runs */
  def taught(path: Path): Vector[Asked] = TeacherBench.load(path).zipWithIndex.flatMap { (p, i) =>
    val tables = Teacher.point(p.seed)._1
    Query.parse(p.program).toOption.collect { case r: Query.Relational => r }
      .flatMap(r => Exact.run(tables, r).toOption)
      .map(gold => Asked(s"${p.seed}-$i", p.lang, p.question, tables, Some(gold)))
  }

  def outcome(gold: Option[Table], got: Option[Table]): String = (gold, got) match
    case (_, None) => if gold.isEmpty then "right" else "asked"
    case (None, Some(_)) => "wrong"
    case (Some(want), Some(t)) => if Sample.same(t, want) then "right" else "wrong"

  def main(args: Array[String]): Unit =
    val repo = sys.env.getOrElse("FRAME_MODEL", "mlx-community/Qwen3-0.6B-4bit")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true); val tok = chat.tokenizer
    sys.env.get("FRAME_ADAPTER").foreach(f => Lora.attach(model.asInstanceOf[Adaptable], f))
    val set = sys.env.get("FRAME_SET").fold(everyday)(f => taught(Path.of(f)))
    val asked = sys.env.get("FRAME_ONLY").fold(set)(n => set.take(n.toInt))
    println(s"$repo${sys.env.get("FRAME_ADAPTER").fold("")(" with " + _)}: ${asked.size} questions")
    val stop = tok.id("<|im_end|>"); val newline = tok.encode("\n").head
    val t0 = System.nanoTime()
    val records = asked.map { a =>
      val (system, user) = Frame.prompt(a.tables, a.text)
      val prompt = chat.prompt(Vector(FrameTrain.msg("system", system), FrameTrain.msg("user", user)), Nil,
        Map("enable_thinking" -> false))
      val read = FrameRead(model, prompt, tok, a.tables, a.text, newline, stop)
      val got = Frame.query(read.lines, a.tables).flatMap(Exact.run(a.tables, _)).toOption
      val o = outcome(a.gold, got)
      val margin = read.margins.minOption.getOrElse(0.0)
      val said = read.lines.dropRight(1).drop(1).mkString(" | ")
      println(f"  [${a.key}%-12s ${a.lang}] $o%-6s $margin%6.2f ${a.text} → $said")
      (a, o, margin, read.lines)
    }
    println(f"the frame reader, ${(System.nanoTime() - t0) / 1e9 / math.max(1, asked.size)}%.1f s a question")
    for lang <- records.map(_._1.lang).distinct.sorted do
      val mine = records.filter(_._1.lang == lang)
      println(s"total $lang: right ${mine.count(_._2 == "right")}, asked ${mine.count(_._2 == "asked")}, " +
        s"wrong ${mine.count(_._2 == "wrong")} of ${mine.size}")
    for out <- sys.env.get("FRAME_RECORDS") do
      Files.write(Path.of(out), records.map { (a, o, m, lines) =>
        Json.Obj(Vector("key" -> Json.Str(a.key), "lang" -> Json.Str(a.lang), "outcome" -> Json.Str(o),
          "margin" -> Json.num(if m.isInfinite then 99.0 else m), "inside" -> Json.Bool(a.gold.nonEmpty),
          "frame" -> Json.Str(lines.mkString(" | ")))).render }.asJava)
      println(s"records: $out")
    model.close()
