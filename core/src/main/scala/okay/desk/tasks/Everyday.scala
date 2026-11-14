package okay.desk.tasks

import okay.desk.{Exact, Query, Table}

/**
 * THE MEASURE OF USEFULNESS (specs/technology.md §9): tables like a person's own — expenses, a shop's sales, stock,
 * shifts, marks, a bank statement — and questions people ask of them, in English and Russian, each with its gold
 * program; some OUTSIDE what the query language says (a share, a change between months, two columns compared): for
 * those the right outcome is a refusal or a question, never an answer. The tables are drawn by a fixed seed, the
 * same on the JVM and in the page. Laws: `EverydaySuite` (every gold runs and gives rows).
 */
object Everyday:
  final case class Q(table: String, en: String, ru: String, gold: Option[String])
  private def rnd = scala.util.Random(2026)

  private def date(r: scala.util.Random, months: Vector[Int]): String =
    val m = months(r.nextInt(months.size))
    f"2026-$m%02d-${1 + r.nextInt(28)}%02d"
  private def csv(name: String, header: String, rows: Iterable[Vector[Any]]): Table =
    Table.parse(name, (header +: rows.toVector.map(_.mkString(","))).mkString("\n") + "\n").fold(sys.error, identity)

  val expenses: Table =
    val r = rnd
    val merchants = Vector("groceries" -> Vector("Biedronka", "Lidl", "Auchan"), "transport" -> Vector("Uber", "Metro"),
      "restaurants" -> Vector("Pizza Hut", "Sushi Bar", "Cafe Nero"), "utilities" -> Vector("Energa", "Orange"),
      "health" -> Vector("Pharmacy", "Clinic"), "entertainment" -> Vector("Netflix", "Cinema City"))
    csv("expenses.csv", "date,category,merchant,amount,payment", (1 to 72).map { _ =>
      val (category, ms) = merchants(r.nextInt(merchants.size))
      val amount = BigDecimal(5 + r.nextInt(240) + r.nextInt(100) / 100.0).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      Vector(date(r, Vector(1, 2, 3)), category, ms(r.nextInt(ms.size)), amount,
        if r.nextInt(4) == 0 then "cash" else "card")
    }.sortBy(_.head.toString))

  val sales: Table =
    val r = rnd
    val products = Vector("coffee" -> 12, "tea" -> 8, "mug" -> 25, "beans 1kg" -> 60, "grinder" -> 180)
    csv("sales.csv", "date,product,region,units,unit_price,revenue", (1 to 60).map { _ =>
      val (product, price) = products(r.nextInt(products.size))
      val units = 1 + r.nextInt(if price > 100 then 6 else 40)
      Vector(date(r, Vector(1, 2, 3)), product, Vector("North", "South", "West")(r.nextInt(3)), units, price,
        units * price)
    }.sortBy(_.head.toString))

  val stock: Table =
    val r = rnd
    val items = Vector("pasta" -> "food", "rice" -> "food", "olive oil" -> "food", "flour" -> "food", "sugar" -> "food",
      "soap" -> "household", "detergent" -> "household", "sponges" -> "household", "paper towels" -> "household",
      "batteries" -> "electronics", "bulbs" -> "electronics", "cables" -> "electronics", "chargers" -> "electronics",
      "notebooks" -> "office", "pens" -> "office", "tape" -> "office")
    csv("stock.csv", "item,category,quantity,reorder_level,supplier", items.zipWithIndex.map { case ((item, cat), i) =>
      Vector(item, cat, if i % 7 == 3 then 0 else r.nextInt(80), 10 + r.nextInt(20),
        Vector("Makro", "Selgros", "Office Depot")(r.nextInt(3)))
    })

  val shifts: Table =
    val r = rnd
    val people = Vector("Anna" -> "cashier", "Boris" -> "cashier", "Clara" -> "stock", "Denis" -> "stock",
      "Eva" -> "manager")
    csv("shifts.csv", "employee,date,role,shift,hours", (1 to 50).map { _ =>
      val (who, role) = people(r.nextInt(people.size))
      val night = r.nextInt(4) == 0
      Vector(who, date(r, Vector(3)), role, if night then "night" else "day", if night then 10 else 4 + r.nextInt(5))
    }.sortBy(_(1).toString))

  val marks: Table =
    val r = rnd
    val students = Vector("Olga", "Ivan", "Maria", "Petro", "Sofia", "Taras")
    csv("marks.csv", "student,subject,term,mark", for
      s <- students; subject <- Vector("math", "physics", "history", "english"); term <- Vector(1, 2)
    yield Vector(s, subject, term, 2 + r.nextInt(4)))

  val bank: Table =
    val r = rnd
    var balance = BigDecimal(2500)
    val outs = Vector("Rent", "Biedronka", "Netflix", "Orange", "Uber", "Allegro", "Pharmacy")
    val days = (1 to 45).map(_ => date(r, Vector(1, 2, 3))).sorted
    csv("bank.csv", "date,description,type,amount,balance", days.map { d =>
      val in = r.nextInt(6) == 0
      val amount = if in then BigDecimal(800 + r.nextInt(3000)) else BigDecimal(5 + r.nextInt(400))
      balance = if in then balance + amount else balance - amount
      val incomes = Vector("Salary", "Transfer from Anna", "Refund")
      Vector(d, if in then incomes(r.nextInt(3)) else outs(r.nextInt(outs.size)),
        if in then "in" else "out", amount, balance)
    })

  val tables: Map[String, Table] = Map("expenses" -> expenses, "sales" -> sales, "stock" -> stock, "shifts" -> shifts,
    "marks" -> marks, "bank" -> bank)

  private def w(c: String, op: String, v: String) = s"""{"column": "$c", "op": "$op", "value": $v}"""
  private def s(v: String) = "\"" + v + "\""
  private def agg(fn: String, column: String, as: String) = s"""{"fn": "$fn", "column": "$column", "as": "$as"}"""
  private def q(from: String, where: Vector[String] = Vector.empty, group: Vector[String] = Vector.empty,
      aggs: Vector[String] = Vector.empty, order: Vector[String] = Vector.empty, limit: Option[Int] = None,
      select: Vector[String] = Vector.empty): String =
    val parts = Vector(s""""from": "$from"""") ++
      Option.when(where.nonEmpty)(s""""where": [${where.mkString(", ")}]""") ++
      Option.when(group.nonEmpty)(s""""group": [${group.map(s).mkString(", ")}]""") ++
      Option.when(aggs.nonEmpty)(s""""aggregate": [${aggs.mkString(", ")}]""") ++
      Option.when(order.nonEmpty)(s""""order": [${order.map(s).mkString(", ")}]""") ++
      limit.map(n => s""""limit": $n""") ++
      Option.when(select.nonEmpty)(s""""select": [${select.map(s).mkString(", ")}]""")
    parts.mkString("{", ", ", "}")
  private val march = Vector(w("date", ">=", s("2026-03-01")), w("date", "<", s("2026-04-01")))
  private val february = Vector(w("date", ">=", s("2026-02-01")), w("date", "<", s("2026-03-01")))
  private val january = Vector(w("date", ">=", s("2026-01-01")), w("date", "<", s("2026-02-01")))
  private def in(t: String, en: String, ru: String, gold: String) = Q(t, en, ru, Some(gold))
  private def out(t: String, en: String, ru: String) = Q(t, en, ru, None)

  val questions: Vector[Q] = Vector(
    in("expenses", "How much did I spend on groceries?", "Сколько я потратил на продукты?",
      q("expenses", Vector(w("category", "=", s("groceries"))), aggs = Vector(agg("sum", "amount", "total")))),
    in("expenses", "How much did I spend in March?", "Сколько я потратил в марте?",
      q("expenses", march, aggs = Vector(agg("sum", "amount", "total")))),
    in("expenses", "Spending by category, largest first.",
      "Расходы по категориям, от больших к меньшим.",
      q("expenses", group = Vector("category"), aggs = Vector(agg("sum", "amount", "total")),
        order = Vector("-total"))),
    in("expenses", "Top 3 merchants by total spent.",
      "Три магазина, где я потратил больше всего.",
      q("expenses", group = Vector("merchant"), aggs = Vector(agg("sum", "amount", "total")), order = Vector("-total"),
        limit = Some(3))),
    in("expenses", "What is my average grocery purchase?",
      "Какая у меня средняя покупка продуктов?",
      q("expenses", Vector(w("category", "=", s("groceries"))), aggs = Vector(agg("avg", "amount", "average")))),
    in("expenses", "How many times did I pay by card?", "Сколько раз я платил картой?",
      q("expenses", Vector(w("payment", "=", s("card"))), aggs = Vector(agg("count", "*", "n")))),
    in("expenses", "What was my biggest single expense?",
      "Какая была моя самая крупная трата?",
      q("expenses", order = Vector("-amount"), limit = Some(1))),
    in("expenses", "Restaurant expenses in February.", "Расходы на рестораны в феврале.",
      q("expenses", Vector(w("category", "=", s("restaurants"))) ++ february)),
    out("expenses", "How did my spending change from January to February?",
      "Как изменились мои расходы с января по февраль?"),
    out("expenses", "What share of my spending went to transport?",
      "Какая доля расходов ушла на транспорт?"),

    in("sales", "What is the total revenue?", "Какая общая выручка?",
      q("sales", aggs = Vector(agg("sum", "revenue", "revenue")))),
    in("sales", "Revenue by region.", "Выручка по регионам.",
      q("sales", group = Vector("region"), aggs = Vector(agg("sum", "revenue", "revenue")))),
    in("sales", "Units sold per product, most first.",
      "Сколько штук продано каждого товара, по убыванию.",
      q("sales", group = Vector("product"), aggs = Vector(agg("sum", "units", "units")), order = Vector("-units"))),
    in("sales", "Which product brought the most revenue?",
      "Какой товар принёс больше всего выручки?",
      q("sales", group = Vector("product"), aggs = Vector(agg("sum", "revenue", "revenue")), order = Vector("-revenue"),
        limit = Some(1))),
    in("sales", "Sales in the North in January.", "Продажи на севере в январе.",
      q("sales", Vector(w("region", "=", s("North"))) ++ january)),
    in("sales", "Average units per sale.", "Сколько в среднем штук в одной продаже?",
      q("sales", aggs = Vector(agg("avg", "units", "average")))),
    in("sales", "How many sales brought more than 1000?",
      "Сколько продаж принесли больше 1000?",
      q("sales", Vector(w("revenue", ">", "1000")), aggs = Vector(agg("count", "*", "n")))),
    in("sales", "Revenue by product in the South.", "Выручка по товарам на юге.",
      q("sales", Vector(w("region", "=", s("South"))), group = Vector("product"),
        aggs = Vector(agg("sum", "revenue", "revenue")))),
    out("sales", "Which product's revenue grew the most from January to March?",
      "Выручка какого товара выросла сильнее всего с января по март?"),

    in("stock", "Which items have fewer than 10 in stock?",
      "Каких товаров на складе меньше 10?",
      q("stock", Vector(w("quantity", "<", "10")))),
    in("stock", "Total quantity per category.", "Общее количество по категориям.",
      q("stock", group = Vector("category"), aggs = Vector(agg("sum", "quantity", "total")))),
    in("stock", "How many items does each supplier provide?",
      "Сколько товаров у каждого поставщика?",
      q("stock", group = Vector("supplier"), aggs = Vector(agg("count", "*", "n")))),
    in("stock", "Which item do we have the most of?",
      "Какого товара больше всего на складе?",
      q("stock", order = Vector("-quantity"), limit = Some(1))),
    in("stock", "Items from Makro.", "Товары от Makro.", q("stock", Vector(w("supplier", "=", s("Makro"))))),
    in("stock", "Which items are out of stock?",
      "Каких товаров нет в наличии?", q("stock", Vector(w("quantity", "=", "0")))),
    in("stock", "Average quantity per category.", "Среднее количество по категориям.",
      q("stock", group = Vector("category"), aggs = Vector(agg("avg", "quantity", "average")))),
    out("stock", "Which items are below their reorder level?",
      "Какие товары ниже уровня дозаказа?"),

    in("shifts", "Total hours per employee.",
      "Сколько часов отработал каждый сотрудник?",
      q("shifts", group = Vector("employee"), aggs = Vector(agg("sum", "hours", "hours")))),
    in("shifts", "Who worked the most hours?", "Кто отработал больше всего часов?",
      q("shifts", group = Vector("employee"), aggs = Vector(agg("sum", "hours", "hours")), order = Vector("-hours"),
        limit = Some(1))),
    in("shifts", "How many shifts did Anna have?", "Сколько смен было у Анны?",
      q("shifts", Vector(w("employee", "=", s("Anna"))), aggs = Vector(agg("count", "*", "n")))),
    in("shifts", "Hours by role.", "Часы по должностям.",
      q("shifts", group = Vector("role"), aggs = Vector(agg("sum", "hours", "hours")))),
    in("shifts", "Who worked night shifts?", "Кто работал в ночные смены?",
      q("shifts", Vector(w("shift", "=", s("night"))), group = Vector("employee"),
        aggs = Vector(agg("count", "*", "n")))),
    in("shifts", "What is the average shift length?", "Какова средняя длина смены?",
      q("shifts", aggs = Vector(agg("avg", "hours", "average")))),
    out("shifts", "Who worked more than 40 hours in a single week?",
      "Кто отработал больше 40 часов за одну неделю?"),

    in("marks", "Average mark per subject.", "Средняя оценка по каждому предмету.",
      q("marks", group = Vector("subject"), aggs = Vector(agg("avg", "mark", "average")))),
    in("marks", "Average mark per student, best first.",
      "Средняя оценка каждого ученика, от лучших.",
      q("marks", group = Vector("student"), aggs = Vector(agg("avg", "mark", "average")), order = Vector("-average"))),
    in("marks", "Who got less than 3 in math?", "У кого по математике ниже 3?",
      q("marks", Vector(w("subject", "=", s("math")), w("mark", "<", "3")))),
    in("marks", "What is the best mark in physics?", "Какая лучшая оценка по физике?",
      q("marks", Vector(w("subject", "=", s("physics"))), aggs = Vector(agg("max", "mark", "best")))),
    in("marks", "How many fives are there?", "Сколько всего пятёрок?",
      q("marks", Vector(w("mark", "=", "5")), aggs = Vector(agg("count", "*", "n")))),
    in("marks", "Olga's marks in the second term.", "Оценки Ольги во втором семестре.",
      q("marks", Vector(w("student", "=", s("Olga")), w("term", "=", "2")))),
    out("marks", "Whose average improved from the first term to the second?",
      "У кого средняя оценка выросла с первого семестра ко второму?"),

    in("bank", "What is my total income?", "Какой у меня общий доход?",
      q("bank", Vector(w("type", "=", s("in"))), aggs = Vector(agg("sum", "amount", "total")))),
    in("bank", "How much did I spend in total?", "Сколько я всего потратил?",
      q("bank", Vector(w("type", "=", s("out"))), aggs = Vector(agg("sum", "amount", "total")))),
    in("bank", "What was the largest payment?", "Какой был самый большой платёж?",
      q("bank", Vector(w("type", "=", s("out"))), order = Vector("-amount"), limit = Some(1))),
    in("bank", "Transactions over 500.",
      "Операции больше 500.", q("bank", Vector(w("amount", ">", "500")))),
    in("bank", "How many payments went to Netflix?", "Сколько платежей ушло на Netflix?",
      q("bank", Vector(w("description", "=", s("Netflix"))), aggs = Vector(agg("count", "*", "n")))),
    in("bank", "What is the balance after the last transaction?",
      "Какой баланс после последней операции?",
      q("bank", order = Vector("-date"), limit = Some(1), select = Vector("balance"))),
    out("bank", "By how much did my balance change each month?",
      "На сколько менялся мой баланс каждый месяц?")
  )

  def run(gold: String): Either[String, Table] = Query.parse(gold).flatMap {
    case r: Query.Relational => Exact.run(tables, r)
    case _ => Left("a relational program expected")
  }
