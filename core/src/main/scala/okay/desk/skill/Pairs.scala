package okay.desk.skill

import okay.desk.*

/**
 * QUESTION → PROGRAM PAIRS, GENERATED (specs/data.md §5: the lever for a small model): random tables (names and
 * columns from pools unlike the bench's), a program shape drawn, its gold program written against the table, the
 * question said in one of several phrasings in English or Russian with the table's own names and values in it.
 * The prompt is the page's (`Prompt.system`), so the model trains on exactly what it will see.
 */
object Pairs:
  final case class Pair(tables: Map[String, Table], question: String, program: String)

  private val tableNames = Vector("products", "employees", "invoices", "flights", "tickets", "students", "visits",
    "shipments", "sales", "reviews", "calls", "bookings")
  private val textCols = Vector("name", "category", "region", "status", "type", "owner", "team", "vendor", "city", "kind")
  private val numCols = Vector("price", "qty", "total", "score", "hours", "weight", "age", "rating", "cost", "count")
  private val words = Vector("alpha", "beta", "gamma", "delta", "north", "south", "east", "west", "open", "closed",
    "new", "done", "red", "blue", "green", "gold", "silver", "home", "work", "spare")

  def table(rnd: scala.util.Random, name: String, n: Int): Table =
    val tc = rnd.shuffle(textCols).take(1 + rnd.nextInt(2)); val nc = rnd.shuffle(numCols).take(1 + rnd.nextInt(2))
    val cols = rnd.shuffle(Vector("id") ++ tc ++ nc ++ (if rnd.nextBoolean() then Vector("day") else Vector.empty))
    val pool = rnd.shuffle(words).take(3 + rnd.nextInt(3))
    Table(name, cols, Vector.tabulate(n)(i => cols.map {
      case "id" => Value.Num(i + 1)
      case "day" => Value.Str(f"2026-11-${1 + rnd.nextInt(9)}%02d")
      case c if numCols.contains(c) => Value.Num(rnd.nextInt(500))
      case _ => Value.Str(pool(rnd.nextInt(pool.size)))
    }))

  /** a second table joined to the first by a text column of the first naming rows of the second */
  def lookup(rnd: scala.util.Random, name: String, keys: Vector[String]): Table =
    val extra = rnd.shuffle(textCols.filter(c => c != "name")).head
    Table(name, Vector("name", extra, "since"), keys.distinct.map(k =>
      Vector(Value.Str(k), Value.Str(words(rnd.nextInt(words.size))), Value.Num(2020 + rnd.nextInt(7)))))

  def edges(rnd: scala.util.Random): Table =
    val ns = rnd.shuffle(Vector("web", "api", "auth", "orders", "billing", "search", "cache", "db", "queue", "mail")).take(6)
    Table("edges", Vector("from", "to"), (0 until 5).toVector.map(i => Vector(Value.Str(ns(i)), Value.Str(ns(i + 1)))) ++
      Vector(Vector(Value.Str(ns(0)), Value.Str(ns(2)))))

  private def pick[A](rnd: scala.util.Random, xs: Vector[A]): A = xs(rnd.nextInt(xs.size))
  private def q(s: String) = "\"" + s + "\""

  /** one pair: a shape drawn, phrased in `ru` or English */
  def one(rnd: scala.util.Random, ru: Boolean): Pair =
    val t = table(rnd, pick(rnd, tableNames), 5 + rnd.nextInt(8))
    val text = t.columns.filter(c => textCols.contains(c)); val nums = t.columns.filter(c => numCols.contains(c))
    val tc = pick(rnd, text); val nc = pick(rnd, nums)
    val v = pick(rnd, t.rows.map(_(t.columns.indexOf(tc)).text)); val n = 50 + rnd.nextInt(400)
    val k = Vector(3, 5, 10)(rnd.nextInt(3))
    def say(e: Vector[String], r: Vector[String]) = pick(rnd, if ru then r else e)
    val shapes: Vector[(String, String)] = Vector(
      say(Vector(s"How many rows are in ${t.name}?", s"How many ${t.name} are there?", s"Count the ${t.name}."),
          Vector(s"Сколько строк в ${t.name}?", s"Сколько всего ${t.name}?", s"Посчитай ${t.name}.")) ->
        s"""{"from": ${q(t.name)}, "aggregate": [{"fn": "count", "column": "*", "as": "n"}]}""",
      say(Vector(s"How many ${t.name} have $tc = $v?", s"How many ${t.name} with $tc $v?", s"Count ${t.name} where $tc is $v."),
          Vector(s"Сколько ${t.name} с $tc = $v?", s"Сколько ${t.name}, у которых $tc равен $v?", s"Посчитай ${t.name}, где $tc это $v.")) ->
        s"""{"from": ${q(t.name)}, "where": [{"column": ${q(tc)}, "op": "=", "value": ${q(v)}}], "aggregate": [{"fn": "count", "column": "*", "as": "n"}]}""",
      say(Vector(s"Which ${t.name} have $tc = $v?", s"Show the ${t.name} with $tc $v.", s"List ${t.name} where $tc is $v."),
          Vector(s"Какие ${t.name} с $tc = $v?", s"Покажи ${t.name}, где $tc равен $v.", s"Выведи ${t.name} с $tc $v.")) ->
        s"""{"from": ${q(t.name)}, "where": [{"column": ${q(tc)}, "op": "=", "value": ${q(v)}}]}""",
      say(Vector(s"Which ${t.name} have $nc over $n?", s"${t.name} with $nc greater than $n.", s"Show ${t.name} where $nc > $n."),
          Vector(s"Какие ${t.name} с $nc больше $n?", s"${t.name}, у которых $nc выше $n.", s"Покажи ${t.name}, где $nc > $n.")) ->
        s"""{"from": ${q(t.name)}, "where": [{"column": ${q(nc)}, "op": ">", "value": $n}]}""",
      say(Vector(s"Which ${t.name} have $nc below $n?", s"${t.name} with $nc less than $n."),
          Vector(s"Какие ${t.name} с $nc меньше $n?", s"${t.name}, у которых $nc ниже $n.")) ->
        s"""{"from": ${q(t.name)}, "where": [{"column": ${q(nc)}, "op": "<", "value": $n}]}""",
      say(Vector(s"How many ${t.name} per $tc?", s"Count of ${t.name} by $tc.", s"Number of ${t.name} in each $tc."),
          Vector(s"Сколько ${t.name} по каждому $tc?", s"Число ${t.name} в разрезе $tc.", s"Сколько ${t.name} в каждом $tc?")) ->
        s"""{"from": ${q(t.name)}, "group": [${q(tc)}], "aggregate": [{"fn": "count", "column": "*", "as": "n"}]}""",
      say(Vector(s"Total $nc by $tc.", s"Sum of $nc per $tc.", s"$nc summed for each $tc."),
          Vector(s"Сумма $nc по $tc.", s"Суммарный $nc по каждому $tc.", s"Итого $nc в разрезе $tc.")) ->
        s"""{"from": ${q(t.name)}, "group": [${q(tc)}], "aggregate": [{"fn": "sum", "column": ${q(nc)}, "as": "total"}]}""",
      say(Vector(s"Average $nc per $tc.", s"Mean $nc by $tc."),
          Vector(s"Средний $nc по $tc.", s"Средняя величина $nc по каждому $tc.")) ->
        s"""{"from": ${q(t.name)}, "group": [${q(tc)}], "aggregate": [{"fn": "avg", "column": ${q(nc)}, "as": "avg"}]}""",
      say(Vector(s"Total $nc by $tc, largest first.", s"$nc by $tc, descending."),
          Vector(s"Сумма $nc по $tc, по убыванию.", s"$nc по $tc, от большего к меньшему.")) ->
        s"""{"from": ${q(t.name)}, "group": [${q(tc)}], "aggregate": [{"fn": "sum", "column": ${q(nc)}, "as": "total"}], "order": ["-total"]}""",
      say(Vector(s"The $k ${t.name} with the largest $nc: id and $nc.", s"Top $k ${t.name} by $nc, id and $nc."),
          Vector(s"$k ${t.name} с самым большим $nc: id и $nc.", s"Топ-$k ${t.name} по $nc: id и $nc.")) ->
        s"""{"from": ${q(t.name)}, "order": ["-$nc"], "limit": $k, "select": ["id", ${q(nc)}]}""",
      say(Vector(s"The ${t.name} row with the largest $nc.", s"Which of the ${t.name} has the highest $nc?"),
          Vector(s"Строка ${t.name} с самым большим $nc.", s"У какого из ${t.name} самый высокий $nc?")) ->
        s"""{"from": ${q(t.name)}, "order": ["-$nc"], "limit": 1}""",
      say(Vector(s"The ${t.name} with $tc = $v, largest $nc first.", s"${t.name} where $tc is $v, by $nc descending."),
          Vector(s"${t.name} с $tc = $v, сначала с большим $nc.", s"${t.name}, где $tc равен $v, по убыванию $nc.")) ->
        s"""{"from": ${q(t.name)}, "where": [{"column": ${q(tc)}, "op": "=", "value": ${q(v)}}], "order": ["-$nc"]}""",
      say(Vector(s"The largest $nc among ${t.name} with $tc = $v.", s"The ${t.name} row with $tc $v and the highest $nc."),
          Vector(s"Самый большой $nc среди ${t.name} с $tc = $v.", s"Строка ${t.name} с $tc $v и наибольшим $nc.")) ->
        s"""{"from": ${q(t.name)}, "where": [{"column": ${q(tc)}, "op": "=", "value": ${q(v)}}], "order": ["-$nc"], "limit": 1}""",
      say(Vector(s"Sum of $nc for ${t.name} with $tc = $v.", s"Total $nc where $tc is $v."),
          Vector(s"Сумма $nc для ${t.name} с $tc = $v.", s"Итого $nc, где $tc равен $v.")) ->
        s"""{"from": ${q(t.name)}, "where": [{"column": ${q(tc)}, "op": "=", "value": ${q(v)}}], "aggregate": [{"fn": "sum", "column": ${q(nc)}, "as": "total"}]}""")
    val (question, program) = pick(rnd, shapes)
    val base = Map(t.name -> t)
    // a join or a datalog shape one time in four, with a second table
    rnd.nextInt(4) match
      case 0 =>
        val key = tc
        val other = lookup(rnd, pick(rnd, Vector("people", "accounts", "staff")), t.rows.map(_(t.columns.indexOf(key)).text))
        val extra = other.columns(1); val ev = pick(rnd, other.rows.map(_(1).text))
        val qj = say(Vector(s"${t.name} whose $key has $extra = $ev in ${other.name}.", s"Total $nc per $extra of ${other.name}."),
          Vector(s"${t.name}, чей $key имеет $extra = $ev в ${other.name}.", s"Сумма $nc по $extra из ${other.name}."))
        val pj = if qj.startsWith("Total") || qj.startsWith("Сумма") then
          s"""{"from": ${q(t.name)}, "join": {"table": ${q(other.name)}, "on": [[${q(key)}, "name"]]}, "group": [${q(extra)}], "aggregate": [{"fn": "sum", "column": ${q(nc)}, "as": "total"}]}"""
          else s"""{"from": ${q(t.name)}, "join": {"table": ${q(other.name)}, "on": [[${q(key)}, "name"]]}, "where": [{"column": ${q(extra)}, "op": "=", "value": ${q(ev)}}]}"""
        Pair(base + (other.name -> other), qj, pj)
      case 1 =>
        val e = edges(rnd); val a = e.rows.head(0).text; val z = e.rows(4)(1).text
        val toward = rnd.nextBoolean()
        val qd = if toward then say(Vector(s"Which nodes reach $z through edges, directly or through others?", s"What leads to $z?"),
            Vector(s"Какие узлы доходят до $z по edges, прямо или через другие?", s"Что ведёт к $z?"))
          else say(Vector(s"What does $a depend on, directly or through others?", s"What is reachable from $a?"),
            Vector(s"От чего зависит $a, прямо или через другие?", s"Что достижимо из $a?"))
        val goal = if toward then s"reach(X, ${Logic.constant(z)})" else s"reach(${Logic.constant(a)}, X)"
        Pair(base + ("edges" -> e), qd, s"""{"datalog": "reach(X, Y) :- edges(X, Y). reach(X, Z) :- edges(X, Y), reach(Y, Z).", "goal": ${q(goal.replace("\"", "\\\""))}}""")
      case _ =>
        // other tables present but unused (edges, a lookup), so that a table's PRESENCE is no signal of the program —
        // the first readers learned "edges present ⇒ a closure" from pairs where edges appeared only then
        val extra = (if rnd.nextInt(3) == 0 then Map("edges" -> edges(rnd)) else Map.empty) ++
          (if rnd.nextInt(3) == 0 then { val o = lookup(rnd, pick(rnd, Vector("people", "accounts", "staff")), t.rows.map(_(0).text)); Map(o.name -> o) } else Map.empty)
        Pair(base ++ extra, question, program)

  /** pairs whose gold programs run (a check on the generator itself) */
  /** `lang`: the pairs in one language alone — None both, Some(false) English, Some(true) Russian */
  def many(seed: Long, n: Int, lang: Option[Boolean] = None): Vector[Pair] =
    val rnd = new scala.util.Random(seed)
    Iterator.continually(one(rnd, lang.getOrElse(rnd.nextBoolean()))).filter { p =>
      Query.parse(p.program).toOption.exists {
        case r: Query.Relational => Exact.run(p.tables, r).isRight
        case l: Query.Logical => Logic.run(p.tables, l).isRight
      }
    }.take(n).toVector
