package okay.desk.bench

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardOpenOption}
import okay.desk.{Phrase, Query, Space, Table, Value}
import okay.desk.Space.{Col, Kind, Program, Schema}
import okay.desk.skill.Pairs
import okay.effects.json.{Json, JsonText}
import scala.jdk.CollectionConverters.*

/**
 * THE TEACHER AS THE SOURCE OF LANGUAGE, THE ENGINE AS THE JUDGE (specs/teacher.md): a point of the space, its grammar
 * sentence, the teacher's rewrites in the way people ask (any language, the values in their own words), and each
 * rewrite read back by the judge WITHOUT the program — as choices of the space — and compared with the program
 * constructor by constructor. Accepted only when every constructor agrees. Build time only; the model is local.
 */
object Teacher:
  // ---- a schema with realistic data, from a seed

  private def pick[A](rnd: scala.util.Random, xs: Vector[A]): A = xs(rnd.nextInt(xs.size))
  private val cities = Vector("Kyiv", "Lviv", "Warsaw", "Berlin", "Krakow", "Odesa")
  private val people = Vector("anna", "bob", "carol", "dmytro", "eva", "fay", "gleb", "hana")
  private val statuses = Vector("paid", "unpaid", "overdue", "refunded")
  private val categories = Vector("books", "games", "tools", "toys", "food")
  private val segments = Vector("retail", "wholesale", "vip")

  def schema(seed: Long): Map[String, Table] =
    val rnd = scala.util.Random(seed)
    val custNames = rnd.shuffle(people).take(5 + rnd.nextInt(3))
    val customers = Table("customers", Vector("name", "city", "segment", "since"), custNames.map(n =>
      Vector(Value.Str(n), Value.Str(pick(rnd, cities)), Value.Str(pick(rnd, segments)),
        Value.Num(2018 + rnd.nextInt(8)))))
    // some customers have no invoices: the absent shape has an answer
    val buyers = custNames.take(custNames.size - 1 - rnd.nextInt(2))
    val invoices = Table("invoices", Vector("id", "customer", "status", "amount", "day"),
      Vector.tabulate(8 + rnd.nextInt(8))(i => Vector(Value.Num(i + 1), Value.Str(pick(rnd, buyers)),
        Value.Str(pick(rnd, statuses)), Value.Num(20 + rnd.nextInt(480)),
        Value.Str(f"2026-${1 + rnd.nextInt(10)}%02d-${1 + rnd.nextInt(27)}%02d"))))
    val products = Table("products", Vector("id", "category", "price", "stock"),
      Vector.tabulate(6 + rnd.nextInt(6))(i => Vector(Value.Num(i + 1), Value.Str(pick(rnd, categories)),
        Value.Num(5 + rnd.nextInt(195)), Value.Num(rnd.nextInt(60)))))
    val tables = Vector(customers, invoices) ++ (if rnd.nextBoolean() then Vector(products) else Vector.empty) ++
      (if rnd.nextInt(4) == 0 then Vector(Pairs.edges(rnd)) else Vector.empty)
    tables.map(t => t.name -> t).toMap

  // TEACH_DOMAINS: the points over the domains' tables (`Domains`: money, dates, months — specs/everyday.md)
  private val domains = sys.env.contains("TEACH_DOMAINS")
  def point(seed: Long): (Map[String, Table], Program) =
    val tables = if domains then { val t = okay.desk.Domains.any(scala.util.Random(seed)); Map(t.name -> t) }
      else schema(seed)
    (tables, Space.random(scala.util.Random(seed * 7919 + 1), Schema(tables)))

  // ---- the model, through okay-mlx's gateway (OpenAI's chat completions)

  final class Model(url: String, model: String):
    private val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30)).build()
    def ask(system: String, user: String, maxTokens: Int = 700, temperature: Double = 0.7): String =
      val body = Json.Obj(Vector("model" -> Json.Str(model), "temperature" -> Json.num(temperature),
        "max_tokens" -> Json.num(maxTokens),
        "messages" -> Json.Arr(Vector(Json.Obj(Vector("role" -> Json.Str("system"), "content" -> Json.Str(system))),
          Json.Obj(Vector("role" -> Json.Str("user"), "content" -> Json.Str("/no_think\n" + user)))))))
      val req = HttpRequest.newBuilder(URI.create(s"$url/chat/completions")).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.render)).build()
      // a gateway restarted (memory taken back, the service brought it up again) loses the connection: tried again
      // for up to ten minutes before the run gives up
      def send(tries: Int): HttpResponse[String] =
        try client.send(req, HttpResponse.BodyHandlers.ofString())
        catch case e: java.io.IOException if tries > 0 =>
          println(s"  the gateway did not answer (${e.getMessage}); again in 30 s")
          Thread.sleep(30000)
          send(tries - 1)
      val res = send(20)
      JsonText.parse(res.body).toOption.flatMap(_.field("choices")).collect { case Json.Arr(cs) => cs }
        .flatMap(_.headOption).flatMap(_.field("message")).flatMap(_.field("content"))
        .collect { case Json.Str(s) => s.replaceAll("(?s)<think>.*?</think>", "").trim }.getOrElse("")

  /** the JSON value inside a model's answer: the outermost array or object, from the first bracket that parses */
  def json(text: String): Option[Json] =
    val starts = text.indices.filter(i => text(i) == '[' || text(i) == '{')
    starts.view.flatMap(i => (text.length until i by -1).view
      .flatMap(j => JsonText.parse(text.substring(i, j)).toOption)
      .headOption).headOption

  // ---- the schema as the judge and the teacher see it

  def described(tables: Map[String, Table]): String =
    val s = Schema(tables)
    tables.values.toVector.sortBy(_.name).map { t =>
      val cols = s.of(t.name).map { c =>
        val vs = if c.kind == Kind.Text then t.rows.map(_(t.columns.indexOf(c.name)).text).distinct.take(10)
          else Vector.empty
        c.name + (if vs.nonEmpty then vs.mkString(" (values: ", ", ", ")") else s" (${c.kind.toString.toLowerCase})")
      }
      s"- ${t.name}: ${cols.mkString("; ")}"
    }.mkString("\n") + (if s.links.isEmpty then ""
      else "\nlinks: " + s.links.map(l => s"${l.from}.${l.column} -> ${l.to}").mkString(", "))

  // ---- the teacher's rewrites

  val languages = Map("en" -> "English", "ru" -> "Russian", "uk" -> "Ukrainian")

  def rewrites(m: Model, tables: Map[String, Table], skeleton: String, lang: String, k: Int = 3): Vector[String] =
    val system =
      s"""You write questions people ask about their data. You get the tables and a question written by a grammar;
         |rewrite it $k different ways a real person would ask it, in ${languages(lang)}: short or chatty, as typed into a
         |chat; say the tables, columns and values in ${languages(lang)}'s own words where people would (a status
         |"unpaid" may become "не оплачено" or "неоплаченные"; "customers" may become "клиенты"). Keep the meaning
         |EXACTLY: the same table, the same conditions and numbers, the same grouping, aggregate, order, direction and
         |number of rows; add nothing, leave nothing out. Answer with a JSON array of $k strings, nothing else.""".stripMargin
    json(m.ask(system, s"Tables:\n${described(tables)}\n\nQuestion: $skeleton"))
      .collect { case Json.Arr(xs) => xs.collect { case Json.Str(s) if s.trim.nonEmpty => s.trim } }
      .getOrElse(Vector.empty)

  // ---- the judge: a question read back as choices of the space, with no program shown

  val judgeRule: String =
    """You read a question about tables into its meaning, as choices. Answer ONE JSON object:
      |{"kind": "rows" | "absent" | "closure",
      | "table": the table whose rows are counted, listed or aggregated (for absent: the table whose rows have none),
      | "absent_from": for absent, the table that has no row for them, else null,
      | "join": a linked table whose column the question also uses, else null,
      | "conditions": [{"column": "table.column", "op": "=" | ">" | "<" | "month", "value": a value OF THAT COLUMN as
      |   listed, or a number; for "month" (a month of a date column: "in March", «в марте») the month as "yyyy-mm"}],
      | "group": "table.column" or null,
      | "aggregate": {"fn": "count" | "sum" | "avg" | "min" | "max", "column": "table.column" or null for count} or null,
      | "order": {"by": "table.column" or "aggregate", "desc": true | false} or null,
      | "limit": a number of rows or null,
      | "node": for closure, the node, else null, "forward": for closure, true when asked what it reaches, else false}
      |"kind" is "rows" for every question about a table's rows, including counts, sums and other aggregates.
      |Use the listed values exactly: if the question says "не оплачено" and the column has "unpaid", answer "unpaid".
      |"How many", "number of", "count", "сколько", "количество" is the aggregate count (with a group: per group).
      |"The largest / smallest X among T", "maximum / minimum X", "total / sum of X", "average X" is that aggregate
      |of X (one number) — not an order. "The T row with the largest X", "which T has the highest X" is order by X desc
      |with limit 1 (the row).""".stripMargin

  /** the judge's reading compared with the program: None when every constructor agrees, else the first that does not */
  def disagreement(p: Program, j: Json): Option[String] =
    def str(js: Option[Json]) = js.collect { case Json.Str(s) => s }
    def num(js: Option[Json]) = js.collect { case Json.Num(n) => n.toDouble }
    def col(s: Option[String]) = s.map(x => x.split('.').last)
    val kind = str(j.field("kind"))
    p match
      case Program.Closure(_, node, forward) =>
        if !kind.contains("closure") then Some("kind")
        else if !str(j.field("node")).contains(node) then Some("node")
        else if !j.field("forward").contains(Json.Bool(forward)) then Some("direction")
        else None
      case Program.Absent(target, link, where) =>
        if !kind.contains("absent") then Some("kind")
        else if !str(j.field("table")).contains(target) then Some("table")
        else if !str(j.field("absent_from")).contains(link.from) then Some("absent_from")
        else conditions(where, j)
      case Program.Rel(from, link, where, group, agg, order, limit) =>
        if !kind.contains("rows") then Some("kind")
        else if !str(j.field("table")).contains(from) then Some("table")
        else if conditions(where, j).nonEmpty then conditions(where, j)
        else if col(str(j.field("group"))) != group.map(_.name) then Some("group")
        else
          val ja = j.field("aggregate").filter(_ != Json.Null)
          val aggOk = (agg, ja) match
            case (None, None) => true
            case (Some((fn, c)), Some(a)) =>
              str(a.field("fn")).contains(fn) && (fn == "count" || col(str(a.field("column"))) == c.map(_.name))
            case _ => false
          if !aggOk then Some("aggregate")
          else
            val jo = j.field("order").filter(_ != Json.Null)
            val orderOk = (order, jo) match
              case (None, None) => true
              case (Some((c, desc)), Some(o)) =>
                val by = str(o.field("by"))
                val sameBy = by.exists(b => col(Some(b)).contains(c) || (b == "aggregate" && agg.nonEmpty))
                sameBy && o.field("desc").contains(Json.Bool(desc))
              case _ => false
            if !orderOk then Some("order")
            else if num(j.field("limit")).map(_.toInt) != limit &&
              !(limit.contains(1) && order.nonEmpty && num(j.field("limit")).contains(1.0)) then
              Some("limit")
            else None

  private def conditions(where: Vector[(Col, String, Value)], j: Json): Option[String] =
    val read = j.field("conditions").collect { case Json.Arr(xs) => xs }.getOrElse(Vector.empty).map { c =>
      (c.field("column").collect { case Json.Str(s) => s.split('.').last }.getOrElse(""),
        c.field("op").collect { case Json.Str(s) => s }.getOrElse(""),
        c.field("value").map {
          case Json.Str(s) => s; case Json.Num(n) => Value.Num(n.toDouble).text; case x => x.render }
          .getOrElse(""))
    }.toSet
    val gold = where.map((c, op, v) => (c.name, op, v.text)).toSet
    if read == gold then None else Some("conditions")

  def judge(m: Model, tables: Map[String, Table], question: String): Option[Json] =
    json(m.ask(judgeRule, s"Tables:\n${described(tables)}\n\nQuestion: $question", 500, temperature = 0))
      .filter(_.isInstanceOf[Json.Obj])

  // ---- the runs: the judge on skeletons first; then pairs, appended as JSON lines (resumable by seed)

  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("pairs")
    val m = Model(sys.env.getOrElse("TEACHER_URL", "http://127.0.0.1:8090/v1"),
      sys.env.getOrElse("TEACHER_MODEL", "mlx-community/Qwen3.6-35B-A3B-4bit"))
    val from = args.lift(1).map(_.toLong).getOrElse(1L); val until = args.lift(2).map(_.toLong).getOrElse(from + 20)
    val reasons = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val t0 = System.nanoTime()
    mode match
      case "judge" =>
        // the judge judged: it reads the grammar's own sentences, whose programs are known
        var agree = 0; var n = 0
        for seed <- from until until do
          val (tables, p) = point(seed)
          val skeleton = Phrase.say(scala.util.Random(seed), p, Schema(tables), ru = false)
          val read = judge(m, tables, skeleton)
          val d = read.fold(Some("no answer"))(disagreement(p, _))
          n += 1; if d.isEmpty then agree += 1 else reasons(d.get) += 1
          if d.nonEmpty then
            println(s"  [$seed] ${d.get}: $skeleton\n      gold $p\n      read ${read.map(_.render).getOrElse("-")}")
        println(f"judge on skeletons: $agree of $n agree; ${reasons.toVector.sortBy(-_._2).mkString(", ")} " +
          f"(${(System.nanoTime() - t0) / 1e9}%.0f s)")
      case _ =>
        val out = Path.of(args.lift(3).getOrElse("data/teacher-pairs.jsonl"))
        Files.createDirectories(Option(out.getParent).getOrElse(Path.of(".")))
        val langs = sys.env.getOrElse("TEACHER_LANGS", "ru,en,uk").split(",").toVector
        var made = 0; var kept = 0
        // resumable: the seeds done are kept beside the pairs (a seed whose rewrites were all rejected leaves no pair)
        val doneFile = Path.of(out.toString + ".done")
        val done = if Files.exists(doneFile) then Files.readAllLines(doneFile).asScala.flatMap(_.trim.toLongOption).toSet
          else Set.empty[Long]
        for seed <- from until until if !done(seed) do
          val (tables, p) = point(seed)
          val lang = langs((seed % langs.size).toInt)
          val skeleton = Phrase.say(scala.util.Random(seed), p, Schema(tables), ru = false)
          val program = Space.compile(p, tables).map(Query.print).getOrElse("")
          rewrites(m, tables, skeleton, lang).foreach { q =>
            made += 1
            judge(m, tables, q).fold(Some("no answer"))(disagreement(p, _)) match
              case None =>
                kept += 1
                val line = Json.Obj(Vector("seed" -> Json.num(seed), "lang" -> Json.Str(lang),
                  "skeleton" -> Json.Str(skeleton),
                  "question" -> Json.Str(q), "program" -> Json.Str(program))).render
                Files.writeString(out, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
              case Some(r) => reasons(r) += 1
          }
          Files.writeString(doneFile, s"$seed\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
          println(f"seed $seed ($lang): $kept of $made kept so far (${(System.nanoTime() - t0) / 1e9}%.0f s)")
        println(s"pairs: $kept of $made rewrites accepted; " +
          s"rejected by ${reasons.toVector.sortBy(-_._2).mkString(", ")}")
