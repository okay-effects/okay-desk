package okay.desk.bench

import okay.desk.calc.Language
import okay.desk.code.Code
import okay.desk.organ.Organ
import okay.effects.json.{Json, JsonText}
import okay.inlay.domain.{Kit, Pair}

/**
 * THE MODEL TAUGHT TO READ CODE (BACKLOG C; specs/organs.md): the Code domain over okay-desk's own index — the
 * facts symdex dumped (`~/.cache/okay-desk/code/<relation>.json`, `query goal=… format=json`), pairs from the
 * space's phrases, an organ induced from the even half, the measure on the odd half: the rules alone, the rules
 * then the organ, the organ then the rules; the law (the block equals the interpreter) on every pair; then a
 * dozen natural questions about this very codebase answered by the exact engine.
 * `CODE_FACTS=<dir> sbt "bench/runMain okay.desk.bench.CodeBench"`.
 */
object CodeBench:
  def main(args: Array[String]): Unit =
    val dir = java.nio.file.Path.of(sys.env.getOrElse("CODE_FACTS", s"${sys.props("user.home")}/.cache/okay-desk/code"))
    val relations = Vector("defined", "kind", "named", "defined_in", "in_module", "calls", "extends", "overrides", "test").map { r =>
      val text = java.nio.file.Files.readString(dir.resolve(s"$r.json"))
      val rows = JsonText.parse(text).toOption.flatMap(_.field("answers")).collect { case Json.Arr(xs) => xs.toVector }.getOrElse(Vector.empty)
        .collect { case Json.Obj(fs) => fs.toVector.sortBy(_._1).collect { case (_, Json.Str(v)) => v } }
      // the answers' fields come sorted by name: A,B · F,S · K,S · M,S · N,S · S — the relation's own order restored
      val ordered = r match
        case "kind" | "defined_in" | "in_module" | "named" => rows.map(xs => Vector(xs(1), xs(0)))
        case _ => rows
      r -> ordered
    }.toMap
    val s = Code.schema(relations)
    println(s"the code: ${s.symbols.size} symbols (${s.asked.size} asked about), ${s.facts.size} facts; the space ${Code.relations.size} relations × ${s.asked.size} symbols")
    def lang(code: String): Language = Languages.load(code).getOrElse(Language.En.language)
    val pairs: Vector[Pair[Code.Point]] = Kit.pairs(Code)(s, 1200, 11, Vector("en", "ru"))
    println(s"  pairs with an answer: ${pairs.size}; by language ${pairs.groupBy(_.language).map((l, ps) => s"$l ${ps.size}").mkString(", ")}")
    val (train, test) = pairs.zipWithIndex.partition(_._2 % 2 == 0) match { case (a, b) => (a.map(_._1), b.map(_._1)) }
    val organ = Code.induce(train, lang)
    println(s"  the organ: ${organ.cues.size} cues over ${organ.vocabulary.size} stems, ${organ.weights.params} parameters")
    organ.cues.take(12).foreach(c => println(s"    ${c.words.toVector.sorted.mkString(" ")}${if c.unless.nonEmpty then " ¬" + c.unless.toVector.sorted.mkString(" ") else ""} → ${c.constructor}"))
    val law = pairs.forall(p => { val ws = Organ.words(p.question, lang(p.language)); organ.forward(ws) == organ.interpret(ws) })
    println(s"  the law (the block equals the interpreter) on ${pairs.size} pairs: $law")
    val byOrgan = Code.ByOrgan(organ, lang)
    for (name, reader) <- Vector("the rules alone" -> Code.rules, "the rules, then the organ" -> Code.first(Code.rules, byOrgan),
        "the organ, then the rules" -> Code.first(byOrgan, Code.rules), "the organ alone" -> byOrgan) do
      val m = Kit.measure(Code)(s, test, reader, Code.copier)
      println(s"$name: ${m.text}")
    // the symbol misses, to see
    val misses = test.flatMap { p => val got = Code.copier.copy(p.question, s, Code.slots(s)(1)); Option.when(!got.contains(p.point.symbol.symbol))((p.question, p.point.symbol.qualified, got.flatMap(s.bySymbol.get).map(_.qualified))) }
    println(s"  symbol misses ${misses.size}: " + misses.take(8).map((q, want, got) => s"[$q → want $want, got ${got.getOrElse("none")}]").mkString(" "))
    // the natural questions about this codebase, read by the organ then the rules, answered by the engine
    val reader = Code.first(byOrgan, Code.rules)
    val natural = Vector(
      "What breaks if I change Fill.exact?", "Which tests cover Strategy.greedyIn?", "Who implements Rules?",
      "Where is Dialogue.read defined?", "What does Organ.induce call?", "Which module is OrganBench in?",
      "Что сломается, если изменить Weigh.pick?", "Какие тесты покрывают Dialogue.decided?", "Кто вызывает Den.run?",
      "Где определён Claims.of?", "Что такое Organs: класс или объект?", "Что переопределяет Grammar.next?")
    println("natural questions about okay-desk itself:")
    for q <- natural do
      val r = Kit.read(Code)(s, q, reader, Code.copier)
      val shown = r.point.map(p => Code.run(p, s).fold(e => s"error $e", a => s"${p.rel} ${p.symbol.qualified} → ${a.size}: ${Code.show(a, s).take(6).mkString(", ")}${if a.size > 6 then ", …" else ""}"))
      println(s"  $q\n    ${shown.getOrElse(s"not read: ${r.parts}")}")
