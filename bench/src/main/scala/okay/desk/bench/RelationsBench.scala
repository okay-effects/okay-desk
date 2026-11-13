package okay.desk.bench

import okay.desk.know.{FactReader, Relations}
import okay.desk.skill.{Probes, Read}
import okay.desk.exec.Wire
import okay.llm.compute.OnMlx
import java.nio.file.{Files, Path}

/** Linguistic supervision for sourced binary facts. The frozen backbone is the browser's 12-layer student. */
object RelationsBench:
  import FactReaderBench.Pair
  val forms = Vector(
    "located-in" -> Vector("{A} is located in {B}.", "{A} lies in {B}.", "{A} is situated in {B}.",
      "{A} находится в {B}.", "{A}, officially the Republic of {A}, is a country in {B}."),
    "capital-of" -> Vector("{A} is the capital of {B}.", "{A} is the capital and largest city of {B}.",
      "{A} serves as the capital of {B}.", "{A} — столица {B}."),
    "part-of" -> Vector("{A} is part of {B}.", "{A} forms part of {B}.", "{A} is a component of {B}.",
      "{A} является частью {B}."),
    "orbits" -> Vector("{A} orbits {B}.", "{A} revolves around {B}.", "{A} moves in orbit around {B}.",
      "{A} вращается вокруг {B}."),
    "parent-of" -> Vector("{A} is a parent of {B}.", "{A} is the mother of {B}.", "{A} is the father of {B}.",
      "{A} — родитель {B}.", "{A} is the biological mother of {B}.",
      "{A} has a son called {B}.", "{A} has a daughter called {B}.",
      "The person {A} is a parent of {B}."),
    "borders" -> Vector("{A} borders {B}.", "{A} shares a border with {B}.", "{A} adjoins {B}.",
      "{A} граничит с {B}."))
  val validation = Vector(
    "located-in" -> Vector("{A} is in {B}.", "{A} is geographically located in {B}."),
    "capital-of" -> Vector("{A} is the national capital of {B}.", "{A} is the capital city of {B}."),
    "part-of" -> Vector("{A} constitutes a part of {B}.", "{A} is a constituent of {B}."),
    "orbits" -> Vector("{A} travels around {B} in an orbit.", "{A} is in orbit around {B}."),
    "parent-of" -> Vector("{A} is one of the parents of {B}.", "{A} is {B}'s parent."),
    "borders" -> Vector("{A} has a border with {B}.", "{A} is adjacent to {B} along a border."))
  val testing = Vector(
    "located-in" -> Vector("{A} can be found in {B}.", "{A} is a city situated in {B}."),
    "capital-of" -> Vector("{A} functions as the capital of {B}.", "{A}, the capital of {B}, is a city."),
    "part-of" -> Vector("{A} belongs to {B} as a component.", "{A} is included as a part of {B}."),
    "orbits" -> Vector("{A} follows an orbit around {B}.", "{A} circles {B} in orbit."),
    "parent-of" -> Vector("{A} has a child named {B}.", "{A} is a biological parent of {B}."),
    "borders" -> Vector("{A} and {B} share a border.", "{A} meets {B} at a common border."))

  def pairs(fs: Vector[(String, Vector[String])], count: Int, seed: Int): Vector[Pair] =
    val rnd = scala.util.Random(seed)
    val subjects = if seed == 11 then Vector("Aster", "New Harbor", "Ольга", "Delta Bay", "East Region")
      else if seed == 12 then Vector("Mira", "North Shore", "Клара", "Old Town")
      else Vector("Rhea", "West Province", "Инга", "South Island")
    val objects = if seed == 11 then Vector("Beryl", "Northern Basin", "Запад", "Outer Sea", "Central Domain")
      else if seed == 12 then Vector("Lyra", "Inner Valley", "Восток", "Great Lake")
      else Vector("Vesta", "Far Coast", "Север", "Lower Kingdom")
    fs.flatMap { (kind, templates) => Vector.fill(count) {
      val a = subjects(rnd.nextInt(subjects.size)); val b = objects(rnd.nextInt(objects.size))
      val template = templates(rnd.nextInt(templates.size))
      val base = template.replace("{A}", a).replace("{B}", b)
      val text = if seed == 11 && !kind.startsWith("not-") && kind != "other" && rnd.nextBoolean() then
        val tails = Vector(
          ", with an estimated population of 3.14 million and an area of 42.8 km2, as of January 2024.",
          ", with an estimated city population of 4.82 million in an area of 76.9 km2 (29.7 sq mi), " +
            "as of March 2022, and a metropolitan population of 8.6 million (2021).",
          ", covering 55.2 square miles (142.9 square kilometres), with 620,000 residents.")
        base.stripSuffix(".") + tails(rnd.nextInt(tails.size))
      else base
      Pair(text, kind, FactReader.Span(text.indexOf(a), text.indexOf(a) + a.length),
        FactReader.Span(text.indexOf(b), text.indexOf(b) + b.length))
    }}

  def negativeForms(fs: Vector[(String, Vector[String])], split: Int): Vector[(String, Vector[String])] =
    fs.map { (kind, _) => ("not-" + kind) -> (split match
      case 0 => Vector(s"It is not true that {A} ${Relations.phrases(kind)} {B}.",
        s"The assertion that {A} ${Relations.phrases(kind)} {B} is false.")
      case 1 => Vector(s"It is false that {A} ${Relations.phrases(kind)} {B}.")
      case _ => Vector(s"The statement that {A} ${Relations.phrases(kind)} {B} is not true.")) }
  def uncertain(fs: Vector[(String, Vector[String])], split: Int): Vector[(String, Vector[String])] =
    Vector("other" -> fs.flatMap { (kind, _) => split match
      case 0 => Vector(s"Perhaps {A} ${Relations.phrases(kind)} {B}.",
        s"It is unknown whether {A} ${Relations.phrases(kind)} {B}.",
        s"Someone claims that {A} ${Relations.phrases(kind)} {B}.")
      case 1 => Vector(s"Maybe {A} ${Relations.phrases(kind)} {B}.",
        s"A person believes {A} ${Relations.phrases(kind)} {B}.")
      case _ => Vector(s"Possibly {A} ${Relations.phrases(kind)} {B}.",
        s"It has been suggested that {A} ${Relations.phrases(kind)} {B}.") })

  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    // LAYERS: the model's last layer; FACTS_FILE: the fact reader trained on the same model
    val on = ComputeReading(dir, sys.env.get("LAYERS").map(_.toInt).getOrElse(12), OnMlx())
    val classes = FactReader.parse(Files.readString(Path.of(sys.env.getOrElse("FACTS_FILE",
      home + "/.cache/okay-desk/facts-readers-small.json"))))
      .fold(sys.error, identity)
    val kinds = FactReader.Kinds.dropRight(1) ++ Relations.kinds
    val trPairs = pairs(forms ++ negativeForms(forms, 0) ++ uncertain(forms, 0), 160, 11) ++
      FactReaderBench.pairs(FactReaderBench.train, 512, 1, Vector("Moby", "Alice", "New York", "Моби"))
    val vaPairs = pairs(validation ++ negativeForms(validation, 1) ++ uncertain(validation, 1), 24, 12) ++
      FactReaderBench.pairs(FactReaderBench.validate, 32, 2, Vector("Dora", "North Bank", "Дина"))
    val tePairs = pairs(testing ++ negativeForms(testing, 2) ++ uncertain(testing, 2), 24, 13) ++
      FactReaderBench.pairs(FactReaderBench.test, 32, 3, Vector("Zeno", "South Port", "Лена"))
    def states(ps: Vector[Pair]) = ps.zipWithIndex.map { (p, i) =>
      if i % 200 == 0 then println(s"reading $i/${ps.size}")
      p -> Read.run(FactReader.features[Read](p.text), on).getOrElse(sys.error("unreadable linguistic example"))
    }
    val tr = states(trPairs); val va = states(vaPairs); val te = states(tePairs)
    val d = tr.head._2.last.length
    val all = tr.flatMap((_, f) => f.states :+ f.last)
    val mean = Vector.tabulate(d)(i => (all.map(_(i).toDouble).sum / all.size).toFloat)
    val std = Vector.tabulate(d)(i => math.max(1e-3,
      math.sqrt(all.map(x => math.pow(x(i) - mean(i), 2)).sum / all.size)).toFloat)
    def norm(x: Array[Float]) = FactReader.normalize(x, mean, std)
    val kind = Probes.Softmax(kinds, 3 * d)
    val roles = Probes.Softmax(FactReader.Roles, 3 * d)
    val globals = tr.map((p, f) => FactReader.kindFeatures(norm(f.last), f.states.map(norm)) -> p.kind)
    val words = tr.flatMap { (p, f) => f.words.zip(f.states).map { (w, state) =>
      val role = if p.kind == "other" then "outside"
        else if w.from >= p.a.from && w.until <= p.a.until then "a"
        else if w.from >= p.b.from && w.until <= p.b.until then "b" else "outside"
      FactReader.roleFeatures(norm(state), norm(f.last)) -> role
    }}
    val rnd = scala.util.Random(42)
    def fit(probe: Probes.Softmax, data: Vector[(Array[Float], String)], steps: Int): Unit =
      for i <- 0 until steps do
        if i % 1000 == 0 then println(s"training $i/$steps")
        probe.train(Vector.fill(64)(data(rnd.nextInt(data.size))), steps = 1, lr = 0.01f)
    fit(kind, globals, 6000); fit(roles, words, 6000)
    def params(km: Float, rm: Float) = FactReader.Parameters(sys.env.get("LAYERS").map(_.toInt).getOrElse(12), mean, std,
      kind.w.toVector.map(_.toVector), kind.b.toVector, roles.w.toVector.map(_.toVector), roles.b.toVector, km, rm)
    def evaluate(data: Vector[(Pair, FactReader.Features)], reader: FactReader.Reader): (Int, Int) =
      val accepted = data.flatMap((p, f) => FactReader.combined(reader, classes, "test", p.text, f).map(p -> _))
      (accepted.size, accepted.count((p, r) => p.kind != r.kind || p.a != r.a || p.b != r.b))
    val settings = Vector(0f, 0.5f, 1f, 2f, 3f, 4f).map { rm =>
      val base = FactReader.Reader(params(0, rm), kinds)
      val candidates = va.flatMap { (p, f) =>
        FactReader.combined(base, classes, "test", p.text, f).map(r => (p, r, base.confidence(f)._2))
      }
      val margins = kinds.map { label =>
        val cs = candidates.filter(_._2.kind == label)
        val settings = Vector(0f, 0.5f, 1f, 2f, 3f, 4f, 6f, 8f, 12f, 16f, 32f, 1e9f).filter { threshold =>
          cs.filter(_._3 >= threshold).forall((p, r, _) => p.kind == r.kind && p.a == r.a && p.b == r.b)
        }
        label -> settings.headOption.getOrElse(1e9f)
      }.toMap
      val reader = FactReader.Reader(params(0, rm), kinds, margins)
      (reader, evaluate(va, reader))
    }
    val (reader, validationScore) = settings.filter(_._2._2 == 0).sortBy(s => -s._2._1).head
    Files.writeString(Path.of("/tmp/okay-relations-candidate.json"),
      Wire.encode(Relations.Artifact(reader.parameters, reader.kindMargins)))
    val testScore = evaluate(te, reader)
    println(s"relations: train=${tr.size}, validation=$validationScore/${va.size}, test=$testScore/${te.size}")
    println(s"margins=${reader.kindMargins}; roles=${reader.parameters.roleMargin}")
    te.foreach { (p, f) => FactReader.combined(reader, classes, "test", p.text, f).foreach { r =>
      if p.kind != r.kind || p.a != r.a || p.b != r.b then println(s"WRONG: $p -> $r")
    }}
    require(validationScore._1 >= 48 && testScore._1 >= 48 && testScore._2 == 0,
      "held-out relation contract failed; do not ship")
    for label <- Relations.names do
      val subset = te.filter(_._1.kind == label)
      val score = evaluate(subset, reader)
      println(s"$label: $score/${subset.size}")
      require(score._1 >= 3 && score._2 == 0, s"insufficient held-out coverage for $label")
    val out = Path.of(args.headOption.getOrElse(home + "/.cache/okay-desk/relations-readers-small.json"))
    Files.createDirectories(out.getParent)
    Files.writeString(out, Wire.encode(Relations.Artifact(reader.parameters, reader.kindMargins)))
    println(s"saved $out (${Files.size(out)} bytes)")
