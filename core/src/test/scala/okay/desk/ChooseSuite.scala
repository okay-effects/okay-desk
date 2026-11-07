package okay.desk

import okay.desk.calc.*
import okay.scala3.*

/** THE ONE OPERATION AS AN EFFECT (specs/calculus.md §8): a reading as a program of `Choose`, its sources as
 * handlers, their composition as nesting — the same words as the function form (`Refine.greedy` with a `Fill`) */
class ChooseSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = new Grammar(s)
  val suite = new CalculusSuite
  def ctx(text: String) = Context(s, text, Term.Hole(Ty.Program))

  test("the data as the only handler: the same term as the exact fill in function form, on the sample"):
    for q <- Sample.questions; text <- Vector(q.en, q.ru) do
      val byFunction = run(Refine.greedy[Pure](g, Fill.exact, ctx(text)))
      val byEffect = run(handle(Choose.refine[Choose](g, ctx(text)), Choose.by[Pure, Refine.Done](Fill.exact[Pure])))
      assertEquals(byEffect.term, byFunction.term, text)
      assertEquals(byEffect.unsure, byFunction.unsure)

  test("the data first, the oracle outside: every question closes to its point; the oracle is asked only where the data is silent"):
    for (p, q) <- suite.gold.zip(Sample.questions); text <- Vector(q.en, q.ru) do
      val gold = Den.term(s, p)
      var asked = 0
      val counting: Fill[Pure] = new Fill[Pure]:
        def apply(ty: Ty, cs: Vector[String], c: Context): Dist ! Pure = { asked += 1; Fill.oracle[Pure](g, gold)(ty, cs, c) }
      val inner: Refine.Done ! (Choose + Choose) = Choose.refine[Choose + Choose](g, ctx(text))
      val done = run(handle(handle(inner, Choose.first[Choose, Refine.Done](Fill.exact[Choose])), Choose.by[Pure, Refine.Done](counting)))
      assert(g.closed(done.term), s"$text: ${done.term.show}")
      assertEquals(Den.program(s, done.term), Right(p))
      val dataAlone = run(handle(Choose.refine[Choose](g, ctx(text)), Choose.by[Pure, Refine.Done](Fill.exact[Pure])))
      assertEquals(asked == 0, g.closed(dataAlone.term), s"$text asked the oracle $asked times")

  test("nobody: no source at all — only the holes with one candidate fill; never wrong"):
    for (p, q) <- suite.gold.zip(Sample.questions); text <- Vector(q.en, q.ru) do
      val done = run(handle(Choose.refine[Choose](g, ctx(text)), Choose.nobody[Pure, Refine.Done]))
      if g.closed(done.term) then assertEquals(Den.program(s, done.term), Right(p), text)
