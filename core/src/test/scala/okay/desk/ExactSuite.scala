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

/** growth and change: the last value against the first in the order of the time column, within each group */
class GrowthSuite extends munit.FunSuite:
  val gdp = Table.parse("gdp.csv", "country,year,value\nA,2021,100\nA,2020,80\nB,2020,50\nB,2021,40\nC,2020,10\n").toOption.get
  test("growth per country, the fastest first; change as a difference; one point gives nothing"):
    val q = Query.parse("""{"from": "gdp", "group": ["country"], "aggregate": [{"fn": "growth", "column": "value", "by": "year", "as": "growth"}], "order": ["-growth"]}""").toOption.get
    val r = Exact.run(Map("gdp" -> gdp), q.asInstanceOf[Query.Relational]).toOption.get
    assertEquals(r.rows.map(_.map(_.text)), Vector(Vector("A", "0.25"), Vector("B", "-0.2"), Vector("C", "")))
    val c = Query.parse("""{"from": "gdp", "group": ["country"], "aggregate": [{"fn": "change", "column": "value", "by": "year", "as": "d"}]}""").toOption.get
    assertEquals(Exact.run(Map("gdp" -> gdp), c.asInstanceOf[Query.Relational]).toOption.get.rows.map(_.map(_.text)),
      Vector(Vector("A", "20"), Vector("B", "-10"), Vector("C", "")))
    assert(Query.print(q).contains("\"by\":\"year\""))
    val noBy = Query.parse("""{"from": "gdp", "aggregate": [{"fn": "growth", "column": "value"}]}""").toOption.get
    assert(Exact.run(Map("gdp" -> gdp), noBy.asInstanceOf[Query.Relational]).left.exists(_.contains("by")))

/** the conjunctive queries compiled to Datalog: a join with conditions == the relational engine; the negation */
class ConjunctiveSuite extends munit.FunSuite:
  val ts = Sample.tables
  test("a join with two conditions == the relational query; customers with no orders; a comparison"):
    val q = Conjunctive.program(ts, "orders", Vector(Conjunctive.Join("customers", "customer", "name")),
      Vector(Conjunctive.Cond("orders", "status", "=", "unpaid"), Conjunctive.Cond("customers", "country", "=", "UA")), select = Vector("id")).toOption.get
    val got = Logic.run(ts, q).toOption.get.rows.map(_.head.text).toSet
    val rel = Query.parse(Sample.questions(6).gold).toOption.get.asInstanceOf[Query.Relational]
    assertEquals(got, Exact.run(ts, rel).toOption.get.rows.map(_.head.text).toSet)
    val none = Conjunctive.program(ts, "customers", absent = Some(Conjunctive.Join("orders", "name", "customer")), select = Vector("name")).toOption.get
    assertEquals(Logic.run(ts, none).toOption.get.rows.map(_.head.text).toSet, Set("fay"))
    val big = Conjunctive.program(ts, "orders", conds = Vector(Conjunctive.Cond("orders", "amount", ">", "100")), select = Vector("id")).toOption.get
    assertEquals(Logic.run(ts, big).toOption.get.rows.map(_.head.text).toSet, Set("1", "4", "5", "8"))

class AbsentPairsSuite extends munit.FunSuite:
  test("the generated absent pairs: the slots read the shape and the table, the answer is the keys the first table lacks"):
    val ps = skill.Pairs.many(7, 400).filter(_.program.contains(" not "))
    assert(ps.size > 30, s"${ps.size} absent pairs of 400")
    for p <- ps do
      val sl = skill.Probes.slots(p.program).get
      assertEquals(sl.shape, "absent")
      val l = Query.parse(p.program).toOption.get.asInstanceOf[Query.Logical]
      val got = Logic.run(p.tables, l).toOption.get.rows.map(_.head.text).toSet
      val other = p.tables(sl.table); val first = p.tables.values.find(_.name != sl.table).get
      // the key column: one whose values all name rows of the other (two may, by the shared word pool)
      val keyCols = first.columns.indices.filter(c => first.rows.forall(r => other.rows.exists(_.head.text == r(c).text)))
      val expected = keyCols.map(k => other.rows.map(_.head.text).filterNot(first.rows.map(_(k).text).toSet).toSet)
      assert(expected.contains(got), s"$got not among $expected")
      assert(got.nonEmpty)

class TranslatedSuite extends munit.FunSuite:
  test("half of the Russian pairs name the table in Russian; the translated question keeps the gold program"):
    val ps = skill.Pairs.many(11, 300, Some(true))
    val ru = ps.filter(p => !p.tables.keys.filter(_ != "edges").exists(n => p.question.contains(n)))
    assert(ru.size > 90 && ru.size < 210, s"${ru.size} of 300 translated")
    assertEquals(skill.Pairs.translated("Сколько строк в products?", Seq("products")), "Сколько строк в товарах?")
    assertEquals(skill.Pairs.translated("Какие people без products?", Seq("products", "people")), "Какие люди без товаров?")
    assertEquals(skill.Pairs.translated("Сумма qty по city из sales.", Seq("sales")), "Сумма qty по city из продаж.")
