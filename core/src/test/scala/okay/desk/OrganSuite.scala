package okay.desk

import okay.desk.calc.*
import okay.desk.organ.*
import okay.scala3.*

/** ORGANS (specs/organs.md): an exact reading rule over words as an inlay program — the weights equal the
 * interpreter; induced from pairs it says the gold on them; as a source it decides along its template only */
class OrganSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = new Grammar(s)
  val en = Language.En.language
  val gold = (new CalculusSuite).gold.map(p => Den.term(s, p))
  /** the sample's English questions with their gold templates — the pairs an organ is induced from */
  val pairs: Vector[(Vector[String], Template)] = Sample.questions.zip(gold).map((q, t) => (Organ.words(q.en, en), Organs.templateOf(t)))
  val organ = Organ.induce("sample-en", pairs, minCover = 1, prune = false)

  test("the organ compiled into weights equals the interpreter on random words of its vocabulary, and on the pairs"):
    val rnd = scala.util.Random(7)
    val vocab = organ.vocabulary :+ "other" :+ "noise"
    val inputs = Vector.fill(150)(Vector.fill(1 + rnd.nextInt(12))(vocab(rnd.nextInt(vocab.size)))) ++ pairs.map(_._1)
    for ws <- inputs do assertEquals(organ.forward(ws), organ.interpret(ws), ws.mkString(" "))
    println(s"  the organ: ${organ.cues.size} cues over ${organ.vocabulary.size} words, ${organ.weights.params} parameters; ${inputs.size} inputs, the weights equal the interpreter")

  test("induced from the sample's pairs, the organ says only parts of each pair's template (by the weights), nothing on noise"):
    var said = 0; var parts = 0
    for (ws, t) <- pairs do
      organ.forward(ws) match
        case Some(got) =>
          for (k, v) <- got.parts do assertEquals(Some(v), t.parts.get(k), s"${ws.mkString(" ")} at $k")
          said += 1; parts += got.parts.size
        case None => ()
    assert(said >= pairs.size / 2, s"$said of ${pairs.size} said")
    assertEquals(organ.forward(Vector("hello", "there")), None)
    println(s"  $said of ${pairs.size} pairs spoken about by the organ, $parts parts, every part the gold's")

  test("as a source: the organ decides the holes of its template, is silent on values, and the reading closes to the gold"):
    for (q, t) <- Sample.questions.zip(gold) if organ.interpret(Organ.words(q.en, en)).isDefined do
      val ctx = Context(s, q.en, Term.Hole(Ty.Program))
      // the organ with the data for the copied values (a value, a number): the term is the gold's
      val fill = Fill.first[Strategy.Rest[Organs]](Organs.fill[Strategy.Rest[Organs]](Vector("sample-en")), Fill.exact[Strategy.Rest[Organs]])
      val (_, term) = run(handle(Strategy.greedyIn[Organs](g, ctx, fill), Organs.byWeights[Pure, (List[Strategy.Step], Option[Term])](Vector(organ))))
      assertEquals(term, Some(t), q.en)
      // silent on a value hole
      val source: Fill[Organs] = Organs.fill[Organs](Vector("sample-en"))
      val d = run(handle(source(Ty.ValueT("orders", "status"), Vector("paid", "unpaid"), ctx), Organs.byInterpreter[Pure, Dist](Vector(organ))))
      assertEquals(d, Vector.empty)
