package okay.desk.ui

import okay.business.scala3.*
import okay.desk.Query
import okay.desk.calc.{Dialogue, Language, Ty}
import Dialogue.Turn
import okay.desk.Table as Data
import okay.desk.puzzle.PuzzleDomain
import okay.http.{Fetch, Response}

/**
 * THE DESK AS AN OKAY-UI PROGRAM (specs/data.md §34): chat, data and examples over one state. The chat shows
 * the model, conversation and question; data manages files and tables; examples opens questions or data in the
 * same chat. A press is a message, a message the next state; what the world does — a file
 * read, an address fetched (okay-http's `Fetch`), the model (`Model`), the disk (`Disk`: every table kept, there
 * again at the next visit) — is an effect a page answers. The same program runs under a test script (`AppSuite`).
 */
object App:
  /** a line of the conversation: who, what; a result's program and its note */
  final case class Entry(role: String, text: String, program: Option[String] = None, note: Option[String] = None,
    url: Option[String] = None)
  /** `phone`: a small screen; use native readers and keep only one model's device alive. */
  final case class State(place: String = "chat", tables: Map[String, Data] = Map.empty, log: Vector[Entry] = Vector.empty,
    model: Option[String] = None, modelName: String = Model.default, problem: Option[String] = None,
    phone: Boolean = false, notice: Option[String] = None, chatVersion: Int = 0,
    /** a question the readers were not sure about and the readings they hesitated between: offered as buttons */
    asking: Option[(String, Vector[String])] = None,
    /** the dialogue's ask (specs/pilot.md §3.2): the hole's question, when `asking` came from the calculus — the choice
     * then answers the hole and the same term is refined, instead of the question rewritten */
    hole: Option[String] = None,
    /** The finite-constraint tool's reading and lessons, used inside the same conversation. */
    puzzle: Puzzles.State = Puzzles.State(), reasoning: Reasoning.State = Reasoning.State(),
    visibleProofs: Set[Int] = Set.empty,
    /** the collection of logic tasks on the examples screen: the page, the class, the language, the task answered */
    tasks: TaskList.View = TaskList.View(),
    /** a task of the collection the desk is solving in the chat: checked once the desk has said its answer */
    solving: Option[TaskList.Solving] = None,
    /** the model chosen by a person (a form, its buttons); without it the model is chosen and loaded by itself and
     * the chat says which it is and how it is */
    chooser: Boolean = true,
    /** the last task the statement reader answered: a short question after it asks about the same things */
    told: Option[String] = None)

  enum Msg:
    case Go(place: String)
    case RunExample(question: String)
    /** the collection of logic tasks shown another way: a page turned, a class chosen */
    case Tasks(view: TaskList.View)
    /** a task of the collection solved in the chat (in Russian or not): the data it is about opened first, the
     * desk's answer checked against the exact one */
    case RunTask(task: okay.desk.tasks.Task, russian: Boolean)
    case CopyChat
    case ClearChat
    case ToggleProof(index: Int)
    /** files chosen: each its path and its text */
    case Opened(files: List[(String, String)])
    case OpenUrl(url: String)
    case OpenUrls(urls: Vector[String])
    case Close(table: String)
    case Load(model: String)
    case Unload
    case Ask(question: String)
    /** THE ABSTENTION AS A DIALOGUE (specs/data.md §30): the person's choice among the readings the readers hesitated
     * between — the question asked again with the choice written into it (an exact fact for the rules) */
    case Clarify(question: String, choice: String)
    case AllowSources
    case Offline
    case ConstraintForm(clue: String, form: String)

  val accept = ".csv,.tsv,.json,.jsonl,.ndjson"

  /** the program: the tables kept on the disk opened again, the addresses given (a link) fetched, then a screen at a
   * time */
  def main[R[_]](opens: List[String], phone: Boolean = false, understanding: Boolean = false,
      recoveredQuestion: Option[String] = None, logic: Option[Boolean] = None, chooser: Boolean = true)(using
      In[Ui, R], In[Model, R], In[Fetch, R], In[Disk, R], In[Clipboard, R]): Unit ! R =
    for
      names <- Disk.names[R]
      kept <- names.filterNot(_.startsWith(Clarified.prefix)).foldLeft(pure[State, R](
        State(phone = phone, chooser = chooser,
          modelName = if understanding || recoveredQuestion.nonEmpty then Model.interpreter else Model.auto(phone), log = recoveredQuestion.toVector.map(q => Entry("user", q)),
          notice = recoveredQuestion.map(_ => "A question was recovered after a page restart. It has not been rerun."),
          // `logic` (in Russian or not): the page opened on the tasks the statement reader states
          place = if logic.nonEmpty then "logic" else "chat",
          tasks = logic.fold(TaskList.View())(ru => TaskList.View(russian = ru, readable = true)))
      )) { (acc, name) =>
        acc.flatMap(s => Disk.get[R](name).map(_.fold(s)(text => opened(s, name, text, quietly = true)))) }
      begun <- opens.foldLeft(pure[State, R](kept))((acc, u) => acc.flatMap(s => step[R](s, Msg.OpenUrl(u))))
      _ <- loop[R](begun)
    yield ()

  def loop[R[_]](s: State)(using In[Ui, R], In[Model, R], In[Fetch, R], In[Disk, R], In[Clipboard, R]): Unit ! R =
    show[Msg, R](render(s)).flatMap(m => step[R](s, m).flatMap(next => loop[R](settled(m, next))))

  /** A TASK OF THE COLLECTION SOLVED IN THE CHAT: once nothing waits for the person, what the desk said last is
   * checked against the task's exact answer and the check is said in the chat. Another example or a cleared chat
   * ends it unchecked. */
  private def settled(m: Msg, s: State): State = s.solving.fold(s) { solving =>
    val waits = s.asking.nonEmpty || s.reasoning.pending.nonEmpty || s.reasoning.constraints ||
      s.reasoning.clarification.nonEmpty
    m match
      case Msg.ClearChat | Msg.RunExample(_) => s.copy(solving = None)
      case _ if waits => s
      case _ =>
        val turn = s.log.drop(solving.from)
        val last = turn.drop(turn.lastIndexWhere(_.role == "user") + 1)
        def texts(role: String): Vector[String] = last.filter(_.role == role).map(_.text)
        s.copy(solving = None,
          log = s.log :+ Entry("check", TaskList.check(solving, texts("model"), texts("result")))) }

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

  def step[R[_]](s: State, m: Msg)(using
      In[Ui, R], In[Model, R], In[Fetch, R], In[Disk, R], In[Clipboard, R]): State ! R =
    val cleared = s.copy(problem = None, asking = None, notice = None)
    m match
      // the logic screen shows the tasks the statement reader states
      case Msg.Go("logic") => pure(s.copy(place = "logic", problem = None, notice = None, tasks =
        if s.tasks.readable then s.tasks.copy(open = None, tried = None)
        else TaskList.View(russian = s.tasks.russian, readable = true)))
      case Msg.Go(place) => pure(s.copy(place = place, problem = None, notice = None))
      case Msg.RunExample(question) => step[R](s.copy(place = "chat", asking = None, hole = None,
        reasoning = s.reasoning.copy(clarification = None)), Msg.Ask(question))
      case Msg.Tasks(view) => pure(s.copy(place = if s.place == "logic" && view.readable then "logic" else "examples",
        tasks = view, problem = None, notice = None))
      // a task the statement language states goes to the statement reader: the model formalizes, the exact
      // solver answers, the reading is shown under the answer — or the doubt is said (no reader loaded: the chat)
      case Msg.RunTask(task, russian) if s.solving.isEmpty && TaskList.stated(task) =>
        val text = task.text.in(russian)
        // THE CHAT AT ONCE, the task in it; then each step where the answer will be: the model loading (when it is
        // not yet), the task read into its statement and solved; then the answer with its reading, and the check
        val from = s.log.size
        val asked = cleared.copy(place = "chat", hole = None, log = s.log :+ Entry("user", text))
        val ready = if asked.model.nonEmpty then pure[State, R](asked) else load[R](asked, asked.modelName)
        ready.flatMap { st =>
          if st.model.isEmpty then pure(st)
          else busy[R](st, "the model reads the task into a formal statement, the exact solver solves it…")
            .flatMap(_ => Model.state[R](text)).flatMap {
              case None => step[R](s.copy(solving = Some(TaskList.Solving(task, russian, from))),
                Msg.RunTask(task, russian))
              case Some(reading) =>
                val (said, read) = okay.desk.statement.Said.words(text, reading, russian)
                pure(st.copy(solving = Some(TaskList.Solving(task, russian, from,
                  reading.result.filter(_ => reading.shown))), log = st.log :+ Entry("model", said, None, read),
                  told = if reading.shown then Some(text) else st.told)) } }
      case Msg.RunTask(task, russian) =>
        task.opens.filterNot(u => s.tables.contains(nameOf(u).takeWhile(_ != '.')))
          .foldLeft(pure[State, R](s.copy(place = "chat")))((acc, u) => acc.flatMap(st => step[R](st, Msg.OpenUrl(u))))
          .flatMap(st => step[R](st.copy(solving = Some(TaskList.Solving(task, russian, st.log.size))),
            Msg.RunExample(task.text.in(russian))))
      case Msg.CopyChat =>
        perform[Clipboard](Clipboard.Copy(transcript(s))).map(ok => s.copy(problem = None,
          notice = Some(if ok then "Chat copied." else "Could not copy the chat. Clipboard access is unavailable.")))
      case Msg.ClearChat => pure(cleared.copy(place = "chat", log = Vector.empty, asking = None, hole = None, told = None,
        chatVersion = s.chatVersion + 1, visibleProofs = Set.empty,
        puzzle = Puzzles.State(), reasoning = Reasoning.State(online = s.reasoning.online)))
      case Msg.ToggleProof(index) =>
        if !s.log.lift(index).exists(_.role == "proof") then pure(cleared)
        else pure(cleared.copy(visibleProofs = if s.visibleProofs(index) then s.visibleProofs - index
          else s.visibleProofs + index))
      case Msg.AllowSources => Reasoning.allow[R](cleared)
      case Msg.Offline => pure(Reasoning.offline(cleared))
      case Msg.ConstraintForm(clue, form) => Puzzles.chosen[R](cleared, clue, form).flatMap(Reasoning.constraints[R])
      case Msg.Opened(files) =>
        val fit = files.filter((path, _) => path.toLowerCase.matches(".*\\.(csv|tsv|json|jsonl|ndjson)$"))
        if fit.isEmpty then pure(cleared.copy(problem = Some("no CSV, TSV or JSON file among those")))
        else fit.foldLeft(pure[State, R](cleared)) { (acc, f) => acc.flatMap { st =>
          val name = f._1.split("/").last
          val next = opened(st, name, f._2)
          if next.tables.size > st.tables.size || next.tables.contains(Data.parse(name, f._2).map(_.name).getOrElse(""))
          then Disk.put[R](name, f._2).map(_ => next) else pure(next) } }
      case Msg.OpenUrls(urls) =>
        urls.foldLeft(pure[State, R](cleared.copy(place = "chat")))((acc, u) =>
          acc.flatMap(st => step[R](st, Msg.OpenUrl(u))))
      case Msg.OpenUrl(url) =>
        val u = url.trim; val name = nameOf(u)
        for
          _ <- busy[R](s, s"fetching $u…")
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
      case Msg.Unload => Model.unload[R].map(_ => cleared.copy(model = None,
        notice = Some("Model unloaded. Chat, open data and cached files are kept.")))
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
      case Msg.Ask(question) if Model.authors(s.modelName) =>
        val q = question.trim
        if q.isEmpty then pure(cleared)
        else Reasoning.start[R](cleared.copy(log = cleared.log :+ Entry("user", q)), q)
      case Msg.Ask(question) =>
        val q = question.trim
        // THE ACT FIRST (Пилот's act layer, specs/pilot.md §3.2): thanks, a yes or a no to the pending question, a
        // correction naming an option — none of them a question about the data
        val lastQuestion = s.asking.map(_._1).orElse(s.log.reverse.collectFirst { case e if e.role == "user" && !e.text.startsWith("I mean") && !e.text.startsWith("Я имею") => e.text })
        val turn = Turn.State(lastQuestion, s.asking.map((_, cs) => (Ty.Program, cs)), Vector.empty)
        val act = Turn.decide(turn, q, Language.builtIn.values.toVector)
        act match
          case Turn.Action.Acknowledge => pure(cleared.copy(log = cleared.log :+ Entry("user", q) :+ Entry("model", Clarified.welcome(q))))
          case Turn.Action.Nothing => pure(cleared.copy(log = cleared.log :+ Entry("user", q) :+ Entry("model", Clarified.nothingPending(q))))
          case Turn.Action.Forget(last) => Model.forget[R](last).map(n => cleared.copy(asking = None, hole = None,
            log = cleared.log :+ Entry("user", q) :+ Entry("model", Clarified.forgot(q, n))))
          case Turn.Action.Answer(pending, answers) => step[R](s, Msg.Clarify(pending, answers.last._2))
          case Turn.Action.Correct(corrected, _) => step[R](s.copy(asking = None, hole = None), Msg.Ask(corrected))
          case Turn.Action.Read(_) =>
            if q.isEmpty then pure(cleared)
            // A TASK TYPED IN THE CHAT (several sentences): the statement reader first — the model writes it down,
            // the exact solver answers; when the reader does not answer it, the chat's other ways, as before
            else if !s.chooser && (okay.desk.statement.Sentences.of(q).size >= 3 || followUp(s, q).nonEmpty) then
              // A QUESTION AFTER A TASK ("Who drinks milk?" after the zebra): the task's conditions with this
              // question read as one task — the same reader, the same exact solver; not sure, the chat's other ways
              val text = followUp(s, q).getOrElse(q)
              val asked = cleared.copy(log = cleared.log :+ Entry("user", q))
              val ready = if asked.model.nonEmpty then pure[State, R](asked) else load[R](asked, asked.modelName)
              ready.flatMap { st =>
                if st.model.isEmpty then Reasoning.start[R](st, q)
                else busy[R](st, "the model reads the task into a formal statement, the exact solver solves it…")
                  .flatMap(_ => Model.state[R](text)).flatMap {
                    case Some(reading) if text != q && !reading.shown => Reasoning.start[R](st, q)
                    // answered, or read and doubted: the reader's words — the answer, or the sentence it is unsure
                    // of; a text it could not write down at all goes the chat's other ways
                    case Some(reading) if reading.shown || reading.statement.nonEmpty && reading.doubt.exists {
                        case _: okay.desk.statement.Reader.Doubt.Unsure | _: okay.desk.statement.Reader.Doubt.Unfamiliar
                          => true
                        case _ => false } =>
                      val ru = okay.desk.statement.Reader.russian(text)
                      val (said, read) = okay.desk.statement.Said.words(text, reading, ru)
                      pure(st.copy(log = st.log :+ Entry("model", said, None, read),
                        told = if text == q && reading.shown then Some(q) else st.told))
                    case _ => Reasoning.start[R](st, q) } }
            else Reasoning.start[R](cleared.copy(log = cleared.log :+ Entry("user", q)), q)

  /** a short question after a task the reader answered: the task's conditions (its own questions left out) and
   * this question, one text — or none: no task, or not a question, or a task of its own */
  def followUp(s: State, q: String): Option[String] =
    val asks = q.trim.endsWith("?") && okay.desk.statement.Sentences.of(q).size < 3
    s.told.filter(_ => asks).map { task =>
      // the task's own text, its questions cut out: the reader sees the conditions as they were written
      val questions = okay.desk.statement.Sentences.of(task)
        .filter(_.in(task).reverse.dropWhile(c => "\"»”’')".contains(c)).startsWith("?"))
      val kept = questions.foldRight(task)((at, t) => t.substring(0, at.from) + t.substring(at.until)).trim
      kept + (if kept.contains('\n') then "\n" else " ") + q.trim }

  /**
   * A STEP GOING ON, shown where the person is: on the chat, the conversation with the question and what was said
   * so far, the step at its end; on another screen, that screen with the step under it. Never the step alone on an
   * empty screen.
   */
  def busy[R[_]](s: State, words: String)(using In[Ui, R]): Unit ! R =
    val parts = if s.place == "chat" then render(s, thinking = Some(Vector(words))).parts
      else render(s).parts :+ Part.info(words)
    perform[Ui](Ui.Working(Screen[Nothing]("okay-desk", parts, Nil)))

  /** the model loaded: the loading begun, its progress shown on this screen until it ends */
  def load[R[_]](st0: State, name: String)(using In[Ui, R], In[Model, R]): State ! R =
    def follow(st: State): State ! R = Model.progress[R].flatMap {
      case Left(words) => perform[Ui](Ui.Working(Screen[Nothing]("okay-desk", render(st, loading = Some(words)).parts, Nil))).flatMap(_ => follow(st))
      case Right(Right(what)) => pure(st.copy(model = Some(what), modelName = name))
      case Right(Left(why)) => pure(st.copy(problem = Some(why)))
    }
    Model.begin[R](name).flatMap(_ => follow(st0.copy(model = None, modelName = name)))

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
      using In[Ui, R], In[Model, R], In[Disk, R], In[Fetch, R]): State ! R =
    answered match
      case Left(words) =>
        // "not sure: A or B": the readings offered to the person as buttons
        val offered = Clarified.readings(words).map(cs => (question, cs))
        val asked = Clarified.askedQuestion(words)
        if offered.isEmpty && asked.isEmpty then Reasoning.general[R](s, question)
        else pure(s.copy(log = s.log :+ Entry("model", asked.getOrElse(words)), asking = offered, hole = asked))
      case Right(json) =>
        val computed = Query.parse(json) match
          case Left(error) =>
            pure[Either[String, (Query, Data, String, String, Option[okay.desk.logic.Protocol.Result])], R](Left(error))
          case Right(q) => Model.query[R](s.tables, q)
        computed.flatMap { ran => ran match
          case Left(problem) if !retried && !Model.reads(s.modelName) =>
            val told = s.copy(log = s.log :+ Entry("system", s"the program failed: $problem — asked to fix it"))
            for
              again <- think[R](told, question, Some((json, problem)))
              next <- answer[R](told, question, again, retried = true)
            yield next
          case Left(problem) => pure(s.copy(log = s.log :+ Entry("system", s"the program failed: $problem\n$json")))
          case Right((q, result, english, russian, proof)) =>
            val note = if Model.reads(s.modelName) then "read from the model's states by the readers — not generated"
              else if result.name == "weights" then "computed by the transformer compiled from the closure program (okay-inlay), in this page"
              else "computed by the exact engine in this page"
            val proofScope = if q.isInstanceOf[Query.Logical] && proof.isEmpty then
              "; expanded proof omitted beyond the current certificate budget" else ""
            val shown = s.copy(log = s.log :+
              Entry("result", result.render(50), Some(Query.print(q)), Some(note + proofScope)))
            // THE ANSWER IN WORDS (specs/calculus.md §10, §14): the answer grammar phrases the exact result;
            // facts are cells or exact derivations from them, never free model-written claims.
            val ru = question.exists(c => c >= 'а' && c <= 'я' || c >= 'А' && c <= 'Я')
            // (a CONCLUSION, not a retelling: the figure that answers — a share, a margin, a gap, who is missing — derived exactly)
            val said = if ru then russian else english
            val sentence = Entry("model", said, None, Some(if ru then "вывод по результату: каждое число — ячейка или точный расчёт по ней" else "concluded from the result: every number is a cell of it or an exact derivation from it"))
            val growth = q match
              case r: Query.Relational => r.aggs.exists(a => a.fn == "growth" || a.fn == "change")
              case _ => false
            val scope = if !growth then Vector.empty else
              val definition = if ru then
                "Изменение рассчитано между первой и последней имеющимися точками каждого ряда; это не годовой темп."
              else "Change compares the first and last available points of each series; it is not an annual rate."
              val cause = if !okay.desk.calc.DataReference.asksCause(question) then "" else if ru then
                " Расчёт не устанавливает экономические причины роста. Для них нужны отдельные свидетельства."
              else " This calculation does not establish economic causes; those need separate evidence."
              Vector(Entry("system", definition + cause))
            val checked = proof.toVector.map(p => LogicalProof.entry(p, ru))
            pure(shown.copy(log = ((shown.log ++ checked) :+ sentence) ++ scope))
        }

  def transcript(s: State): String = s.log.map { e =>
    (Vector(e.role.capitalize + ":", e.text) ++ e.note.toVector ++ e.url.toVector ++
      e.program.toVector.map("Program:\n" + _)).mkString("\n")
  }.mkString("\n\n")

  // ---- the screens
  val filesForm: Form[List[(String, String)]] = Form.one(Field.files("Files, or a whole folder (CSV, TSV, JSON)", accept))
  val urlForm: Form[String] = Form.one(Field.text("The address of a file on the internet (the host must allow cross-origin reads)"))
  def modelForm(current: String): Form[String] =
    // Faces that initialize a choice at its first option must agree with the program's current model too.
    val choices = Model.choices.sortBy((key, _) => if key == current then 0 else 1)
    Form.one(Field.choice[String]("The model", choices.map((k, l) => (l, k))*).initially(current))
  val questionForm: Form[String] = Form.one(Field.longText("Ask anything: a question, a problem, or data to reason about"))
  val inferenceExample = "Moby is a whale. Every whale is a mammal. Every mammal is an animal. Is Moby an animal?"
  val researchExample = "What is the capital of France?"
  val gridExample = "People: Alice, Bob\nPets: dog, cat\nAlice is in house 1.\nBob goes with cat.\nWho owns dog?"

  private def top(s: State): List[Part] =
    List(Part.block("top")(Part.block("brand")(Part.text("okay-desk")),
      Part.text("Ask, reason, and work with data in one chat. The model proposes steps; tools collect evidence " +
        "and compute exact results. Local by default; external sources are used only when you enable them."))) ++
        s.problem.toList.map(Part.problem)

  /** the screen of the place; `loading`: the model's progress in place of its form; `thinking`: a question's steps so
   * far in place of its answer */
  def render(s: State, loading: Option[String] = None, thinking: Option[Vector[String]] = None): Screen[Msg] =
    if s.place == "data" then data(s)
    else if s.place == "examples" || s.place == "logic" then
      TaskList.answering[Msg](s.tasks, Msg.Tasks(_), Msg.RunTask(_, _)).fold(
        if s.place == "logic" then logic(s) else examples(s))((parts, buttons) =>
        Screen[Msg]("okay-desk — a task")((top(s) ++ parts)*)(buttons*))
    else chat(s, loading, thinking)

  /** THE LOGIC SCREEN, where the page opens: the tasks the statement reader states and nothing else — one press of
   * "Solve" loads the model if it is not loaded and answers in the chat */
  def logic(s: State): Screen[Msg] =
    val (collection, taskButtons) = TaskList.section[Msg](s.tasks, Msg.Tasks(_), Msg.RunTask(_, _))
    Screen[Msg]("okay-desk")((top(s) ++ List(Part.block("back")(Part.slot("back")), collection))*)(
      (List(Button("Chat: ask anything")(Msg.Go("chat"): Msg).at("back"),
        Button("More examples")(Msg.Go("examples"): Msg).at("back")) ++ taskButtons)*)

  /** a question's steps: those decided marked done, the last the one going on */
  def steps(lines: Vector[String]): Part =
    Part.block("thinking")((Part.block("small")(Part.text("thinking — what is decided so far:")) +:
      lines.zipWithIndex.map((l, i) => if i < lines.size - 1 then Part.text(s"✓ $l") else Part.info(s"⋯ $l")))*)

  /** THE CHAT: the model (its form, or its progress), the way to the data, the conversation, the question */
  def chat(s: State, loading: Option[String], thinking: Option[Vector[String]] = None): Screen[Msg] =
    val models = modelForm(s.modelName)
    val questionInput = if s.chatVersion == 0 then questionForm else
      Form.one(Field.longText("Ask anything: a question, a problem, or data to reason about")
        .keyed(s"ask-question-${s.chatVersion}"))
    val modelPart: Part = loading.fold(models: Part)(words => Part.block("loading")(Part.info(words)))
    val note = Part.block("model-note")(Part.text(s.model.getOrElse(
      if s.phone then "not loaded yet — exact tools work without it; the native readers use WebGPU, WebGL2 or CPU"
      else "not loaded yet — loaded when a question needs it")),
      Part.text(if Model.authors(s.modelName) then
        "Experimental: the language model interprets the whole task. Its interpretation can be wrong." else ""),
      Part.text(if Model.authors(s.modelName) then
        "First download: about 305 MiB of model files, cached for later visits. Working memory is additional. " +
          "Tested with WebGL2 in desktop Chromium; physical phone testing is still pending." else ""),
      Part.slot("model-actions"))
    // with no chooser the screen is the buttons and the question: what is not so yet is not said
    val dataNote = Part.block("data-note")(Part.text(
      if s.tables.nonEmpty then s"the data: ${s.tables.keys.toList.sorted.mkString(", ")}"
      else if s.chooser then "no data open yet" else ""),
      Part.text(if s.reasoning.online then "online sources enabled for this visit"
        else if s.chooser then "online sources off" else ""),
      Part.slot("data"))
    // THE MODEL IN THE CHAT, where no person chooses it: which one this browser got and how it is — not loaded
    // yet, loading (its progress), loaded (on which device) — the first line of the conversation
    val modelLine = Part.block("system")(Part.text(s"Model: ${Model.choices.toMap.getOrElse(s.modelName, s.modelName)}"),
      Part.block("small")(loading.fold(Part.text(s.model.getOrElse(
        "not loaded yet — it loads by itself when a question needs it")): Part)(words => Part.info(words))))
    // the model is said in the chat once it is wanted — loading, then loaded and on what; before, nothing
    // while the model loads its line is the newest part of the conversation, under the question it loads for;
    // loaded, it stands at the top
    val head: List[Part] = if s.chooser then List(modelPart, note, dataNote)
      else dataNote :: (if s.model.nonEmpty && loading.isEmpty then List(modelLine) else Nil)
    val loadingLine: List[Part] = if !s.chooser && loading.nonEmpty then List(modelLine) else Nil
    val log = s.log.zipWithIndex.map { (e, i) =>
      // a question typed as constraints is code; a task in sentences is read as text
      val code = e.role == "result" || e.role == "user" && okay.desk.know.Knowledge.isConstraints(e.text) &&
        !e.text.contains(". ")
      Part.block(e.role)((List(if code then Part.code(e.text) else if e.role == "user" || e.role == "model" && e.text.contains("\n") then Part.block("lines")(e.text.split("\n").toList.map(Part.text)*) else Part.text(e.text)) ++
        e.note.toList.map(n => if e.role == "result" && n.contains("\n") then Part.code(n)
          else Part.block("small")(n.split("\n").toList.map(Part.text)*)) ++
        e.url.toList.map(url => Part.Link("Source", url)) ++
        (if e.role == "proof" && !s.visibleProofs(i) then List(Part.slot(s"proof-$i"))
         else e.program.toList.map(p => Part.code("the program", p.split("\n").toList, Set.empty)) ++
           (if e.role == "proof" then List(Part.slot(s"proof-$i")) else Nil)))*)
    }
    val proofButtons = if thinking.isDefined || loading.isDefined then Nil else s.log.zipWithIndex.collect {
      case (e, i) if e.role == "proof" =>
        Button(if s.visibleProofs(i) then "Hide proof" else "Show proof")(Msg.ToggleProof(i): Msg).at(s"proof-$i")
    }.toList
    // while a question is thought about: its steps where the answer will be, no question form
    val tools = (if s.reasoning.pending.nonEmpty then List(Part.block("clarify")(Part.slot("research"))) else Nil) ++
      (if s.reasoning.constraints then List(Part.block("clarify")(Part.slot("constraints"))) else Nil)
    val end: List[Part] = thinking.fold(s.asking.toList.map((q, _) => Part.block("clarify")(Part.text(Clarified.prompt(q, s.hole)),
      Part.slot("clarify"))) ++ List(questionInput: Part) ++ tools)(
      lines => List(steps(lines)))
    val choices = if thinking.isDefined then Nil
      else s.asking.toList.flatMap((q, cs) => cs.map(c => Button(Clarified.button(q, c, s.hole.isDefined))(Msg.Clarify(q, c): Msg).at("clarify")))
    val research = if s.reasoning.pending.isEmpty then Nil else List(
      Button("Use online sources", Style.Primary)(Msg.AllowSources: Msg).at("research"),
      Button("Stay offline")(Msg.Offline: Msg).at("research"))
    val constraints = if !s.reasoning.constraints then Nil else s.puzzle.parsed.toList.flatMap { parsed =>
      parsed.clues.filter(c => c.read.isEmpty && !c.world).flatMap(c => Puzzles.options(c, parsed.schema).map {
        (form, constraint) => Button(PuzzleDomain.readBack(constraint, "en"))(
          Msg.ConstraintForm(c.text, form): Msg).at("constraints")
      })
    }
    val active = s.log.nonEmpty && loading.isEmpty && thinking.isEmpty
    val actions = if !active then Nil else List(Part.block("chat-actions")(
      Part.slot("chat-actions"), Part.block("small")(Part.text(s.notice.getOrElse("")))))
    val chatButtons = if !active then Nil else List(
      Button("Copy chat")(Msg.CopyChat: Msg).at("chat-actions"),
      Button("Clear chat")(Msg.ClearChat: Msg).at("chat-actions"))
    Screen[Msg]("okay-desk")((top(s) ++ head ++ log ++ loadingLine ++ actions ++ end)*)(
      (List(Button(if s.tables.isEmpty then "Data: open files" else s"Data: ${s.tables.size} open")(
        Msg.Go("data"): Msg).at("data"), Button("Examples")(Msg.Go("examples"): Msg).at("data")) ++
        // (while loading, no other load; while thinking, no question form and no other question)
        (if !s.chooser || loading.isDefined || thinking.isDefined then Nil
         else List(Button.submit("Load the model", models, Style.Plain)(Msg.Load(_): Msg))) ++
        (if s.chooser && s.model.isDefined && loading.isEmpty && thinking.isEmpty then
          List(Button("Unload model")(Msg.Unload: Msg).at("model-actions")) else Nil) ++
        choices ++ research ++ constraints ++ chatButtons ++ proofButtons ++
        (if s.reasoning.online then List(Button("Turn off online sources")(Msg.Offline: Msg).at("data")) else Nil) ++
        (if thinking.isDefined then Nil else List(Button.submit("Ask", questionInput)(Msg.Ask(_): Msg))))*)

  /** THE DATA: the tables open, files or a folder, and an address. */
  def data(s: State): Screen[Msg] =
    val tables = if s.tables.isEmpty then Vector(Part.block("small")(Part.text("no file open yet")))
      else Vector(Part.tree("", s.tables.values.toVector.sortBy(_.name).map(t => Part.TreeNode(t.name,
        s"${t.name}: ${t.size} rows, ${t.columns.size} columns", children = t.describe.split("\n").drop(1).toList.map(l =>
          Part.TreeNode(t.name + ":" + l.trim.takeWhile(_ != ':'), l.trim))))*), Part.block("tables")(Part.slot("tables")))
    val closes = s.tables.keys.toList.sorted.map(n => Button(s"Close $n")(Msg.Close(n): Msg).at("tables"))
    Screen[Msg]("okay-desk — the data")((top(s) ++ List(Part.block("back")(Part.slot("back")),
      Part.section("The tables")((tables :+ (filesForm: Part) :+ (urlForm: Part))*)))*)(
      (List(Button("← Back to the chat", Style.Primary)(Msg.Go("chat"): Msg).at("back")) ++ closes ++ List(
        Button("Examples")(Msg.Go("examples"): Msg).at("back"),
        Button.submit("Open the files", filesForm, Style.Plain)(Msg.Opened(_): Msg),
        Button.submit("Open the address", urlForm, Style.Plain)(Msg.OpenUrl(_): Msg)))*)

  /** All sample questions and data sets live here; opening one returns to the same chat. */
  def examples(s: State): Screen[Msg] =
    val dataExamples = Examples.all.map(e => Part.block("example", e.name)(Part.text(e.about),
      Part.block("small")(Part.text(e.urls.mkString("  "))), Part.text(s"Source: ${e.source}"), Part.slot(s"example-${e.key}")))
    val opens = Examples.all.map(e => Button("Open")(Msg.OpenUrls(e.urls): Msg).at(s"example-${e.key}"))
    val questions = List(
      Button("Find a sourced answer")(Msg.RunExample(researchExample): Msg).at("examples"),
      Button("Try an inference")(Msg.RunExample(inferenceExample): Msg).at("examples"),
      Button("Try a grid problem")(Msg.RunExample(gridExample): Msg).at("examples"),
      Button("Two calculations")(Msg.RunExample("2 + 2\n3 * 7"): Msg).at("examples"))
    val (collection, taskButtons) = TaskList.section[Msg](s.tasks, Msg.Tasks(_), Msg.RunTask(_, _))
    Screen[Msg]("okay-desk — examples")((top(s) ++ List(Part.block("back")(Part.slot("back")),
      Part.section("Example questions")(Part.text(
        "Online research loads the small model; the other questions use exact tools directly."),
        Part.block("examples")(Part.slot("examples"))),
      collection,
      Part.section("Examples of open data")((Part.text(
        "Files are read directly from their hosts, which must allow cross-origin reads. " +
        "Opened files stay on this device for the next visit.") +: dataExamples.toList)*)))*)(
      (List(Button("← Back to the chat", Style.Primary)(Msg.Go("chat"): Msg).at("back"),
        Button("Data: open files")(Msg.Go("data"): Msg).at("back")) ++ questions ++ opens.toList ++ taskButtons)*)
