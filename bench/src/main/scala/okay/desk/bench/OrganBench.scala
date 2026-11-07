package okay.desk.bench

import okay.desk.*
import okay.desk.calc.*
import okay.desk.organ.*
import okay.scala3.*

/**
 * THE FIRST EXPERIMENT (specs/organs.md §4): the teacher's pairs split by the seed's parity; organs induced per
 * language from the even half (the gold term's template by the question's words); on the odd half the data alone
 * against the data with the organs — right / open / wrong — the organs run by their WEIGHTS (the compiled block),
 * and the law checked on every pair (the weights equal the interpreter). No model, no weight of a model changed.
 * `TEACHER_PAIRS=<file> sbt "bench/runMain okay.desk.bench.OrganBench"`.
 */
object OrganBench:
  def main(args: Array[String]): Unit =
    val path = java.nio.file.Path.of(sys.env.getOrElse("TEACHER_PAIRS", "bench/data/teacher-pairs.jsonl"))
    val taught = TeacherBench.load(path)
    println(s"teacher pairs: ${taught.size}; by language ${taught.groupBy(_.lang).map((l, ps) => s"$l ${ps.size}").mkString(", ")}")
    val schemas = scala.collection.mutable.Map.empty[Long, (Map[String, Table], Space.Schema, Grammar)]
    def of(seed: Long) = schemas.getOrElseUpdate(seed, { val t = Teacher.schema(seed); val s = Space.Schema(t); (t, s, new Grammar(s)) })
    def language(code: String): Language = Languages.load(code).getOrElse(Language.En.language)
    // the gold terms
    val withGold = taught.flatMap { t =>
      val (_, s, _) = of(t.seed)
      Query.parse(t.program).toOption.flatMap(q => OfQuery(s, q)).map(p => (t, Den.term(s, p)))
    }
    println(s"  gold terms for ${withGold.size} of ${taught.size} pairs")
    val (train, test) = withGold.partition(_._1.seed % 2 == 0)
    // the organs: one per language, induced from the even half
    val organs: Map[String, Organ] = train.groupBy(_._1.lang).map { (lang, ps) =>
      val pairs = ps.map((t, g) => (Organ.words(t.question, language(lang)), Organs.templateOf(g)))
      val o = Organ.induce(s"teacher-$lang", pairs, minCover = sys.env.getOrElse("ORGAN_COVER", "2").toInt)
      println(s"  $lang: ${o.cues.size} cues over ${o.vocabulary.size} words from ${pairs.size} pairs, ${o.weights.params} parameters, ${o.paths.size} paths")
      lang -> o
    }
    // the law on every pair: the weights equal the interpreter
    var lawChecked = 0
    for (t, _) <- withGold; o <- organs.get(t.lang) do
      val ws = Organ.words(t.question, language(t.lang))
      require(o.forward(ws) == o.interpret(ws), s"the law: ${t.question}"); lawChecked += 1
    println(s"  the law held on $lawChecked pairs (the block equals the interpreter)")
    type S[X] = Strategy.Rest[Organs][X]
    def read(t: TeacherBench.Taught, fill: Fill[S]): Option[Term] =
      val (_, s, g) = of(t.seed)
      val ctx = Context(s, t.question, Term.Hole(Ty.Program), languages = Languages.forQuestion(t.question))
      val (_, term) = okay.scala3.run(handle(Strategy.greedyIn[Organs](g, ctx, fill), Organs.byWeights[Pure, (List[Strategy.Step], Option[Term])](organs.values.toVector)))
      term
    val arms: Vector[(String, String => Fill[S])] = Vector(
      "the data alone" -> (_ => Fill.exact[S]),
      "the organ, then the data" -> (lang => Fill.first[S](Organs.fill[S](Vector(s"teacher-$lang")), Fill.exact[S])),
      "the data, then the organ" -> (lang => Fill.first[S](Fill.exact[S], Organs.fill[S](Vector(s"teacher-$lang")))))
    val baseline = scala.collection.mutable.Map.empty[(Long, String), String]
    for (arm, fillOf) <- arms do
      val langs = Vector("en", "ru", "uk"); val zero = langs.map(_ -> 0).toMap
      var right = zero; var open = zero; var wrong = zero; var fixed = 0; var broken = 0
      for (t, gold) <- test do
        val (_, s, _) = of(t.seed)
        val term = read(t, fillOf(t.lang))
        val want = Den.run(s, gold).toOption.map(_._2)
        val got = term.flatMap(x => Den.run(s, x).toOption.map(_._2))
        val outcome = if got.exists(r => want.exists(w => Sample.same(r, w))) then "right" else if term.isEmpty then "open" else "wrong"
        outcome match
          case "right" => right = right.updated(t.lang, right(t.lang) + 1)
          case "open" => open = open.updated(t.lang, open(t.lang) + 1)
          case _ => wrong = wrong.updated(t.lang, wrong(t.lang) + 1)
        val key = (t.seed, t.question)
        if arm == "the data alone" then baseline(key) = outcome
        else
          if baseline(key) != "right" && outcome == "right" then fixed += 1
          if baseline(key) == "right" && outcome != "right" then broken += 1
      val line = langs.map(l => s"$l ${right(l)}/${open(l)}/${wrong(l)}").mkString("  ")
      println(s"$arm (right/open/wrong of ${test.size} held-out pairs): $line" +
        (if arm != "the data alone" then s"  — fixed $fixed, broken $broken against the data alone" else ""))
    // the law's second number: the same readings by the interpreter
    val byInterpreter = test.count { (t, _) =>
      val (_, s, g) = of(t.seed)
      val ctx = Context(s, t.question, Term.Hole(Ty.Program), languages = Languages.forQuestion(t.question))
      val fill = Fill.first[S](Organs.fill[S](Vector(s"teacher-${t.lang}")), Fill.exact[S])
      val a = okay.scala3.run(handle(Strategy.greedyIn[Organs](g, ctx, fill), Organs.byWeights[Pure, (List[Strategy.Step], Option[Term])](organs.values.toVector)))._2
      val b = okay.scala3.run(handle(Strategy.greedyIn[Organs](g, ctx, fill), Organs.byInterpreter[Pure, (List[Strategy.Step], Option[Term])](organs.values.toVector)))._2
      a == b
    }
    println(s"  the readings by the weights equal the readings by the interpreter on $byInterpreter of ${test.size}")
