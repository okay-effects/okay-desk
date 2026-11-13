package okay.desk.statement

/** THE LEARNT GATE'S LAWS: its file read back as written; the signs in their documented order; agreement and the
 * margins move the probability of wrong the way the weights say */
class GateSuite extends munit.FunSuite:
  private def reading(least: Map[String, Float], answer: Option[Vector[String]], news: (Int, Int) = (0, 0)) =
    Reader.Reading(None, answer, least, news, None)
  private val bars = Map("en tag" -> 2f, "en frame" -> 1f, "en link" -> 1f, "en pick" -> 1f)

  test("the file is read back as written") {
    val g = Gate(Vector(0.5, -1.25, 3.0), 0.03)
    assertEquals(Gate.parse(Gate.write(g)), Right(g))
  }

  test("the signs: bias, a's margins, b's margins, novelty, agreement, language") {
    val a = reading(Map("tag" -> 4f, "frame" -> 0.5f), Some(Vector("x")), (1, 0))
    val b = reading(Map("tag" -> 2f), Some(Vector("x")))
    val f = Gate.features(a, bars, b, bars, russian = false)
    assertEquals(f.length, 13)
    assertEqualsDouble(f(0), 1.0, 1e-9)
    assertEqualsDouble(f(1), 1.0, 1e-9)    // tag 4 over its bar 2: 2 − 1
    assertEqualsDouble(f(2), -0.5, 1e-9)   // frame 0.5 over 1
    assertEqualsDouble(f(3), 3.0, 1e-9)    // no link decided: sure
    assertEqualsDouble(f(5), 0.0, 1e-9)    // b's tag 2 over 2
    assertEqualsDouble(f(9), math.log(2), 1e-9)
    assertEqualsDouble(f(11), 1.0, 1e-9)   // the same answer
    assertEqualsDouble(f(12), 0.0, 1e-9)
  }

  test("agreeing lowers the probability of wrong when its weight is negative; no answer is never shown") {
    val g = Gate(Vector.fill(11)(0.0) ++ Vector(-3.0, 0.0), 0.3)
    val a = reading(Map.empty, Some(Vector("x")))
    assert(g.shows(a, bars, reading(Map.empty, Some(Vector("x"))), bars, russian = false))
    assert(!g.shows(a, bars, reading(Map.empty, Some(Vector("y"))), bars, russian = false))
    assert(!g.shows(reading(Map.empty, None), bars, a, bars, russian = false))
  }
