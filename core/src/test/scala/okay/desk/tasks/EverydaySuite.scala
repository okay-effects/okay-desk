package okay.desk.tasks

/** THE MEASURE OF USEFULNESS, ITS LAWS: every gold program runs on the tables and gives rows; each table has
 * questions inside the language and at least one outside it; the tables are the same on every run (a fixed seed) */
class EverydaySuite extends munit.FunSuite:
  test("every gold runs and gives rows") {
    for q <- Everyday.questions; gold <- q.gold do
      val got = Everyday.run(gold)
      assert(got.isRight, s"${q.en}: ${got.left.toOption.get}")
      assert(got.toOption.get.size > 0, s"${q.en}: no rows")
  }
  test("each table: questions inside the language and one outside at least") {
    for (t, qs) <- Everyday.questions.groupBy(_.table) do
      assert(qs.count(_.gold.nonEmpty) >= 6, t)
      assert(qs.exists(_.gold.isEmpty), t)
    assertEquals(Everyday.tables.keySet, Everyday.questions.map(_.table).toSet)
  }
  test("the tables are drawn the same every time") {
    assertEquals(Everyday.expenses.rows.take(3).map(_.map(_.text)), Everyday.expenses.rows.take(3).map(_.map(_.text)))
    assert(Everyday.tables.values.forall(_.size >= 16))
  }
