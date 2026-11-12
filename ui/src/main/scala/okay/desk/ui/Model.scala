package okay.desk.ui

import okay.business.scala3.*
import okay.desk.Table as Data
import okay.desk.know.Knowledge
import okay.desk.exec.Computation
import okay.desk.exec.ComputePlan
import okay.desk.{Query, Table}
import okay.desk.know.{Deduction, FactReader}
import okay.desk.calc.{DataReference, Language}
import okay.desk.skill.Words
import okay.desk.puzzle.{Puzzle, Solver}

/** THE MODEL IN THE PAGE, AS AN EFFECT: loaded by its name (the readers on the cut model, or a model that generates); a
  * question answered with a program's text — read from the states by the readers, or written by the model, a retry told
  * what failed. Final answers are phrased by the result handler. A page answers the model effects by futures on its
  * device; a test by a table of answers. The program never sees weights, engines or devices.
  */
enum Model[X]:
  /** the model's loading begun; `Progress` tells how it goes */
  case Begin(name: String) extends Model[Unit]
  /** Release the active model's device and runtime state; cached files are kept. */
  case Unload() extends Model[Unit]

  /** a moment later: `Left(words)` while it loads (the file being fetched, the weights going to the device), or `Right`
    * of the end — a description of what runs, or why not
    */
  case Progress() extends Model[Either[String, Either[String, String]]]

  /** the program for a question: `Right(json)`, or `Left(words)` — what the model said instead, or why not */
  case Program(tables: Map[String, Data], question: String, retry: Option[(String, String)])
      extends Model[Either[String, String]]

  /** a question's program begun — read by the readers, or written by the model, a retry told what failed; `Thought`
    * tells how it goes. `choice`: the person's answer to the hole the SAME question stopped at (the dialogue,
    * specs/pilot.md §3.2) — the reading refines the same term; a new question starts afresh
    */
  case Ask(tables: Map[String, Data], question: String, retry: Option[(String, String)], choice: Option[String] = None)
      extends Model[Unit]

  /** a moment later: `Left(steps)` while it thinks — what is decided, a line each, the last the one being decided now —
    * or `Right` of the program as `Program` gives it
    */
  case Thought() extends Model[Either[Vector[String], Either[String, String]]]

  /** a logic task read into its statement by the statement reader and solved by the exact solvers
    * (specs/formalization.md §12.1): the reading with its answer or its doubt; None where no reader is loaded
    */
  case State(text: String) extends Model[Option[okay.desk.statement.Reader.Reading]]

  /** «не запоминай»: the lessons of this question's words taken back; how many */
  case Forget(question: String) extends Model[Int]

  /** Consult dataset dictionaries before deciding whether a question needs the data readers. No weights required. */
  case References(tables: Map[String, Data], question: String) extends Model[Boolean]

  /** A general reasoning program: search, exact constraints, or checked quotations from collected evidence. */
  case Document(url: String, body: String, contentType: String) extends Model[Either[String, Knowledge.Source]]
  case ReadFacts(quotes: Vector[Knowledge.Quote]) extends Model[Either[String, Vector[FactReader.Reading]]]
  case Reason(question: String, sources: Vector[Knowledge.Source], observations: Vector[String],
      learned: Vector[FactReader.Reading])
      extends Model[Either[String, Knowledge.Step]]
  /** Begin model-authored interpretation of the entire task; poll without blocking the page. */
  case Author(question: String, tables: Map[String, Data], sources: Vector[Knowledge.Source],
      observations: Vector[String]) extends Model[Unit]
  case Authored() extends Model[Either[Vector[String], Either[Model.AuthorFailure, Knowledge.Step]]]

  /** A tool invocation proposed by the model/program; the model does not perform this computation. */
  case Execute(job: Computation.Job) extends Model[Either[String, Computation.Output]]
  case ExecutePlan(nodes: Vector[ComputePlan.Node]) extends Model[Either[String, Vector[ComputePlan.Result]]]

