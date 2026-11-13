package okay.desk.bench

import okay.desk.know.FactReader
import okay.desk.skill.{Probes, Read}
import okay.desk.exec.Wire
import okay.llm.compute.OnMlx
import java.nio.file.{Files, Path}

/** Train small readouts on the actual phone student's states. Synthetic assertions are linguistic supervision,
 * not claims about the world. Validation/test templates and names are disjoint from training. */
object FactReaderBench:
  case class Pair(text: String, kind: String, a: FactReader.Span, b: FactReader.Span)
  val kinds = FactReader.Kinds
  val train = Vector(
    "fact" -> Vector("{A} is a {B}.", "{A} belongs to the class {B}.", "{A} is classified as a {B}.",
      "{A} — {B}.", "{A} относится к классу «{B}».",
      "The entity {A} belongs to {B}.", "Classify {A} as {B}.", "{A} has type {B}.",
      "The classification of {A} is {B}.", "{A} является элементом класса «{B}»."),
    "not-fact" -> Vector("{A} is not a {B}.", "{A} does not belong to the class {B}.",
      "{A} — не {B}.", "{A} не относится к классу «{B}».",
      "{A} is excluded from {B}.", "{A} is not inside the class {B}.",
      "{A} is external to the class {B}.", "{A} cannot be classified as {B}.",
      "{A} исключается из категории «{B}».", "{A} находится вне класса «{B}»."),
    "every" -> Vector("Every {A} is a {B}.", "Each {A} belongs to the class {B}.",
      "The class {A} is included in the class {B}.",
      "Каждый представитель класса «{A}» входит в класс «{B}».",
      "All members of {A} belong to {B}.", "If something is a {A}, it is a {B}.",
      "The category {A} implies the category {B}.", "{A} is a subclass of {B}.",
      "Любой представитель «{A}» относится к «{B}»."),
    "every-not" -> Vector("No {A} is a {B}.", "Every {A} is excluded from the class {B}.",
      "Ни один представитель класса «{A}» не входит в класс «{B}».",
      "Being in {A} excludes being in {B}.", "All members of {A} are outside {B}.",
      "If something is a {A}, it cannot be a {B}.", "The categories {A} and {B} are disjoint.",
      "Класс «{A}» исключает принадлежность к «{B}»."),
    "other" -> Vector("Some {A} are {B}.", "Not every {A} is a {B}.", "It is unknown whether {A} is a {B}.",
      "Не каждый «{A}» относится к классу «{B}».", "Возможно, {A} относится к классу «{B}».",
      "Someone says {A} belongs to {B}.", "Suppose {A} belongs to {B}.",
      "Does {A} belong to {B}.", "A report claims {A} is a {B}.",
      "Нет сведений, что «{A}» относится к «{B}»."))
  val validate = Vector(
    "fact" -> Vector("{A} is a member of the class {B}.", "{A} принадлежит классу «{B}»."),
    "not-fact" -> Vector("It is false that {A} is a {B}.", "{A} не принадлежит классу «{B}»."),
    "every" -> Vector("Any {A} is also a {B}.", "Все представители класса «{A}» — «{B}»."),
    "every-not" -> Vector("There is no {A} that is a {B}.", "Классы «{A}» и «{B}» не пересекаются."),
    "other" -> Vector("Perhaps {A} is a {B}.", "Неизвестно, принадлежит ли {A} классу «{B}»."))
  val test = Vector(
    "fact" -> Vector("{A} is an instance of {B}.", "{A} входит в класс «{B}»."),
    "not-fact" -> Vector("{A} is outside the class {B}.", "{A} исключён из класса «{B}»."),
    "every" -> Vector("Being a {A} entails being a {B}.", "Любой «{A}» является представителем класса «{B}»."),
    "every-not" -> Vector("Membership in {A} rules out membership in {B}.",
      "Для любого «{A}» принадлежность к классу «{B}» исключена."),
    "other" -> Vector("If {A} is a {B}, a check is required.", "Кто-то считает, что {A} — {B}."))

  def pairs(forms: Vector[(String, Vector[String])], count: Int, seed: Int, names: Vector[String]): Vector[Pair] =
    val rnd = scala.util.Random(seed)
    val en = Vector("whale", "mammal", "animal", "bird", "vehicle", "planet", "city", "machine",
      "marine mammal", "red object", "living organism", "large animal")
    val ru = Vector("кит", "млекопитающее", "животное", "птица", "машина", "город",
      "морское млекопитающее", "живой организм", "красный предмет")
    forms.flatMap { (kind, templates) => Vector.fill(count) {
      val template = templates(rnd.nextInt(templates.size))
      val russian = template.exists(c => c >= 'А' && c <= 'я')
      val base = if russian then ru else en
      val adjectives = if russian then Vector("маленький", "большой", "синий", "новый")
        else Vector("small", "blue", "distant", "cold", "bright", "ancient")
      val nouns = base ++ base.flatMap(n => adjectives.map(a => s"$a $n"))
      val a = if kind == "fact" || kind == "not-fact" then names(rnd.nextInt(names.size))
        else nouns(rnd.nextInt(nouns.size))
      val rest = nouns.filterNot(x => x.contains(a) || a.contains(x))
      val b = rest(rnd.nextInt(rest.size))
      val text = template.replace("{A}", a).replace("{B}", b)
      Pair(text, kind, FactReader.Span(text.indexOf(a), text.indexOf(a) + a.length),
        FactReader.Span(text.indexOf(b), text.indexOf(b) + b.length))
    }}

  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    // LAYERS: the model's last layer (the student's 12; another core's its own)
    val layers = sys.env.get("LAYERS").map(_.toInt).getOrElse(12)
    val reading = ComputeReading(dir, layers, OnMlx())
    val training = pairs(train, 512, 1,
      Vector("Moby", "Alice", "Bob", "New York", "Белла", "Моби", "green box", "whale", "bird", "robot"))
    val validation = pairs(validate, 32, 2, Vector("Dora", "North Bank", "Дина", "Юрий"))
    val testing = pairs(test, 32, 3, Vector("Zeno", "South Port", "Лена", "Нора"))
    def states(ps: Vector[Pair]) = ps.zipWithIndex.map { (p, i) =>
      if i % 100 == 0 then println(s"reading $i/${ps.size}…")
      p -> Read.run(FactReader.features[Read](p.text), reading).getOrElse(sys.error("unreadable training span"))
    }
    val tr = states(training); val va = states(validation); val te = states(testing)
    val d = tr.head._2.last.length
    val all = tr.flatMap((_, f) => f.states :+ f.last)
    val mean = Vector.tabulate(d)(i => (all.map(_(i).toDouble).sum / all.size).toFloat)
    val std = Vector.tabulate(d)(i => math.max(1e-3,
      math.sqrt(all.map(x => math.pow(x(i) - mean(i), 2)).sum / all.size)).toFloat)
    def norm(x: Array[Float]) = FactReader.normalize(x, mean, std)
    val kind = Probes.Softmax(kinds, 3 * d)
    val roles = Probes.Softmax(FactReader.Roles, 3 * d)
    val globalPairs = tr.map((p, f) => FactReader.kindFeatures(norm(f.last), f.states.map(norm)) -> p.kind)
    val wordPairs = tr.flatMap { (p, f) => f.words.zip(f.states).map { (w, state) =>
      val tag = if p.kind == "other" then "outside"
        else if w.from >= p.a.from && w.until <= p.a.until then "a"
        else if w.from >= p.b.from && w.until <= p.b.until then "b" else "outside"
      FactReader.roleFeatures(norm(state), norm(f.last)) -> tag
    }}
    val rnd = scala.util.Random(sys.env.get("FACT_SEED").map(_.toInt).getOrElse(4))   // FACT_SEED: the spread of a training
    def fit(probe: Probes.Softmax, examples: Vector[(Array[Float], String)], steps: Int): Unit =
      for i <- 0 until steps do
        if i % 250 == 0 then println(s"training $i/$steps…")
        probe.train(Vector.fill(64)(examples(rnd.nextInt(examples.size))), steps = 1, lr = 0.01f)
    fit(kind, globalPairs, 2400)
    fit(roles, wordPairs, 3600)
    def parameters(km: Float, rm: Float) = FactReader.Parameters(layers, mean, std,
      kind.w.toVector.map(_.toVector), kind.b.toVector, roles.w.toVector.map(_.toVector), roles.b.toVector, km, rm)
    def measure(data: Vector[(Pair, FactReader.Features)], km: Float, rm: Float): (Int, Int) =
      val reader = FactReader.Reader(parameters(km, rm))
      val answered = data.flatMap { (p, f) => reader.read("test", p.text, f).map(r => p -> r) }
      val wrong = answered.count { (p, r) => p.kind != r.kind || p.a != r.a || p.b != r.b }
      (answered.size, wrong)
    val settings = for
      km <- Vector(0f, 0.5f, 1f, 2f, 3f, 4f, 6f, 8f, 12f, 16f)
      rm <- Vector(0f, 0.25f, 0.5f, 1f, 2f, 3f)
    yield (km, rm, measure(va, km, rm))
    println("settings: " + settings.sortBy(x => (x._3._2, -x._3._1)).take(8))
    val chosen = settings.filter(_._3._2 == 0).sortBy(x => -x._3._1).headOption
      .getOrElse(sys.error("no validation setting without accepted errors; do not ship this reader"))
    val (km, rm, validationScore) = chosen
    val testScore = measure(te, km, rm)
    println(s"$layers-layer fact reader: validation=$validationScore/${va.size}, test=$testScore/${te.size}; " +
      s"margins=$km/$rm")
    for (p, f) <- te do
      val out = FactReader.Reader(parameters(km, rm)).read("test", p.text, f)
      if out.exists(r => r.kind != p.kind || r.a != p.a || r.b != p.b) then println(s"WRONG: $p -> $out")
    require(validationScore._1 >= 24 && testScore._1 >= 24 && testScore._2 == 0,
      "held-out fact reader contract failed; inspect the findings")
    // Regressions found after the first held-out run: modifiers must not disappear from copied categories.
    def audit(text: String, kind: String, a: String, b: String): Pair =
      Pair(text, kind, FactReader.Span(text.indexOf(a), text.indexOf(a) + a.length),
        FactReader.Span(text.lastIndexOf(b), text.lastIndexOf(b) + b.length))
    val auditPairs = Vector(
      audit("Europa is an instance of moon.", "fact", "Europa", "moon"),
      audit("Being a moon entails being a natural satellite.", "every", "moon", "natural satellite"),
      audit("Each moon belongs to the class natural satellite.", "every", "moon", "natural satellite"),
      audit("The class moon is included in the class natural satellite.", "every", "moon", "natural satellite"),
      audit("Any moon is also a natural satellite.", "every", "moon", "natural satellite"),
      audit("The class natural satellite is included in the class celestial body.",
        "every", "natural satellite", "celestial body"),
      audit("Perhaps Europa is a planet.", "other", "Europa", "planet"),
      audit("Europa is outside the class planet.", "not-fact", "Europa", "planet"),
      audit("Europa не принадлежит классу «планета».", "not-fact", "Europa", "планета"))
    val auditStates = states(auditPairs)
    for (p, f) <- auditStates; r <- FactReader.Reader(parameters(km, rm)).read("audit", p.text, f)
      if r.kind != p.kind || r.a != p.a || r.b != p.b do println(s"AUDIT WRONG: $p -> $r")
    val auditScore = measure(auditStates, km, rm)
    println(s"post-development regressions: $auditScore/${auditPairs.size}")
    require(auditScore._1 >= 3 && auditScore._2 == 0, "modifier/polarity regression; do not ship this reader")
    val out = Path.of(args.headOption.getOrElse(home + "/.cache/okay-desk/facts-readers-small.json"))
    Files.createDirectories(out.getParent)
    Files.writeString(out, Wire.encode(parameters(km, rm)))
    println(s"fact reader saved: $out (${Files.size(out)} bytes)")
