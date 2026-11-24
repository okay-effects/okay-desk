package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.{Adaptable, Chat, Lora, Model, Models}
import okay.desk.*
import okay.desk.skill.Read
import okay.scala3.*
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
  type RP[X] = (Read + Person)[X]
  /** a question, its tables, the gold answer, the gold frame (for the person a dialogue plays) */
  final case class Asked(key: String, lang: String, text: String, tables: Map[String, Table], gold: Option[Table],
      frame: Option[Vector[String]] = None)

  def everyday: Vector[Asked] = Everyday.questions.zipWithIndex.flatMap { (q, i) =>
    val tables = Map(q.table -> Everyday.tables(q.table))
    val gold = q.gold.flatMap(Everyday.run(_).toOption)
    val frame = Everyday.frame(q)
    Vector(Asked(s"${q.table}-$i", "en", q.en, tables, gold, frame),
      Asked(s"${q.table}-$i", "ru", q.ru, tables, gold, frame))
  }

  /** the teacher's pairs as questions: those whose program is relational and runs */
  def taught(path: Path): Vector[Asked] = TeacherBench.load(path).zipWithIndex.flatMap { (p, i) =>
    val tables = Teacher.point(p.seed)._1
    Query.parse(p.program).toOption.collect { case r: Query.Relational => r }
      .flatMap(r => Exact.run(tables, r).toOption)
      .map(gold => Asked(s"${p.seed}-$i", p.lang, p.question, tables, Some(gold),
        Query.parse(p.program).toOption.flatMap(Frame.of(_, tables))))
  }

  def outcome(gold: Option[Table], got: Option[Table]): String = (gold, got) match
    case (_, None) => if gold.isEmpty then "right" else "asked"
    case (None, Some(_)) => "wrong"
    case (Some(want), Some(t)) => if Sample.same(t, want) then "right" else "wrong"

  def main(args: Array[String]): Unit =
    val repo = sys.env.getOrElse("FRAME_MODEL", "mlx-community/Qwen3-0.6B-4bit")
    // a repository in the cache, or a model directory (a merged reader: FrameMerge's)
    val dir = Models.snapshot(repo).orElse(Some(Path.of(repo)).filter(Files.isDirectory(_)))
      .getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true)
    sys.env.get("FRAME_ADAPTER").foreach(f => Lora.attach(model.asInstanceOf[Adaptable], f))
    val set = sys.env.get("FRAME_SET").fold(everyday)(f => taught(Path.of(f)))
    val asked = sys.env.get("FRAME_ONLY").fold(set)(n => set.take(n.toInt))
    println(s"$repo${sys.env.get("FRAME_ADAPTER").fold("")(" with " + _)}: ${asked.size} questions")
    val reading = MlxReading(chat, model, 1)
    val t0 = System.nanoTime()
    var asks = 0
    var reverseLog = Vector.empty[(String, Boolean, String)]
    var checksLog = Vector.empty[String]
    val records = asked.map { a =>
      // FRAME_DIALOGUE=<bar>: where in doubt the person is asked; here the person is played by the gold frame — the
      // option offered that is one of its lines, else none of them
      // the frame reader is a program over `Read` (here mlx-scala) and `Person` (here the gold frame, or none asked)
      val doubt = sys.env.get("FRAME_DIALOGUE").map(b => FrameReading.Doubt(b.toDouble))
      val program = FrameReading.read[RP](a.tables, a.text, doubt,
        link = sys.env.get("FRAME_LINK").fold(0.0)(_.toDouble),
        guess = sys.env.get("FRAME_GUESS").fold(0.0)(_.toDouble), prune = sys.env.contains("FRAME_PRUNE"))
      FrameReading.pruneNumbers = !sys.env.contains("FRAME_KEEP_NUMBERS")
      FrameReading.canonical = sys.env.contains("FRAME_CANONICAL")
      FrameReading.pruneSingle = !sys.env.contains("FRAME_KEEP_SINGLE")
      FrameReading.pruneText = sys.env.contains("FRAME_PRUNE_TEXT")
      val person = if doubt.isEmpty then Person.silent[Read, FrameReading.Reading]
        else Person.byFrame[Read, FrameReading.Reading](a.frame)
      val read = Read.run(handle(program, person), reading)
      asks += read.asked
      val got = if read.unresolved then None
        else Frame.query(read.lines, a.tables).flatMap(Exact.run(a.tables, _)).toOption
      val forward = outcome(a.gold, got)
      // FRAME_CHECKS=1: the result checked against the question (FrameChecks) — a misfit is a question to the person
      val checked = sys.env.contains("FRAME_CHECKS") && !read.unresolved &&
        FrameChecks.suspicious(a.text, read.lines, got).nonEmpty
      if checked then checksLog :+= forward
      // FRAME_REVERSE=1 (T7): the reverse reading's second opinion — where it prefers another candidate, a person is
      // asked (the outcome "asked"); FRAME_REVERSE=switch: the reverse's frame taken instead
      val o = if checked then "asked"
        else sys.env.get("FRAME_REVERSE").filter(_ => !read.unresolved).fold(forward) { mode =>
        def readOn(start: Vector[String]) = handle(FrameReading.read[RP](a.tables, a.text, None, start = start,
          link = sys.env.get("FRAME_LINK").fold(0.0)(_.toDouble),
          guess = sys.env.get("FRAME_GUESS").fold(0.0)(_.toDouble), prune = sys.env.contains("FRAME_PRUNE")),
          Person.silent[Read, FrameReading.Reading])
        val v = Read.run(FrameReverse.candidates[Read](read, readOn).flatMap(fs =>
          FrameReverse.judge[Read](a.tables, a.text, a.lang, fs)), reading)
        val switched = outcome(a.gold, Frame.query(v.best, a.tables).flatMap(Exact.run(a.tables, _)).toOption)
        reverseLog :+= (forward, v.agrees, switched)
        if v.agrees then forward else if mode == "switch" then switched else "asked" }
      val margin = read.margin
      val said = read.lines.dropRight(1).drop(1).mkString(" | ")
      println(f"  [${a.key}%-12s ${a.lang}] $o%-6s $margin%6.2f ${a.text} → $said")
      (a, o, margin, read.lines)
    }
    println(f"the frame reader, ${(System.nanoTime() - t0) / 1e9 / math.max(1, asked.size)}%.1f s a question")
    for b <- sys.env.get("FRAME_DIALOGUE") do
      val each = asks.toDouble / asked.size
      println(f"the dialogue at the bar $b: $asks questions asked of the person, $each%.2f a question")
    if checksLog.nonEmpty then
      println(s"the checks: ${checksLog.size} asked; there the reading was right ${checksLog.count(_ == "right")}, " +
        s"wrong ${checksLog.count(_ == "wrong")}")
    if reverseLog.nonEmpty then
      val dis = reverseLog.filterNot(_._2)
      println(s"the reverse reading: disagreed on ${dis.size} of ${reverseLog.size}; there the forward was right " +
        s"${dis.count(_._1 == "right")}, wrong ${dis.count(_._1 == "wrong")}; the reverse's frame right " +
        s"${dis.count(_._3 == "right")}; agreed and wrong ${reverseLog.count(r => r._2 && r._1 == "wrong")}")
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

