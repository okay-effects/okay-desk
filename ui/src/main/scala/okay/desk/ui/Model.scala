package okay.desk.ui

import okay.business.scala3.*
import okay.desk.Query
import okay.desk.Table as Data

/**
 * THE MODEL IN THE PAGE, AS AN EFFECT: loaded by its name (the readers on the cut model, or a model that generates);
 * a question answered with a program's text — read from the states by the readers, or written by the model, a retry
 * told what failed; a result summarized in a sentence where a model generates (none from the readers). A page answers
 * it by futures on its device; a test by a table of answers. The program never sees weights, engines or devices.
 */
enum Model[X]:
  /** the model's loading begun; `Progress` tells how it goes */
  case Begin(name: String) extends Model[Unit]
  /** a moment later: `Left(words)` while it loads (the file being fetched, the weights going to the device), or
   * `Right` of the end — a description of what runs, or why not */
  case Progress() extends Model[Either[String, Either[String, String]]]
  /** the program for a question: `Right(json)`, or `Left(words)` — what the model said instead, or why not */
  case Program(tables: Map[String, Data], question: String, retry: Option[(String, String)])
    extends Model[Either[String, String]]
  /** a question's program begun — read by the readers, or written by the model, a retry told what failed;
   * `Thought` tells how it goes. `choice`: the person's answer to the hole the SAME question stopped at (the dialogue,
   * specs/pilot.md §3.2) — the reading refines the same term; a new question starts afresh */
  case Ask(tables: Map[String, Data], question: String, retry: Option[(String, String)], choice: Option[String] = None)
    extends Model[Unit]
  /** a moment later: `Left(steps)` while it thinks — what is decided, a line each, the last the one being decided
   * now — or `Right` of the program as `Program` gives it */
  case Thought() extends Model[Either[Vector[String], Either[String, String]]]
  /** a sentence about the result, where a model can say one; `None` where nothing is generated */
  case Summary(question: String, query: Query, result: Data) extends Model[Option[String]]

object Model:
  def begin[R[_]](name: String)(using In[Model, R]): Unit ! R = perform[Model](Begin(name))
  def progress[R[_]](using In[Model, R]): Either[String, Either[String, String]] ! R = perform[Model](Progress())
  def program[R[_]](tables: Map[String, Data], question: String, retry: Option[(String, String)] = None)(
      using In[Model, R]): Either[String, String] ! R = perform[Model](Program(tables, question, retry))
  def ask[R[_]](tables: Map[String, Data], question: String, retry: Option[(String, String)] = None,
      choice: Option[String] = None)(using In[Model, R]): Unit ! R = perform[Model](Ask(tables, question, retry, choice))
  def thought[R[_]](using In[Model, R]): Either[Vector[String], Either[String, String]] ! R = perform[Model](Thought())
  def summary[R[_]](question: String, query: Query, result: Data)(using In[Model, R]): Option[String] ! R =
    perform[Model](Summary(question, query, result))

  /** THE MODELS A PAGE OFFERS: the readers first (the thesis) — the student by default (§21: 12 layers distilled
   * toward the cut's layer-19 state, the same skill file), the cut for comparison — then the models that generate */
  val choices: List[(String, String)] = List(
    "readers-core" -> "The core: Qwen3 0.6B cut, the map's components removed, distilled back + readers (no generation)",
    "readers-small" -> "Qwen3 0.6B student, 12 layers + readers (122 MB, no generation)",
    "readers" -> "Qwen3 0.6B cut, 19 layers + readers (244 MB, no generation; for comparison)",
    "Qwen3-1.7B-q4f16_1-MLC" -> "Qwen3 1.7B (~1 GB, generates)",
    "Qwen3-0.6B-q4f16_1-MLC" -> "Qwen3 0.6B (~0.4 GB, generates)",
    "Qwen2.5-3B-Instruct-q4f16_1-MLC" -> "Qwen2.5 3B (~1.7 GB, generates)")

  /** the models read by the readers: their files' directory on the page and the layer the skill reads */
  val readers: Map[String, (String, Int)] = Map("readers-core" -> ("model-core", 19), "readers-small" -> ("model-small", 12), "readers" -> ("model", 19))
  /** the model a page loads unless another is chosen */
  val default = "readers-core"
  /** whether a model is read by the readers (no generation) */
  def reads(name: String): Boolean = readers.contains(name)

  /** answered by a table: a test's model, or a page with none */
  def answeredBy[R[_], Rest[_], A](load: String => Either[String, String],
      program: (Map[String, Data], String) => Either[String, String])(p: A ! R)(using Split[Model, R, Rest]): A ! Rest =
    // the question asked, answered at once when its thought is asked for
    var asked0: Option[(Map[String, Data], String)] = None
    handle(p, Handler.Tail[Model, Rest, A, A](a => pure(a), [X] => (asked: Model[X]) => asked match
      case Begin(_) => ()
      case Progress() => Right(load(""))
      case Program(tables, question, _) => program(tables, question)
      case Ask(tables, question, _, _) => asked0 = Some((tables, question))
      case Thought() => Right(asked0.fold(Left("no question asked"))((t, q) => program(t, q)))
      case Summary(_, _, _) => None))
