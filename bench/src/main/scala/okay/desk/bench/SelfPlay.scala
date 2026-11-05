package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Qwen3, Session}
import okay.desk.*
import okay.desk.skill.*

/**
 * SELF-PLAY WITH A CONSISTENCY JUDGE (specs/data.md §13, stage 2 of the plan): the small model learns from itself.
 * It writes questions about a schema (free text, its own understanding); the readers answer each with a program;
 * the exact engine runs it; the JUDGE accepts a pair only when (a) the two readers — the probe and ask — agreed on
 * every slot they both read, and (b) the round trip holds: the program said back as a question by a template reads
 * to the same program. Accepted pairs retrain the readers. Measured honestly: readers trained on self-verified
 * pairs alone, against readers trained on the gold pairs, on the same 200 held-out gold pairs (`Readers.measure`).
 * `sbt "bench/runMain okay.desk.bench.SelfPlay"`; DESK_LAYER the layer (20 for the 0.6B).
 */
object SelfPlay:
  /** the model asked for questions about a schema: one per line, in English or Russian */
  def questions(chat: Chat, model: Model, tables: Map[String, Table], ru: Boolean, n: Int): Vector[String] =
    val tok = chat.tokenizer
    val schema = tables.values.toVector.sortBy(_.name).map(_.describe).mkString("\n")
    // asked for their variety: one of each kind, named, so the accepted set covers the shapes
    val kinds = if ru then Vector("сколько строк подходят под условие (назови столбец и значение из данных)", "список строк с условием на столбец",
      "сумма или среднее по группам (назови столбец группировки)", "несколько самых больших по числовому столбцу (назови число)", "что достижимо из узла по рёбрам или что ведёт к узлу")
      else Vector("how many rows meet a condition (name a column and a value from the data)", "which rows meet a condition on a column",
      "a total or an average per group (name the group column)", "the top few by a numeric column (say how many)", "what is reachable from a node through the edges, or what leads to a node")
    val ask = if ru then s"Вот таблицы:\n$schema\n\nНапиши $n коротких вопросов об этих данных, по одному каждого вида: ${kinds.mkString("; ")}. " +
      "Используй имена столбцов и значения из таблиц как есть. По одному в строке, без нумерации, без пояснений."
      else s"Here are tables:\n$schema\n\nWrite $n short questions about this data, one of each kind: ${kinds.mkString("; ")}. " +
      "Use the columns' names and the values from the tables as they are. One per line, no numbering, no explanation."
    val prompt = chat.ask(ask, Map("enable_thinking" -> false))
    val s = Session(model, prompt, 400, chat.stops)
    val out = try tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector) finally s.close()
    out.replaceAll("(?s)<think>.*?</think>", "").split("\n").map(_.trim.replaceAll("^[-*\\d.)\\s]+", "")).filter(q => q.length > 8 && q.length < 140).toVector.distinct.take(n)

  /** the program said back as a question, by a template — the round trip's other half */
  def restate(program: String): Option[String] =
    Query.parse(program).toOption.collect {
      case Query.Logical(_, goal) =>
        val GoalFrom = """^\w+\(\s*"?([^",()]+)"?\s*,\s*[A-Z]\w*\s*\)$""".r; val GoalTo = """^\w+\(\s*[A-Z]\w*\s*,\s*"?([^",()]+)"?\s*\)$""".r
        goal.trim match
          case GoalFrom(a) => s"What is reachable from $a, directly or through others?"
          case GoalTo(b) => s"Which nodes reach $b, directly or through others?"
          case _ => "What is reachable?"
      case Query.Relational(from, join, where, group, aggs, select, order, limit) =>
        val cond = where.headOption.map {
          case Cond.Cmp(c, "=", v) => s" with $c = ${v.text}"
          case Cond.Cmp(c, op, v) => s" with $c $op ${v.text}"
          case Cond.Has(c, t) => s" whose $c contains $t"
          case Cond.Empty(c, is) => s" with $c ${if is then "empty" else "not empty"}"
        }.getOrElse("")
        val agg = aggs.headOption.map(a => a.fn match
          case "count" => s"How many $from$cond"
          case f => s"${f.capitalize} of ${a.column} for $from$cond").getOrElse(s"Which $from$cond")
        val grouped = if group.nonEmpty then s" per ${group.head}" else ""
        val ordered = order.headOption.map(o => s", ${if o.desc then "largest" else "smallest"} ${o.column.stripPrefix("-")} first").getOrElse("")
        val limited = limit.map(l => s", top $l").getOrElse("")
        val joined = join.map(j => s" (joined with ${j.table})").getOrElse("")
        val sel = if select.nonEmpty then s": ${select.mkString(" and ")}" else ""
        s"$agg$grouped$joined$ordered$limited$sel?"
    }

  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("mlx-community/Qwen3-0.6B-4bit")
    val rounds = args.lift(1).map(_.toInt).getOrElse(60)
    val layer = scala.sys.env.getOrElse("DESK_LAYER", "20").toInt
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[Qwen3]
    val gold = Pairs.many(42, 600); val held = Pairs.many(43, 200)
    // the readers that answer during self-play: trained on the gold first (the bootstrap: a model must read before
    // it can judge itself); the EXPERIMENT trains fresh readers on the self-verified pairs alone
    val answering = MlxReading.ready(chat, model, layer, repo, gold)
    val rnd = new scala.util.Random(9)
    val accepted = Vector.newBuilder[Pairs.Pair]
    var asked = 0; var programs = 0; var agreed = 0; var roundTrips = 0
    val t0 = System.nanoTime()
    for r <- 1 to rounds do
      val schema = Pairs.one(rnd, rnd.nextBoolean()).tables
      for q <- questions(chat, model, schema, ru = rnd.nextBoolean(), n = 5) do
        asked += 1
        answering.programWith(schema, q) match
          case Some((program, consistent)) =>
            programs += 1
            // THE EXACT JUDGE: every table, column or value the question names (a word of 3+ letters) is in the program
            val named = schema.values.flatMap(t => (t.name +: t.columns).map(n => (n, s"\"$n\"")) ++
              t.rows.flatten.map(_.text).distinct.filter(v => v.length >= 3 && !v.forall(_.isDigit)).map(v => (v, s"\"$v\""))).toVector.distinct
            val lowerQ = q.toLowerCase
            val mentioned = named.filter((n, _) => lowerQ.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(n.toLowerCase)}\\b.*"))
            val exact = mentioned.nonEmpty && mentioned.forall((_, lit) => program.contains(lit))
            if consistent && exact then
              agreed += 1
              val back = restate(program)
              val same = back.exists(b => answering.programWith(schema, b).exists(_._1 == program))
              if same then
                roundTrips += 1
                accepted += Pairs.Pair(schema, q, program)
          case None => ()
      if r % 10 == 0 then println(f"round $r: asked $asked, programs $programs, readers agreed $agreed, round trips $roundTrips (${(System.nanoTime() - t0) / 1e9}%.0f s)")
    val self = accepted.result()
    println(s"self-verified pairs: ${self.size} of $asked questions; e.g.\n  " + self.take(5).map(p => s"${p.question}\n    → ${p.program}").mkString("\n  "))
    val fresh = Readers(MlxReading(chat, model, layer))
    if self.size >= 50 then
      fresh.train(self)
      println("readers trained on the self-verified pairs alone, measured on the held-out gold:")
      fresh.measure(held)
    println("readers trained on the gold, measured on the same held-out:")
    answering.measure(held)
    model.close()
