package okay.desk

/** the exact handlers: a sample of orders and customers; the programs the model would write; what they must give */
class ExactSuite extends munit.FunSuite:
  val orders = Table.parse("orders.csv",
    """id,customer,amount,status,day
      |1,ann,120,paid,2026-11-01
      |2,bob,80,unpaid,2026-11-02
      |3,ann,30.5,paid,2026-11-02
      |4,cid,200,unpaid,2026-11-03
      |5,bob,"1,000",paid,2026-11-04
      |""".stripMargin).toOption.get
  val customers = Table.parse("customers.tsv", "name\tcity\nann\tKyiv\nbob\tLviv\ncid\tKyiv\n").toOption.get
  val tables = Map("orders" -> orders, "customers" -> customers)

  test("parsed: numbers as numbers, quotes kept, the schema said"):
    assertEquals(orders.columns, Vector("id", "customer", "amount", "status", "day"))
    assertEquals(orders.rows(2)(2), Value.Num(30.5))
    assertEquals(orders.rows(4)(2), Value.Str("1,000"))
    assert(orders.describe.contains("amount: text"), orders.describe) // one text value makes the column text

  test("count by status, unpaid first"):
    val q = Query.parse("""{"from": "orders", "group": ["status"], "aggregate": [{"fn": "count", "column": "*", "as": "n"},
      {"fn": "sum", "column": "amount", "as": "total"}], "order": ["status"]}""").toOption.get
    val r = Exact.run(tables, q.asInstanceOf[Query.Relational]).toOption.get
    assertEquals(r.columns, Vector("status", "n", "total"))
    assertEquals(r.rows.map(_.map(_.text)), Vector(Vector("paid", "3", "150.5"), Vector("unpaid", "2", "280")))

  test("where, order, limit; a join; an unknown column told"):
    val top = Query.parse("""{"from": "orders", "where": [{"column": "status", "op": "=", "value": "unpaid"}],
      "order": ["-amount"], "limit": 1, "select": ["customer", "amount"]}""").toOption.get
    assertEquals(Exact.run(tables, top.asInstanceOf[Query.Relational]).toOption.get.rows.map(_.map(_.text)), Vector(Vector("cid", "200")))
    val joined = Query.parse("""{"from": "orders", "join": {"table": "customers", "on": [["customer", "name"]]},
      "group": ["city"], "aggregate": [{"fn": "count", "column": "*", "as": "orders"}]}""").toOption.get
    assertEquals(Exact.run(tables, joined.asInstanceOf[Query.Relational]).toOption.get.rows.map(_.map(_.text)),
      Vector(Vector("Kyiv", "3"), Vector("Lviv", "2")))
    val bad = Query.parse("""{"from": "orders", "where": [{"column": "price", "op": ">", "value": 1}]}""").toOption.get
    assert(Exact.run(tables, bad.asInstanceOf[Query.Relational]).left.exists(_.contains("no column price")))

  test("datalog over the tables: who is in the same city as ann"):
    val q = Query.parse("""{"datalog": "same(X, Y) :- customers(X, C), customers(Y, C), X != Y.", "goal": "same(ann, Y)"}""").toOption.get
    val r = Logic.run(tables, q.asInstanceOf[Query.Logical]).toOption.get
    assertEquals(r.rows.map(_.map(_.text)), Vector(Vector("cid")))

  test("the answer's program is found; a summary with a number not in the result is dropped"):
    assertEquals(Prompt.program("Sure:\n```json\n{\"from\": \"orders\"}\n```"), Right("{\"from\": \"orders\"}"))
    assert(Prompt.program("There are no such files.").isLeft)
    val r = Table("result", Vector("n"), Vector(Vector(Value.Num(3))))
    assert(Prompt.faithful("There are 3 paid orders.", r))
    assert(!Prompt.faithful("There are 4 paid orders.", r))
    assert(Prompt.faithful("Exactly 3.0 of them.", r))
