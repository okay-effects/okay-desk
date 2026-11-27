package okay.desk.books

/** THE PAY QUESTIONS AS LINES (SPRINT K6): drawn meanings reachable option by option and run by okay-tax's engine
 * (its own laws, the statutes' quotes and worked examples, are in okay-tax) */
class PaySuite extends munit.FunSuite:
  test("pay questions: a drawn meaning's lines reachable option by option, and run to an answer"):
    val rnd = scala.util.Random(5)
    for _ <- 1 to 400 do
      val (lang, q, gold) = PayData.draw(rnd)
      assertEquals(okay.frames.Laws.reach(PayLines.space)((), q, okay.desk.LineTerm(gold)), None, s"[$lang] $q")
      assert(PayLines.answer((), gold).isRight, s"$q: ${PayLines.answer((), gold)}")

  test("a few drawn questions, shown"):
    val rnd = scala.util.Random(11)
    for _ <- 1 to 8 do
      val (lang, q, gold) = PayData.draw(rnd)
      println(s"  [$lang] $q\n      ${gold.mkString(" | ")}\n      ${PayLines.answer((), gold).merge}")

  test("tax rules: each rule's line reached and closed, all and among the search's first three of its own asks"):
    for among <- Seq(0, 3); r <- okay.tax.Rules.all; ask <- r.asks.values.flatMap(_.split(';').map(_.trim)) do
      val s = RuleLines(withSays = false, among)
      assertEquals(okay.frames.Laws.reach(s.space)((), ask, okay.desk.LineTerm(s.lines(r.id))), None, s"$among $ask")
      assertEquals(s.answer((), s.lines(r.id)), Right(r.id))
