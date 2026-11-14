package okay.desk

/** HOW THE QUESTION WAS UNDERSTOOD, ITS LAWS: the mistakes it must show are visible in its words (a count where a sum
 * was meant, a day where a month was), a month is said as one, and every gold of the measures is said in both
 * languages with no part left unsaid */
class UnderstoodSuite extends munit.FunSuite:
  private def said(json: String, ru: Boolean = false) = Understood(Query.parse(json).toOption.get, ru)

  test("a sum and a count are told apart") {
    assertEquals(said("""{"from": "expenses", "where": [{"column": "category", "op": "=", "value": "groceries"}],
      "aggregate": [{"fn": "sum", "column": "amount", "as": "total"}]}"""),
      "the sum of «amount» — where «category» = groceries")
    assertEquals(said("""{"from": "expenses", "aggregate": [{"fn": "count", "column": "*", "as": "n"}]}""", ru = true),
      "число строк «expenses»")
  }
  test("a month is said as one") {
    assertEquals(said("""{"from": "expenses", "where": [{"column": "date", "op": ">=", "value": "2026-03-01"},
      {"column": "date", "op": "<", "value": "2026-04-01"}], "aggregate": [{"fn": "sum", "column": "amount", "as": "t"}]}""",
      ru = true), "сумма «amount» — где «date» в марте 2026")
  }
  test("groups, order and limit") {
    assertEquals(said("""{"from": "expenses", "group": ["merchant"], "aggregate": [{"fn": "sum", "column": "amount",
      "as": "total"}], "order": ["-total"], "limit": 3}"""),
      "the sum of «amount» — for each «merchant» — sorted by «total», largest first — the first 3")
  }
  test("every gold of the measures is said in both languages") {
    val golds = tasks.Everyday.questions.flatMap(_.gold) ++ Sample.questions.map(_.gold)
    for g <- golds; ru <- Vector(false, true) do
      val s = said(g, ru)
      assert(s.nonEmpty && !s.contains("?"), s"$g → $s")
  }
