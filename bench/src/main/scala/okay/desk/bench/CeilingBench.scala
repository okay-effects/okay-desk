package okay.desk.bench

import okay.desk.*
import okay.desk.tasks.Everyday

/**
 * THE CEILING OF THE FORMALIZATION (specs/direction.md): the same everyday questions (`Everyday`, en and ru) read
 * by the teacher (Qwen3.6-35B-A3B on the gateway) into the same query language, run by the same exact engine,
 * scored as `EverydayBench` scores the phone's model: right, asked (no program — for a question outside the language
 * that is right), wrong. What the big model reaches is what the language and the tools allow; the gap to the
 * phone's model is what training it can win. `TEACHER_URL`, `TEACHER_MODEL` as `Teacher`.
 */
object CeilingBench:
  def main(args: Array[String]): Unit =
    val m = Teacher.Model(sys.env.getOrElse("TEACHER_URL", "http://127.0.0.1:8090/v1"),
      sys.env.getOrElse("TEACHER_MODEL", "mlx-community/Qwen3.6-35B-A3B-4bit"))
    final case class Count(right: Int = 0, asked: Int = 0, wrong: Int = 0)
    var counts = Map.empty[(String, String), Count].withDefaultValue(Count())
    val t0 = System.nanoTime()
    for q <- Everyday.questions; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
      val tables = Map(q.table -> Everyday.tables(q.table))
      val system = Prompt.system(tables) + "\nIf the query language cannot say what is asked exactly, do not " +
        "approximate it: answer in one sentence that it cannot."
      val answer = m.ask(system, text, 600, temperature = 0)
      val ran = Prompt.program(answer).map(p => p -> Everyday.run(p))
      val got = ran.toOption.flatMap((p, r) => r.toOption.map(p -> _))
      // a program that did not run: why, for the tools' side (specs/direction.md §2)
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
      val said = got.fold("(asked)" + why.getOrElse(" " + answer.take(80)))(_._1.take(150))
      println(f"  [${q.table}%-8s $lang] $outcome%-15s $text → $said")
    println(f"the teacher as the reader, ${(System.nanoTime() - t0) / 1e9 / (2 * Everyday.questions.size)}%.1f s a question:")
    for t <- Everyday.tables.keys.toVector.sorted do
      val (en, ru) = (counts((t, "en")), counts((t, "ru")))
      println(f"  $t%-9s en ${en.right}%2d / ${en.asked}%2d / ${en.wrong}%2d   " +
        f"ru ${ru.right}%2d / ${ru.asked}%2d / ${ru.wrong}%2d")
    for lang <- Vector("en", "ru") do
      val c = counts.collect { case ((_, l), c) if l == lang => c }
      println(s"total $lang: right ${c.map(_.right).sum}, asked ${c.map(_.asked).sum}, wrong ${c.map(_.wrong).sum} " +
        s"of ${Everyday.questions.size}")
