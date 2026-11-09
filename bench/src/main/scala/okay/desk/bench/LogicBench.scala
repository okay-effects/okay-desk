package okay.desk.bench

import okay.desk.calc.Term
import okay.desk.exec.Wire
import okay.desk.logic.{Compile, Corpus, LogicGrammar}
import okay.desk.skill.{Extract, LogicRead, Probes, Read}
import okay.desk.skill.Extract.{Evidence, Reading}
import okay.desk.skill.LogicRead.{Features, Layout, Masked, Step}

import java.nio.file.{Files, Path}

/**
 * THE READER TRAINED ON THE STUDENT (specs/logic.md §5.4): the 12-layer student's state at the last token of the
 * chat template's assistant header, then `Program: ` and the gold constructors so far, trains one masked softmax
 * over the grammar's options. A copied constant is then read by a pointer: the query is the state at the constant's
 * hole, the keys are the states of the task's words that say an option, and the gold occurrence (or `Known`) is the
 * target. The margins are calibrated on Validate for no closed-wrong reading, the Test split is measured twice — on
 * the gold path and by the real decoder — and the weights are saved as `Wire`. A head that closes a wrong answer is
 * not saved: an open hole is a question, not a guess.
 */
object LogicBench:
  final case class Example(item: Corpus.Item, grammar: LogicGrammar, gold: Reading,
      steps: Vector[Step], features: Vector[Features], layout: Layout)

  def term(i: Corpus.Item): Term =
    val p = okay.datalog.Parse.program(i.rules).fold(e => sys.error(e), identity)
    val a = okay.datalog.Parse.atom(i.goal).fold(e => sys.error(e), identity)
    Compile.ofProgram(p, a, i.ontology).fold(e => sys.error(e), identity)

  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("LOGIC_DIR", sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main")))
    // the same head/pointer experiment on any intact backbone: the student's cut is layer 12, the native control 27
    val layers = sys.env.get("LOGIC_LAYERS").map(_.toInt).getOrElse(12)
    println(s"backbone: $dir, layers=$layers")
    val reading = ComputeReading(dir, layers, ComputeReading.device.getOrElse(okay.llm.compute.OnMlx()))
    val trainCount = sys.env.get("LOGIC_TRAIN").map(_.toInt).getOrElse(16)
    val epochs = sys.env.get("LOGIC_EPOCHS").map(_.toInt).getOrElse(40)
    val lr = sys.env.get("LOGIC_LR").map(_.toFloat).getOrElse(0.05f)

    var read = 0
    def example(i: Corpus.Item): Example =
      val g = LogicGrammar(i.ontology)
      val gold = Extract.gold(g, i.text, term(i)).fold(e => sys.error(s"${i.text}: $e"), identity)
      val steps = LogicRead.steps(g, gold)
      val layout = Read.run(LogicRead.layout[Read](i.text), reading)
      val features = steps.map { s =>
        read += 1
        if read % 100 == 0 then println(s"reading $read…")
        Read.run(LogicRead.features[Read](i.text, s.prefix, layout, LogicRead.shown(g, s.ty)), reading)
      }
      Example(i, g, gold, steps, features, layout)

    def examples(split: Corpus.Split, count: Int): Vector[Example] =
      Corpus.items(split, count, seed = 7).map(example)

    val train = examples(Corpus.Split.Train, trainCount)
    val validate = examples(Corpus.Split.Validate, 8)
    val test = examples(Corpus.Split.Test, 8)
    println(s"items: train=${train.size} validate=${validate.size} test=${test.size}; steps: " +
      s"${train.map(_.steps.size).sum}/${validate.map(_.steps.size).sum}/${test.map(_.steps.size).sum}; " +
      s"words: ${train.map(_.layout.at.size).sum}/${validate.map(_.layout.at.size).sum}/" +
      s"${test.map(_.layout.at.size).sum}")

    val d = train.head.features.head.last.length
    val all = train.flatMap(_.features.map(_.last))
    val mean = Vector.tabulate(d)(i => (all.map(_(i).toDouble).sum / all.size).toFloat)
    val std = Vector.tabulate(d)(i => math.max(1e-3,
      math.sqrt(all.map(x => math.pow(x(i) - mean(i), 2)).sum / all.size)).toFloat)
    def norm(x: Array[Float]): Array[Float] = LogicRead.normalize(x, mean, std)

    val letters = train.flatMap(e => e.steps.flatMap(_.options)).distinct.sorted
    println(s"letters: ${letters.size}, d=$d")
    val head = Masked(letters, d)
    val examplesToTrain = train.flatMap(e => e.steps.zip(e.features).map { (s, f) =>
      (norm(f.last), s.options, s.target)
    })
    head.train(examplesToTrain, epochs = epochs, lr = lr, seed = 1, batch = 32)
    println(s"trained: ${epochs} epochs, lr=$lr, ${examplesToTrain.size} examples")

    def fromAt(gold: Reading, i: Int): Int =
      gold.choices.take(i).flatMap(_.evidence).collect { case Evidence.Text(s) => s.end }.lastOption.getOrElse(0)

    /** the pointer's examples: the hole's state as the query, the task's words that say an option as the keys, and
     * the gold occurrence's key (or `none` for `Known`) as the target */
    def copyExamples(ex: Vector[Example]): Vector[(Array[Float], Vector[Array[Float]], Int)] =
      ex.flatMap { e =>
        e.steps.indices.flatMap { i =>
          val s = e.steps(i)
          if !Extract.copies(s.ty, s.target) then None
          else
            val f = e.features(i)
            val cs = LogicRead.copiesAt(e.grammar, e.item.text, s.ty, fromAt(e.gold, i), f.words, f.states)
            val target = e.gold.choices(i).evidence match
              case Some(Evidence.Text(_)) => cs.indexWhere(_._1 == s.target)
              case _                      => cs.size
            if target < 0 then None
            else
              val query = LogicRead.scale(norm(e.features(i).last), d)
              Some((query, cs.map(c => LogicRead.scale(norm(c._3), d)), target))
        }
      }

    val trainCopies = copyExamples(train)
    val validateCopies = copyExamples(validate)
    // the pointer's own margin of safety: a rate that diverges reads every constant as the first key
    val tuned = Vector((150, 0.05f), (150, 0.02f), (300, 0.01f), (300, 0.005f)).map { (steps, rate) =>
      val p = Probes.Pointer(d, 64)
      if trainCopies.nonEmpty then p.train(trainCopies, steps = steps, lr = rate)
      val nan = p.wq.flatten.exists(v => !v.isFinite) || p.wk.flatten.exists(v => !v.isFinite) ||
        p.none.exists(v => !v.isFinite)
      val ok = trainCopies.count((q, keys, y) => p.predict(q, keys) == y)
      val okV = validateCopies.count((q, keys, y) => p.predict(q, keys) == y)
      println(s"pointer steps=$steps lr=$rate: train $ok/${trainCopies.size} " +
        s"validate $okV/${validateCopies.size} nan=$nan")
      (okV, (steps, rate), p)
    }
    val (_, (pointerSteps, pointerLr), pointer) = tuned.maxBy(_._1)
    println(s"pointer: ${trainCopies.size} examples, steps=$pointerSteps lr=$pointerLr")
    def pointerAccuracy(ex: Vector[Example]): (Int, Int) =
      val cases = copyExamples(ex)
      (cases.count((q, keys, target) => pointer.predict(q, keys) == target), cases.size)
    for (name, ex) <- Vector("train" -> train, "validate" -> validate) do
      val (ok, n) = pointerAccuracy(ex)
      println(s"$name pointer: $ok/$n")

    def constants(i: Corpus.Item): Set[String] = i.ontology.constants.values.flatten.toSet
    for (name, ex) <- Vector("train" -> train, "validate" -> validate) do
      var ok = 0; var n = 0; var copyOk = 0; var copyN = 0; var conOk = 0; var conN = 0
      for e <- ex; i <- e.steps.indices do
        val s = e.steps(i)
        val good = head.scores(norm(e.features(i).last), s.options).headOption.exists(_._1 == s.target)
        n += 1; if good then ok += 1
        if constants(e.item).contains(s.target) then
          copyN += 1; if good then copyOk += 1
        else
          conN += 1; if good then conOk += 1
      println(s"$name steps: $ok/$n; copied constants: $copyOk/$copyN; constructors: $conOk/$conN")

    /** the real decoder's decisions along the gold path: exact, abstained, closed wrong */
    final case class Score(exact: Int, abstained: Int, wrong: Int)
    def goldPath(ex: Vector[Example], margin: Float, pointerMargin: Float): Score =
      val reader = LogicRead.Reader(LogicRead.parameters(head, pointer, layers, mean, std, margin, pointerMargin))
      var exact = 0; var abstained = 0; var wrong = 0
      for e <- ex do
        var i = 0
        var from = 0
        var stop = false
        var bad = false
        while !stop && i < e.steps.size do
          val s = e.steps(i)
          reader.decide(e.grammar, e.item.text, s.ty, e.features(i), from) match
            case None => abstained += 1; stop = true
            case Some(c) if c.con != s.target => wrong += 1; bad = true; stop = true
            case Some(c) =>
              from = c.evidence.collect { case Evidence.Text(sp) => sp.end }.getOrElse(from)
              i += 1
        if !stop && !bad then exact += 1
      Score(exact, abstained, wrong)

    /** the copied constants read along the gold path, for the pointer's own number */
    def copyPath(ex: Vector[Example], reader: LogicRead.Reader): (Int, Int) =
      var ok = 0; var n = 0
      for e <- ex; i <- e.steps.indices if Extract.copies(e.steps(i).ty, e.steps(i).target) do
        n += 1
        val c = reader.decide(e.grammar, e.item.text, e.steps(i).ty, e.features(i), fromAt(e.gold, i))
        if c.exists(_.con == e.steps(i).target) then ok += 1
      (ok, n)

    /** the head's own confidence at the gold path: right and wrong steps per stage, with the gap it saw */
    def gaps(ex: Vector[Example]): Unit =
      val rows = for
        e <- ex
        i <- e.steps.indices
        s = e.steps(i)
        if !Extract.copies(s.ty, s.target)
        x = norm(e.features(i).last)
        ranked = head.scores(x, s.options)
        gap = if ranked.size < 2 then Float.PositiveInfinity else ranked.head._2 - ranked(1)._2
        right = ranked.headOption.exists(_._1 == s.target)
      yield (Extract.stageOfType(s.ty).toString, right, gap, s.target, ranked.headOption.map(_._1).getOrElse("-"))
      for stage <- rows.map(_._1).distinct.sorted do
        val rs = rows.filter(_._1 == stage)
        val ok = rs.filter(_._2)
        val bad = rs.filterNot(_._2)
        def q(xs: Vector[Float], p: Double): String =
          if xs.isEmpty then "-" else "%.2f".format(xs.sorted.apply(((xs.size - 1) * p).round.toInt))
        println(s"gaps $stage: right ${ok.size} (${q(ok.map(_._3), 0.1)}/${q(ok.map(_._3), 0.5)}), " +
          s"wrong ${bad.size} (${q(bad.map(_._3), 0.5)}/${q(bad.map(_._3), 0.9)})")
        for (_, _, gap, target, got) <- bad.sortBy(-_._3).take(4) do
          println(s"  wrong: want $target got $got gap ${"%.2f".format(gap)}")
    gaps(validate)

    val grid = Vector(0f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 6f, 8f, 12f, 24f, 48f)
    val pointerGrid = Vector(0f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 6f)
    println(s"train: ${goldPath(train, 0f, 0f)} at margins 0")
    val settings = for m <- grid; pm <- pointerGrid yield ((m, pm), goldPath(validate, m, pm))
    val safe = settings.filter(_._2.wrong == 0).sortBy { case ((m, pm), s) => (-s.exact, m, pm) }
    println(s"validation: ${settings.size} margin pairs, ${safe.size} without a wrong-closed reading")
    for ((m, pm), s) <- safe.take(10) do
      println(s"  margin=$m pointer=$pm: exact=${s.exact}/${validate.size} abstained=${s.abstained} " +
        s"wrong=${s.wrong}")
    // a safe margin that abstains every task reads nothing: the gate needs a reading that is exact somewhere
    val usable = safe.filter(_._2.exact > 0)
    if usable.isEmpty then
      println(s"no margin both safe and exact on validation (${safe.size} safe, all abstaining): do not ship")
      return
    val ((margin, pointerMargin), validation) = usable.head
    println(s"chosen: margin=$margin pointer=$pointerMargin validation: exact=${validation.exact}/${validate.size} " +
      s"abstained=${validation.abstained} wrong=${validation.wrong}")

    val parameters = LogicRead.parameters(head, pointer, layers, mean, std, margin, pointerMargin)
    val reader = LogicRead.Reader(parameters)
    val goldTest = goldPath(test, margin, pointerMargin)
    val decoded = test.map { e =>
      val got = Read.run(reader.read[Read](e.grammar, e.item.text, e.layout), reading)
      (e, got)
    }
    val summary = Extract.summarize(decoded.map((e, got) => (e.grammar, e.gold, got)))
    def family(e: Example): String = e.item.family
    for f <- decoded.map(_._1).map(family).distinct.sorted do
      val rows = decoded.filter((e, _) => family(e) == f)
      val s = Extract.summarize(rows.map((e, got) => (e.grammar, e.gold, got)))
      println(s"test/$f: exact=${s.exact} semantic=${s.semanticExact} abstained=${s.abstained} " +
        s"closedWrong=${s.closedWrong} goalWrong=${s.goalWrong} relationWrong=${s.relationWrong} " +
        s"valueWrong=${s.valueWrong} spanF1=${"%.3f".format(s.spanF1)}")
    val (copyOk, copyN) = copyPath(test, reader)
    val (testPointerOk, testPointerN) = pointerAccuracy(test)
    println(s"test copies: $copyOk/$copyN; pointer: $testPointerOk/$testPointerN")
    println(s"test gold path: exact=${goldTest.exact}/${test.size} abstained=${goldTest.abstained} " +
      s"wrong=${goldTest.wrong}")
    println(s"test decoder: exact=${summary.exact}/${summary.tasks} semantic=${summary.semanticExact} " +
      s"abstained=${summary.abstained} closedWrong=${summary.closedWrong} goalWrong=${summary.goalWrong} " +
      s"relationWrong=${summary.relationWrong} valueWrong=${summary.valueWrong} " +
      s"spanF1=${"%.3f".format(summary.spanF1)}")
    for (e, got) <- decoded if !Extract.verdict(e.grammar, e.gold, got).exact do
      val renameOnly = Extract.same(e.grammar, e.gold, got)
      val why = if renameOnly then "RENAME ONLY" else "NOT EXACT"
      println(s"$why (${e.item.family}): ${e.item.text}\n  gold: ${Extract.word(e.gold).mkString(" ")}\n" +
        s"  got:  ${Extract.word(got).mkString(" ")}")
    if summary.closedWrong > 0 then
      println("a wrong-closed reading: do not ship this head")
      return
    // the M3 gate (specs/logic.md §8): a head is shippable only when it reads whole held-out programs exactly;
    // an all-abstain or rename-only head is a research result, not a promoted reader
    val gate = math.ceil(0.70 * summary.tasks).toInt
    if summary.exact < gate then
      println(s"below the exact-program gate (${summary.exact}/${summary.tasks} exact, need $gate): do not ship")
      return
    // artifacts are labelled by the read-out layer: the 12-layer student and the 28-layer control never share a file
    val out = Path.of(args.headOption.getOrElse(home + s"/.cache/okay-desk/logic-reader-$layers.json"))
    Files.createDirectories(out.getParent)
    Files.writeString(out, Wire.encode(parameters))
    println(s"logic reader saved: $out (${Files.size(out)} bytes)")
