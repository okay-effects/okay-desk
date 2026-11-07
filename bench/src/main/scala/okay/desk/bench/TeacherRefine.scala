package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.desk.*
import okay.desk.calc.*
import okay.desk.skill.Read

/**
 * THE CALCULUS ON THE TEACHER'S PAIRS (specs/calculus.md §15): natural wording in en, ru, uk, judged by the engine
 * (specs/teacher.md, a sibling agent's), read by the calculus — the data, the readers when DESK_SOURCE=readers, the
 * model's tokens — and compared with the gold by RealBench's parts and by the result. By language: right / open /
 * wrong; the data-alone arm apart. TEACHER_PAIRS the file (default data/teacher-pairs.jsonl), all the pairs: the
 * calculus trains on none of them (the readers, when used, were trained on the grammar's pairs only).
 */
object TeacherRefine:
  def main(args: Array[String]): Unit =
    val path = java.nio.file.Path.of(sys.env.getOrElse("TEACHER_PAIRS", "data/teacher-pairs.jsonl"))
    val taught = TeacherBench.load(path)
    println(s"teacher pairs: ${taught.size}; by language ${taught.groupBy(_.lang).map((l, ps) => s"$l ${ps.size}").mkString(", ")}")
    val repo = sys.env.getOrElse("DESK_MODELS", "local/Qwen3-0.6B-4bit-cut19")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo: not in the cache"))
    val layer = sys.env.getOrElse("DESK_LAYER", "19").toInt
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[Qwen3]
    val reading: okay.desk.skill.Reading = ComputeReading.device.fold(MlxReading(chat, model, layer))(c => { println(s"  reading by the page's program on ${c.api}"); ComputeReading(dir, layer, c) })
    val readers: Option[okay.desk.skill.Readers] = Option.when(sys.env.get("DESK_SOURCE").contains("readers")) {
      val on = MlxReading.ready(chat, model, layer, repo, sys.error("no skill file")); println("  the readers' skill as a Weigh source"); on.readers }
    def run(tables: Map[String, Table], q: Query): Either[String, Table] = q match
      case r: Query.Relational => Exact.run(tables, r)
      case l: Query.Logical => Logic.run(tables, l)
    for arm <- Vector("the data alone", "the sources in order") do
      val langs = Vector("en", "ru", "uk")
      val zero = langs.map(_ -> 0).toMap; var right = zero; var open = zero; var wrong = zero
      val byKey = scala.collection.mutable.Map.empty[String, (Int, Int)].withDefaultValue((0, 0))
      for t <- taught do
        val tables = Teacher.schema(t.seed); val s = Space.Schema(tables); val g = new calc.Grammar(s)
        val fill: Fill[Read] = if arm == "the data alone" then Fill.exact[Read] else Fill.first[Read]((Vector(Fill.exact[Read]) ++ readers.map(_.weigh[Read]).toVector :+ Fill.model[Read](tables))*)
        val term = Read.run(Strategy.greedyIn[Read](g, Context(s, t.question, Term.Hole(Ty.Program)), fill), reading)._2
        val gold = Query.parse(t.program).flatMap(q => run(tables, q)).toOption
        val got = term.flatMap(x => Den.run(s, x).toOption)
        val ok = got.exists((_, r) => gold.exists(gd => Sample.same(r, gd)))
        if ok then right = right.updated(t.lang, right(t.lang) + 1)
        else if term.isEmpty then open = open.updated(t.lang, open(t.lang) + 1)
        else
          wrong = wrong.updated(t.lang, wrong(t.lang) + 1)
          if wrong.values.sum <= 12 then println(s"  [${t.lang} ${t.seed}] WRONG ${t.question} → ${term.map(_.show).getOrElse("")} (gold ${t.program.take(120)})")
        for (q, gp) <- got.map(_._1).map(RefineBench.parts) zip Query.parse(t.program).toOption.map(RefineBench.parts); k <- gp.keys ++ q.keys do
          val (r, n) = byKey(k); byKey(k) = (r + (if q.getOrElse(k, "-") == gp.getOrElse(k, "-") then 1 else 0), n + 1)
      println(s"$repo calculus on the teacher's pairs, $arm | " + langs.map(l => s"$l: ${right(l)} right, ${open(l)} open, ${wrong(l)} wrong of ${taught.count(_.lang == l)}").mkString(" | "))
      if arm != "the data alone" then println("  by constructor (closed readings): " + byKey.toVector.sortBy(_._1).map((k, v) => f"$k ${v._1 * 100.0 / math.max(v._2, 1)}%.0f%% (${v._2})").mkString(", "))
    model.close()
