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

  test("clues: a constraint's lines are reachable option by option and read back to it"):
    import okay.desk.puzzle.{Constraint, Ref, Sort}
    val w = ClueWorld(5, Vector(Sort("nation", Vector("Englishman", "Spaniard", "Japanese", "Norwegian", "Dane")),
      Sort("colour", Vector("red", "green", "ivory", "yellow", "blue"))))
    val cs = Vector(Constraint.Same(Ref("nation", "Englishman"), Ref("colour", "red")),
      Constraint.Left(Ref("colour", "ivory"), Ref("nation", "Dane")), Constraint.At(Ref("nation", "Norwegian"), 1),
      Constraint.Apart(Ref("nation", "Dane"), Ref("colour", "blue"), 3), Constraint.Middle(Ref("colour", "green")),
      Constraint.NotAt(Ref("colour", "yellow"), 2), Constraint.Adjacent(Ref("nation", "Japanese"), Ref("colour", "red")))
    for c <- cs do
      val gold = Clues.lines(c)
      gold.indices.foreach(i => assert(Clues.next(w, "", gold.take(i)).contains(gold(i)), s"$c: ${gold(i)}"))
      assertEquals(Clues.constraint(w, gold), Some(c))
    // a symmetric relation is the same answer in either order
    assertEquals(Clues.answer(w, Clues.lines(Constraint.Adjacent(Ref("nation", "Japanese"), Ref("colour", "red")))),
      Clues.answer(w, Clues.lines(Constraint.Adjacent(Ref("colour", "red"), Ref("nation", "Japanese")))))
