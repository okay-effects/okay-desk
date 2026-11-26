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
    val rnd = scala.util.Random(seed)
    val rows = domain match
      case "clues" => clues(rnd, n)
      case "pay" => okay.desk.books.PayData.rows(rnd, n)
      case _ => units(rnd, n)
    Files.write(out, rows.asJava)
    println(s"$out: ${rows.size} $domain texts")

  private def row(domain: String, lang: String, q: String, system: String, user: String, lines: Vector[String],
      world: Json = Json.Null): String =
    Json.obj("domain" -> Json.str(domain), "lang" -> Json.str(lang), "question" -> Json.str(q),
      "system" -> Json.str(system), "user" -> Json.str(user), "lines" -> Json.Arr(lines.map(Json.str)),
      "world" -> world).render

  private def units(rnd: scala.util.Random, n: Int): Vector[String] =
    val space = LineSpace.UnitsAndDates
    (1 to n).toVector.map { _ =>
      val p = Units.random(rnd, Units)
      val lang = if rnd.nextBoolean() then "ru" else "en"
      val q = Units.say(rnd, p, Units, lang)
      val (system, user) = space.prompt((), q)
      row("units", lang, q, system, user, space.lines(p)) }

  /** CLUES OF DRAWN GRIDS (the houses, the floors, the race): a true constraint of a drawn placement, said by the
   * setting's voice (`Grids.Voice`, either order where it has two), with its world: the positions and the lists */
  private def clues(rnd: scala.util.Random, n: Int): Vector[String] =
    import okay.desk.puzzle.{Constraint, Ref, Sort}
    import okay.desk.tasks.Grids
    Iterator.continually {
      val s = Vector(Grids.houses, Grids.floors, Grids.race)(rnd.nextInt(3))
      val k = 3 + rnd.nextInt(3)
      val people = rnd.shuffle(s.people).take(k)
      val features = rnd.shuffle(s.features).take(2 + rnd.nextInt(2))
        .map(f => f.copy(items = rnd.shuffle(f.items).take(k)))
      val sorts = Sort(s.sort, people.map(_.key)) +: features.map(f => Sort(f.sort, f.items.map(_.key)))
      val values = sorts.flatMap(_.values)
      Option.when(values.distinct.size == values.size) {
        val at: Map[Ref, Int] = sorts.flatMap(so => rnd.shuffle((1 to k).toVector).zip(so.values)
          .map((p, v) => Ref(so.name, v) -> p)).toMap
        val refs = at.keys.toVector
        def pick[A](xs: Vector[A]): Option[A] = Option.when(xs.nonEmpty)(xs(rnd.nextInt(xs.size)))
        val pairs = for a <- refs; b <- refs if a != b yield (a, b)
        val kinds = Vector[() => Option[Constraint]](
          () => pick(pairs.filter((a, b) => a.sort != b.sort && at(a) == at(b))).map(Constraint.Same(_, _)),
          () => pick(pairs.filter((a, b) => a.sort != b.sort && at(a) != at(b))).map(Constraint.Differ(_, _)),
          () => pick(refs).map(r => Constraint.At(r, at(r))),
          () => if s.notAt.isEmpty then None
            else pick(refs).flatMap(r => pick((1 to k).filter(_ != at(r)).toVector).map(Constraint.NotAt(r, _))),
          () => if k % 2 == 0 then None else pick(refs.filter(r => at(r) == (k + 1) / 2)).map(Constraint.Middle(_)),
          () => pick(pairs.filter((a, b) => at(a) < at(b) - 1)).map(Constraint.Left(_, _)),
          () => pick(pairs.filter((a, b) => at(a) == at(b) + 1)).map(Constraint.RightNext(_, _)),
          () => pick(pairs.filter((a, b) => (at(a) - at(b)).abs == 1)).map(Constraint.Adjacent(_, _)),
          () => if s.between.isEmpty || k < 3 then None
            else pick(pairs.filter((a, b) => (2 to 3).contains((at(a) - at(b)).abs)))
              .map((a, b) => Constraint.Apart(a, b, (at(a) - at(b)).abs)))
        kinds(rnd.nextInt(kinds.size))().map { c =>
          val (words, _) = Grids.Voice(s, people, features).say(c, mirror = rnd.nextBoolean())
          val lang = if rnd.nextBoolean() then "ru" else "en"
          val q = okay.desk.statement.Writing.plain(words.in(lang == "ru"))
          val w = ClueWorld(k, sorts)
          val (system, user) = Clues.prompt(w, q)
          row("clues", lang, q, system, user, Clues.lines(c), Json.obj("positions" -> Json.num(k),
            "sorts" -> Json.Arr(sorts.map(so => Json.obj("name" -> Json.str(so.name),
              "values" -> Json.Arr(so.values.map(Json.str))))))) } }
    }.flatten.flatten.take(n).toVector

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
    // RULES_SAYS=1: the rules' prompt lists what each says, not its title alone
    // RULES_AMONG=n: among the word search's first n only
    val rules = okay.desk.books.RuleLines(sys.env.get("RULES_SAYS").contains("1"),
      sys.env.get("RULES_AMONG").fold(0)(_.toInt))
    // okay-tax's held-out questions (data/heldout.tsv: rule id, language, question) as texts of the domain "rules"
    def quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val texts = if args(0).endsWith(".tsv") then
      Files.readAllLines(Path.of(args(0))).asScala.toVector.filterNot(l => l.startsWith("#") || l.isBlank)
        .map(_.split('\t')).map(a => Json.fromText(s"""{"domain": "rules", "lang": ${quoted(a(1))}, "question": """ +
          s"""${quoted(a(2))}, "lines": [${rules.lines(a(0)).map(quoted).mkString(", ")}]}"""))
    else Files.readAllLines(Path.of(args(0))).asScala.toVector.filter(_.trim.nonEmpty).map(Json.fromText)
    println(s"$repo${sys.env.get("FRAME_ADAPTER").fold("")(" with " + _)}: ${texts.size} texts")
    var failures = Vector.empty[String]
    val t0 = System.nanoTime()
    val records = texts.map { j =>
      val domain = j("domain").string
      val (q, lang, gold) = (j("question").string, j("lang").string, j("lines").list.map(_.string))
      val (got, want) = domain match
        case "units" =>
          val s = LineSpace.UnitsAndDates
          val r = Read.run(CoreReading.read[Unit, Read](s, (), q), reading)
          (s.answer((), r.lines), s.answer((), gold))
        case "clues" =>
          val w = ClueWorld(j("world")("positions").int, j("world")("sorts").list.map(s =>
            okay.desk.puzzle.Sort(s("name").string, s("values").list.map(_.string))))
          val r = Read.run(CoreReading.read[ClueWorld, Read](Clues, w, q), reading)
          (Clues.answer(w, r.lines), Clues.answer(w, gold))
        case "pay" =>
          val r = Read.run(CoreReading.read[Unit, Read](okay.desk.books.PayLines, (), q), reading)
          (okay.desk.books.PayLines.answer((), r.lines), okay.desk.books.PayLines.answer((), gold))
        case "rules" =>
          val r = Read.run(CoreReading.read[Unit, Read](rules, (), q), reading)
          (rules.answer((), r.lines), rules.answer((), gold))
        case other => sys.error(s"no space for $other")
      val o = if got.isRight && got == want then "right" else "wrong"
      // FRAME_FAILURES: the misread texts kept as they are (the truth known: the meaning was drawn) — self-play's mix
      if o == "wrong" then failures :+= j.render
      println(s"  [$domain $lang] $o $q → ${got.fold(e => s"($e)", identity)} (want ${want.fold(identity, identity)})")
      (lang, o)
    }
    println(f"the core reading, ${(System.nanoTime() - t0) / 1e9 / math.max(1, texts.size)}%.2f s a text")
    for lang <- records.map(_._1).distinct.sorted do
      val mine = records.filter(_._1 == lang)
      println(s"total $lang: right ${mine.count(_._2 == "right")}, wrong ${mine.count(_._2 == "wrong")} " +
        s"of ${mine.size}")
    for out <- sys.env.get("FRAME_FAILURES") do
      Files.write(Path.of(out), failures.asJava)
      println(s"failures: ${failures.size} written to $out")
    model.close()

