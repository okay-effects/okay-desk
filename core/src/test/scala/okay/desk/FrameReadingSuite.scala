package okay.desk

import okay.desk.skill.{Read, Reading}
import okay.desk.tasks.Everyday
import okay.scala3.*

/** THE FRAME READ AS A PROGRAM (specs/frames.md §2): over a model that prefers a target frame's lines by a given
 * margin, and a person who answers from the gold — the choices, the doubt, the questions, none of them */
class FrameReadingSuite extends munit.FunSuite:
  type RP[X] = (Read + Person)[X]
  private val tables = Map("expenses" -> Everyday.tables("expenses"))
  private val target = Vector("table: expenses", "answer: sum amount", "where: category = groceries", Frame.end)

  /** a model whose tokens are characters; an option of the target scores 0, the others −margin, a line of `doubtful`
   * only −0.01 against the target's */
  private def model(margin: Double, doubtful: Set[String] = Set.empty): Reading = new Reading:
    def text(t: Array[Int]) = new String(t.init.map(_.toChar))
    def answer[X](op: Read[X]): X = (op match
      case Read.Prompt(_, _) => Array(0)
      case Read.Encode(s) => if s == "<|im_end|>" then Array(1) else s.map(_.toInt).toArray
      case Read.Decode(ids) => ids.map(_.toChar).mkString
      case Read.Score(context, options) =>
        // the target's next line (the lines decided: the newlines in the context, the first after the prompt)
        val next = target.lift(context.count(_ == 10) - 1)
        options.map(o => if next.contains(text(o)) then 0.0 else if doubtful(text(o)) then -0.01 else -margin)
      case Read.Note(_) => ()
      case other => sys.error(s"not asked: $other")).asInstanceOf[X]

  private def readWith(r: Reading, person: Handler[Person, Read, FrameReading.Reading, FrameReading.Reading],
      doubt: Option[FrameReading.Doubt]) =
    Read.run(handle(FrameReading.read[RP](tables, "How much did I spend on groceries?", doubt), person), r)

  test("sure choices: the target frame, its margins, no question") {
    val got = readWith(model(5.0), Person.silent, Some(FrameReading.Doubt(0.5)))
    assertEquals(got.lines, target)
    assert(got.margins.forall(_ >= 5.0 - 1e-9), got.margins)
    assertEquals(got.asked, 0)
  }

  test("a doubt asks the person; the gold's option taken; at most two questions; none of them: unresolved") {
    val unsure = model(5.0, doubtful = Set("answer: count", "where: category = transport"))
    val asked = readWith(unsure, Person.byFrame(Some(target)), Some(FrameReading.Doubt(0.5)))
    assertEquals(asked.lines, target)
    assertEquals(asked.asked, 2)
    val lost = readWith(unsure, Person.byFrame(Some(Vector("table: expenses", "answer: cannot", Frame.end))),
      Some(FrameReading.Doubt(0.5)))
    assert(lost.unresolved && lost.asked == 1, lost.toString)
    val notAsked = readWith(unsure, Person.byFrame(Some(target)), None)
    assertEquals((notAsked.lines, notAsked.asked), (target, 0))
  }
