package okay.desk.tasks

import okay.desk.Sample

/**
 * DATA: questions over tables that are opened first — the sample's orders, customers and service graph. The statement
 * is a query (a relational one, or Datalog over the tables); the exact engines compute the rows. The first thirteen
 * are the bench's own questions (`Sample.questions`), with the rows they must give written out here.
 */
object DataTasks:
  /** what a data task opens: the sample's three tables, as the page's first example names them */
  val sample: Vector[String] = Vector("sample/orders.csv", "sample/customers.csv", "sample/edges.csv")

  private def data(title: (String, String), text: (String, String), program: String, expect: String*): Task =
    val shown = expect.map(_.replace(",", ", ")).mkString("; ")
    Task(Kind.Data, Words(title._1, title._2), Words(text._1, text._2), Words(shown, shown), Gold.Table(program),
      expect.toVector, sample)
  private def bench(i: Int, title: (String, String), expect: String*): Task =
    val q = Sample.questions(i)
    data(title, (q.en, q.ru), q.gold, expect*)
  private val reach = "reach(X, Y) :- edges(X, Y). reach(X, Z) :- edges(X, Y), reach(Y, Z)."

  val tasks: Vector[Task] = Vector(
    bench(0, ("Unpaid orders: how many", "Неоплаченные заказы: сколько"), "4"),
    bench(1, ("Totals by customer", "Суммы по клиентам"), "bob,1080", "eve,310", "ann,210", "cid,200", "dee,45"),
    bench(2, ("Orders over 100", "Заказы больше 100"), "1,ann,120,paid,2026-11-01,Kyiv",
      "4,cid,200,unpaid,2026-11-03,Kyiv", "5,bob,1000,paid,2026-11-04,Lviv", "8,eve,310,paid,2026-11-05,Lviv"),
    bench(3, ("The three largest orders", "Три самых больших заказа"), "5,1000", "8,310", "4,200"),
    bench(4, ("Average by city", "Среднее по городам"), "Kyiv,102.5", "Lviv,463.3333333333333", "Odesa,45"),
    bench(5, ("Orders per status", "Заказы по статусам"), "paid,4", "unpaid,4"),
    bench(6, ("Unpaid orders from UA", "Неоплаченные заказы из UA"), "4,cid,200,unpaid,2026-11-03,Kyiv,cid,UA,2023",
      "6,dee,45,unpaid,2026-11-04,Odesa,dee,UA,2026", "7,ann,60,unpaid,2026-11-05,Kyiv,ann,UA,2024"),
    bench(7, ("Totals by country", "Суммы по странам"), "DE,310", "PL,1080", "UA,455"),
    bench(8, ("Orders of one day", "Заказы за один день"), "2,bob,80,unpaid,2026-11-02,Lviv",
      "3,ann,30,paid,2026-11-02,Kyiv"),
    bench(9, ("What web depends on", "От чего зависит web"), "checkout", "orders-db", "payments", "stripe"),
    bench(10, ("Who reaches orders-db", "Кто доходит до orders-db"), "catalog", "checkout", "search", "web"),
    bench(11, ("The largest unpaid order", "Самый большой неоплаченный заказ"), "4,cid,200,unpaid,2026-11-03,Kyiv"),
    bench(12, ("Customers without orders", "Клиенты без заказов"), "fay"),
    data(("Customers from UA: how many", "Клиенты из UA: сколько"),
      ("How many customers are from UA?", "Сколько клиентов из UA?"),
      """{"from": "customers", "where": [{"column": "country", "op": "=", "value": "UA"}], """ +
        """"aggregate": [{"fn": "count", "column": "*", "as": "n"}]}""", "3"),
    data(("Paid orders in Kyiv", "Оплаченные заказы в Киеве"),
      ("Paid orders in Kyiv: id and amount.", "Оплаченные заказы в Kyiv: id и сумма."),
      """{"from": "orders", "where": [{"column": "status", "op": "=", "value": "paid"}, """ +
        """{"column": "city", "op": "=", "value": "Kyiv"}], "select": ["id", "amount"]}""", "1,120", "3,30"),
    data(("Paid in total", "Всего оплачено"),
      ("What is the total amount of the paid orders?", "Какова общая сумма оплаченных заказов?"),
      """{"from": "orders", "where": [{"column": "status", "op": "=", "value": "paid"}], """ +
        """"aggregate": [{"fn": "sum", "column": "amount", "as": "total"}]}""", "1460"),
    data(("The smallest order", "Самый маленький заказ"),
      ("The smallest order: id and amount.", "Самый маленький заказ: id и сумма."),
      """{"from": "orders", "order": ["amount"], "limit": 1, "select": ["id", "amount"]}""", "3,30"),
    data(("Orders per city", "Заказы по городам"), ("How many orders per city?", "Сколько заказов в каждом городе?"),
      """{"from": "orders", "group": ["city"], "aggregate": [{"fn": "count", "column": "*", "as": "n"}]}""",
      "Kyiv,4", "Lviv,3", "Odesa,1"),
    data(("Orders of one customer", "Заказы одного клиента"),
      ("Orders of bob: id and amount.", "Заказы клиента bob: id и сумма."),
      """{"from": "orders", "where": [{"column": "customer", "op": "=", "value": "bob"}], """ +
        """"select": ["id", "amount"]}""", "2,80", "5,1000"),
    data(("Customers since 2025", "Клиенты с 2025 года"),
      ("Which customers have been with us since 2025 or later? Names only.",
        "Какие клиенты с нами с 2025 года или позже? Только имена."),
      """{"from": "customers", "where": [{"column": "since", "op": ">=", "value": 2025}], "select": ["name"]}""",
      "bob", "dee", "eve", "fay"),
    data(("Who depends on stripe", "Кто зависит от stripe"),
      ("Which services depend on stripe, directly or through others?",
        "Какие сервисы зависят от stripe, прямо или через других?"),
      s"""{"datalog": "$reach", "goal": "reach(X, stripe)"}""", "checkout", "payments", "web"),
    data(("What search depends on", "От чего зависит search"),
      ("What does search depend on, directly or through others?", "От чего зависит search, прямо или через других?"),
      s"""{"datalog": "$reach", "goal": "reach(search, X)"}""", "catalog", "orders-db"))
