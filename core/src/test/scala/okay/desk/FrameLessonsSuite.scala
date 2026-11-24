package okay.desk

class FrameLessonsSuite extends munit.FunSuite:
  test("a choice kept by the tables and the question's words, and read back"):
    val t = Sample.tables
    val l = FrameLessons().add(t, "Сколько заказов из Львова?", "where: city = Lviv")
    assertEquals(l.lines(t, "сколько заказов   из Львова"), Set("where: city = Lviv"))
    assertEquals(l.lines(t, "Сколько заказов из Киева?"), Set.empty[String])
    assertEquals(FrameLessons.parse(l.json), l)
    assertEquals(l.forget(t, "Сколько заказов из Львова?").lines(t, "Сколько заказов из Львова?"), Set.empty[String])
