package okay.desk.bench

import okay.desk.logic.{Compile, Corpus, LogicGrammar}
import okay.desk.skill.{Extract, LogicRead, Read}
import okay.desk.skill.LogicRead.Masked
import okay.desk.calc.Term

import java.nio.file.Path

/**
 * A PROBE, NOT A READER (specs/logic.md §5.4): before a prefix head can read a whole program, the goal's predicate
 * must be readable from the task alone. One state per task (the prompt with `Program: `), one masked softmax over the
 * goal's options, trained on Train and measured on Validate — the first question of the M3 gate, at a fraction of
 * the cost. If the goal is not readable, no prefix head can be.
 */
object LogicProbe:
  def term(i: Corpus.Item): Term =
    val p = okay.datalog.Parse.program(i.rules).fold(e => sys.error(e), identity)
    val a = okay.datalog.Parse.atom(i.goal).fold(e => sys.error(e), identity)
    Compile.ofProgram(p, a, i.ontology).fold(e => sys.error(e), identity)

  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val reading = ComputeReading(dir, 12, ComputeReading.device.getOrElse(okay.llm.compute.OnMlx()))
    def items(split: Corpus.Split, count: Int): Vector[(Corpus.Item, LogicGrammar, Vector[String], Array[Float])] =
      Corpus.items(split, count, seed = 7).map { i =>
        val g = LogicGrammar(i.ontology)
        val gold = Extract.gold(g, i.text, term(i)).fold(e => sys.error(e), identity)
        val steps = LogicRead.steps(g, gold)
        // the goal's predicate: the second hole (the first is `Program` at the root)
        val f = Read.run(LogicRead.features[Read](i.text, steps(1).prefix), reading)
        (i, g, steps(1).options, f.last)
      }
    val train = items(Corpus.Split.Train, 16)
    val validate = items(Corpus.Split.Validate, 8)
    val d = train.head._4.length
    val all = train.map(_._4)
    val mean = Vector.tabulate(d)(i => (all.map(_(i).toDouble).sum / all.size).toFloat)
    val std = Vector.tabulate(d)(i => math.max(1e-3,
      math.sqrt(all.map(x => math.pow(x(i) - mean(i), 2)).sum / all.size)).toFloat)
    def norm(x: Array[Float]): Array[Float] = LogicRead.normalize(x, mean, std)
    val letters = train.flatMap(_._3).distinct.sorted
    println(s"goal options: $letters")
    val head = Masked(letters, d)
    val examples = train.map((i, g, options, x) =>
      (norm(x), options, Extract.word(Extract.gold(g, i.text, term(i)).fold(e => sys.error(e), identity))(1)))
    head.train(examples, epochs = 400, lr = 0.2f, seed = 1, batch = 16)
    def accuracy(ex: Vector[(Corpus.Item, LogicGrammar, Vector[String], Array[Float])]): (Int, Int) =
      var ok = 0
      for (i, g, options, x) <- ex do
        val want = Extract.word(Extract.gold(g, i.text, term(i)).fold(e => sys.error(e), identity))(1)
        val got = head.scores(norm(x), options).head._1
        if got == want then ok += 1
        else println(s"  miss: ${i.family} '${i.text}' want=$want got=$got")
      (ok, ex.size)
    println(s"goal from the task: train=${accuracy(train)} validate=${accuracy(validate)}")
    // a nearest-mean baseline: does the task's family kind separate at all?
    val means = train.groupBy(_._1.family).view.mapValues { xs =>
      val m = Vector.tabulate(d)(i => (xs.map(_._4(i).toDouble).sum / xs.size).toFloat)
      norm(m.toArray)
    }.toMap
    var near = 0
    for (i, _, _, x) <- validate do
      val best = means.toVector.map((f, m) => f -> m.indices.map(k => math.pow(norm(x)(k) - m(k), 2)).sum)
        .minBy(_._2)._1
      if best == i.family then near += 1
    println(s"nearest family mean on validate: $near/${validate.size}")
