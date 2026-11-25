package okay.desk.books

/**
 * THE BOOK'S WORKED EXAMPLES AS LAWS (specs/books.md §1): the theory reproduces ZUS's 2026 tables and the PIT scale's
 * own figure; and every value's quote is in its source, verbatim, where the sources are on this machine
 */
class PaySuite extends munit.FunSuite:
  private val t = PayTheory.y2026
  private def v(k: String) = t(k).value

  test("ZUS 2026, full base: 1103.27 + 452.16 + 94.39 + 138.47 + 138.47 = 1926.76; 1788.29 without sickness"):
    val (withSick, fund) = Pay.jdgSocial(t, v("zus.full.base"), sickness = true, labourFund = true)
    assertEquals(withSick + fund, BigDecimal("1926.76"))
    val (noSick, fund2) = Pay.jdgSocial(t, v("zus.full.base"), sickness = false, labourFund = true)
    assertEquals(noSick + fund2, BigDecimal("1788.29"))

  test("ZUS 2026, preferential base: 281.44 + 115.34 + 24.08 + 35.32 = 456.18; 420.86 without sickness"):
    assertEquals(Pay.jdgSocial(t, v("zus.preferential.base"), sickness = true, labourFund = false)._1,
      BigDecimal("456.18"))
    assertEquals(Pay.jdgSocial(t, v("zus.preferential.base"), sickness = false, labourFund = false)._1,
      BigDecimal("420.86"))

  test("health on ryczałt is 9% of 60%, 100%, 180% of 9 228.64"):
    for (share, k) <- Vector("0.6" -> "health.lump.1", "1" -> "health.lump.2", "1.8" -> "health.lump.3") do
      val base = (BigDecimal("9228.64") * BigDecimal(share)).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      assertEquals((base * v("health.rate")).setScale(2, BigDecimal.RoundingMode.HALF_UP), v(k))

  test("the PIT scale: 120 000 → 10 800 zł (its own figure)"):
    assertEquals(BigDecimal(120000) * v("pit.rate1") - v("pit.reducing"), BigDecimal(10800))

  test("every quote is in its source, verbatim (the sources in okay-desk-private/books/pay/src)"):
    val dir = java.nio.file.Path.of(sys.props("user.home"), "work/my/okay-desk-private/books/pay/src")
    val files = Map("pit" -> "pit-2026.txt", "lump" -> "ryczalt-2025.txt", "sus" -> "sus-2026.txt",
      "health" -> "health.txt", "zus" -> "zus-skladki.txt", "mp" -> "mp-2025-1274.txt")
    if files.values.forall(f => java.nio.file.Files.exists(dir.resolve(f))) then
      val texts = files.view.mapValues(f => java.nio.file.Files.readString(dir.resolve(f))
        .replace("​", "").replaceAll("\\s+", " ")).toMap
      for (k, st) <- t do
        assert(texts(st.source).contains(st.quote.replaceAll("\\s+", " ")), s"$k: «${st.quote}» not in ${st.source}")

  // (the figure published for 2026, not in these sources: a check of the engine, not a law of the book)
  test("UoP at the minimum wage, 4 806 zł: take-home 3 605.85 zł (a month)"):
    val m = Pay.employment(t, BigDecimal(4806)).months.head
    assertEquals(m.net, BigDecimal("3605.85"))

  test("JDG: the linear tax deducts the health contribution up to 14 100 zł; the health never under 432.54"):
    val y = Jdg.year(t, BigDecimal(30000), BigDecimal(0), Form.Linear, Zus.Full, sickness = false)
    val m = y.months.head
    assertEquals(m.health, ((BigDecimal(30000) - BigDecimal("138.47") - m.social) * BigDecimal("0.049"))
      .setScale(2, BigDecimal.RoundingMode.HALF_UP))
    val taxable = (BigDecimal(30000) - BigDecimal("138.47") - m.social) * 12
    assertEquals(y.months.map(_.tax).sum,
      ((taxable - (m.health * 12).min(BigDecimal(14100))) * BigDecimal("0.19"))
        .setScale(0, BigDecimal.RoundingMode.HALF_UP))
    assertEquals(Jdg.year(t, BigDecimal(3000), BigDecimal(2500), Form.Scale, Zus.Full, sickness = false).months.head
      .health, BigDecimal("432.54"))

  test("the comparison for 21 000 zł a month (B2B) against UoP at 20 000 gross"):
    val rows = Jdg.compare(t, BigDecimal(21000), BigDecimal(500), BigDecimal("0.12"), sickness = false,
      Some(BigDecimal(20000)))
    rows.foreach((k, net) => println(f"  $k%-40s ${net / 12}%10.2f a month, $net%12.2f a year"))
    assert(rows.size == 7)

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
