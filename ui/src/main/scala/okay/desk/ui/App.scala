package okay.desk.ui

import okay.business.scala3.*
import okay.desk.{Exact, Prompt, Query, Weights}
import okay.desk.Table as Data
import okay.http.{Fetch, Response}

/**
 * THE DESK AS AN OKAY-UI PROGRAM (specs/data.md §16, §19): two screens of one state. THE CHAT: the model (chosen,
 * loaded — its progress shown in place), a button to the data, the conversation, the question. THE DATA: the tables
 * open (each with a button that closes it alone), files or a folder, an address on the internet, the examples of
 * open data (each opened by a press). A press is a message, a message the next state; what the world does — a file
 * read, an address fetched (okay-http's `Fetch`), the model (`Model`), the disk (`Disk`: every table kept, there
 * again at the next visit) — is an effect a page answers. The same program runs under a test script (`AppSuite`).
 */
object App:
  /** a line of the conversation: who, what; a result's program and its note */
  final case class Entry(role: String, text: String, program: Option[String] = None, note: Option[String] = None)
  /** `phone`: a small screen — the student with the readers (122 MB) is what fits; the models that generate are a
   * computer's */
  final case class State(place: String = "chat", tables: Map[String, Data] = Map.empty, log: Vector[Entry] = Vector.empty,
    model: Option[String] = None, modelName: String = Model.default, problem: Option[String] = None, phone: Boolean = false)

  enum Msg:
    case Go(place: String)
    /** files chosen: each its path and its text */
    case Opened(files: List[(String, String)])
    case OpenUrl(url: String)
    case OpenUrls(urls: Vector[String])
    case Close(table: String)
    case Load(model: String)
    case Ask(question: String)

  val accept = ".csv,.tsv,.json,.jsonl,.ndjson"

  /** the program: the tables kept on the disk opened again, the addresses given (a link) fetched, then a screen at a
   * time */
  def main[R[_]](opens: List[String], phone: Boolean = false)(using In[Ui, R], In[Model, R], In[Fetch, R], In[Disk, R])
      : Unit ! R =
    for
      names <- Disk.names[R]
      kept <- names.foldLeft(pure[State, R](State(phone = phone))) { (acc, name) =>
        acc.flatMap(s => Disk.get[R](name).map(_.fold(s)(text => opened(s, name, text, quietly = true)))) }
      begun <- opens.foldLeft(pure[State, R](kept))((acc, u) => acc.flatMap(s => step[R](s, Msg.OpenUrl(u))))
      _ <- loop[R](begun)
    yield ()

  def loop[R[_]](s: State)(using In[Ui, R], In[Model, R], In[Fetch, R], In[Disk, R]): Unit ! R =
    show[Msg, R](render(s)).flatMap(m => step[R](s, m).flatMap(loop[R]))

  /** a file's text as a table, added; a problem said */
  def opened(s: State, name: String, text: String, quietly: Boolean = false): State =
    Data.parse(name, text) match
      case Right(t) => s.copy(tables = s.tables + (t.name -> t),
        log = if quietly then s.log else s.log :+ Entry("system", s"opened $name as table ${t.name}: ${t.size} rows, ${t.columns.size} columns"))
      case Left(e) => s.copy(log = s.log :+ Entry("system", s"$name not read: $e"))

  /** the name a file is a table by */
  private def nameOf(url: String): String =
    url.split("[?#]").head.split("/").last match
      case n if n.toLowerCase.matches(".*\\.(csv|tsv|json|jsonl|ndjson)$") => n
      case n => n + ".csv"

  def step[R[_]](s: State, m: Msg)(using In[Ui, R], In[Model, R], In[Fetch, R], In[Disk, R]): State ! R =
    val cleared = s.copy(problem = None)
    m match
      case Msg.Go(place) => pure(cleared.copy(place = place))
      case Msg.Opened(files) =>
        val fit = files.filter((path, _) => path.toLowerCase.matches(".*\\.(csv|tsv|json|jsonl|ndjson)$"))
        if fit.isEmpty then pure(cleared.copy(problem = Some("no CSV, TSV or JSON file among those")))
        else fit.foldLeft(pure[State, R](cleared)) { (acc, f) => acc.flatMap { st =>
          val name = f._1.split("/").last
          val next = opened(st, name, f._2)
          if next.tables.size > st.tables.size || next.tables.contains(Data.parse(name, f._2).map(_.name).getOrElse(""))
          then Disk.put[R](name, f._2).map(_ => next) else pure(next) } }
      case Msg.OpenUrls(urls) =>
        urls.foldLeft(pure[State, R](cleared))((acc, u) => acc.flatMap(st => step[R](st, Msg.OpenUrl(u))))
      case Msg.OpenUrl(url) =>
        val u = url.trim; val name = nameOf(u)
        for
          _ <- working[R]("okay-desk")(Part.info(s"fetching $u…"))
          r <- perform[Fetch](Fetch.Call("GET", u, Map.empty, "", (r: Response) => r))
          next <-
            if r.status == 200 then Disk.put[R](name, r.body).map(_ => opened(cleared, name, r.body))
            else pure[State, R](cleared.copy(log = cleared.log :+ Entry("system", s"$u not read: " +
              (if r.status == 0 then r.body else s"HTTP ${r.status}") + " — the host may not allow cross-origin reads; download the file and open it")))
        yield next
      case Msg.Close(name) =>
        // the file kept under the table's name, or the file's name: both forgotten
        val files = cleared.tables.get(name).toList.map(_ => name)
        files.foldLeft(pure[Unit, R](()))((acc, f) => acc.flatMap(_ => Disk.remove[R](f))).flatMap(_ =>
          Disk.names[R].flatMap(ns => ns.filter(n => n.stripSuffix(".csv").stripSuffix(".tsv").stripSuffix(".json").stripSuffix(".jsonl").stripSuffix(".ndjson") == name)
            .foldLeft(pure[Unit, R](()))((acc, f) => acc.flatMap(_ => Disk.remove[R](f)))))
          .map(_ => cleared.copy(tables = cleared.tables - name, log = cleared.log :+ Entry("system", s"closed $name")))
      case Msg.Load(name) =>
        // the loading begun; its progress shown on this screen until it ends
        def follow(st: State): State ! R = Model.progress[R].flatMap {
          case Left(words) => perform[Ui](Ui.Working(Screen[Nothing]("okay-desk", render(st, loading = Some(words)).parts, Nil))).flatMap(_ => follow(st))
          case Right(Right(what)) => pure(st.copy(model = Some(what), modelName = name))
          case Right(Left(why)) => pure(st.copy(problem = Some(why)))
        }
        Model.begin[R](name).flatMap(_ => follow(cleared.copy(modelName = name)))
      case Msg.Ask(question) =>
        val q = question.trim
        if s.model.isEmpty then pure(cleared.copy(problem = Some("load the model first")))
        else if s.tables.isEmpty then pure(cleared.copy(problem = Some("open a file first — the Data button")))
        else if q.isEmpty then pure(cleared)
        else
          val asked = cleared.copy(log = cleared.log :+ Entry("user", q))
          for
            answered <- think[R](asked, q)
            result <- answer[R](asked, q, answered, retried = false)
          yield result

  /** THE QUESTION THOUGHT ABOUT, ITS STEPS SHOWN: the program asked for, then followed — each moment the steps so far
   * (what is decided: the table, the form of the answer, the columns, the condition; the last the one being decided
   * now, with its seconds) in place of the answer, until the program comes */
  def think[R[_]](s: State, question: String, retry: Option[(String, String)] = None)(
      using In[Ui, R], In[Model, R]): Either[String, String] ! R =
    def follow: Either[String, String] ! R = Model.thought[R].flatMap {
      case Left(steps) =>
        perform[Ui](Ui.Working(Screen[Nothing]("okay-desk", render(s, thinking = Some(steps)).parts, Nil)))
          .flatMap(_ => follow)
      case Right(answered) => pure(answered)
    }
    Model.ask[R](s.tables, question, retry).flatMap(_ => follow)

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
          case Left(problem) if !retried && !Model.reads(s.modelName) =>
            val told = s.copy(log = s.log :+ Entry("system", s"the program failed: $problem — asked to fix it"))
            for
              again <- think[R](told, question, Some((json, problem)))
              next <- answer[R](told, question, again, retried = true)
            yield next
          case Left(problem) => pure(s.copy(log = s.log :+ Entry("system", s"the program failed: $problem\n$json")))
          case Right((q, result)) =>
            val note = if Model.reads(s.modelName) then "read from the model's states by the readers — not generated"
              else if result.name == "weights" then "computed by the transformer compiled from the closure program (okay-inlay), in this page"
              else "computed by the exact engine in this page"
            val shown = s.copy(log = s.log :+ Entry("result", result.render(50), Some(Query.print(q)), Some(note)))
            Model.summary[R](question, q, result).map {
              case Some(words) if Prompt.faithful(words, result) => shown.copy(log = shown.log :+ Entry("model", words))
              case Some(_) => shown.copy(log = shown.log :+ Entry("system", "the model's summary stated a number not in the result and was dropped"))
              case None => shown
            }

  // ---- the screens
  val filesForm: Form[List[(String, String)]] = Form.one(Field.files("Files, or a whole folder (CSV, TSV, JSON)", accept))
  val urlForm: Form[String] = Form.one(Field.text("The address of a file on the internet (the host must allow cross-origin reads)"))
  def modelForm(current: String): Form[String] =
    Form.one(Field.choice[String]("The model", Model.choices.map((k, l) => (l, k))*).initially(current))
  val questionForm: Form[String] = Form.one(Field.longText("Ask about the data: how many orders are unpaid? total by customer? …"))

  private def top(s: State): List[Part] =
    List(Part.block("top")(Part.block("brand")(Part.text("okay-desk")),
      Part.text("Chat with your data — in the browser, offline. Open files from your machine or the internet; a small model " +
        "in the page reads the question into a program, an exact engine runs it. Numbers come from the engine, never " +
        "from the model. Nothing leaves the browser."))) ++ s.problem.toList.map(Part.problem)

  /** the screen of the place; `loading`: the model's progress in place of its form; `thinking`: a question's steps so
   * far in place of its answer */
  def render(s: State, loading: Option[String] = None, thinking: Option[Vector[String]] = None): Screen[Msg] =
    if s.place == "data" then data(s) else chat(s, loading, thinking)

  /** a question's steps: those decided marked done, the last the one going on */
  def steps(lines: Vector[String]): Part =
    Part.block("thinking")((Part.block("small")(Part.text("thinking — what is decided so far:")) +:
      lines.zipWithIndex.map((l, i) => if i < lines.size - 1 then Part.text(s"✓ $l") else Part.info(s"⋯ $l")))*)

  /** THE CHAT: the model (its form, or its progress), the way to the data, the conversation, the question */
  def chat(s: State, loading: Option[String], thinking: Option[Vector[String]] = None): Screen[Msg] =
    val models = modelForm(s.modelName)
    val modelPart: Part = loading.fold(models: Part)(words => Part.block("loading")(Part.info(words)))
    val note = Part.block("model-note")(Part.text(s.model.getOrElse(
      if s.phone then "not loaded yet — on a phone the student with the readers (122 MB) is the one that fits; the models that generate (0.4–1.7 GB) are for a computer"
      else "the model is not loaded yet")))
    val dataNote = Part.block("data-note")(Part.text(
      if s.tables.isEmpty then "no data open yet" else s"the data: ${s.tables.keys.toList.sorted.mkString(", ")}"), Part.slot("data"))
    val log = s.log.map { e =>
      Part.block(e.role)((List(if e.role == "result" then Part.code(e.text) else Part.text(e.text)) ++
        e.note.toList.map(n => Part.block("small")(Part.text(n))) ++
        e.program.toList.map(p => Part.code("the program", p.split("\n").toList, Set.empty)))*)
    }
    // while a question is thought about: its steps where the answer will be, no question form
    val end: List[Part] = thinking.fold(List(questionForm: Part))(lines => List(steps(lines)))
    Screen[Msg]("okay-desk")((top(s) ++ List(modelPart, note, dataNote) ++ log ++ end)*)(
      (List(Button(if s.tables.isEmpty then "Data: open files" else s"Data: ${s.tables.size} open")(Msg.Go("data"): Msg).at("data")) ++
        // (while loading, no other load; while thinking, no question form and no other question)
        (if loading.isDefined || thinking.isDefined then Nil
         else List(Button.submit("Load the model", models, Style.Plain)(Msg.Load(_): Msg))) ++
        (if thinking.isDefined then Nil else List(Button.submit("Ask", questionForm)(Msg.Ask(_): Msg))))*)

  /** THE DATA: the tables open, files or a folder, an address, the examples */
  def data(s: State): Screen[Msg] =
    val tables = if s.tables.isEmpty then Vector(Part.block("small")(Part.text("no file open yet")))
      else Vector(Part.tree("", s.tables.values.toVector.sortBy(_.name).map(t => Part.TreeNode(t.name,
        s"${t.name}: ${t.size} rows, ${t.columns.size} columns", children = t.describe.split("\n").drop(1).toList.map(l =>
          Part.TreeNode(t.name + ":" + l.trim.takeWhile(_ != ':'), l.trim))))*), Part.block("tables")(Part.slot("tables")))
    val examples = Examples.all.map(e => Part.block("example", e.name)(Part.text(e.about),
      Part.block("small")(Part.text(e.urls.mkString("  "))), Part.text(s"Source: ${e.source}"), Part.slot(s"example-${e.key}")))
    val closes = s.tables.keys.toList.sorted.map(n => Button(s"Close $n")(Msg.Close(n): Msg).at("tables"))
    val opens = Examples.all.map(e => Button("Open")(Msg.OpenUrls(e.urls): Msg).at(s"example-${e.key}"))
    Screen[Msg]("okay-desk — the data")((top(s) ++ List(Part.block("back")(Part.slot("back")),
      Part.section("The tables")((tables :+ (filesForm: Part) :+ (urlForm: Part))*), Part.section("Examples of open data")((List(Part.text(
        "Each file comes straight from its host into your browser; okay-desk reads it there and nothing leaves the page. " +
        "A host must allow cross-origin reads for that — these do. Every file opened is kept on this device for the next visit.")) ++ examples)*)))*)(
      (List(Button("← Back to the chat", Style.Primary)(Msg.Go("chat"): Msg).at("back")) ++ closes ++ opens.toList ++ List(
        Button.submit("Open the files", filesForm, Style.Plain)(Msg.Opened(_): Msg),
        Button.submit("Open the address", urlForm, Style.Plain)(Msg.OpenUrl(_): Msg)))*)