/**
 * THE GATE OF SELF-PLAY (SPRINT K4): a new adapter against the current, measure by measure — the logs of the same
 * sets (`FrameBench`: right / asked / wrong; `DomainBench`: right / wrong). Accepted only when no set has more wrong
 * than before (the silent errors the guarantee is about) and all of them together have more right.
 * `sbt "bench/runMain okay.desk.bench.Gate <old logs, comma-separated> <new logs, the same order>"`: prints ACCEPT
 * or REJECT and the table
 */
object Gate:
  private val total = """total \w+: right (\d+)(?:, asked (\d+))?, wrong (\d+)""".r
  /** right and wrong summed over a log's languages */
  def tally(log: Path): (Int, Int) =
    Files.readAllLines(log).asScala.flatMap(l => total.findFirstMatchIn(l)).foldLeft((0, 0)) { (acc, m) =>
      (acc._1 + m.group(1).toInt, acc._2 + m.group(3).toInt) }
  def main(args: Array[String]): Unit =
    val (olds, news) = (args(0).split(",").toVector.map(Path.of(_)), args(1).split(",").toVector.map(Path.of(_)))
    require(olds.size == news.size, "the same sets, in the same order")
    val rows = olds.zip(news).map((o, n) => (n.getFileName.toString, tally(o), tally(n)))
    for (name, (r0, w0), (r1, w1)) <- rows do println(f"  $name%-40s right $r0%4d → $r1%4d   wrong $w0%4d → $w1%4d")
    val noMoreWrong = rows.forall((_, o, n) => n._2 <= o._2)
    val moreRight = rows.map(_._3._1).sum > rows.map(_._2._1).sum
    println(if noMoreWrong && moreRight then "ACCEPT" else
      s"REJECT (${if !noMoreWrong then "more wrong somewhere" else "no more right"})")

/**
 * TWO READERS AGREEING, FROM THEIR RECORDS (the page's way, frames.md §6.2): each question read by both (`FrameBench`
 * with FRAME_RECORDS); the same frame is an answer (its outcome), frames that part are a question to the person.
 * `sbt "bench/runMain okay.desk.bench.AgreeOffline <records a> <records b>"`
 */
object AgreeOffline:
  private def load(p: String): Map[(String, String), (String, String)] =
    Files.readAllLines(Path.of(p)).asScala.toVector.filter(_.trim.nonEmpty).map(Json.fromText).map { j =>
      (j("key").string, j("lang").string) -> (j("outcome").string, j("frame").string) }.toMap
  def main(args: Array[String]): Unit =
    val (a, b) = (load(args(0)), load(args(1)))
    val both = a.keySet intersect b.keySet
    val outcomes = both.toVector.map { k =>
      val ((oa, fa), (_, fb)) = (a(k), b(k))
      if fa == fb then oa else "asked" }
    def n(o: String) = outcomes.count(_ == o)
    println(s"${both.size} read by both: right ${n("right")}, asked ${n("asked")}, wrong ${n("wrong")} " +
      s"(agreed on ${both.count(k => a(k)._2 == b(k)._2)})")
