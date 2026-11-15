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
