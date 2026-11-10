package okay.desk.bench

import okay.desk.calc.Term
import okay.desk.logic.{Compile, Corpus, Schema}
import okay.desk.skill.{LogicRead, Read}
import okay.desk.skill.LogicRead.Masked

import java.nio.file.Path

/**
 * THE SCHEMA-FIRST READER (specs/logic.md §5.6): the corpus' programs carry a small number of schemas — the form of
 * the question with its constants taken out — and every Test schema also appears in Train. So the honest reading is
 * one decision for the schema and one pointer read per constant, not 34 constructor decisions (specs/logic.md §5.5).
 *
 * This bench asks the one question that decides whether that path works: can the student choose the schema from the
 * task text? It trains one masked softmax over the family's schemas, with the state at the last token of the prompt
 * (`last`) and with the mean of the task's word states (`mean`), and prints the accuracy on Validate and Test, the
 * majority baseline, and the whole-program expectation `schema accuracy × pointer^slots`. No head is saved: this is
 * a measurement, not a promoted reader. `SCHEMA_MODEL=0` prints the model-free part only.
 */
object LogicSchema:
  def term(i: Corpus.Item): Term =
    val p = okay.datalog.Parse.program(i.rules).fold(e => sys.error(e), identity)
    val a = okay.datalog.Parse.atom(i.goal).fold(e => sys.error(e), identity)
    Compile.ofProgram(p, a, i.ontology).fold(e => sys.error(e), identity)

  final case class Task(item: Corpus.Item, schema: Schema)

  private def splitOf(name: String): Corpus.Split =
    Corpus.Split.values.find(_.toString.toLowerCase == name).get

  def main(args: Array[String]): Unit =
    val trainCount = sys.env.get("SCHEMA_TRAIN").map(_.toInt).getOrElse(16)
    val splits = Vector("train" -> trainCount, "validate" -> 8, "test" -> 8)
    val tasks = splits.map((name, n) =>
      name -> Corpus.items(splitOf(name), n, seed = 7).map(i => Task(i, Schema.of(term(i))))).toMap
    val families = tasks("validate").map(_.item.family).distinct.sorted

    def byFamily(xs: Vector[Task], f: String): Vector[Task] = xs.filter(_.item.family == f)
    def labels(xs: Vector[Task]): Vector[String] = xs.map(t => Schema.name(t.schema)).distinct.sorted

    println("schemas per family (library of Train):")
    for f <- families do
      val train = byFamily(tasks("train"), f)
      val lib = labels(train)
      val slots = train.map(_.schema.size)
      val majority = train.groupBy(t => Schema.name(t.schema)).view.mapValues(_.size).toVector.maxBy(_._2)
      println(s"  $f: ${lib.size} schemas, slots ${slots.min}..${slots.max} " +
        s"(mean ${"%.1f".format(slots.sum.toDouble / slots.size)}), majority ${majority._2}/${train.size}")
      for l <- lib do println(s"    $l")
    val lib = labels(tasks("train"))
    println(s"  all: ${lib.size} schemas over ${tasks("train").size} Train tasks")

    println()
    println("coverage (a Test schema the Train library never showed is unreadable by construction):")
    for f <- families do
      val train = labels(byFamily(tasks("train"), f)).toSet
      val validate = labels(byFamily(tasks("validate"), f))
      val test = labels(byFamily(tasks("test"), f))
      println(s"  $f: validate ${validate.count(train.contains)}/${validate.size}, " +
        s"test ${test.count(train.contains)}/${test.size}")

    // the majority baseline: always the family's most common schema — what a schema head must beat
    def majority(f: String): String =
      byFamily(tasks("train"), f).groupBy(t => Schema.name(t.schema)).view.mapValues(_.size).toVector.maxBy(_._2)._1
    for name <- Vector("validate", "test") do
      val ok = tasks(name).count(t => Schema.name(t.schema) == majority(t.item.family))
      println(s"  $name majority baseline: $ok/${tasks(name).size}")

    println()
    val slots = tasks("validate").map(_.schema.size)
    val meanSlots = slots.sum.toDouble / slots.size
    val pointer = 147.0 / 177.0 // LogicBench's Test pointer on gold prefixes (§5.4)
    println(s"decisions: 1 schema + ${"%.1f".format(meanSlots)} slots = " +
      s"${"%.1f".format(1 + meanSlots)} per task (34.1 as constructors); at pointer " +
      s"${"%.1f".format(100 * pointer)}% the whole-program expectation is schema × " +
      s"${"%.2f".format(math.pow(pointer, meanSlots))}")

    if sys.env.get("SCHEMA_MODEL").contains("0") then return

    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("LOGIC_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val layers = sys.env.get("LOGIC_LAYERS").map(_.toInt).getOrElse(12)
    val epochs = sys.env.get("SCHEMA_EPOCHS").map(_.toInt).getOrElse(300)
    val lr = sys.env.get("SCHEMA_LR").map(_.toFloat).getOrElse(0.1f)
    println(s"\nbackbone: $dir, layers=$layers")
    val reading = ComputeReading(dir, layers, ComputeReading.device.getOrElse(okay.llm.compute.OnMlx()))

    var read = 0
    def lastState(t: Task): Array[Float] =
      read += 1
      if read % 20 == 0 then println(s"reading $read…")
      Read.run(LogicRead.features[Read](t.item.text, Vector.empty), reading).last

    val lastStates = tasks.view.mapValues(_.map(t => t -> lastState(t))).toMap
    val d = lastStates("train").head._2.length

    /** the bag of the task's tokens: one forward pass over the template without `Program: `, averaged */
    def meanState(t: Task): Array[Float] =
      val ids = Read.run(Read.encode[Read](okay.desk.skill.OnQwen.template(LogicRead.System, t.item.text)),
        reading)
      val states = Read.run(Read.states[Read](ids, ids.indices.toVector), reading)
      Array.tabulate(d)(i => states.map(_(i)).sum / states.size)

    val meanStates = tasks.view.mapValues(_.map(t => t -> meanState(t))).toMap
    val all = lastStates("train").map((_, f) => f)
    val mean = Vector.tabulate(d)(i => (all.map(_(i).toDouble).sum / all.size).toFloat)
    val std = Vector.tabulate(d)(i => math.max(1e-3,
      math.sqrt(all.map(x => math.pow(x(i) - mean(i), 2)).sum / all.size)).toFloat)
    def norm(x: Array[Float]): Array[Float] = LogicRead.normalize(x, mean, std)

    def run(name: String, states: Map[String, Vector[(Task, Array[Float])]]): Unit =
      val head = Masked(lib, d)
      val train = states("train").map((t, x) =>
        (norm(x), labels(byFamily(tasks("train"), t.item.family)), Schema.name(t.schema)))
      head.train(train, epochs = epochs, lr = lr, seed = 1, batch = 8)
      println(s"\nschema head ($name): ${train.size} examples, ${epochs} epochs, lr=$lr")
      for split <- Vector("validate", "test") do
        var ok = 0
        var n = 0
        var exact = 0.0
        val perFamily = scala.collection.mutable.Map.empty[String, (Int, Int)]
        for (t, x) <- states(split) do
          val options = labels(byFamily(tasks("train"), t.item.family))
          val got = head.scores(norm(x), options).headOption.map(_._1)
          val good = got.contains(Schema.name(t.schema))
          n += 1; if good then ok += 1
          if good then exact += math.pow(pointer, t.schema.size)
          val (a, b) = perFamily.getOrElse(t.item.family, (0, 0))
          perFamily(t.item.family) = (a + (if good then 1 else 0), b + 1)
          if !good then println(s"    wrong (${t.item.family}): want ${Schema.name(t.schema)}\n" +
            s"      got  ${got.getOrElse("-")}")
        println(s"  $split: $ok/$n schemas")
        for f <- families do
          val (a, b) = perFamily(f)
          println(s"    $f: $a/$b")
        println(s"  $split whole programs with the pointer: ${"%.1f".format(100 * exact / n)}% " +
          s"(schema ${"%.1f".format(100.0 * ok / n)}%, perfect pointer would give that)")

    run("last", lastStates)
    run("mean", meanStates)
