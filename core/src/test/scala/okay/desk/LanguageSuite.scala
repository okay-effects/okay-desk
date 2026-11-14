package okay.desk

/** THE QUERY LANGUAGE GROWN (specs/direction.md §2): each new form against a result computed by hand */
class LanguageSuite extends munit.FunSuite:
  private val t = Table.parse("t.csv",
    """date,kind,amount,limit
      |2026-01-05,food,10,15
      |2026-01-20,taxi,30,20
      |2026-02-02,food,25,15
      |2026-02-09,food,5,15
      |2026-02-10,taxi,30,50
      |""".stripMargin).toOption.get
  private def run(json: String): Either[String, Vector[Vector[String]]] =
    Query.parse(json).flatMap {
      case r: Query.Relational => Exact.run(Map("t" -> t), r).map(_.rows.map(_.map(_.text)))
      case _ => Left("relational expected")
    }

  test("an aggregate over its own rows, and a value computed from aggregates: a share of the total") {
    assertEquals(run("""{"from": "t", "aggregate": [{"fn": "sum", "column": "amount", "as": "food", "where":
      [{"column": "kind", "op": "=", "value": "food"}]}, {"fn": "sum", "column": "amount", "as": "all"}],
      "compute": [{"as": "share", "expr": "food / all"}], "select": ["share"]}"""), Right(Vector(Vector("0.4"))))
  }

  test("a change between two months: each month's sum, their difference") {
    assertEquals(run("""{"from": "t", "aggregate": [{"fn": "sum", "column": "amount", "as": "jan", "where":
      [{"column": "date", "op": "month", "value": "2026-01"}]}, {"fn": "sum", "column": "amount", "as": "feb",
      "where": [{"column": "date", "op": "month", "value": "2026-02"}]}], "compute": [{"as": "change",
      "expr": "feb - jan"}], "select": ["change"]}"""), Right(Vector(Vector("20"))))
  }

  test("grouped by a date's month and week; having on the result") {
    assertEquals(run("""{"from": "t", "group": ["month(date)"], "aggregate": [{"fn": "sum", "column": "amount",
      "as": "s"}]}"""), Right(Vector(Vector("2026-01", "40"), Vector("2026-02", "60"))))
    // weeks begin on Monday: 2026-02-02 and 2026-02-09 are Mondays
    assertEquals(run("""{"from": "t", "group": ["week(date)"], "aggregate": [{"fn": "count", "column": "*",
      "as": "n"}], "having": [{"column": "n", "op": ">", "value": 1}]}"""), Right(Vector(Vector("2026-02-09", "2"))))
  }

  test("a column against another of the same row") {
    assertEquals(run("""{"from": "t", "where": [{"column": "amount", "op": ">", "column2": "limit"}],
      "select": ["date"]}"""), Right(Vector(Vector("2026-01-20"), Vector("2026-02-02"))))
  }

  test("computed on plain rows; the expression's precedence, parentheses, a missing name told") {
    assertEquals(run("""{"from": "t", "where": [{"column": "kind", "op": "=", "value": "taxi"}],
      "compute": [{"as": "x", "expr": "(amount - limit) * 2 + 1"}], "select": ["x"]}"""),
      Right(Vector(Vector("21"), Vector("-39"))))
    assert(run("""{"from": "t", "compute": [{"as": "x", "expr": "amount / nothing"}]}""").left.exists(
      _.contains("nothing")))
  }

  test("the new forms printed and read back to the same program") {
    val json = """{"from": "t", "group": ["month(date)"], "aggregate": [{"fn": "sum", "column": "amount", "as": "a",
      "where": [{"column": "amount", "op": ">", "column2": "limit"}]}], "compute": [{"as": "b", "expr": "a * 2"}],
      "having": [{"column": "b", "op": ">", "value": 10}]}"""
    val q = Query.parse(json)
    assertEquals(q.flatMap(p => Query.parse(Query.print(p))), q)
  }

  test("an answer with the gold's column under another name, beside other columns, is the same") {
    val gold = Table("r", Vector("change"), Vector(Vector(Value.Num(30))))
    val got = Table("r", Vector("jan", "feb", "delta"), Vector(Vector(Value.Num(40), Value.Num(70), Value.Num(30))))
    assert(Sample.same(got, gold))
    assert(!Sample.same(Table("r", Vector("jan"), Vector(Vector(Value.Num(40)))), gold))
  }

  test("one of several values; a group's date part named as grouped in order and select") {
    assertEquals(run("""{"from": "t", "where": [{"column": "kind", "op": "in", "value": ["taxi", "bus"]}],
      "select": ["amount"]}"""), Right(Vector(Vector("30"), Vector("30"))))
    assertEquals(run("""{"from": "t", "group": ["month(date)"], "aggregate": [{"fn": "count", "column": "*",
      "as": "n"}], "order": ["-month(date)"], "select": ["month(date)", "n"]}"""),
      Right(Vector(Vector("2026-02", "3"), Vector("2026-01", "2"))))
  }
