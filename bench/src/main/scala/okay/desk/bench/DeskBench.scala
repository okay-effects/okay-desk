package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Session}
import okay.desk.*

/**
 * THE BENCH: sample tables (orders, customers, edges); questions in English and Russian, each with its gold
 * program (run by the exact handlers: the gold result); for each model, the page's loop — the prompt, the program
 * from the answer, checked and run, one retry with the problem in words — and the result compared to the gold as a
 * set of rows. Printed per model: right at once, right after the retry, wrong result, no program; by language.
 */
object DeskBench:
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
    case l: Query.Logical => Logic.run(tables, l)
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

  def main(args: Array[String]): Unit =
    val repos = scala.sys.env.getOrElse("DESK_MODELS", "mlx-community/Qwen3-0.6B-4bit,mlx-community/Qwen3.5-0.8B-MLX-4bit," +
      "mlx-community/Qwen3-1.7B-4bit,mlx-community/Qwen3.5-2B-MLX-4bit,mlx-community/Qwen3.5-4B-MLX-4bit").split(",").toVector
    val golds = questions.map(q => run(Query.parse(q.gold).toOption.get).toOption.get)
    println(s"${questions.size} questions × 2 languages; gold results computed")
    val system = Prompt.system(tables)
    val summary = Vector.newBuilder[String]
    for repo <- repos do
      Models.snapshot(repo) match
        case None => println(s"$repo: not in the cache, skipped")
        case Some(dir) =>
          val chat = Chat.load(dir); val model = Model.load(dir, mapped = true); val tok = chat.tokenizer
          // an adapter trained by `Train`, if DESK_ADAPTER names its directory (the file lora-<model>.safetensors there)
          val adapter = scala.sys.env.get("DESK_ADAPTER").map(d => s"$d/lora-${repo.split('/').last}.safetensors")
            .filter(f => java.nio.file.Files.exists(java.nio.file.Path.of(f)))
            .map(f => { println(s"adapter: $f"); mlx.llm.Lora.attach(model.asInstanceOf[mlx.llm.Adaptable], f) })
          def msg(role: String, content: String) = okay.effects.json.Json.obj("role" -> okay.effects.json.Json.Str(role),
            "content" -> okay.effects.json.Json.Str(content))
          val end = tok.id("<|im_end|>")
          def complete(ms: Vector[(String, String)], max: Int, masked: Boolean): String =
            val prompt = chat.prompt(ms.map(msg), Nil, Map("enable_thinking" -> false))
            val port = if masked then Grammar(tok, tables, end) else null
            val s = Session(model, prompt, max, chat.stops, port = port)
            try
              val toks = Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
              if masked then "```json\n" + port.out.toString + "\n```" else tok.decode(toks)
            finally s.close()
          for masked <- Seq(false, true) do {
          var at = Map("en" -> 0, "ru" -> 0); var retried = Map("en" -> 0, "ru" -> 0)
          var wrong = Map("en" -> 0, "ru" -> 0); var none = Map("en" -> 0, "ru" -> 0)
          val t0 = System.nanoTime()
          for (q, i) <- questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
            val answer = complete(Vector("system" -> system, "user" -> text), 500, masked)
            def attempt(a: String): Either[String, Table] =
              Prompt.program(a).left.map(_ => "no program").flatMap(Query.parse).flatMap(run)
            attempt(answer) match
              case Right(t) if same(t, golds(i)) => at = at.updated(lang, at(lang) + 1)
              case first =>
                val problem = first.left.getOrElse("the result differs from what the question asks")
                if first.left.exists(_ == "no program") then none = none.updated(lang, none(lang) + 1)
                else
                  val again = complete(Vector("system" -> system, "user" -> text, "assistant" -> answer,
                    "user" -> s"That program failed: $problem. Reply with the corrected JSON only."), 500, masked)
                  attempt(again) match
                    case Right(t) if same(t, golds(i)) => retried = retried.updated(lang, retried(lang) + 1)
                    case _ => wrong = wrong.updated(lang, wrong(lang) + 1)
                      if scala.sys.env.contains("DESK_VERBOSE") then println(s"  [$lang ${i + 1}] ${answer.take(300).replace("\n", " ")}")
          val n = questions.size
          val line = f"$repo%-42s ${if masked then "masked" else "free  "} | en: ${at("en")}/$n at once, +${retried("en")} after the retry, ${wrong("en")} wrong, ${none("en")} none" +
            f" | ru: ${at("ru")}/$n, +${retried("ru")}, ${wrong("ru")} wrong, ${none("ru")} none | ${(System.nanoTime() - t0) / 1e9}%.0f s"
          println(line); summary += line
          }
          adapter.foreach(_.close())
          model.close()
    println("\nmodel | en: right at once, after the retry, wrong, no program | ru | time")
    summary.result().foreach(println)
