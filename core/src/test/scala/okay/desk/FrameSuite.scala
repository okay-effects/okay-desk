package okay.desk

import okay.desk.tasks.Everyday

/** THE FRAME (specs/frames.md): the everyday questions each reachable by choices, compiled to the gold's answer */
class FrameSuite extends munit.FunSuite:
  private def reachable(lines: Vector[String], tables: Map[String, Table], question: String): Option[String] =
    lines.indices.collectFirst { case i if !Frame.next(lines.take(i), tables, question).contains(lines(i)) =>
      s"${lines(i)} not among ${Frame.next(lines.take(i), tables, question).take(12).mkString(" | ")}" }

  // the frames of the questions the language grew for, written by hand
  private val grown: Map[String, Vector[String]] = Map(
    "How did my spending change from January to February?" -> Vector("table: expenses",
      "answer: change sum amount", "from: date month 2026-01", "to: date month 2026-02", "end"),
    "What share of my spending went to transport?" -> Vector("table: expenses", "answer: share sum amount",
      "part: category = transport", "end"),
    "Which product's revenue grew the most from January to March?" -> Vector("table: sales",
      "answer: change sum revenue", "from: date month 2026-01", "to: date month 2026-03", "group: product",
      "order: answer desc", "limit: 1", "end"),
    "Which items are below their reorder level?" -> Vector("table: stock",
      "answer: rows", "where: quantity < column reorder_level", "end"),
    "Who worked more than 20 hours in a single week?" -> Vector("table: shifts", "answer: sum hours",
      "group: employee", "group: week(date)", "keep: answer > 20", "end"),
    "Whose average improved from the first term to the second?" -> Vector("table: marks",
      "answer: change avg mark", "from: term = 1", "to: term = 2", "group: student", "keep: answer > 0", "end"),
    "By how much did my balance change each month?" -> Vector("table: bank", "answer: change sum amount",
      "from: type = out", "to: type = in", "group: month(date)", "end"))

  test("every everyday question inside the language: a frame reachable by the options, the gold's answer") {
    val problems = Everyday.questions.filter(_.gold.nonEmpty).flatMap { q =>
      val tables = Map(q.table -> Everyday.tables(q.table))
      val gold = Everyday.run(q.gold.get).toOption.get
      val frame = grown.get(q.en).orElse(Query.parse(q.gold.get).toOption.flatMap(Frame.of(_, tables)))
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
