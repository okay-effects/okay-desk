package okay.desk.bench

import okay.desk.calc.Term
import okay.desk.exec.Wire
import okay.desk.logic.{Compile, Corpus, LogicGrammar}
import okay.desk.skill.{Extract, LogicRead, Read}
import okay.desk.skill.Extract.Reading
import okay.desk.skill.LogicRead.{Features, Masked, Step}

import java.nio.file.{Files, Path}

/**
 * THE READER TRAINED ON THE STUDENT (specs/logic.md §5.4): the 12-layer student's state at the last token of the
 * chat template's assistant header, then `Program: ` and the gold constructors so far, trains one masked softmax
 * over the grammar's options. The margin is calibrated on Validate for no closed-wrong reading, the Test split is
 * measured twice — on the gold path (the head's own decisions) and by the real decoder — and the weights are saved
 * as `Wire`. A head that closes a wrong answer is not saved: an open hole is a question, not a guess.
 */
object LogicBench:
  final case class Example(item: Corpus.Item, grammar: LogicGrammar, gold: Reading,
      steps: Vector[Step], features: Vector[Features])

  def term(i: Corpus.Item): Term =
    val p = okay.datalog.Parse.program(i.rules).fold(e => sys.error(e), identity)
    val a = okay.datalog.Parse.atom(i.goal).fold(e => sys.error(e), identity)
    Compile.ofProgram(p, a, i.ontology).fold(e => sys.error(e), identity)

  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val reading = ComputeReading(dir, 12, ComputeReading.device.getOrElse(okay.llm.compute.OnMlx()))
    val trainCount = sys.env.get("LOGIC_TRAIN").map(_.toInt).getOrElse(16)
    val epochs = sys.env.get("LOGIC_EPOCHS").map(_.toInt).getOrElse(40)
    val lr = sys.env.get("LOGIC_LR").map(_.toFloat).getOrElse(0.05f)

    var read = 0
    def example(i: Corpus.Item): Example =
      val g = LogicGrammar(i.ontology)
      val gold = Extract.gold(g, i.text, term(i)).fold(e => sys.error(s"${i.text}: $e"), identity)
      val steps = LogicRead.steps(g, gold)
      val features = steps.map { s =>
        read += 1
        if read % 100 == 0 then println(s"reading $read…")
        Read.run(LogicRead.features[Read](i.text, s.prefix), reading)
      }
      Example(i, g, gold, steps, features)

    def examples(split: Corpus.Split, count: Int): Vector[Example] =
      Corpus.items(split, count, seed = 7).map(example)

    val train = examples(Corpus.Split.Train, trainCount)
    val validate = examples(Corpus.Split.Validate, 8)
    val test = examples(Corpus.Split.Test, 8)
    println(s"items: train=${train.size} validate=${validate.size} test=${test.size}; steps: " +
      s"${train.map(_.steps.size).sum}/${validate.map(_.steps.size).sum}/${test.map(_.steps.size).sum}")

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

    /** the head's own decisions along the gold path: exact, abstained, closed wrong */
    def goldPath(ex: Vector[Example], margin: Float): (Int, Int, Int) =
      var exact = 0; var abstained = 0; var wrong = 0
      for e <- ex do
        var i = 0
        var stop = false
        var bad = false
        while !stop && i < e.steps.size do
          val s = e.steps(i)
          val ranked = head.scores(norm(e.features(i).last), s.options)
          val gap = if ranked.size > 1 then ranked(0)._2 - ranked(1)._2 else Float.MaxValue
          if ranked.head._1 != s.target then
            if gap >= margin then { wrong += 1; bad = true } else abstained += 1
            stop = true
          else if gap < margin then { abstained += 1; stop = true }
          else i += 1
        if !stop && !bad then exact += 1
      (exact, abstained, wrong)

    val grid = Vector(0f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 6f, 8f, 12f)
    println(s"train: ${goldPath(train, 0f)} at margin 0, ${goldPath(train, 1f)} at margin 1")
    val settings = grid.map(m => (m, goldPath(validate, m)))
    println("validation: " + settings.map((m, s) => s"$m -> exact=${s._1} abstained=${s._2} wrong=${s._3}")
      .mkString("; "))
    val safe = settings.filter(_._2._3 == 0).sortBy(s => (-s._2._1, s._1))
    if safe.isEmpty then
      println("no margin without a wrong-closed reading: do not ship this head")
      return
    val (margin, validation) = safe.head
    println(s"margin=$margin validation: exact=${validation._1}/${validate.size} " +
      s"abstained=${validation._2} wrong=${validation._3}")

    val parameters = LogicRead.parameters(head, 12, mean, std, margin)
    val reader = LogicRead.Reader(parameters)
    val goldTest = goldPath(test, margin)
    val decoded = test.map { e =>
      val got = Read.run(reader.read[Read](e.grammar, e.item.text), reading)
      (e, got)
    }
    val summary = Extract.summarize(decoded.map((e, got) => (e.grammar, e.gold, got)))
    def family(e: Example): String = e.item.family
    for f <- decoded.map(_._1).map(family).distinct.sorted do
      val rows = decoded.filter((e, _) => family(e) == f)
      val s = Extract.summarize(rows.map((e, got) => (e.grammar, e.gold, got)))
      println(s"test/$f: exact=${s.exact}/${s.tasks} abstained=${s.abstained} " +
        s"closedWrong=${s.closedWrong} goalWrong=${s.goalWrong} relationWrong=${s.relationWrong} " +
        s"valueWrong=${s.valueWrong} spanF1=${"%.3f".format(s.spanF1)}")
    println(s"test gold path: exact=${goldTest._1}/${test.size} abstained=${goldTest._2} wrong=${goldTest._3}")
    println(s"test decoder: exact=${summary.exact}/${summary.tasks} abstained=${summary.abstained} " +
      s"closedWrong=${summary.closedWrong} goalWrong=${summary.goalWrong} relationWrong=${summary.relationWrong} " +
      s"valueWrong=${summary.valueWrong} spanF1=${"%.3f".format(summary.spanF1)}")
    for (e, got) <- decoded if !Extract.verdict(e.grammar, e.gold, got).exact do
      println(s"NOT EXACT (${e.item.family}): ${e.item.text}\n  gold: ${Extract.word(e.gold).mkString(" ")}\n" +
        s"  got:  ${Extract.word(got).mkString(" ")}")
    if summary.closedWrong > 0 then
      println("a wrong-closed reading: do not ship this head")
      return
    val out = Path.of(args.headOption.getOrElse(home + "/.cache/okay-desk/logic-reader-small.json"))
    Files.createDirectories(out.getParent)
    Files.writeString(out, Wire.encode(parameters))
    println(s"logic reader saved: $out (${Files.size(out)} bytes)")