/**
 * THE CHECKS ON A MEASURE ALREADY RUN (no model): a `FrameBench` log's frames read back, run, and checked against
 * their questions (`FrameChecks`) — what the checks would have turned into questions to the person, right and wrong.
 * `sbt "bench/runMain okay.desk.bench.ChecksOffline <bench log> [<teacher pairs>]"` (none: `Everyday`)
 */
object ChecksOffline:
  def main(args: Array[String]): Unit =
    val set = args.lift(1).fold(FrameBench.everyday)(f => FrameBench.taught(Path.of(f)))
    val byKey = set.map(a => (a.key, a.lang) -> a).toMap
    val line = """\[(\S+)\s+(\w\w)\] (right|wrong|asked)\s+\S+ (.*?) → (.*)$""".r
    val logged = Files.readAllLines(Path.of(args(0))).asScala.toVector.flatMap(l => line.findFirstMatchIn(l))
    val rows = logged.flatMap { m =>
      byKey.get((m.group(1), m.group(2))).map { a =>
        val said = m.group(5).split(" \\| ").toVector.filter(_.nonEmpty)
        val runs = a.tables.keys.toVector.flatMap { t =>
          val lines = (s"table: $t" +: said) :+ Frame.end
          Frame.query(lines, a.tables).flatMap(Exact.run(a.tables, _)).toOption.map(lines -> _) }
        val (lines, result) = runs.headOption.fold(((s"table: ?" +: said) :+ Frame.end, Option.empty[Table]))(
          (l, r) => (l, Some(r)))
        (m.group(3), FrameChecks.suspicious(a.text, lines, result), a.lang) } }
    val flagged = rows.filter(_._2.nonEmpty)
    println(s"${rows.size} read; the checks flag ${flagged.size}: right ${flagged.count(_._1 == "right")}, " +
      s"wrong ${flagged.count(_._1 == "wrong")}, asked ${flagged.count(_._1 == "asked")}")
    for (reason, rs) <- flagged.groupBy(_._2.get).toVector.sortBy(-_._2.size) do
      println(s"  $reason: ${rs.size} (right ${rs.count(_._1 == "right")}, wrong ${rs.count(_._1 == "wrong")})")
    val before = rows.groupBy(_._1).view.mapValues(_.size).toMap
    val after = rows.map(r => if r._2.nonEmpty && r._1 != "asked" then "asked" else r._1).groupBy(identity)
      .view.mapValues(_.size).toMap
    println(s"before: right ${before.getOrElse("right", 0)}, asked ${before.getOrElse("asked", 0)}, " +
      s"wrong ${before.getOrElse("wrong", 0)}; with the checks: right ${after.getOrElse("right", 0)}, " +
      s"asked ${after.getOrElse("asked", 0)}, wrong ${after.getOrElse("wrong", 0)}")
