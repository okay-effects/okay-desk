package okay.desk.books

/** THE PAY QUESTIONS AS LINES (SPRINT K6): drawn meanings reachable option by option and run by okay-tax's engine
 * (its own laws, the statutes' quotes and worked examples, are in okay-tax) */
class PaySuite extends munit.FunSuite:
  test("pay questions: a drawn meaning's lines reachable option by option, and run to an answer"):
    val rnd = scala.util.Random(5)
    for _ <- 1 to 400 do
      val (lang, q, gold) = PayData.draw(rnd)
      gold.indices.foreach { i =>
        assert(PayLines.next((), q, gold.take(i)).contains(gold(i)),
          s"[$lang] $q: ${gold(i)} not offered after ${gold.take(i)}; offered ${PayLines.next((), q, gold.take(i))}") }
      assertEquals(PayLines.next((), q, gold), Vector.empty)
      assert(PayLines.answer((), gold).isRight, s"$q: ${PayLines.answer((), gold)}")

  test("a few drawn questions, shown"):
    val rnd = scala.util.Random(11)
    for _ <- 1 to 8 do
      val (lang, q, gold) = PayData.draw(rnd)
      println(s"  [$lang] $q\n      ${gold.mkString(" | ")}\n      ${PayLines.answer((), gold).merge}")
