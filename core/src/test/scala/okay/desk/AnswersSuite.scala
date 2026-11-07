package okay.desk

import okay.desk.calc.*
import okay.scala3.*

/** GENERATION BY THE SAME PROGRAM (specs/calculus.md §10): the answer rules under greedy give one sentence, under
 * every way give all wordings; every term's facts are cells; every sentence reads back — both languages */
class AnswersSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val suite = new CalculusSuite

  test("every wording of every gold answer: facts are cells, the sentence reads back; greedy is the first wording"):
    var sentences = 0
    for (p, q) <- suite.gold.zip(Sample.questions); ru <- Vector(false, true) do
      val (_, r) = Den.run(s, Den.term(s, p)).toOption.get
      val rules = new Answers(p, r, ru)
      val ctx = Answers.context(s)
      val all = Strategy.every(rules, ctx)
      assert(all.nonEmpty, q.en)
      for t <- all do
        assert(rules.closed(t), t.show)
        assert(rules.facts(t).forall(f => r.column(f.column).exists(i => r.rows(f.row)(i).text == f.text)), s"${q.en}: ${t.show}")
        val sentence = rules.text(t)
        assert(Answers.readBack(rules, t, sentence), s"${q.en}: $sentence")
        sentences += 1
      val (_, one) = Strategy.greedy(rules, ctx, Fill.lift(new Fill[Pure] { def apply(ty: Ty, cs: Vector[String], c: Context): Dist ! Pure = pure(cs.map(x => (x, 0.0))) }))
      assertEquals(one, all.headOption, q.en)
    println(s"  answers: $sentences sentences over 13 results × 2 languages, all grounded and read back")
    val (_, r) = Den.run(s, Den.term(s, suite.gold(1))).toOption.get
    println("  e.g. " + Strategy.every(new Answers(suite.gold(1), r, ru = false), Answers.context(s)).map(new Answers(suite.gold(1), r, ru = false).text).mkString(" | "))

/** the page's construction (from the query) agrees with the calculus' (from the point) on every gold result */
class OfQuerySuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val suite = new CalculusSuite
  test("Answer.ofQuery(query, result) == Answer.of(point, result) for the 13 gold, both languages"):
    for (p, q) <- suite.gold.zip(Sample.questions) do
      val (query, r) = Den.run(s, Den.term(s, p)).toOption.get
      val a = Answer.of(p, r); val b = Answer.ofQuery(query, r)
      assertEquals(b, a, q.en)
      for ru <- Vector(false, true) do assert(Answer.readBack(Answer.text(b, ru), b), Answer.text(b, ru))
