package okay.desk

import okay.desk.tasks.Everyday

/** THE FRAME (specs/frames.md): the everyday questions each reachable by choices, compiled to the gold's answer */
class FrameSuite extends munit.FunSuite:
  private def reachable(lines: Vector[String], tables: Map[String, Table], question: String): Option[String] =
    lines.indices.collectFirst { case i if !Frame.next(lines.take(i), tables, question).contains(lines(i)) =>
      s"${lines(i)} not among ${Frame.next(lines.take(i), tables, question).take(12).mkString(" | ")}" }


  test("every everyday question inside the language: a frame reachable by the options, the gold's answer") {
    val problems = Everyday.questions.filter(_.gold.nonEmpty).flatMap { q =>
      val tables = Map(q.table -> Everyday.tables(q.table))
      val gold = Everyday.run(q.gold.get).toOption.get
      val frame = Everyday.frame(q)
      frame match
        case None => Vector(s"${q.en}: no frame")
        case Some(lines) =>
          reachable(lines, tables, q.en).map(p => s"${q.en}: $p").toVector ++
            (Frame.query(lines, tables).flatMap(Exact.run(tables, _)) match
              case Right(t) if Sample.same(t, gold) => Vector.empty
              case Right(t) => Vector(s"${q.en}: ${t.render(5)}\n  gold ${gold.render(5)}")
              case Left(e) => Vector(s"${q.en}: $e"))
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("a question outside: cannot, then the end") {
    val t = Map("expenses" -> Everyday.tables("expenses"))
    assert(Frame.next(Vector("table: expenses"), t, "Why?").contains("answer: cannot"))
    assertEquals(Frame.next(Vector("table: expenses", "answer: cannot"), t, "Why?"), Vector(Frame.end))
    assert(Frame.query(Vector("table: expenses", "answer: cannot", "end"), t).isLeft)
  }

  test("a number said in words is an option: three largest, «три самых», «п'ять»") {
    val t = Map("expenses" -> Everyday.tables("expenses"))
    val so = Vector("table: expenses", "answer: rows", "order: amount desc")
    assert(Frame.next(so, t, "Show the three largest expenses").contains("limit: 3"))
    assert(Frame.next(so, t, "Покажи три самых больших расхода").contains("limit: 3"))
    assert(Frame.next(so, t, "Покажи п'ять найбільших").contains("limit: 5"))
  }

  test("a date is no number, «топ-3» is 3: the forms FrameLaws found unoffered on the never-trained questions") {
    assertEquals(Frame.numbers("Покажи топ-3 мероприятия"), Vector("3"))
    assert(!Frame.numbers("Покажите лекарства от 11.01.2026").contains("11.01"))
    assert(!Frame.numbers("Ile było podróży 10 marca 2026 roku?").contains("10"))
    assertEquals(Frame.numbers("Transactions over 500, top 3"), Vector("500", "3"))
    val t = Table.parse("m.csv", "date,cost\n2026-01-11,5\n2026-03-10,7\n").toOption.get.copy(name = "m")
    val tables = Map("m" -> t)
    val so = Vector("table: m", "answer: rows")
    assert(Frame.next(so, tables, "Покажите записи от 11.01.2026").contains("where: date = 2026-01-11"))
    assert(Frame.next(so, tables, "Ile było 10 marca 2026?").contains("where: date = 2026-03-10"))
    assert(Frame.next(so, tables, "Что было 10 марта?").contains("where: date = 2026-03-10"))
    assert(Frame.next(so, tables, "What happened on March 10?").contains("where: date = 2026-03-10"))
  }
