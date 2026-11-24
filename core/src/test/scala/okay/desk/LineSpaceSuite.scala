package okay.desk

import okay.inlay.domain.Units

/** the core's domains: every drawn point of the units read by its own lines' options and run to the domain's answer */
class LineSpaceSuite extends munit.FunSuite:
  private val u = LineSpace.UnitsAndDates

  test("units: a drawn point's lines are reachable option by option and run to the point's own answer"):
    val rnd = scala.util.Random(3)
    for _ <- 1 to 300 do
      val p = Units.random(rnd, Units)
      val q = Units.say(rnd, p, Units, if rnd.nextBoolean() then "ru" else "en")
      val gold = LineSpace.UnitsAndDates.lines(p)
      gold.indices.foreach { i =>
        assert(u.next((), q, gold.take(i)).contains(gold(i)), s"$q: ${gold(i)} not offered after ${gold.take(i)}") }
      assertEquals(u.next((), q, gold), Vector.empty)
      assertEquals(u.answer((), gold), Units.run(p, Units), q)

  test("tables: the prompt is the frame reader's"):
    val t = Sample.tables
    assertEquals(LineSpace.Tables.prompt(t, "How many orders?"), Frame.prompt(t, "How many orders?"))
