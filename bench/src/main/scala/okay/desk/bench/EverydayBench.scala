package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.desk.*
import okay.desk.calc.*
import okay.desk.skill.Read
import okay.desk.tasks.Everyday

/**
 * THE PAGE ON EVERYDAY TABLES (specs/technology.md §9): `Everyday`'s questions read as the page reads a question
 * about data — the calculus, its holes filled by the data first, the readers' skill next, the model's own tokens
 * last (`Engine.readByCalculus` without the person's answers and lessons) — over the page's core (the 12-layer
 * student, the teacher's skill file). Outcomes: right (the result equals the gold's), asked (an open term — the page
 * asks), wrong; for a question outside the language, asked is right and any answer is wrong. By table and language.
 * `sbt "bench/runMain okay.desk.bench.EverydayBench"`; DESK_MODELS, DESK_LAYER, DESK_SKILL to read another core.
 */
object EverydayBench:
  def main(args: Array[String]): Unit =
    // as a person uses it: the question's own table open (EVERYDAY_ALL=1: all six at once, the harder case)
    val all = sys.env.contains("EVERYDAY_ALL")
    def open(t: String): Map[String, Table] = if all then Everyday.tables else Map(t -> Everyday.tables(t))
    val repo = sys.env.getOrElse("DESK_MODELS", "local/Qwen3-0.6B-4bit-cut19-vocab-p12-m4")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo: not in the cache"))
    val layer = sys.env.getOrElse("DESK_LAYER", "12").toInt
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[Qwen3]
    val reading = MlxReading(chat, model, layer)
    val skill = okay.desk.skill.Readers()
    skill.load(Files.readAllBytes(Path.of(sys.env.getOrElse("DESK_SKILL",
      sys.props("user.home") + "/.cache/okay-desk/readers-Qwen3-0.6B-4bit-cut19-19.skill"))))
    // the page's margin: two candidates closer than ln 3 in log-probability are a question (Engine, `?margin=`)
    val margin = sys.env.get("DESK_MARGIN").map(_.toDouble).getOrElse(math.log(3))
    type S[X] = Strategy.Rest[Read][X]
    def fill(tables: Map[String, Table]): Fill[S] = Fill.first[S](Fill.exact[S], skill.weigh[S], Fill.model[S](tables))
    println(s"core $repo at $layer; ${Everyday.questions.size} questions over ${Everyday.tables.size} tables, " +
      (if all then "all open at once" else "each question with its own table"))
    final case class Count(right: Int = 0, asked: Int = 0, wrong: Int = 0):
      def +(o: String) = o match
        case "right" => copy(right = right + 1)
        case "asked" => copy(asked = asked + 1)
        case _ => copy(wrong = wrong + 1)
    var counts = Map.empty[(String, String), Count].withDefaultValue(Count())
    val t0 = System.nanoTime()
    for q <- Everyday.questions; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
      val tables = open(q.table)
      val s = Space.Schema(tables); val g = new Grammar(s)
      val term = Read.run(Strategy.greedyIn[Read](g, Context(s, text, Term.Hole(Ty.Program), okay.desk.skill.Words.none,
        Languages.forQuestion(text)), fill(tables), margin), reading)._2
      val got = term.flatMap(t => Den.run(s, t).toOption)
      val gold = q.gold.flatMap(Everyday.run(_).toOption)
      val outcome = (gold, got) match
        case (_, None) => if q.gold.isEmpty then "right" else "asked"
        case (None, Some(_)) => "WRONG (outside)"
        case (Some(want), Some((_, t))) => if Sample.same(t, want) then "right" else "WRONG"
      counts = counts.updated((q.table, lang), counts((q.table, lang)) + outcome)
      val said = got.fold("(asked)")((p, _) => Query.print(p).take(150))
      println(f"  [${q.table}%-8s $lang] $outcome%-15s $text → $said")
    val seconds = (System.nanoTime() - t0) / 1e9 / (2 * Everyday.questions.size)
    println(f"by table (right / asked / wrong), $seconds%.1f s a question:")
    for t <- Everyday.tables.keys.toVector.sorted do
      val (en, ru) = (counts((t, "en")), counts((t, "ru")))
      println(f"  $t%-9s en ${en.right}%2d / ${en.asked}%2d / ${en.wrong}%2d   " +
        f"ru ${ru.right}%2d / ${ru.asked}%2d / ${ru.wrong}%2d")
    for lang <- Vector("en", "ru") do
      val c = counts.collect { case ((_, l), c) if l == lang => c }
      println(s"total $lang: right ${c.map(_.right).sum}, asked ${c.map(_.asked).sum}, wrong ${c.map(_.wrong).sum} " +
        s"of ${Everyday.questions.size}")
    model.close()
