package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.desk.*
import okay.desk.calc.*
import okay.desk.skill.{Pairs, Read}

/**
 * THE CALCULUS ON A MODEL (specs/calculus.md §6): refinement with the data first and the model's own tokens next
 * (the text tier — any model with logits), on the sample and on held-out pairs, compared with the gold by
 * constructor. `sbt "bench/runMain okay.desk.bench.RefineBench [repo] [held-out n]"`; DESK_LAYER for the reading.
 */
object RefineBench:
  def parts(q: Query): Map[String, String] = q match
    case Query.Relational(from, join, where, group, aggs, _, order, limit) =>
      val aliases = aggs.map(a => a.as -> a.fn).toMap
      Map("kind" -> "rows", "table" -> from, "join" -> join.map(_.table).getOrElse("-"),
        "where" -> where.map { case Cond.Cmp(c, op, v) => s"$c $op ${v.text}"; case w => w.toString }.sorted.mkString(";"),
        "group" -> group.mkString(","), "agg" -> aggs.map(a => s"${a.fn} ${a.column}").mkString(","),
        "order" -> order.map(o => s"${if o.desc then "-" else ""}${aliases.getOrElse(o.column, o.column)}").mkString(","), "limit" -> limit.fold("-")(_.toString))
    case Query.Logical(rules, goal) =>
      if rules.contains(" not ") then Map("kind" -> "absent", "rules" -> rules.replaceAll("\\s", ""))
      else Map("kind" -> "closure", "goal" -> goal.replaceAll("\\s", ""))

  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("local/Qwen3-0.6B-4bit-cut19")
    val n = args.lift(1).map(_.toInt).getOrElse(100)
    val layer = scala.sys.env.getOrElse("DESK_LAYER", "19").toInt
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[Qwen3]
    val reading: okay.desk.skill.Reading = ComputeReading.device.fold(MlxReading(chat, model, layer))(c => { println(s"  reading by the page's program on ${c.api}"); ComputeReading(dir, layer, c) })
    // DESK_SOURCE=readers: the trained readers as the source between the data and the model's tokens (the vector tier)
    val readers: Option[okay.desk.skill.Readers] = Option.when(sys.env.get("DESK_SOURCE").contains("readers")) {
      val on = MlxReading.ready(chat, model, layer, repo, sys.error("no skill file")); println("  the readers' skill as a Weigh source"); on.readers }
    def refine(tables: Map[String, Table], question: String, exactOnly: Boolean): Refine.Done =
      val s = Space.Schema(tables); val g = new calc.Grammar(s)
      val fill: Fill[Strategy.Rest[Read]] = if exactOnly then Fill.exact[Strategy.Rest[Read]] else Fill.first[Strategy.Rest[Read]]((Vector(Fill.exact[Strategy.Rest[Read]]) ++ readers.map(_.weigh[Strategy.Rest[Read]]).toVector :+ Fill.model[Strategy.Rest[Read]](tables))*)
      val (steps, term) = Read.run(Strategy.greedyIn[Read](g, Context(s, question, Term.Hole(Ty.Program)), fill), reading)
      term.fold(Refine.Done(steps.lastOption.map(_._2).getOrElse(Term.Hole(Ty.Program)), steps.map((ty, _) => (ty, "")).toVector, steps.lastOption.map((ty, _) => (ty, Vector.empty[String]))))(t => Refine.Done(t, Vector.empty, None))
    // the sample
    val s = Space.Schema(Sample.tables); val g = new calc.Grammar(s)
    var right = Map("en" -> 0, "ru" -> 0); var open = 0
    for (q, i) <- Sample.questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
      val done = refine(Sample.tables, text, exactOnly = false)
      val gold = Sample.run(Query.parse(q.gold).toOption.get).toOption.get
      if !g.closed(done.term) then { open += 1; println(s"  [$lang ${i + 1}] open: ${done.term.show} ?${done.unsure.map(_._1).getOrElse("")}") }
      else Den.run(s, done.term) match
        case Right((_, r)) if Sample.same(r, gold) => right = right.updated(lang, right(lang) + 1)
        case other => println(s"  [$lang ${i + 1}] ${done.term.show} → ${other.fold(e => e, (q, _) => Query.print(q))}")
    println(s"$repo refine (data, then the model's tokens) | en: ${right("en")}/13 | ru: ${right("ru")}/13 | open $open")
    // held-out pairs by constructor
    val held = Pairs.many(44, n)
    val keys = Vector("kind", "table", "join", "where", "group", "agg", "order", "limit", "goal", "rules")
    val rightK = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0); val total = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var whole = 0; var opened = 0; var exactWhole = 0; var exactOpen = 0
    val shown = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    for p <- held do
      val sch = Space.Schema(p.tables); val gr = new calc.Grammar(sch)
      val goldParts = Query.parse(p.program).toOption.map(parts).getOrElse(Map.empty)
      val ex = refine(p.tables, p.question, exactOnly = true)
      if gr.closed(ex.term) then
        if Den.run(sch, ex.term).toOption.map((q, _) => parts(q)) == Some(goldParts) then exactWhole += 1
        else println(s"    DATA WRONG: ${p.question} → ${ex.term.show} (gold ${p.program.take(160)})")
      else exactOpen += 1
      val done = refine(p.tables, p.question, exactOnly = false)
      if !gr.closed(done.term) then opened += 1
      else Den.run(sch, done.term).toOption.map((q, _) => parts(q)) match
        case Some(got) =>
          for k <- keys if got.contains(k) || goldParts.contains(k) do
            total(k) += 1
            if got.getOrElse(k, "-") == goldParts.getOrElse(k, "-") then rightK(k) += 1
            else if shown(k) < 3 then { shown(k) += 1; println(s"    $k: ${p.question} → got ${got.getOrElse(k, "-")}, gold ${goldParts.getOrElse(k, "-")}") }
          if keys.forall(k => got.getOrElse(k, "-") == goldParts.getOrElse(k, "-")) then whole += 1
        case None => ()
    println(f"  the data alone on $n held-out: closed and whole-right ${exactWhole * 100.0 / n}%.0f%%, open ${exactOpen * 100.0 / n}%.0f%% (never wrong is the law)")
    println(f"  data then the model's tokens on $n held-out: whole ${whole * 100.0 / n}%.1f%%, open $opened; " +
      keys.filter(total.contains).map(k => f"$k ${rightK(k) * 100.0 / total(k)}%.0f%% (${total(k)})").mkString(", "))
    model.close()
