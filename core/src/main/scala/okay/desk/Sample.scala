package okay.desk

/** THE SAMPLE (specs/data.md §1): three tables — orders, customers, edges — and the bench's questions in English and
 * Russian, each with its gold program; the JVM bench (DeskBench) and the page's self-test (`?selftest`) share them */
object Sample:
  val orders = Table.parse("orders.csv",
    """id,customer,amount,status,day,city
      |1,ann,120,paid,2026-11-01,Kyiv
      |2,bob,80,unpaid,2026-11-02,Lviv
      |3,ann,30,paid,2026-11-02,Kyiv
      |4,cid,200,unpaid,2026-11-03,Kyiv
      |5,bob,1000,paid,2026-11-04,Lviv
      |6,dee,45,unpaid,2026-11-04,Odesa
      |7,ann,60,unpaid,2026-11-05,Kyiv
      |8,eve,310,paid,2026-11-05,Lviv
      |""".stripMargin).toOption.get
  val customers = Table.parse("customers.csv",
    "name,country,since\nann,UA,2024\nbob,PL,2025\ncid,UA,2023\ndee,UA,2026\neve,DE,2025\n").toOption.get
  val edges = Table.parse("edges.csv",
    "from,to\nweb,checkout\ncheckout,payments\ncheckout,orders-db\npayments,stripe\nsearch,catalog\ncatalog,orders-db\n").toOption.get
  val tables: Map[String, Table] = Map("orders" -> orders, "customers" -> customers, "edges" -> edges)

  /** a question in both languages and the gold program */
  final case class Q(en: String, ru: String, gold: String)
  val questions: Vector[Q] = Vector(
    Q("How many orders are unpaid?", "Сколько заказов не оплачено?",
      """{"from": "orders", "where": [{"column": "status", "op": "=", "value": "unpaid"}], "aggregate": [{"fn": "count", "column": "*", "as": "n"}]}"""),
    Q("Total amount by customer, largest first.", "Сумма заказов по каждому клиенту, по убыванию.",
      """{"from": "orders", "group": ["customer"], "aggregate": [{"fn": "sum", "column": "amount", "as": "total"}], "order": ["-total"]}"""),
    Q("Which orders are over 100?", "Какие заказы больше 100?",
      """{"from": "orders", "where": [{"column": "amount", "op": ">", "value": 100}]}"""),
    Q("The three largest orders: id and amount.", "Три самых больших заказа: id и сумма.",
      """{"from": "orders", "order": ["-amount"], "limit": 3, "select": ["id", "amount"]}"""),
    Q("Average order amount per city.", "Средняя сумма заказа по городам.",
      """{"from": "orders", "group": ["city"], "aggregate": [{"fn": "avg", "column": "amount", "as": "avg"}]}"""),
    Q("How many orders per status?", "Сколько заказов в каждом статусе?",
      """{"from": "orders", "group": ["status"], "aggregate": [{"fn": "count", "column": "*", "as": "n"}]}"""),
    Q("Unpaid orders of customers from UA.", "Неоплаченные заказы клиентов из UA.",
      """{"from": "orders", "join": {"table": "customers", "on": [["customer", "name"]]}, "where": [{"column": "status", "op": "=", "value": "unpaid"}, {"column": "country", "op": "=", "value": "UA"}]}"""),
    Q("Total amount per country.", "Сумма заказов по странам.",
      """{"from": "orders", "join": {"table": "customers", "on": [["customer", "name"]]}, "group": ["country"], "aggregate": [{"fn": "sum", "column": "amount", "as": "total"}]}"""),
    Q("Orders on 2026-11-02.", "Заказы за 2026-11-02.",
      """{"from": "orders", "where": [{"column": "day", "op": "=", "value": "2026-11-02"}]}"""),
    Q("What does web depend on, directly or through others?", "От чего зависит web, прямо или через других?",
      """{"datalog": "reach(X, Y) :- edges(X, Y). reach(X, Z) :- edges(X, Y), reach(Y, Z).", "goal": "reach(web, X)"}"""),
    Q("Which services reach orders-db?", "Какие сервисы доходят до orders-db?",
      """{"datalog": "reach(X, Y) :- edges(X, Y). reach(X, Z) :- edges(X, Y), reach(Y, Z).", "goal": "reach(X, \"orders-db\")"}"""),
    Q("The largest unpaid order.", "Самый большой неоплаченный заказ.",
      """{"from": "orders", "where": [{"column": "status", "op": "=", "value": "unpaid"}], "order": ["-amount"], "limit": 1}"""))

  def run(q: Query): Either[String, Table] = q match
    case r: Query.Relational => Exact.run(tables, r)
    case l: Query.Logical => Weights.either(tables, l).map(_._1)
  def rows(t: Table): Set[Vector[String]] = t.rows.map(_.map(_.text)).toSet
  /** the same rows (the columns' order free, extra columns allowed when the gold's are all there) */
  def same(got: Table, gold: Table): Boolean =
    if got.size != gold.size then false
    else if got.columns.nonEmpty && got.columns.forall(c => gold.column(c).isDefined) && got.columns.size < gold.columns.size then
      // a subset of the gold's columns chosen: the gold projected onto them
      val gi = got.columns.map(c => gold.column(c).get)
      rows(got) == gold.rows.map(r => gi.map(i => r(i).text)).toSet
    else
      val idx = gold.columns.map(c => got.column(c))
      if idx.exists(_.isEmpty) then
        // the names may differ (an aggregate's alias): compare by position when the widths agree
        got.columns.size == gold.columns.size && rows(got) == rows(gold)
      else rows(gold) == got.rows.map(r => idx.map(i => r(i.get).text)).toSet

