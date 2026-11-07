package okay.desk

import okay.desk.calc.*
import okay.scala3.*
import okay.scala3.std.{Choose, Writer}

/** THE SOURCES AS HANDLERS (specs/calculus.md §8): the one program on the standard effects, the sources nested
 * around it — the data as the only handler gives the function form's term; the data first with the oracle outside
 * closes every question, the oracle asked exactly where the data is silent; no source at all is never wrong */
class WeighSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = new Grammar(s)
  val suite = new CalculusSuite
  def ctx(text: String) = Context(s, text, Term.Hole(Ty.Program))
  type Step = Strategy.Step
  type Two[X] = (Choose + Weigh + Weigh + Writer % Step)[X]
  /** the program with two sources: `inner` first (forwarding when silent), `outer` last */
  def twoSources(text: String, inner: Fill[Pure], outer: Fill[Pure]): Option[Term] =
    val p: Term ! Two = Strategy.program[Two](g, ctx(text))
    val afterInner: Term ! (Choose + Weigh + Writer % Step) = handle(p, Weigh.first[Choose + Weigh + Writer % Step, Term](Fill.lift(inner)))
    val afterOuter: Term ! (Choose + Writer % Step) = handle(afterInner, Weigh.by[Choose + Writer % Step, Term](Fill.lift(outer)))
    val first: Option[Term] ! (Pure + Writer % Step) = Choose.first(afterOuter)
    run(Writer.run[Step, Pure + Writer % Step, Pure, Option[Term]](first))._2

  test("the data as the only handler: the same term as the exact fill in function form, on the sample"):
    for q <- Sample.questions; text <- Vector(q.en, q.ru) do
      val byFunction = run(Refine.greedy[Pure](g, Fill.exact, ctx(text)))
      val (_, byEffect) = Strategy.greedy(g, ctx(text), Fill.exact[Pure])
      if g.closed(byFunction.term) then assertEquals(byEffect, Some(byFunction.term), text) else assertEquals(byEffect, None, text)

  test("the data first, the oracle outside: every question closes to its point; the oracle is asked exactly where the data is silent"):
    for (p, q) <- suite.gold.zip(Sample.questions); text <- Vector(q.en, q.ru) do
      val gold = Den.term(s, p)
      var asked = 0
      val counting: Fill[Pure] = new Fill[Pure]:
        def apply(ty: Ty, cs: Vector[String], c: Context): Dist ! Pure = { asked += 1; Fill.oracle[Pure](g, gold)(ty, cs, c) }
      val done = twoSources(text, Fill.exact[Pure], counting)
      assertEquals(done.flatMap(t => Den.program(s, t).toOption), Some(p), text)
      val (_, dataAlone) = Strategy.greedy(g, ctx(text), Fill.exact[Pure])
      assertEquals(asked == 0, dataAlone.isDefined, s"$text asked the oracle $asked times")

  test("nobody: no source at all — only the holes with one candidate fill; never wrong"):
    for (p, q) <- suite.gold.zip(Sample.questions); text <- Vector(q.en, q.ru) do
      val (_, done) = Strategy.greedy(g, ctx(text), Fill.lift(new Fill[Pure] { def apply(ty: Ty, cs: Vector[String], c: Context): Dist ! Pure = pure(Vector.empty) }))
      done.foreach(t => assertEquals(Den.program(s, t), Right(p), text))