object Model:
  enum AuthorFailure:
    case Generation(message: String)
    case InvalidProgram(program: String, message: String)
  def execute[R[_]](job: Computation.Job)(using In[Model, R]): Either[String, Computation.Output] ! R =
    perform[Model](Execute(job))
  def executePlan[R[_]](nodes: Vector[ComputePlan.Node])(using
      In[Model, R]
  ): Either[String, Vector[ComputePlan.Result]] ! R = perform[Model](ExecutePlan(nodes))
  def calculate[R[_]](question: String, expression: String)(using In[Model, R]): Either[String, String] ! R =
    execute[R](Computation.Job.Calculate(question, expression)).map(_.flatMap {
      case Computation.Output.Number(value) => Right(value)
      case _                                => Left("calculation returned the wrong result type")
    })
  def deduce[R[_]](question: String, sources: Vector[Knowledge.Source], request: Deduction.Request,
      learned: Vector[FactReader.Reading] = Vector.empty)(using
      In[Model, R]
  ): Either[String, Deduction.Result] ! R =
    execute[R](Computation.Job.Deduce(question, sources, request, learned)).map(_.flatMap {
      case Computation.Output.Deduced(value) => Right(value)
      case _                                 => Left("deduction returned the wrong result type")
    })
  def solve[R[_]](puzzle: Puzzle)(using In[Model, R]): Either[String, Solver.Outcome] ! R =
    execute[R](Computation.Job.Solve(puzzle, 10)).map(_.flatMap {
      case Computation.Output.Solved(value) => Right(value)
      case _                                => Left("solver returned the wrong result type")
    })
  def query[R[_]](tables: Map[String, Table], q: Query)(using
      In[Model, R]
  ): Either[String, (Query, Table, String, String, Option[okay.desk.logic.Protocol.Result])] ! R =
    execute[R](Computation.Job.QueryData(tables, q)).map(_.flatMap {
      case Computation.Output.Rows(written, table, by, en, ru) if written == q =>
        Right((q, table.copy(name = by), en, ru, None))
      case Computation.Output.ProvenRows(written, table, by, en, ru, proof) if written == q =>
        Right((q, table.copy(name = by), en, ru, Some(proof)))
      case _ => Left("query returned the wrong result type")
    })
  def cite[R[_]](sources: Vector[Knowledge.Source], quotes: Vector[Knowledge.Quote])(using
      In[Model, R]
  ): Either[String, Vector[Knowledge.Citation]] ! R =
    execute[R](Computation.Job.Cite(sources, quotes)).map(_.flatMap {
      case Computation.Output.Cited(values) => Right(values)
      case _                                => Left("citation lookup returned the wrong result type")
    })
  def begin[R[_]](name: String)(using In[Model, R]): Unit ! R = perform[Model](Begin(name))
  def unload[R[_]](using In[Model, R]): Unit ! R = perform[Model](Unload())
  def progress[R[_]](using In[Model, R]): Either[String, Either[String, String]] ! R = perform[Model](Progress())
  def program[R[_]](tables: Map[String, Data], question: String, retry: Option[(String, String)] = None)(using
      In[Model, R]
  ): Either[String, String] ! R = perform[Model](Program(tables, question, retry))
  def ask[R[_]](
      tables: Map[String, Data],
      question: String,
      retry: Option[(String, String)] = None,
      choice: Option[String] = None
  )(using In[Model, R]): Unit ! R = perform[Model](Ask(tables, question, retry, choice))
  def state[R[_]](text: String)(using In[Model, R]): Option[okay.desk.statement.Reader.Reading] ! R =
    perform[Model](State(text))
  def thought[R[_]](using In[Model, R]): Either[Vector[String], Either[String, String]] ! R = perform[Model](Thought())
  def references[R[_]](tables: Map[String, Data], question: String)(using In[Model, R]): Boolean ! R =
    perform[Model](References(tables, question))
  def forget[R[_]](question: String)(using In[Model, R]): Int ! R = perform[Model](Forget(question))
  def document[R[_]](url: String, body: String, contentType: String)(using In[Model, R])
      : Either[String, Knowledge.Source] ! R = perform[Model](Document(url, body, contentType))
  def readFacts[R[_]](quotes: Vector[Knowledge.Quote])(using In[Model, R])
      : Either[String, Vector[FactReader.Reading]] ! R = perform[Model](ReadFacts(quotes))
  def reason[R[_]](question: String, sources: Vector[Knowledge.Source], observations: Vector[String],
      learned: Vector[FactReader.Reading] = Vector.empty)(using
      In[Model, R]
  ): Either[String, Knowledge.Step] ! R = perform[Model](Reason(question, sources, observations, learned))
  def author[R[_]](question: String, tables: Map[String, Data], sources: Vector[Knowledge.Source],
      observations: Vector[String])(using In[Model, R]): Unit ! R =
    perform[Model](Author(question, tables, sources, observations))
  def authored[R[_]](using In[Model, R]): Either[Vector[String], Either[AuthorFailure, Knowledge.Step]] ! R =
    perform[Model](Authored())

  val interpreter = "native-understanding"
  /** Native readers, the experimental native program author, and the existing WebLLM models. */
  val choices: List[(String, String)] = List(
    "readers-small" -> "Qwen3 0.6B student, 12 layers + readers (122 MB, no generation)",
    interpreter -> "Qwen3 0.6B, 28 layers + program adapter (~305 MiB, experimental)",
    "Qwen3-1.7B-q4f16_1-MLC" -> "Qwen3 1.7B (~1 GB, generates)",
    "Qwen3-0.6B-q4f16_1-MLC" -> "Qwen3 0.6B (~0.4 GB, generates)",
    "Qwen2.5-3B-Instruct-q4f16_1-MLC" -> "Qwen2.5 3B (~1.7 GB, generates)"
  )

  /** the models read by the readers: their files' directory on the page and the layer the skill reads */
  val readers: Map[String, (String, Int)] =
    Map("readers-small" -> ("model-small", 12))
  val nativeModels: Map[String, (String, Int)] = readers + (interpreter -> ("model-understanding", 28))

  /** the model a page loads unless another is chosen */
  val default = "readers-small"

  /** THE MODEL CHOSEN FOR A BROWSER, by itself: the student and its readers — the one model every browser this page
    * runs in can hold (a phone's WebGL2 as well); its device (WebGPU, else WebGL2, else the CPU) is found when it
    * loads. A stronger model for a stronger browser enters here.
    */
  def auto(phone: Boolean): String =
    val _ = phone
    default

  /** whether a model is read by the readers (no generation) */
  def reads(name: String): Boolean = readers.contains(name)
  def authors(name: String): Boolean = name == interpreter

  /** answered by a table: a test's model, or a page with none */
  def answeredBy[R[_], Rest[_], A](
      load: String => Either[String, String],
      program: (Map[String, Data], String) => Either[String, String],
      reason: (String, Vector[Knowledge.Source], Vector[String]) => Either[String, Knowledge.Step] = (q, sources, _) =>
        Right(Knowledge.baseline(q, sources)),
      readFacts: Vector[Knowledge.Quote] => Either[String, Vector[FactReader.Reading]] = _ => Right(Vector.empty),
      words: Words = Words.none,
      unload: () => Unit = () => (),
      state: String => Option[okay.desk.statement.Reader.Reading] = _ => None
  )(p: A ! R)(using Split[Model, R, Rest]): A ! Rest =
    // the question asked, answered at once when its thought is asked for
    var asked0: Option[(Map[String, Data], String)] = None
    var loadingName = ""
    var authoring: Either[String, Knowledge.Step] = Left("no model authoring request")
    handle(
      p,
      Handler.Tail[Model, Rest, A, A](
        a => pure(a),
        [X] =>
          (asked: Model[X]) =>
            asked match
              case Begin(name)                  => loadingName = name
              case Unload()                     => unload()
              case Progress()                   => Right(load(loadingName))
              case Program(tables, question, _) => program(tables, question)
              case Ask(tables, question, _, _)  => asked0 = Some((tables, question))
              case Thought() => Right(asked0.fold(Left("no question asked"))((t, q) => program(t, q)))
              case Forget(_) => 0
              case State(text) => state(text)
              case References(tables, question) =>
                DataReference.matches(tables, question, words, Language.detect(question).flatMap(Language.builtIn.get))
              case ReadFacts(quotes) => readFacts(quotes)
              case Document(_, _, _) => Left("document parsing needs the browser handler")
              case Reason(question, sources, observations, learned) =>
                if learned.nonEmpty then Right(Knowledge.baseline(question, sources, learned))
                else reason(question, sources, observations)
              case Author(question, _, sources, observations) =>
                authoring = reason(question, sources, observations)
                ()
              case Authored() => Right(authoring.left.map(AuthorFailure.Generation(_)))
              case Execute(job)                            => Computation.run(job)
              case ExecutePlan(nodes)                      => ComputePlan.sync(nodes)
      )
    )
