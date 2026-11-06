package okay.desk.ui

import okay.business.scala3.*
import okay.desk.{Exact, Prompt, Query, Weights}
import okay.desk.Table as Data
import okay.http.{Fetch, Response}

/**
 * THE DESK AS AN OKAY-UI PROGRAM (specs/data.md §16): one screen, shown again after every press — the data open
 * (each table with its schema and a button that closes it alone), the ways to open more (files or a folder, an
 * address on the internet, the examples), the model, the conversation, the question. A press is a message, a
 * message the next state; what the world does — a file read, an address fetched (okay-http's `Fetch`), the model
 * (`Model`) — is an effect a page answers. The same program runs under a test script (`AppSuite`).
 */
object App:
  /** a line of the conversation: who, what; a result's program and its note */
  final case class Entry(role: String, text: String, program: Option[String] = None, note: Option[String] = None)
  /** `phone`: a small screen — the cut model (244 MB) is what fits; the models that generate are a computer's */
  final case class State(tables: Map[String, Data] = Map.empty, log: Vector[Entry] = Vector.empty,
    model: Option[String] = None, modelName: String = "readers", problem: Option[String] = None, phone: Boolean = false)

  enum Msg:
    /** files chosen: each its path and its text */
    case Opened(files: List[(String, String)])
    case OpenUrl(url: String)
    case Close(table: String)
    case Load(model: String)
    case Ask(question: String)

  val accept = ".csv,.tsv,.json,.jsonl,.ndjson"

  /** the program: the addresses to open first (the examples' links), then a screen at a time */
  def main[R[_]](opens: List[String], phone: Boolean = false)(using In[Ui, R], In[Model, R], In[Fetch, R]): Unit ! R =
    opens.foldLeft(pure[State, R](State(phone = phone)))((acc, u) => acc.flatMap(s => step[R](s, Msg.OpenUrl(u)))).flatMap(loop[R])

  def loop[R[_]](s: State)(using In[Ui, R], In[Model, R], In[Fetch, R]): Unit ! R =
    show[Msg, R](render(s)).flatMap(m => step[R](s, m).flatMap(loop[R]))

  /** a file's text as a table, added; a problem said */
  def opened(s: State, name: String, text: String): State =
    Data.parse(name, text) match
      case Right(t) => s.copy(tables = s.tables + (t.name -> t),
        log = s.log :+ Entry("system", s"opened $name as table ${t.name}: ${t.size} rows, ${t.columns.size} columns"))
      case Left(e) => s.copy(log = s.log :+ Entry("system", s"$name not read: $e"))

  def step[R[_]](s: State, m: Msg)(using In[Ui, R], In[Model, R], In[Fetch, R]): State ! R =
    val cleared = s.copy(problem = None)
    m match
      case Msg.Opened(files) =>
        val fit = files.filter((path, _) => path.toLowerCase.matches(".*\\.(csv|tsv|json|jsonl|ndjson)$"))
        pure(if fit.isEmpty then cleared.copy(problem = Some("no CSV, TSV or JSON file among those"))
          else fit.foldLeft(cleared)((st, f) => opened(st, f._1.split("/").last, f._2)))
      case Msg.OpenUrl(url) =>
        val u = url.trim
        val name = u.split("[?#]").head.split("/").last match
          case n if n.toLowerCase.matches(".*\\.(csv|tsv|json|jsonl|ndjson)$") => n
          case n => n + ".csv"
        for
          _ <- working[R]("okay-desk")(Part.info(s"fetching $u…"))
          r <- perform[Fetch](Fetch.Call("GET", u, Map.empty, "", (r: Response) => r))
        yield
          if r.status == 200 then opened(cleared, name, r.body)
          else cleared.copy(log = cleared.log :+ Entry("system", s"$u not read: " + (if r.status == 0 then r.body else s"HTTP ${r.status}") +
            " — the host may not allow cross-origin reads; download the file and open it"))
      case Msg.Close(name) =>
        pure(cleared.copy(tables = cleared.tables - name, log = cleared.log :+ Entry("system", s"closed $name")))
      case Msg.Load(name) =>
        for
          _ <- working[R]("okay-desk")(Part.info(s"loading ${Model.choices.toMap.getOrElse(name, name)}…"))
          got <- Model.load[R](name)
        yield got match
          case Right(what) => cleared.copy(model = Some(what), modelName = name)
          case Left(why) => cleared.copy(problem = Some(why))
      case Msg.Ask(question) =>
        val q = question.trim
        if s.model.isEmpty then pure(cleared.copy(problem = Some("load the model first")))
        else if s.tables.isEmpty then pure(cleared.copy(problem = Some("open a file first")))
        else if q.isEmpty then pure(cleared)
        else
          val asked = cleared.copy(log = cleared.log :+ Entry("user", q))
          for
            _ <- working[R]("okay-desk")(Part.info(if s.modelName == "readers" then "reading the question…" else "the model is writing the program…"))
            answered <- Model.program[R](s.tables, q)
            result <- answer[R](asked, q, answered, retried = false)
          yield result

  /** the program run by the exact engine — or, where a model generates, asked once more with what failed */
  def answer[R[_]](s: State, question: String, answered: Either[String, String], retried: Boolean)(
      using In[Ui, R], In[Model, R]): State ! R =
    answered match
      case Left(words) => pure(s.copy(log = s.log :+ Entry("model", words)))
      case Right(json) =>
        val ran = Query.parse(json).flatMap {
          case q: Query.Relational => Exact.run(s.tables, q).map(r => (q: Query, r))
          case q: Query.Logical => Weights.either(s.tables, q).map((r, by) => (q: Query, r.copy(name = by)))
        }
        ran match
          case Left(problem) if !retried && s.modelName != "readers" =>
            for
              _ <- working[R]("okay-desk")(Part.info(s"the program failed: $problem — asking the model to fix it"))
              again <- Model.program[R](s.tables, question, Some((json, problem)))
              next <- answer[R](s.copy(log = s.log :+ Entry("system", s"the program failed: $problem — asked to fix it")), question, again, retried = true)
            yield next
          case Left(problem) => pure(s.copy(log = s.log :+ Entry("system", s"the program failed: $problem\n$json")))
          case Right((q, result)) =>
            val note = if s.modelName == "readers" then "read from the model's states by the readers — not generated"
              else if result.name == "weights" then "computed by the transformer compiled from the closure program (okay-inlay), in this page"
              else "computed by the exact engine in this page"
            val shown = s.copy(log = s.log :+ Entry("result", result.render(50), Some(Query.print(q)), Some(note)))
            Model.summary[R](question, q, result).map {
              case Some(words) if Prompt.faithful(words, result) => shown.copy(log = shown.log :+ Entry("model", words))
              case Some(_) => shown.copy(log = shown.log :+ Entry("system", "the model's summary stated a number not in the result and was dropped"))
              case None => shown
            }

  // ---- the screen
  val filesForm: Form[List[(String, String)]] = Form.one(Field.files("Files, or a whole folder (CSV, TSV, JSON)", accept))
  val urlForm: Form[String] = Form.one(Field.text("The address of a file on the internet (the host must allow cross-origin reads)"))
  val questionForm: Form[String] = Form.one(Field.longText("Ask about the data: how many orders are unpaid? total by customer? …"))

  // THE ORDER OF THE PAGE: the model first (buttons at the top, one a model, the current one marked), then the data
  // and the ways to open more, then the conversation, and the question last — a form is drawn where its button is
  // (okay-faces), so the question's button is the last
  def render(s: State): Screen[Msg] =
    val head = List(Part.block("top")(Part.block("brand")(Part.text("okay-desk")),
      Part.text("Chat with your data — in the browser, offline. Open files from your machine or the internet; a small model " +
        "in the page reads the question into a program, an exact engine runs it. Numbers come from the engine, never " +
        "from the model. Nothing leaves the browser.")),
      Part.section("The model")(Part.block("models")(Part.slot("models")), Part.block("small")(Part.text(s.model.getOrElse(
        if s.phone then "not loaded yet — on a phone the cut model with the readers (244 MB) is the one that fits; the models that generate (0.4–1.7 GB) are for a computer"
        else "not loaded yet"))))) ++ s.problem.toList.map(Part.problem)
    // the open tables as a tree: each closed, its columns under it; the buttons that close them in one row
    val tables = if s.tables.isEmpty then Vector(Part.block("small")(Part.text("no file open yet")))
      else Vector(Part.tree("", s.tables.values.toVector.sortBy(_.name).map(t => Part.TreeNode(t.name,
        s"${t.name}: ${t.size} rows, ${t.columns.size} columns", children = t.describe.split("\n").drop(1).toList.map(l =>
          Part.TreeNode(t.name + ":" + l.trim.takeWhile(_ != ':'), l.trim))))*), Part.block("tables")(Part.slot("tables")))
    val data = Part.section("The data")((tables :+ (filesForm: Part) :+ (urlForm: Part) :+
      Part.block("small")(Part.link("Examples of open data — files that open by one link", "examples.html")))*)
    val log = s.log.map { e =>
      Part.block(e.role)((List(if e.role == "result" then Part.code(e.text) else Part.text(e.text)) ++
        e.note.toList.map(n => Part.block("small")(Part.text(n))) ++
        e.program.toList.map(p => Part.code("the program", p.split("\n").toList, Set.empty)))*)
    }
    val closes = s.tables.keys.toList.sorted.map(n => Button(s"Close $n")(Msg.Close(n): Msg).at("tables"))
    val models = Model.choices.map((k, l) => Button(l, if s.modelName == k && s.model.isDefined then Style.Primary else Style.Plain)(Msg.Load(k): Msg).at("models"))
    Screen[Msg]("okay-desk")((head ++ List(data) ++ log :+ (questionForm: Part))*)(
      (models ++ closes ++ List(
        Button.submit("Open the files", filesForm, Style.Plain)(Msg.Opened(_): Msg),
        Button.submit("Open the address", urlForm, Style.Plain)(Msg.OpenUrl(_): Msg),
        Button.submit("Ask", questionForm)(Msg.Ask(_): Msg)))*)
