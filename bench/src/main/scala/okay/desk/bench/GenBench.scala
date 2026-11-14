package okay.desk.bench

import mlx.llm.{Chat, Lora, Model, Models, Session}
import okay.desk.*
import okay.desk.tasks.Everyday

/**
 * THE READER THAT WRITES, MEASURED (specs/direction.md §6): a small model (`GEN_MODEL`, the released Qwen3 0.6B by
 * default) with its adapter (`GEN_ADAPTER`, from `Train`) writes the program for each `Everyday` question from the
 * page's prompt, greedy — free, and masked by the grammar (`GEN_MASKED=1`, the port `DeskBench` uses) — and the exact
 * engine runs it. Scored as `CeilingBench` scores the teacher: right, asked (no program that runs; for a question
 * outside the language that is right), wrong.
 */
object GenBench:
  def main(args: Array[String]): Unit =
    val repo = sys.env.getOrElse("GEN_MODEL", "mlx-community/Qwen3-0.6B-4bit")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true); val tok = chat.tokenizer
    val adapter = sys.env.get("GEN_ADAPTER").map(f => Lora.attach(model.asInstanceOf[mlx.llm.Adaptable], f))
    println(s"$repo${adapter.fold("")(_ => " with " + sys.env("GEN_ADAPTER"))}")
    val end = tok.id("<|im_end|>")
    def msg(role: String, content: String) = okay.effects.json.Json.obj("role" -> okay.effects.json.Json.Str(role),
      "content" -> okay.effects.json.Json.Str(content))
    val masks = sys.env.get("GEN_MASKED").fold(Seq(false))(m => if m == "both" then Seq(false, true) else Seq(m == "1"))
    for masked <- masks do
      final case class Count(right: Int = 0, asked: Int = 0, wrong: Int = 0)
      var counts = Map.empty[(String, String), Count].withDefaultValue(Count())
      val t0 = System.nanoTime()
      for q <- Everyday.questions; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
        val tables = Map(q.table -> Everyday.tables(q.table))
        val prompt = chat.prompt(Vector(msg("system", Prompt.system(tables)), msg("user", text)), Nil,
          Map("enable_thinking" -> false))
        val port = if masked then Grammar(tok, tables, end) else null
        val s = Session(model, prompt, 400, chat.stops, port = port)
        val answer =
          try
            val toks = Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
            if masked then "```json\n" + port.out.toString + "\n```" else tok.decode(toks)
          finally s.close()
        val ran = Prompt.program(answer).map(p => p -> Everyday.run(p))
        val got = ran.toOption.flatMap((p, r) => r.toOption.map(p -> _))
        val why = ran.toOption.flatMap((p, r) => r.left.toOption.map(e => s" [$e] " + p.replaceAll("\\s+", " ")))
        val gold = q.gold.flatMap(Everyday.run(_).toOption)
        val outcome = (gold, got) match
          case (_, None) => if q.gold.isEmpty then "right" else "asked"
          case (None, Some(_)) => "WRONG (outside)"
          case (Some(want), Some((_, t))) => if Sample.same(t, want) then "right" else "WRONG"
        val c = counts((q.table, lang))
        counts = counts.updated((q.table, lang),
          if outcome == "right" then c.copy(right = c.right + 1)
          else if outcome == "asked" then c.copy(asked = c.asked + 1) else c.copy(wrong = c.wrong + 1))
        val said = got.fold("(asked)" + why.getOrElse(" " + answer.take(80).replaceAll("\\s+", " ")))(
          _._1.replaceAll("\\s+", " ").take(160))
        println(f"  [${q.table}%-8s $lang] $outcome%-15s $text → $said")
      val seconds = (System.nanoTime() - t0) / 1e9 / (2 * Everyday.questions.size)
      println(f"the writing reader${if masked then ", masked" else ""}, $seconds%.1f s a question:")
      for t <- Everyday.tables.keys.toVector.sorted do
        val (en, ru) = (counts((t, "en")), counts((t, "ru")))
        println(f"  $t%-9s en ${en.right}%2d / ${en.asked}%2d / ${en.wrong}%2d   " +
          f"ru ${ru.right}%2d / ${ru.asked}%2d / ${ru.wrong}%2d")
      for lang <- Vector("en", "ru") do
        val c = counts.collect { case ((_, l), c) if l == lang => c }
        println(s"total $lang: right ${c.map(_.right).sum}, asked ${c.map(_.asked).sum}, wrong ${c.map(_.wrong).sum} " +
          s"of ${Everyday.questions.size}")
    model.close()
