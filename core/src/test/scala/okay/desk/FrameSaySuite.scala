package okay.desk

import okay.desk.tasks.Everyday

/** THE CANONICAL WORDS (specs/guarantees.md §3.2–3.3): every frame of the option space said and read back to itself */
class FrameSaySuite extends munit.FunSuite:
  /** a frame drawn by walking the options at random, from a question with numbers, a month and a date in it */
  private def walk(tables: Map[String, Table], rnd: scala.util.Random): Vector[String] =
    val question = "in March 2026, more than 20 or 3, since 2026-02-01"
    var lines = Vector.empty[String]
    while !lines.lastOption.contains(Frame.end) && lines.size < 12 do
      val options = Frame.next(lines, tables, question)
      lines = lines :+ (if options.isEmpty then Frame.end else options(rnd.nextInt(options.size)))
    if lines.last == Frame.end then lines else lines :+ Frame.end

  test("every frame drawn over the measure's and the domains' tables: said and read back to itself, en and ru") {
    val rnd = scala.util.Random(7)
    val tableSets = Everyday.tables.values.toVector.map(t => Map(t.name -> t)) ++
      (1 to 40).map(s => { val t = Domains.any(scala.util.Random(s)); Map(t.name -> t) })
    val problems = for
      tables <- tableSets; _ <- 1 to 60; lang <- Vector("en", "ru")
      frame = walk(tables, rnd)
      said = FrameSay.say(frame, lang)
      back = FrameSay.parse(said, lang)
      if back != Right(frame)
    yield s"$lang: ${frame.mkString(" | ")}\n  said: $said\n  read: $back"
    assert(problems.isEmpty, problems.take(5).mkString("\n"))
  }

  test("the words of a share and a change, as a person reads them") {
    val share = Vector("table: expenses", "answer: share sum amount", "part: category = transport", Frame.end)
    assertEquals(FrameSay.say(share, "en"), "table «expenses»; share of the sum of «amount»; the part where " +
      "«category» = «transport»")
    assertEquals(FrameSay.say(share, "ru"), "таблица «expenses»; доля: сумма «amount»; часть, где «category» = " +
      "«transport»")
    val change = Vector("table: sales", "answer: change sum revenue", "from: date month 2026-01",
      "to: date month 2026-03", "group: product", "order: answer desc", "limit: 1", Frame.end)
    assertEquals(FrameSay.say(change, "en"), "table «sales»; change of the sum of «revenue»; from «date» in month " +
      "«2026-01»; to «date» in month «2026-03»; for each «product»; sorted by the answer descending; first «1»")
  }
