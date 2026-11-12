package okay.desk

import okay.desk.know.Knowledge
import Knowledge.{Quote, Source, Step}

class KnowledgeSuite extends munit.FunSuite:
  val sources = Vector(
    Source("a", "Water", "https://example.org/water", "Water freezes at 0 °C. Ice is solid water.", "2026-11-08"),
    Source("b", "Rain", "https://example.org/rain", "Rain falls from clouds.", "2026-11-08")
  )

  test("one reasoning language accepts searches, constraints and sourced answers, rejecting malformed programs"):
    assertEquals(Knowledge.parse("""{"search":["why rain falls"]}"""), Right(Step.Search(Vector("why rain falls"))))
    assertEquals(Knowledge.parse("""{"insufficient":true}"""), Right(Step.Insufficient))
    for bad <- Vector(
        "{}",
        "[]",
        "{\"search\":[]}",
        "{\"search\":[3]}",
        "{\"answer\":[{}]}",
        "{\"search\":[\"x\"],\"insufficient\":true}"
      )
    do assert(Knowledge.parse(bad).isLeft, bad)

  test("source lookup in compiled weights equals the interpreter; fabricated quotes and citations cannot pass"):
    val quotes = Vector(Quote("a", "Water freezes at 0 °C."), Quote("b", "Rain falls from clouds."))
    val grounded = Knowledge.ground(sources, quotes)
    assertEquals(grounded, Knowledge.ground(sources, quotes, weights = false))
    assertEquals(grounded.toOption.get.map(_.source.url), sources.map(_.url))
    assert(Knowledge.ground(sources, Vector(Quote("a", "Water freezes at 10 °C."))).isLeft)
    assert(Knowledge.ground(sources, Vector(Quote("unknown", sources.head.text))).isLeft)
    assert(Knowledge.ground(sources, Vector(Quote("a", ""))).isLeft)
    assert(Knowledge.ground(sources, Vector(Quote("a", sources(1).text))).isLeft)

  test("collected evidence is cached by the full question, including hash collisions"):
    val cache = Knowledge.cache("why?", sources)
    assertEquals(Knowledge.cached("why?", cache), Some(sources))
    assertEquals(Knowledge.cached("another question", cache), None)
    assertEquals(Knowledge.cached("why?", "bad JSON"), None)
    assert(Knowledge.searchUrl("a & b?", "en").contains("a%20%26%20b%3F"))

  test("the search adapter keeps revision provenance and rejects empty/error responses"):
    val text = """{"curtimestamp":"2026-11-08T16:00:00Z","query":{"pages":[
      |{"pageid":42,"lastrevid":7,"title":"Rain","extract":"Rain falls from clouds."}]}}
      |""".stripMargin
    val found = Knowledge.searchResults(text, "en").toOption.get
    assertEquals(found.head.url, "https://en.wikipedia.org/w/index.php?curid=42&oldid=7")
    assertEquals(found.head.date, "2026-11-08")
    assert(Knowledge.searchResults("{\"error\":{}}", "en").isLeft)

  test("arithmetic is exact and rejects invented factual operands and executable text"):
    import okay.desk.know.Arithmetic
    assertEquals(Arithmetic.run("(2 + 3) * 4", "(2 + 3) * 4"), Right("20"))
    assertEquals(Arithmetic.run("0.1 + 0.2", "0.1 + 0.2"), Right("0.3"))
    assertEquals(Arithmetic.run("1 / 3", "1 / 3"), Right("1/3"))
    assertEquals(Arithmetic.run("1 / 8", "1 / 8"), Right("0.125"))
    val large = "123456789012345678901234567891 * 123456789012345678901234567891 / 2"
    assert(Arithmetic.run(large, large).toOption.get.endsWith(".5"), "a terminating decimal must not round")
    assert(Arithmetic.run("1 / 0", "1 / 0").isLeft)
    assert(Arithmetic.run("2 + 9", "2 + 3").isLeft)
    assert(Arithmetic.run("1; sys.exit()", "1").isLeft)
