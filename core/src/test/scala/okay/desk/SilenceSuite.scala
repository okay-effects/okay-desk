package okay.desk

import okay.desk.calc.*
import okay.desk.calc.Dialogue.Outcome
import okay.desk.organ.*
import okay.scala3.*

/** THE SILENCES (BACKLOG A 2, B 10) and THE BEAM (B 15): the data asks on a disputed word instead of deciding; an
 * organ's maybe is silence alone and a vote with the others; the beam of width 1 is the greedy reading, and with a
 * perfect source every width closes to the gold */
class SilenceSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = new Grammar(s)
  val gold = (new CalculusSuite).gold.map(p => Den.term(s, p))
  type P[X] = Strategy.Rest[Pure][X]

  test("a disputed word: the data offers both constructors and asks, with them as the options; no reader is asked after"):
    for q <- Vector("How much money is unpaid?", "Сколько денег не оплачено?") do
      val ctx = Context(s, q, Term.Hole(Ty.Program))
      // a reader after the data that would decide anything: it must not be reached at the disputed hole
      var reached = false
      val greedy: Fill[P] = new Fill[P]:
        def apply(ty: Ty, cs: Vector[String], c: Context): Dist ! P = { if ty.isInstanceOf[Ty.FnT] then reached = true; pure(cs.map(x => (x, 0.0))) }
      run(Dialogue.read[Pure](g, ctx, Fill.first[P](Fill.exact[P], greedy))) match
        case Outcome.Open(ty, _, cs, _) =>
          assert(ty.isInstanceOf[Ty.FnT], s"$q stopped at $ty")
          assertEquals(cs.toSet, Set("count", "sum"), q)
          assert(cs.forall(c => Dialogue.question(ty, cs, Language.En.language).contains(c)))
        case other => fail(s"$q: $other")
      assert(!reached, s"$q: a reader decided the disputed hole")
    // a plain count stays a count: the dispute is a word, not the function
    run(Dialogue.read[Pure](g, Context(s, "How many orders are unpaid?", Term.Hole(Ty.Program)), Fill.exact[P])) match
      case Outcome.Closed(t) => assertEquals(t, gold.head)
      case other => fail(other.toString)

  test("an organ's maybe cue is silence alone, and a sure cue decides; the file keeps the tier"):
    val sure = Cue(Set("how", "man"), Set.empty, "6", "count", sure = true)
    val maybe = Cue(Set("total"), Set.empty, "6", "sum", sure = false)
    val o = Organ("tiers", Vector("how", "man", "total"), Vector(sure, maybe))
    assertEquals(o.forward(Vector("how", "man", "order")), Some(Template(Map("6" -> "count"))))
    assertEquals(o.forward(Vector("total", "amount")), None)                                   // a maybe alone: silent
    assertEquals(o.forwardAll(Vector("total", "amount")), Some(Template(Map("6" -> "sum"))))   // but it votes
    assertEquals(o.forward(Vector("how", "man", "total")), None)                               // a sure and a maybe disagreeing: silent
    for ws <- Vector(Vector("how", "man"), Vector("total"), Vector("how", "total"), Vector("x")) do assertEquals(o.forward(ws), o.interpret(ws), ws.toString)
    val back = OrganFile.parse(OrganFile.json(o)).fold(e => fail(e), identity)
    assertEquals(back.cues.map(_.sure), Vector(true, false))

  test("the beam: width 1 equals the greedy reading on every sample question; with the oracle every width closes to the gold"):
    for (t, q) <- gold.zip(Sample.questions); text <- Vector(q.en, q.ru) do
      val ctx = Context(s, text, Term.Hole(Ty.Program))
      val (gs, gt) = Strategy.greedy(g, ctx, Fill.exact[Pure])
      val (bs, bt) = run(Strategy.beam[Pure](g, ctx, Fill.exact[P], width = 1))
      assertEquals(bt, gt, text); assertEquals(bs.map(_._1), gs.map(_._1), text)
      for w <- Vector(2, 4) do
        val (_, ot) = run(Strategy.beam[Pure](g, ctx, Fill.oracle[P](g, t), width = w))
        assertEquals(ot, Some(t), s"$text width $w")
    // a source with two candidates close: the beam carries both and the better-scored closed term wins
    val two: Fill[P] = new Fill[P]:
      def apply(ty: Ty, cs: Vector[String], c: Context): Dist ! P = ty match
        case Ty.TableT => pure(Vector(("customers", -0.1), ("orders", -0.2)))
        case _ => Fill.oracle[P](g, gold.head)(ty, cs, c)
    val (_, bt) = run(Strategy.beam[Pure](g, Context(s, Sample.questions.head.en, Term.Hole(Ty.Program)), Fill.first[P](two, Fill.exact[P]), width = 2))
    assert(bt.isDefined)
