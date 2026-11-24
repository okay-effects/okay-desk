package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.{Adaptable, Chat, Lora, Model, Models}
import okay.desk.*
import okay.desk.skill.Read
import okay.effects.json.Json
import okay.inlay.domain.Units
import scala.jdk.CollectionConverters.*

/**
 * THE CORE MODEL'S DOMAINS AS TEXTS (SPRINT K2): drawn meanings of a domain, each said as a question (the domain's
 * phrases, English and Russian) with its lines — jsonl lines {"domain", "lang", "question", "system", "user",
 * "lines"} that `FrameTrain` takes as `CORE_TEXTS` and `DomainBench` measures.
 * `sbt "bench/runMain okay.desk.bench.DomainData units <n> <seed> <out.jsonl>"`
 */
object DomainData:
  def main(args: Array[String]): Unit =
    val (domain, n, seed, out) = (args(0), args(1).toInt, args(2).toLong, Path.of(args(3)))
    require(domain == "units", s"no data for $domain yet")
    val rnd = scala.util.Random(seed)
    val space = LineSpace.UnitsAndDates
    val rows = (1 to n).map { _ =>
      val p = Units.random(rnd, Units)
      val lang = if rnd.nextBoolean() then "ru" else "en"
      val q = Units.say(rnd, p, Units, lang)
      val (system, user) = space.prompt((), q)
      Json.obj("domain" -> Json.str(domain), "lang" -> Json.str(lang), "question" -> Json.str(q),
        "system" -> Json.str(system), "user" -> Json.str(user),
        "lines" -> Json.Arr(space.lines(p).map(Json.str))).render }
    Files.write(out, rows.asJava)
    println(s"$out: ${rows.size} $domain texts")

/**
 * THE CORE MODEL MEASURED ON A DOMAIN (SPRINT K3): each text read by `CoreReading` (the model's choices among the
 * domain's options), run exactly, and compared with the run of its own lines: right or wrong, by language — a domain
 * the adapter never saw is the test of the core. `FRAME_MODEL`, `FRAME_ADAPTER` as `FrameBench`.
 * `sbt "bench/runMain okay.desk.bench.DomainBench <texts.jsonl>"`
 */
object DomainBench:
  def main(args: Array[String]): Unit =
    val repo = sys.env.getOrElse("FRAME_MODEL", "mlx-community/Qwen3-0.6B-4bit")
    val dir = Models.snapshot(repo).orElse(Some(Path.of(repo)).filter(Files.isDirectory(_)))
      .getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true)
    sys.env.get("FRAME_ADAPTER").foreach(f => Lora.attach(model.asInstanceOf[Adaptable], f))
    val reading = MlxReading(chat, model, 1)
    val texts = Files.readAllLines(Path.of(args(0))).asScala.toVector.filter(_.trim.nonEmpty).map(Json.fromText)
    println(s"$repo${sys.env.get("FRAME_ADAPTER").fold("")(" with " + _)}: ${texts.size} texts")
    val t0 = System.nanoTime()
    val records = texts.map { j =>
      val domain = j("domain").string
      val (q, lang, gold) = (j("question").string, j("lang").string, j("lines").list.map(_.string))
      val (got, want) = domain match
        case "units" =>
          val s = LineSpace.UnitsAndDates
          val r = Read.run(CoreReading.read[Unit, Read](s, (), q), reading)
          (s.answer((), r.lines), s.answer((), gold))
        case other => sys.error(s"no space for $other")
      val o = if got.isRight && got == want then "right" else "wrong"
      println(s"  [$domain $lang] $o $q → ${got.fold(e => s"($e)", identity)} (want ${want.fold(identity, identity)})")
      (lang, o)
    }
    println(f"the core reading, ${(System.nanoTime() - t0) / 1e9 / math.max(1, texts.size)}%.2f s a text")
    for lang <- records.map(_._1).distinct.sorted do
      val mine = records.filter(_._1 == lang)
      println(s"total $lang: right ${mine.count(_._2 == "right")}, wrong ${mine.count(_._2 == "wrong")} of ${mine.size}")
    model.close()
