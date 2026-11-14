package okay.desk

/** A MONTH NAMED, ITS LAWS: every month said in each language is read back as itself; "may" the verb is no month; the
 * value takes the year written, else the column's latest with that month */
class MonthsSuite extends munit.FunSuite:
  test("every month said in en, ru, uk, pl is read back") {
    for lang <- Vector("en", "ru", "uk", "pl"); m <- 1 to 12 do
      assertEquals(Months.in(s"Total ${Months.said(lang, m)}."), Vector(m), s"$lang $m: ${Months.said(lang, m)}")
  }
  test("Russian cases and short forms") {
    assertEquals(Months.in("Сколько я потратил за март?"), Vector(3))
    assertEquals(Months.in("расходы с января по февраль"), Vector(1, 2))
    assertEquals(Months.in("sales in Jan"), Vector(1))
  }
  test("may the verb is no month; in May is") {
    assertEquals(Months.in("What may I spend?"), Vector.empty)
    assertEquals(Months.in("What did I spend in May?"), Vector(5))
  }
  test("the value: the year written, else the latest year with that month") {
    val dates = Vector("2025-03-02", "2026-03-15", "2026-04-01")
    assertEquals(Months.value(3, "in March", dates), Some("2026-03"))
    assertEquals(Months.value(3, "in March 2025", dates), Some("2025-03"))
    assertEquals(Months.value(7, "in July", dates), None)
  }
