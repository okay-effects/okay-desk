package okay.desk.bench

import mlx.llm.{Chat, Model, Models}
import okay.desk.*
import okay.desk.calc.*
import okay.desk.learn.Author
import okay.desk.organ.{Organ, Organs, Template}
import okay.desk.skill.Read

/**
 * THE MODEL AUTHORS ORGANS (specs/organs.md §3 v1, BACKLOG B 12): on the teacher's pairs the data alone misreads or
 * leaves open, the model (through `Read`, on MLX or the CPU — `DESK_DEVICE`) is asked for a cue per hole of the gold
 * the data did not decide; each proposal is checked by the law and by the oracle (the other pairs); the accepted
 * cues are an organ measured on the held-out half beside the INDUCED organ (the reference). The number: how many
 * misreadings the model's own cues fix, against the induction's.
 * `TEACHER_PAIRS=<file> DESK_DEVICE=mlx sbt "bench/runMain okay.desk.bench.AuthorBench"`.
 */
object AuthorBench:
  def main(args: Array[String]): Unit =
    val path = java.nio.file.Path.of(sys.env.getOrElse("TEACHER_PAIRS", "bench/data/teacher-pairs.jsonl"))
    val taught = TeacherBench.load(path)
    val budget = sys.env.getOrElse("AUTHOR_MAX", "60").toInt
    val schemas = scala.collection.mutable.Map.empty[Long, (Map[String, Table], Space.Schema, Grammar)]
    def of(seed: Long) = schemas.getOrElseUpdate(seed, { val t = Teacher.schema(seed); val s = Space.Schema(t); (t, s, new Grammar(s)) })
    def language(code: String): Language = Languages.load(code).getOrElse(Language.En.language)
    val withGold = taught.flatMap { t => val (_, s, _) = of(t.seed); Query.parse(t.program).toOption.flatMap(q => OfQuery(s, q)).map(p => (t, Den.term(s, p))) }
    val (train, test) = withGold.partition(_._1.seed % 2 == 0)
    val pairsOf: Map[String, Vector[(Vector[String], Template)]] = train.groupBy(_._1.lang).view.mapValues(_.map((t, g) => (Organ.words(t.question, language(t.lang)), Organs.templateOf(g)))).toMap
    val induced: Map[String, Organ] = pairsOf.map((lang, ps) => lang -> Organ.induce(s"induced-$lang", ps))
    val repo = sys.env.getOrElse("DESK_MODELS", "local/Qwen3-0.6B-4bit-cut19-no-free-ru-m4")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo: not in the cache"))
    val layer = sys.env.getOrElse("DESK_LAYER", "19").toInt
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[mlx.llm.Qwen3]
    val reading: okay.desk.skill.Reading = ComputeReading.device.fold(MlxReading(chat, model, layer))(c => ComputeReading(dir, layer, c))
    // the holes the data alone did not decide on the training half: what the model is asked about
    var asked = 0; var accepted = 0
    val proposals = scala.collection.mutable.ArrayBuffer.empty[Author.Proposal]
    for (t, gold) <- train.take(budget * 4) if asked < budget do
      val (tables, s, g) = of(t.seed)
      val lang = language(t.lang)
      val ctx = Context(s, t.question, Term.Hole(Ty.Program), languages = Languages.forQuestion(t.question))
      val (steps, term) = Strategy.greedy(g, ctx, Fill.exact[okay.scala3.Pure])
      val wrongOrOpen = term.isEmpty || term.exists(_ != gold)
      if wrongOrOpen then
        // the first hole where the data's reading and the gold part
        val goldT = Organs.templateOf(gold)
        val got = term.map(Organs.templateOf).getOrElse(Template(Map.empty))
        val diverging = goldT.parts.toVector.sortBy(_._1).find((k, v) => got.parts.get(k) != Some(v))
        for (path, con) <- diverging do
          asked += 1
          val candidates = steps.collectFirst { case (ty, at) if Organs.key(Organs.pathOf(at)) == path => g.constructors(ty) }.getOrElse(Vector(con))
          val others = pairsOf(t.lang).filterNot(_._1 == Organ.words(t.question, lang))
          val p = Read.run(Author.propose[Read](tables, t.question, lang, path, con, candidates, others), reading)
          proposals += p
          if p.accepted then accepted += 1
          println(s"  [${t.lang}] ${if p.accepted then "ACCEPTED" else "rejected"} $path=$con for «${t.question}»: ${p.raw.take(100)} — ${p.why}")
    println(s"the model asked for $asked cues, $accepted accepted by the law and the oracle")
    val authored: Map[String, Organ] = proposals.groupBy(p => taught.find(_.question == p.question).map(_.lang).getOrElse("en")).map((lang, ps) => lang -> Author.organ(ps.toVector, s"authored-$lang"))
    type S[X] = Strategy.Rest[Organs][X]
    for (name, organs) <- Vector("the induced organ, then the data" -> induced, "the model's authored organ, then the data" -> authored) do
      var right = 0; var open = 0; var wrong = 0
      for (t, gold) <- test do
        val (_, s, g) = of(t.seed)
        val ctx = Context(s, t.question, Term.Hole(Ty.Program), languages = Languages.forQuestion(t.question))
        val fill = Fill.first[S](Organs.fill[S](organs.keys.map(l => s"${organs(l).name}").toVector), Fill.exact[S])
        val term = okay.scala3.run(okay.scala3.handle(Strategy.greedyIn[Organs](g, ctx, fill), Organs.byWeights[okay.scala3.Pure, (List[Strategy.Step], Option[Term])](organs.values.toVector)))._2
        val want = Den.run(s, gold).toOption.map(_._2); val got = term.flatMap(x => Den.run(s, x).toOption.map(_._2))
        if got.exists(r => want.exists(w => Sample.same(r, w))) then right += 1 else if term.isEmpty then open += 1 else wrong += 1
      println(s"$name: right $right, open $open, wrong $wrong of ${test.size}")
