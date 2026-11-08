package okay.desk.bench

import okay.desk.calc.Language
import okay.desk.code.Code
import okay.effects.json.{Json, JsonText}
import okay.inlay.domain.Kit

/**
 * THE BOOK ABOUT ITSELF (BACKLOG D 24; the operator: «книгу о себе»): the documentation of okay-desk read against
 * its own index. Every sentence of the docs and specs that names code (`Fill.exact`, `Strategy.program`, …) and
 * says a relation (calls, implements, tests, defined in) is read by the code organ into a point; the engine
 * answers; the sentence's OTHER named symbols are looked for in the answer. Verified: the claim holds in the code;
 * contradicted: it does not (the doc has drifted); unread: the sentence says no relation the organ knows. A model
 * that reads its own book this way learns what it can check, and finds where the book is wrong.
 * `sbt "bench/runMain okay.desk.bench.SelfRead docs/calculus.md specs/calculus.md specs/pilot.md specs/organs.md"`.
 */
object SelfRead:
  def main(args: Array[String]): Unit =
    val dir = java.nio.file.Path.of(sys.env.getOrElse("CODE_FACTS", s"${sys.props("user.home")}/.cache/okay-desk/code"))
    val relations = Vector("defined", "kind", "named", "defined_in", "in_module", "calls", "extends", "overrides", "test").map { r =>
      val rows = JsonText.parse(java.nio.file.Files.readString(dir.resolve(s"$r.json"))).toOption.flatMap(_.field("answers")).collect { case Json.Arr(xs) => xs.toVector }.getOrElse(Vector.empty)
        .collect { case Json.Obj(fs) => fs.toVector.sortBy(_._1).collect { case (_, Json.Str(v)) => v } }
      r -> (r match { case "kind" | "defined_in" | "in_module" | "named" => rows.map(xs => Vector(xs(1), xs(0))); case _ => rows })
    }.toMap
    val s = Code.schema(relations)
    def lang(code: String): Language = Languages.load(code).getOrElse(Language.En.language)
    val pairs = Kit.pairs(Code)(s, 1200, 11, Vector("en", "ru"))
    val organ = Code.induce(pairs, lang)
    // (prose is not a question: the organ, induced from questions, over-fires on a sentence that lists names; the
    // hand-written relation words are the stricter reader here — SELF_READER=organ to compare)
    val reader = if sys.env.get("SELF_READER").contains("organ") then Code.first(Code.ByOrgan(organ, lang), Code.rules) else Code.rules
    val Named = "`([A-Z][\\w.]*)`".r
    var verified = 0; var contradicted = 0; var unread = 0; var noClaim = 0
    for file <- (if args.isEmpty then Array("docs/calculus.md") else args) do
      val text = java.nio.file.Files.readString(Bench.root.resolve(file))
      val sentences = text.replaceAll("\n+", " ").split("(?<=[.!?])\\s+").toVector
      for sentence <- sentences do
        val names = Named.findAllMatchIn(sentence).map(_.group(1)).toVector.distinct
        if names.size >= 2 then
          val bare = sentence.replace("`", "")
          val r = Kit.read(Code)(s, bare, reader, Code.copier)
          r.point match
            case None => unread += 1
            case Some(p) =>
              val answer = Code.run(p, s).getOrElse(Vector.empty)
              val shown = Code.show(answer, s).toSet ++ answer.toSet
              val others = names.filterNot(n => n == p.symbol.qualified || n == p.symbol.short)
              val found = others.filter(o => shown.exists(a => a == o || a.endsWith("." + o) || o.endsWith("." + a)))
              if others.isEmpty then noClaim += 1
              else if found.nonEmpty then { verified += 1; println(s"  verified   [${p.rel} ${p.symbol.qualified} ∋ ${found.mkString(", ")}]  ${sentence.take(140)}") }
              else { contradicted += 1; println(s"  not found  [${p.rel} ${p.symbol.qualified} ∌ ${others.mkString(", ")}]  ${sentence.take(140)}") }
    println(s"the book about itself: verified $verified, not found in the code $contradicted, no checkable claim $noClaim, unread $unread")
