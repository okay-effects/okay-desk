package okay.desk.bench

import okay.desk.skill.{Probes, Read, Reading}
import okay.desk.statement.{Assemble, Con, Faults, Problem, Reader, Span, Statement}
import okay.desk.statement.Assemble.{Choices, Tag}
import okay.desk.tasks.{Drawn, Task, Tasks}

import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * THE FIRST READER OF STATEMENTS ON THE STUDENT (specs/formalization.md §7, steps 3–4; §10): the frozen 12-layer
 * student runs once over a task of the drawn corpus, and small heads read the decisions of `Assemble` off its
 * states — a tag at every token (a linear head over the token and its word's end), a sentence's frames by where
 * it is looked at (a few learned looks over its tokens, a head per place), the link of a name the data does not
 * settle (a pointer), the attribute a question asks for (its words against the words that list each attribute the
 * data leaves open). Trained on Train.
 *
 * Measured two ways on Validate, Test and the families never trained on (Unseen), in both languages: each KIND OF
 * DECISION on the gold path, and the WHOLE TASK by the real assembly — the statement the heads' decisions give is
 * checked, compiled and run, and its answer is judged against the task's. THE GATE is the reader's own
 * (`Reader.read`, §12.1): one result, no word or pair of words outside the names that training never had, every
 * least margin over its bar — the bars set on Validate. The heads, the words and the bars are the reader the page
 * loads (`Reader` in core: one program here and there); READ_SAVE writes it as a file.
 */
object StatementRead:
  import Reader.{Heads, Known, Seen, tagNames, top}
  private val (stop, depth) = ("stop", 3)

  // READ_ECHO=1: the task said twice and read at its second saying (`Reader.see`)
  private val echo = sys.env.get("READ_ECHO").flatMap(_.toIntOption).getOrElse(0)
  def see(reading: Reading, text: String, norm: Array[Float] => Array[Float]): Seen =
    val seen = Read.run(Reader.see[Read](text, norm, echo), reading)
    require(seen.tokens.nonEmpty, s"the prompt does not read back the task: $text")
    seen

  /** the questions an assembly asked, with the answers it was given */
  final class Asked(inner: Choices) extends Choices:
    var frames = Vector.empty[(Int, Int, Option[Con])]
    var links = Vector.empty[(Span, Vector[Span], Option[Span])]
    var picks = Vector.empty[(Int, Vector[Span], Int)]
    def frame(sentence: Int, k: Int): Option[Con] =
      val a = inner.frame(sentence, k)
      frames :+= (sentence, k, a)
      a
    def link(at: Span, earlier: Vector[Span]): Option[Span] =
      val a = inner.link(at, earlier)
      links :+= (at, earlier, a)
      a
    def pick(sentence: Int, attributes: Vector[Span]): Int =
      val a = inner.pick(sentence, attributes)
      picks :+= (sentence, attributes, a)
      a


  /** a decision on the gold path: its kind, whether the head had it right, the head's margin */
  final case class Decision(kind: String, right: Boolean, margin: Float, said: String = "", words: String = "")
  /** a task by the real assembly: how its answer came out, the least margin of each kind of decision in it, and
   * whether there is an answer to show — a statement that compiles and settles on one result */
  /** `settled`: the exact side decided how some sentences are read (`Reader.read`) */
  final case class Outcome(how: String, least: Map[String, Float], definite: Boolean, news: (Int, Int),
      settled: Boolean = false, answer: String = "")
  final case class Judged(language: String, family: String, decisions: Vector[Decision], outcome: Outcome)

  def judge(seen: Seen, gold: Statement, golden: Assemble.Decisions, trained: Reader.Trained)
      : (Vector[Decision], Outcome) =
    val heads = trained.heads
    val tagged = seen.reads.map(heads.tag)
    val asked = Asked(golden.choices)
    val _ = Assemble.assemble(gold.problem, seen.text, seen.tokens, golden.tags, asked)
    val decisions =
      tagged.zip(golden.tags).zipWithIndex.flatMap { case (((tag, margin), right), i) =>
        // a wrong tag with the words it fell on: the token in ‹›, two tokens before it
        def words = seen.text.substring(seen.tokens(math.max(0, i - 2)).from, seen.tokens(i).from) + "‹" +
          seen.tokens(i).in(seen.text).trim + "›"
        Vector(Decision("tag", tag == right, margin, s"$right as $tag", if tag == right then "" else words)) ++
          Option.when(right != Tag.Outside)(Decision("piece", tag == right, margin)) } ++
      asked.frames.map { (i, k, right) =>
        val (con, margin) = heads.frame(seen, i, k)
        def name(c: Option[Con]): String = c.fold(stop)(_.toString)
        Decision("frame", con == right, margin, s"$k: ${name(right)} as ${name(con)}",
          if con == right then "" else seen.sentences(i).in(seen.text).trim) } ++
      asked.links.filter(_._2.nonEmpty).map { (at, earlier, right) =>
        val (of, margin) = heads.link(seen, at, earlier)
        Decision("link", of == right, margin) } ++
      asked.picks.filter(_._2.size > 1).map { (i, attributes, right) =>
        val (p, margin) = heads.pick(seen, i, attributes)
        def said(k: Int): String = attributes(k).in(seen.text).take(28)
        def words = s"«${seen.sentences(i).in(seen.text)}» ${said(right)} as ${said(p)}"
        Decision("pick", p == right, margin, s"$right>$p of ${attributes.size}", if p == right then "" else words) }
    val right = Faults.result(gold, seen.text).getOrElse(Vector.empty)
    val reading = Reader.read(trained, seen)
    val (read, got) = (reading.statement, reading.result)
    val how = (read, got) match
      case (_, None) => "refused"
      case (Some(`gold`), _) => "exact"
      case (_, Some(`right`)) => "harmless"
      case (_, Some(r)) if Faults.undecided(r) && !Faults.undecided(right) => "signalled"
      case _ => "silent"
    // an answer there is to show: "none" and "several" are answers (§8, point 4)
    (decisions, Outcome(how, reading.least, got.nonEmpty, reading.news, reading.settled.nonEmpty,
      got.fold("")(_.mkString("|").replace('\t', ' '))))

  private def env(name: String, default: Int): Int = sys.env.get(name).map(_.toInt).getOrElse(default)

  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("LOGIC_DIR", sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main")))
    val layers = env("LOGIC_LAYERS", 12)
    println(s"backbone: $dir, layers=$layers")
    // READ_BACKBONE: the student (causal, the default), the student with no causal mask (open), or a bidirectional
    // encoder read from E5_DIR at READ_LAYER (e5)
    val encoder = Option.when(sys.env.get("READ_BACKBONE").contains("e5"))(EncoderReading(Path.of(sys.env.getOrElse(
      "E5_DIR", home + "/.cache/okay-desk/e5-small")), env("READ_LAYER", 12)))
    val reading: Reading = encoder.getOrElse(sys.env.getOrElse("READ_BACKBONE", "student") match
      case "student" => ComputeReading(dir, layers, ComputeReading.device.getOrElse(okay.llm.compute.OnMlx()))
      case "open" => ComputeReading(dir, layers, OpenMlx())
      case other => sys.error(s"READ_BACKBONE=$other: student, open or e5"))
    println(s"reader: ${sys.env.getOrElse("READ_BACKBONE", "student")}")
    val started = System.nanoTime()
    def minutes: String = f"${(System.nanoTime() - started) / 6e10}%.1f min"

    def stated(task: Task): Vector[(String, okay.desk.statement.Writing.Stated)] =
      task.stated.toVector.flatMap(r => Vector("en" -> r.en, "ru" -> r.ru))
    // READ_SCALES: train on the first few scales only — how the scales never met are read then (§10)
    val scales = env("READ_SCALES", Int.MaxValue)
    // READ_GRIDS: train on the first few settings of a grid only — the curve of the settings never met
    val grids = Drawn.grids.drop(env("READ_GRIDS", Int.MaxValue)).toSet
    val train = Drawn.items(Drawn.Split.Train, env("READ_TRAIN", 1200)).filter(i => !grids(i.family) &&
      (!i.family.startsWith("comparison") || i.family.drop("comparison".length).toInt < scales))
    println(s"train: ${train.size} tasks of ${train.map(_.family).distinct.size} families")

    val store = sys.env.get("READ_STORE").map(p => StateStore(Path.of(p)))
    // READ_LOAD: a reader saved before (READ_SAVE) measured again with nothing trained — its heads, words, bars;
    // the sets are read in minutes instead of half an hour
    val loaded = sys.env.get("READ_LOAD").map(p =>
      Reader.parse(Files.readAllBytes(Path.of(p))).fold(e => sys.error(s"$p: $e"), identity))
    loaded.foreach(l => require(l.echo == echo, s"the reader was trained with echo ${l.echo}; READ_ECHO is $echo"))
    // the states' mean and deviation, from the first tasks of Train
    val (mean, std) = loaded.fold {
      def raw(text: String): Seen = store.fold(see(reading, text, identity))(_.seen(text, see(reading, text, identity)))
      val sample = train.take(20).flatMap(i => stated(i.task)).flatMap((_, s) => raw(s.text).states)
      val d = sample.head.length
      val mean = Array.tabulate(d)(i => (sample.map(_(i).toDouble).sum / sample.size).toFloat)
      val std = Array.tabulate(d)(i => math.max(1e-3,
        math.sqrt(sample.map(x => math.pow(x(i) - mean(i), 2)).sum / sample.size)).toFloat)
      (mean, std) }(l => (l.mean, l.std))
    val d = mean.length
    def norm(x: Array[Float]): Array[Float] =
      Array.tabulate(d)(i => math.max(-4f, math.min(4f, (x(i) - mean(i)) / std(i))))
    println(s"d=$d; ${loaded.fold("trained here")(_ => "the reader loaded, nothing trained")}; $minutes")

    // READ_SEED: the heads' start and the order of their examples — for the spread of one configuration
    // READ_SEEDS (e.g. 1,2,3): the heads trained and measured for each seed over states the model gave ONCE — the
    // model is the same for every seed, so each text is read once (READ_SAVE and READ_OUT then carry {seed})
    val seeds = sys.env.get("READ_SEEDS").map(_.split(",").toVector.map(_.trim.toInt))
      .getOrElse(Vector(env("READ_SEED", 1)))
    // (kept only when there is more than one seed: some 10 GB of states for the whole corpus)
    val memo = scala.collection.mutable.HashMap.empty[String, Seen]
    // READ_STORE=<dir>: the core's states kept on disk (`StateStore`) — read once by the model, then from the disk by
    // every later run over the same core (name the directory by the core: base, adapter, echo)
    def seenOf(text: String): Seen = store match
      case Some(st) =>
        val raw = st.seen(text, see(reading, text, identity))
        Seen(raw.text, raw.tokens, raw.states.map(norm))
      case None =>
        if seeds.size == 1 then see(reading, text, norm) else memo.getOrElseUpdate(text, see(reading, text, norm))
    def perSeed(path: String, seed: Int): String = path.replace("{seed}", seed.toString)
    require(seeds.size == 1 || Vector("READ_SAVE", "READ_OUT").forall(k => sys.env.get(k).forall(_.contains("{seed}"))),
      "with several seeds, READ_SAVE and READ_OUT name each seed's file by {seed}")
    for seed <- seeds do
      val heads = loaded.fold(Heads(d, seed))(_.heads)
      println(s"seed: $seed")
      val known = loaded.fold(Known())(_.known)
      // (its bars are set on Validate below; the sets after it are read with them, as the page reads)
      var trained = Reader.Trained(mean, std, heads, known, Map.empty, echo)
      println(s"echo: $echo")
      val rnd = scala.util.Random(seed)
      val tagExamples = Vector.newBuilder[(Array[Float], Vector[String], String)]
      val frameExamples = Vector.fill(depth)(Vector.newBuilder[(Vector[Array[Float]], Int)])
      val linkExamples = Vector.newBuilder[(Array[Float], Vector[Array[Float]], Int)]
      val pickExamples = Vector.newBuilder[(Vector[Array[Float]], Vector[Vector[Array[Float]]], Int)]
      var (read, out) = (0, 0)
      // READ_TEACHER: the teacher's rewrites of training tasks (`StatementTeacher`), in four languages, taught too
      val taught = sys.env.get("READ_TEACHER").toVector.flatMap(p => StatementTeacher.load(Path.of(p)))
        .filter(_._1.split == "Train")
      println(s"the teacher's rewrites taught: ${taught.size} (" +
        taught.groupBy(_._1.language).toVector.sortBy(_._1).map((l, xs) => s"$l ${xs.size}").mkString(", ") + ")")
      def learn(s: okay.desk.statement.Writing.Stated): Unit =
        val seen = seenOf(s.text)
        read += 1
        if read % 100 == 0 then println(s"train: $read texts read; $minutes")
        Assemble.decisions(s.statement, s.text, seen.tokens) match
          case Left(_) => out += 1
          case Right(golden) =>
            known.learn(seen, golden.tags)
            for (x, tag) <- seen.reads.zip(golden.tags) if tag != Tag.Outside || rnd.nextFloat() < 0.35f do
              tagExamples += ((x, tagNames, tag.toString))
            val asked = Asked(golden.choices)
            val _ = Assemble.assemble(s.statement.problem, s.text, seen.tokens, golden.tags, asked)
            for (i, k, con) <- asked.frames if k < depth do
              frameExamples(k) += ((seen.each(i), heads.frameClass(con)))
            for (at, earlier, of) <- asked.links if earlier.nonEmpty do
              val (q, keys) = heads.linkKeys(seen, at, earlier)
              linkExamples += ((q, keys, of.fold(earlier.size)(earlier.indexOf)))
            for (i, attributes, p) <- asked.picks if attributes.size > 1 do
              val (q, keys) = heads.pickKeys(seen, i, attributes)
              pickExamples += ((q, keys, p))
      if loaded.isEmpty then
        for item <- train; (_, s) <- stated(item.task) do learn(s)
        for (_, s) <- taught do learn(s)
      val (tagX, linkX, pickX) = (tagExamples.result(), linkExamples.result(), pickExamples.result())
      println(s"train: $read texts, $out not in reach; examples: tags ${tagX.size}, frames " +
        s"${frameExamples.map(_.result().size).mkString("/")}, links ${linkX.size}, picks ${pickX.size}; $minutes")

      if loaded.isEmpty then
        heads.tags.train(tagX, epochs = env("READ_TAG_EPOCHS", 6), lr = 0.05f, seed = seed, batch = 32)
        println(s"tags trained; $minutes")
        for (head, k) <- heads.frames.zipWithIndex do
          head.train(frameExamples(k).result(), epochs = env("READ_FRAME_EPOCHS", 10))
        println(s"frames trained; $minutes")
        // a pointer in small batches: `Pointer.train` is one step over all it is given
        for _ <- 1 to env("READ_POINTER_EPOCHS", 12) do
          rnd.shuffle(linkX).grouped(64).foreach(heads.link.train(_, steps = 1, lr = 0.05f))
        heads.pick.train(pickX, epochs = env("READ_PICK_EPOCHS", 15), lr = 0.05f)
      val picked = pickX.count((q, keys, y) => top(heads.pick.scores(q, keys))._1 == y)
      def fit(p: Probes.Pointer, xs: Vector[(Array[Float], Vector[Array[Float]], Int)]): String =
        f"${100.0 * xs.count((q, keys, y) => p.predict(q, keys) == y) / math.max(1, xs.size)}%.1f%%"
      println(s"pointers trained, on Train: link ${fit(heads.link, linkX)}, pick $picked of ${pickX.size}; $minutes")

      def drawn(split: Drawn.Split, count: Int): Vector[Judged] =
        measure(split.toString, Drawn.items(split, count).map(i => i.family -> i.task))
      def measure(name: String, tasks: Vector[(String, Task)]): Vector[Judged] =
        val judged = for (family, task) <- tasks; (language, s) <- stated(task) yield
          val seen = seenOf(s.text)
          Assemble.decisions(s.statement, s.text, seen.tokens).toOption.map { golden =>
            val (decisions, outcome) = judge(seen, s.statement, golden, trained)
            Judged(language, family, decisions, outcome) }
        println(s"$name: ${judged.size} texts, ${judged.count(_.isEmpty)} not in reach; $minutes")
        judged.flatten

      val validate = drawn(Drawn.Split.Validate, env("READ_VALIDATE", 300))
      val kinds = Vector("tag", "piece", "frame", "link", "pick")
      // THE GATE'S BARS: by language and kind of decision, the least margin that 99 of 100 answers of Validate have
      def wrongOf(j: Judged): Boolean = j.outcome.how == "silent" || j.outcome.how == "signalled"
      val quantile = (for language <- Vector("en", "ru"); k <- Reader.kinds yield
        val of = validate.filter(j => j.language == language && j.outcome.definite)
          .flatMap(_.outcome.least.get(k)).sorted
        s"$language $k" -> of.lift((of.size - 1) / 100).getOrElse(0f)).toMap
      // READ_GATE=conformal: ONE threshold per language on a text's least margin in units of its kind's median,
      // the smallest whose shown answers of Validate keep (wrong + 1) / (shown + 1) under READ_ALPHA (0.01) — the
      // finite-sample bound of conformal risk control (a guarantee for texts like Validate's, none beyond them)
      val conformal = (for language <- Vector("en", "ru") yield
        val mine = validate.filter(j => j.language == language && j.outcome.definite)
        val scale = Reader.kinds.map(k => k -> {
          val ms = mine.flatMap(_.outcome.least.get(k)).sorted
          math.max(1e-3f, ms.lift(ms.size / 2).getOrElse(1f)) }).toMap
        def score(j: Judged): Float = Reader.kinds.flatMap(k => j.outcome.least.get(k).map(_ / scale(k))).minOption
          .getOrElse(Float.MaxValue)
        val alpha = sys.env.get("READ_ALPHA").flatMap(_.toDoubleOption).getOrElse(0.01)
        val t = (0f +: mine.map(score).sorted).find { t =>
          val shown = mine.filter(score(_) >= t)
          (shown.count(wrongOf) + 1.0) / (shown.size + 1.0) <= alpha }.getOrElse(Float.MaxValue)
        Reader.kinds.map(k => s"$language $k" -> (if t == Float.MaxValue then t else t * scale(k)))).flatten.toMap
      val bars = loaded.map(_.bars)
        .getOrElse(if sys.env.get("READ_GATE").contains("conformal") then conformal else quantile)
      println("the gate: " + sys.env.getOrElse("READ_GATE", "quantile"))
      trained = trained.copy(bars = bars)
      println("the gate's bars: " + bars.toVector.sorted.map((k, m) => f"$k $m%.2f").mkString(", "))
      // as the page decides: one answer, no word never met, and every margin over its bar — or the exact side
      // decided the sentences the heads were unsure of
      def closed(j: Judged): Boolean = j.outcome.definite && j.outcome.news == (0, 0) && (j.outcome.settled ||
        Reader.kinds.forall(k => j.outcome.least.get(k).forall(_ >= bars(s"${j.language} $k"))))
      for path <- sys.env.get("READ_SAVE").map(perSeed(_, seed)) do
        Files.write(Path.of(path), Reader.write(trained.copy(bars = bars)))
        println(s"the reader is in $path: ${Files.size(Path.of(path)) / 1024} KB, words ${known.words.size}, " +
          s"pairs ${known.pairs.size}")

      def pct(n: Int, of: Int): String = if of == 0 then "—" else f"${100.0 * n / of}%.1f%%"
      // `totals`: a set whose families and wordings its reader's author must not see (the sealed one) — sums only
      def report(name: String, js: Vector[Judged], totals: Boolean = false): Unit =
        def family(j: Judged): String = if totals then "-" else j.family
        // READ_OUT: every task's outcome and least margins, a line each — a gate is tried on them without reading
        // again
        for path <- sys.env.get("READ_OUT").map(perSeed(_, seed)) do
          Files.writeString(Path.of(path), js.map(j => (Vector(name, j.language, family(j), j.outcome.how) ++
            (j.outcome.least.toVector ++ Vector("word" -> -j.outcome.news._1.toFloat,
              "pair" -> -j.outcome.news._2.toFloat)).sorted.map((k, m) => s"$k=$m") :+ s"answer=${j.outcome.answer}")
            .mkString("\t") + "\n").mkString,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        for language <- js.map(_.language).distinct.sorted do
          val mine = js.filter(_.language == language)
          val ds = mine.flatMap(_.decisions)
          val perKind = kinds.map { k =>
            val of = ds.filter(_.kind == k)
            s"$k ${pct(of.count(_.right), of.size)} of ${of.size}" }
          val hows = Vector("exact", "harmless", "signalled", "refused", "silent")
          val (shown, open) = mine.partition(closed)
          println(s"$name $language: ${mine.size} texts")
          println("  decisions: " + perKind.mkString("; "))
          println("  no gate:   " +
            hows.map(h => s"$h ${pct(mine.count(_.outcome.how == h), mine.size)}").mkString(", "))
          println(s"  the gate:  asked ${pct(open.size, mine.size)}, shown ${shown.size}: " +
            hows.map(h => s"$h ${shown.count(_.outcome.how == h)}").mkString(", "))
          val decided = mine.filter(_.outcome.settled)
          println(s"  the solver decided ${decided.size}: " +
            hows.map(h => s"$h ${decided.count(_.outcome.how == h)}").mkString(", "))
        if !totals then details(js)
      def details(js: Vector[Judged]): Unit =
        val families = js.groupBy(_.family).toVector.sortBy(_._1).map { (f, of) =>
          s"$f ${pct(of.count(j => j.outcome.how == "exact" || j.outcome.how == "harmless"), of.size)}" }
        println("  right by family (no gate): " + families.mkString(", "))
        val wrong = js.flatMap(j => j.decisions.filter(x => !x.right && x.kind != "piece").map(x =>
          s"${j.family.takeWhile(!_.isDigit)} ${j.language} ${x.kind} ${x.said}"))
        println("  wrong decisions: " + wrong.groupBy(identity).view.mapValues(_.size).toVector.sortBy(-_._2).take(24)
          .map((w, n) => s"$n × $w").mkString("; "))
        def words(kind: String) = js.flatMap(j => j.decisions.filter(x => x.kind == kind && x.words.nonEmpty)
          .map(x => s"${j.language} ${x.said}: ${x.words}"))
        println("  picks: " + js.flatMap(_.decisions.filter(_.kind == "pick").map(_.said)).groupBy(identity).view
          .mapValues(_.size).toVector.sortBy(_._1).map((w, n) => s"$n × $w").mkString("; "))
        println("  wrong picks: " + words("pick").take(16).mkString("; "))
        println("  wrong frames: " + js.flatMap(j => j.decisions.filter(x => x.kind == "frame" && x.words.nonEmpty)
          .map(x => s"${j.family} ${x.said}: ${x.words}")).take(60).mkString("; "))
        println("  wrong tags: " + words("tag").groupBy(identity).view.mapValues(_.size).toVector.sortBy(-_._2).take(30)
          .map((w, n) => s"$n × $w").mkString("; "))

      report("Validate", validate)
      report("Test", drawn(Drawn.Split.Test, env("READ_TEST", 300)))
      report("Unseen", drawn(Drawn.Split.Unseen, env("READ_UNSEEN", 300)))
      report("Sealed", drawn(Drawn.Split.Sealed, env("READ_SEALED", 300)), totals = true)
      // READ_TEACHER_TEST: the teacher's rewrites of TEST tasks — the held-out measure of every language it wrote
      for p <- sys.env.get("READ_TEACHER_TEST") do
        val written = StatementTeacher.load(Path.of(p)).filter(_._1.split == "Test")
        val judged = written.flatMap { (k, s) =>
          val seen = seenOf(s.text)
          Assemble.decisions(s.statement, s.text, seen.tokens).toOption.map { golden =>
            val (decisions, outcome) = judge(seen, s.statement, golden, trained)
            Judged(k.language, k.family, decisions, outcome) } }
        println(s"Teacher test: ${written.size} texts, ${written.size - judged.size} not in reach; $minutes")
        report("Teacher test", judged)
      // THE STANDARD SET: the tasks of the collection this statement language states (§5), none of them in the corpus
      report("Collection", measure("Collection", Tasks.entries.map(e => e.kind.toString -> e.task)
        .filter((_, t) => t.stated.exists(_.en.statement.problem == Problem.Assign))))
      // THE SAVED FILE, READ BACK: the collection by the reader a page would load — what is shown, and of it wrong
      for path <- sys.env.get("READ_SAVE").map(perSeed(_, seed))
          saved <- Reader.parse(Files.readAllBytes(Path.of(path))).toOption do
        val read = for e <- Tasks.entries; (language, s) <- stated(e.task)
          if s.statement.problem == Problem.Assign yield
          val r = Reader.read(saved, seenOf(s.text))
          (language, s, r, Faults.result(s.statement, s.text).toOption)
        for language <- Vector("en", "ru") do
          val mine = read.filter(_._1 == language)
          val shown = mine.filter(_._3.shown)
          println(s"Collection by the saved file, $language: ${mine.size} texts, shown ${shown.size}, of them wrong " +
            s"${shown.count((_, _, r, right) => r.result != right)}; asked ${mine.size - shown.size}")
        for (language, s, r, _) <- read.filter(_._3.shown).take(4); st <- r.statement; result <- r.result do
          println(s"  e.g. ${result.mkString(", ")} — said as ${Reader.answer(s.text, st, result, language == "ru")}")
        // why the rest is asked: each doubt in its words, with how many texts it stops
        val doubts = read.filterNot(_._3.shown).map((language, s, r, _) =>
          okay.desk.statement.Said.words(s.text, r, language == "ru")._1.take(170))
        for (words, n) <- doubts.groupBy(identity).view.mapValues(_.size).toVector.sortBy(-_._2).take(40) do
          println(s"  asked $n ×: $words")
