package okay.desk.ui

import okay.business.scala3.*
import okay.desk.{Exact, Prompt, Query, Weights}
import okay.desk.calc.{Dialogue, Language, Ty}
import Dialogue.Turn
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
    model: Option[String] = None, modelName: String = Model.default, problem: Option[String] = None, phone: Boolean = false,
    /** a question the readers were not sure about and the readings they hesitated between: offered as buttons */
    asking: Option[(String, Vector[String])] = None,
    /** the dialogue's ask (specs/pilot.md §3.2): the hole's question, when `asking` came from the calculus — the choice
     * then answers the hole and the same term is refined, instead of the question rewritten */
    hole: Option[String] = None)

  enum Msg:
    case Go(place: String)
    /** files chosen: each its path and its text */
    case Opened(files: List[(String, String)])
    case OpenUrl(url: String)
    case OpenUrls(urls: Vector[String])
    case Close(table: String)
    case Load(model: String)
    case Ask(question: String)
    /** THE ABSTENTION AS A DIALOGUE (specs/data.md §30): the person's choice among the readings the readers hesitated
     * between — the question asked again with the choice written into it (an exact fact for the rules) */
    case Clarify(question: String, choice: String)

  val accept = ".csv,.tsv,.json,.jsonl,.ndjson"

  /** the program: the tables kept on the disk opened again, the addresses given (a link) fetched, then a screen at a
   * time */
  def main[R[_]](opens: List[String], phone: Boolean = false)(using In[Ui, R], In[Model, R], In[Fetch, R], In[Disk, R])
      : Unit ! R =
    for
      names <- Disk.names[R]
      kept <- names.filterNot(_.startsWith(Clarified.prefix)).foldLeft(pure[State, R](State(phone = phone))) { (acc, name) =>
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
    val cleared = s.copy(problem = None, asking = None)
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
      case Msg.Load(name) => load[R](cleared, name)
      case Msg.Clarify(question, choice) =>
        val asked = cleared.copy(log = cleared.log :+ Entry("user", Clarified.saying(question, choice)), hole = None)
        // the dialogue's hole answered: the SAME question, the choice as a source; else the question rewritten
        val byHole = s.hole.isDefined
        val again = if byHole then question else Clarified.question(question, choice)
        for
          answered <- think[R](asked, again, choice = Option.when(byHole)(choice))
          result <- answer[R](asked, again, answered, retried = false)
          // an answer to the clarified question: the pair kept on the disk (the question as asked, the choice, the
          // program) — the data a later training reads
          _ <- answered.fold(_ => pure[Unit, R](()), program => Clarified.keep[R](question, choice, program))
        yield result
      case Msg.Ask(question) =>
        val q = question.trim
        // THE ACT FIRST (Пилот's act layer, specs/pilot.md §3.2): thanks, a yes or a no to the pending question, a
        // correction naming an option — none of them a question about the data
        val turn = Turn.State(s.asking.map(_._1), s.asking.map((_, cs) => (Ty.Program, cs)), Vector.empty)
        val act = Turn.decide(turn, q, Language.builtIn.values.toVector)
        act match
          case Turn.Action.Acknowledge => pure(cleared.copy(log = cleared.log :+ Entry("user", q) :+ Entry("model", Clarified.welcome(q))))
          case Turn.Action.Nothing => pure(cleared.copy(log = cleared.log :+ Entry("user", q) :+ Entry("model", Clarified.nothingPending(q))))
          case Turn.Action.Answer(pending, answers) => step[R](s, Msg.Clarify(pending, answers.last._2))
          case Turn.Action.Correct(corrected, _) => step[R](s.copy(asking = None, hole = None), Msg.Ask(corrected))
          case Turn.Action.Read(_) =>
            if s.tables.isEmpty then pure(cleared.copy(problem = Some("open a file first — the Data button")))
            else if q.isEmpty then pure(cleared)
            else
              // THE MODEL LOADED BY THE FIRST QUESTION (the operator, from the phone): the one chosen in the form, the
              // page's default else — its progress shown, then the question goes on; a loading that fails is the answer
              val ready: State ! R = if s.model.isDefined then pure(cleared) else load[R](cleared, s.modelName)
              ready.flatMap { st =>
                if st.model.isEmpty then pure(st)
                else
                  val asked = st.copy(log = st.log :+ Entry("user", q))
                  for
                    answered <- think[R](asked, q)
                    result <- answer[R](asked, q, answered, retried = false)
                  yield result
              }

  /** the model loaded: the loading begun, its progress shown on this screen until it ends */
  def load[R[_]](st0: State, name: String)(using In[Ui, R], In[Model, R]): State ! R =
    def follow(st: State): State ! R = Model.progress[R].flatMap {
      case Left(words) => perform[Ui](Ui.Working(Screen[Nothing]("okay-desk", render(st, loading = Some(words)).parts, Nil))).flatMap(_ => follow(st))
      case Right(Right(what)) => pure(st.copy(model = Some(what), modelName = name))
      case Right(Left(why)) => pure(st.copy(problem = Some(why)))
    }
    Model.begin[R](name).flatMap(_ => follow(st0.copy(modelName = name)))

  /** THE QUESTION THOUGHT ABOUT, ITS STEPS SHOWN: the program asked for, then followed — each moment the steps so far
   * (what is decided: the table, the form of the answer, the columns, the condition; the last the one being decided
   * now, with its seconds) in place of the answer, until the program comes */
  def think[R[_]](s: State, question: String, retry: Option[(String, String)] = None, choice: Option[String] = None)(
      using In[Ui, R], In[Model, R]): Either[String, String] ! R =
    def follow: Either[String, String] ! R = Model.thought[R].flatMap {
      case Left(steps) =>
        perform[Ui](Ui.Working(Screen[Nothing]("okay-desk", render(s, thinking = Some(steps)).parts, Nil)))
          .flatMap(_ => follow)
      case Right(answered) => pure(answered)
    }
    Model.ask[R](s.tables, question, retry, choice).flatMap(_ => follow)

  /** the program run by the exact engine — or, where a model generates, asked once more with what failed */
  def answer[R[_]](s: State, question: String, answered: Either[String, String], retried: Boolean)(
      using In[Ui, R], In[Model, R]): State ! R =
    answered match
      case Left(words) =>
        // "not sure: A or B": the readings offered to the person as buttons
        val offered = Clarified.readings(words).map(cs => (question, cs))
        val asked = Clarified.askedQuestion(words)
        pure(s.copy(log = s.log :+ Entry("model", asked.getOrElse(words)), asking = offered, hole = asked))
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
            // THE ANSWER IN WORDS (specs/calculus.md §10, §14): a model that generates summarizes (kept only when faithful);
            // else the answer grammar says the result — its facts are cells of the result by construction
            val ru = question.exists(c => c >= 'а' && c <= 'я' || c >= 'А' && c <= 'Я')
            // (a CONCLUSION, not a retelling: the figure that answers — a share, a margin, a gap, who is missing — derived exactly)
            val said = okay.desk.calc.Claims.text(okay.desk.calc.Claims.of(q, result, s.tables), ru)
            val sentence = Entry("model", said, None, Some(if ru then "вывод по результату: каждое число — ячейка или точный расчёт по ней" else "concluded from the result: every number is a cell of it or an exact derivation from it"))
            Model.summary[R](question, q, result).map {
              case Some(words) if Prompt.faithful(words, result) => shown.copy(log = shown.log :+ Entry("model", words))
              case Some(_) => shown.copy(log = shown.log :+ Entry("system", "the model's summary stated a number not in the result and was dropped") :+ sentence)
              case None => shown.copy(log = shown.log :+ sentence)
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
      if s.phone then "not loaded yet — the first question loads it; on a phone the core with the readers is the one that fits; the models that generate (0.4–1.7 GB) are for a computer"
      else "not loaded yet — the first question loads it")))
    val dataNote = Part.block("data-note")(Part.text(
      if s.tables.isEmpty then "no data open yet" else s"the data: ${s.tables.keys.toList.sorted.mkString(", ")}"), Part.slot("data"))
    val log = s.log.map { e =>
      Part.block(e.role)((List(if e.role == "result" then Part.code(e.text) else Part.text(e.text)) ++
        e.note.toList.map(n => Part.block("small")(Part.text(n))) ++
        e.program.toList.map(p => Part.code("the program", p.split("\n").toList, Set.empty)))*)
    }
    // while a question is thought about: its steps where the answer will be, no question form
    val end: List[Part] = thinking.fold(s.asking.toList.map((q, _) => Part.block("clarify")(Part.text(Clarified.prompt(q, s.hole)),
      Part.slot("clarify"))) ++ List(questionForm: Part))(lines => List(steps(lines)))
    val choices = if thinking.isDefined then Nil
      else s.asking.toList.flatMap((q, cs) => cs.map(c => Button(Clarified.button(q, c, s.hole.isDefined))(Msg.Clarify(q, c): Msg).at("clarify")))
    Screen[Msg]("okay-desk")((top(s) ++ List(modelPart, note, dataNote) ++ log ++ end)*)(
      (List(Button(if s.tables.isEmpty then "Data: open files" else s"Data: ${s.tables.size} open")(Msg.Go("data"): Msg).at("data")) ++
        // (while loading, no other load; while thinking, no question form and no other question)
        (if loading.isDefined || thinking.isDefined then Nil
         else List(Button.submit("Load the model", models, Style.Plain)(Msg.Load(_): Msg))) ++
        choices ++
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
