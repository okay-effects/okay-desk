package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.desk.*
import okay.desk.calc.*
import okay.desk.skill.Read

/**
 * THE CALCULUS ON REAL DATA (specs/calculus.md §14): RealBench's hand-written GDP questions (a sibling agent's, §28)
 * read by the calculus — the data first, the model's own tokens next, no trained reader — on the JVM by the page's
 * program (`DESK_DEVICE=mlx|cpu`) or mlx-scala's forward. The outcomes as RealBench counts them: right (the result
 * equals a gold's), refused (an open term), wrong. The data-alone arm apart. The calculus' dictionary is the
 * generator's (`Phrase.Russian`) until the sibling's `Words` (the data's own) is committed — Russian is expected
 * to read fewer; the number says how many.
 */
object RealRefine:
  def main(args: Array[String]): Unit =
    val path = sys.env.getOrElse("DESK_GDP", s"${sys.props("user.home")}/.cache/okay-desk/real/gdp.csv")
    val gdp = Table.parse("gdp.csv", String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)), "UTF-8")).fold(e => sys.error(e), identity)
    val tables = Map(gdp.name -> gdp)
    val s = Space.Schema(tables); val g = new calc.Grammar(s)
    println(s"gdp: ${gdp.size} rows; the space's links ${s.links}, kinds ${s.cols.map(c => s"${c.name}:${c.kind}").mkString(" ")}")
    def run(q: Query): Either[String, Table] = q match
      case r: Query.Relational => Exact.run(tables, r)
      case l: Query.Logical => Logic.run(tables, l)
    val golds = RealBench.questions.map(_.golds.map(gold => Query.parse(gold).flatMap(run).toOption))
    val repo = sys.env.getOrElse("DESK_MODELS", "local/Qwen3-0.6B-4bit-cut19-no-free-ru-m4")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo: not in the cache"))
    val layer = sys.env.getOrElse("DESK_LAYER", "19").toInt
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[Qwen3]
    val reading: okay.desk.skill.Reading = ComputeReading.device.fold(MlxReading(chat, model, layer))(c => { println(s"  reading by the page's program on ${c.api}"); ComputeReading(dir, layer, c) })
    // the data's own words (§29) when DESK_WORDS names their file; the readers as a source when DESK_SOURCE=readers
    val words = sys.env.get("DESK_WORDS").map(p => okay.desk.skill.Words.parse(java.nio.file.Files.readString(java.nio.file.Path.of(p))).fold(e => sys.error(e), identity)).getOrElse(okay.desk.skill.Words.none)
    if !words.isEmpty then println(s"  the data's own words: ${words.tables.size} tables, ${words.columns.size} columns, ${words.values.size} values")
    val readers: Option[okay.desk.skill.Readers] = Option.when(sys.env.get("DESK_SOURCE").contains("readers")) {
      val on = MlxReading.ready(chat, model, layer, repo, sys.error("no skill file")); on.readers.words = words; println("  the readers' skill as a Weigh source"); on.readers }
    def refine(text: String, exactOnly: Boolean): Option[Term] =
      val fill: Fill[Read] = if exactOnly then Fill.exact[Read] else Fill.first[Read]((Vector(Fill.exact[Read]) ++ readers.map(_.weigh[Read]).toVector :+ Fill.model[Read](tables))*)
      Read.run(Strategy.greedyIn[Read](g, Context(s, text, Term.Hole(Ty.Program), words, Languages.forQuestion(text)), fill), reading)._2
    for arm <- Vector("the data alone", "the data, then the model's tokens") do
      val zero = Map("en" -> 0, "ru" -> 0); var right = zero; var refused = zero; var wrong = zero
      for (q, i) <- RealBench.questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
        val term = refine(text, exactOnly = arm == "the data alone")
        val got = term.flatMap(t => Den.run(s, t).toOption.map(_._2))
        val ok = got.exists(t => golds(i).flatten.exists(gold => Sample.same(t, gold)))
        val outcome = if ok then "right" else if term.isEmpty then "refused" else "WRONG"
        outcome match
          case "right" => right = right.updated(lang, right(lang) + 1)
          case "refused" => refused = refused.updated(lang, refused(lang) + 1)
          case _ => wrong = wrong.updated(lang, wrong(lang) + 1)
        if arm != "the data alone" || outcome == "WRONG" then
          println(f"  [$lang ${i + 1}%2d${if q.outside then " outside" else ""}] $outcome%-7s $text → ${term.map(_.show).getOrElse("(open)")}")
      println(s"$repo calculus, $arm | en: ${right("en")} right, ${refused("en")} refused, ${wrong("en")} wrong | ru: ${right("ru")} right, ${refused("ru")} refused, ${wrong("ru")} wrong (of ${RealBench.questions.size}, ${RealBench.questions.count(_.outside)} outside)")
    model.close()
