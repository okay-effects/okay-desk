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
  /** a sentence about the result, where a model can say one; `None` where nothing is generated */
  case Summary(question: String, query: Query, result: Data) extends Model[Option[String]]

object Model:
  def begin[R[_]](name: String)(using In[Model, R]): Unit ! R = perform[Model](Begin(name))
  def progress[R[_]](using In[Model, R]): Either[String, Either[String, String]] ! R = perform[Model](Progress())
  def program[R[_]](tables: Map[String, Data], question: String, retry: Option[(String, String)] = None)(
      using In[Model, R]): Either[String, String] ! R = perform[Model](Program(tables, question, retry))
  def summary[R[_]](question: String, query: Query, result: Data)(using In[Model, R]): Option[String] ! R =
    perform[Model](Summary(question, query, result))

  /** THE MODELS A PAGE OFFERS: the readers first (the thesis), then the models that generate (WebLLM) */
  val choices: List[(String, String)] = List(
    "readers" -> "Qwen3 0.6B cut + readers (244 MB, no generation)",
    "Qwen3-1.7B-q4f16_1-MLC" -> "Qwen3 1.7B (~1 GB, generates)",
    "Qwen3-0.6B-q4f16_1-MLC" -> "Qwen3 0.6B (~0.4 GB, generates)",
    "Qwen2.5-3B-Instruct-q4f16_1-MLC" -> "Qwen2.5 3B (~1.7 GB, generates)")

  /** answered by a table: a test's model, or a page with none */
  def answeredBy[R[_], Rest[_], A](load: String => Either[String, String],
      program: (Map[String, Data], String) => Either[String, String])(p: A ! R)(using Split[Model, R, Rest]): A ! Rest =
    handle(p, Handler.Tail[Model, Rest, A, A](a => pure(a), [X] => (asked: Model[X]) => asked match
      case Begin(_) => ()
      case Progress() => Right(load(""))
      case Program(tables, question, _) => program(tables, question)
      case Summary(_, _, _) => None))
