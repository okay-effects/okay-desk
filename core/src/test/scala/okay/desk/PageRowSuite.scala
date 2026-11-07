package okay.desk

import okay.desk.calc.*
import okay.desk.skill.Read
import okay.scala3.*
import okay.scala3.std.Async
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/** THE PAGE'S ROW: the reading program run in `Async + Read` as the browser runs it (futures), the model's tokens
 * asked (a fake model: flat logits), the readers' skill when the file is there — it must run through, on the JVM
 * AND on Scala.js (`coreJS/test`); found by the page's self-test failing with a row mismatch */
class PageRowSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = new Grammar(s)
  test("greedyIn in Async + Read with the model's fill, answered by futures, runs through"):
    type R[X] = (Async + Read)[X]
    val fake: [X] => Read[X] => Future[X] = [X] => (op: Read[X]) => Future.successful((op match
      case Read.Note(_) => ()
      case Read.States(ids, at) => at.map(_ => Array.fill(1024)(0.1f))
      case Read.Logits(_) => Array.fill(200000)(0f)
      case Read.Encode(text) => text.getBytes("UTF-8").map(_.toInt & 0xff)
      case Read.Decode(ids) => ids.map(_.toChar).mkString
      case Read.Prompt(a, b) => (a + b).getBytes("UTF-8").map(_.toInt & 0xff)).asInstanceOf[X])
    val questions = Vector("The three largest orders: id and amount.", "Самый большой неоплаченный заказ.", "How many orders are unpaid?")
    type S[X] = Strategy.Rest[R][X]
    // a source that ALWAYS performs a Read op before weighing: the remap of the row is exercised at every hole
    val asking: Fill[S] = new Fill[S]:
      def apply(ty: Ty, cs: Vector[String], c: Context): Dist ! S = Read.note[S]("asked").map(_ => cs.map(x => (x, 0.0)))
    Future.sequence(questions.map { text =>
      val fill: Fill[S] = Fill.first[S](Fill.exact[S], asking, Fill.model[S](Sample.tables))
      val p = Strategy.greedyIn[R](g, Context(s, text, Term.Hole(Ty.Program)), fill)
      Read.future(p, fake).map((steps, term) => assert(term.isDefined || steps.nonEmpty, text))
    }).map(_ => ())

  /** THE DATA'S REGULAR EXPRESSIONS ON SCALA.JS: every sample question and the English/Russian phrasings that use each
   * rule run through the exact fill in JS too — a flag expression JS rejects ("(?U)(?s)") was found on the live page */
  test("the exact fill on every sample question, and on the phrasings of each rule, runs through on this platform"):
    val extra = Vector("In which year was the GDP of Poland the highest?", "The largest of the orders", "Maximum since of customers.",
      "Where does amount grow fastest?", "Покажи первые 5", "Which 5 orders grew fastest?", "no, by city", "спасибо")
    for q <- Sample.questions.flatMap(q => Vector(q.en, q.ru)) ++ extra do
      val (steps, term) = Strategy.greedy(g, Context(s, q, Term.Hole(Ty.Program)), Fill.exact[Pure])
      assert(term.isDefined || steps.nonEmpty, q)
      Dialogue.Act.of(q, Vector(Language.En.language, Language.Ru.language))
