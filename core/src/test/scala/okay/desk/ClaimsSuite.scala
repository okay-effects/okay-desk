package okay.desk

import okay.desk.calc.*

/** THE ANSWER AS A CONCLUSION (Claims): on every gold result a claim is made, its sentence in both languages states
 * only the claim's figures (cells and exact derivations), and the derivations recompute */
class ClaimsSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val suite = new CalculusSuite

  test("every gold result has a claim; its sentence states only its figures; the derivations recompute"):
    for (p, q) <- suite.gold.zip(Sample.questions) do
      val (query, r) = Den.run(s, Den.term(s, p)).toOption.get
      val c = Claims.of(query, r, Sample.tables)
      assert(c != Claims.Claim.Nothing, q.en)
      for ru <- Vector(false, true) do
        val sentence = Claims.text(c, ru)
        assert(Claims.readBack(sentence, c), s"${q.en}: $sentence (figures ${Claims.figures(c)})")
      c match
        case Claims.Claim.Share(_, n, of, pct) => assertEquals(pct.text, f"${n * 100.0 / of}%.0f%%")
        case Claims.Claim.Spread(_, _, (_, tv), (_, bv), gap, _) => assertEqualsDouble(gap.text.toDouble, tv.toDouble - bv.toDouble, 0.01)
        case _ => ()
    println("  e.g.\n" + suite.gold.zip(Sample.questions).map((p, q) => { val (query, r) = Den.run(s, Den.term(s, p)).toOption.get
      s"    ${q.en} → ${Claims.text(Claims.of(query, r, Sample.tables), ru = false)}" }).mkString("\n"))
