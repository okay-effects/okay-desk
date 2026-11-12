package okay.desk.bench

import okay.desk.know.Formal
import okay.effects.json.Json

class GsmProgramsSuite extends munit.FunSuite:
  test("authored intermediate results expand into an exact executable expression"):
    val q = "There are 8 boxes with 4 marbles each. Remove 5 marbles. How many marbles remain?"
    val pair = GsmPrograms.compile(q, "Total <<8*4=32>>32. Remaining <<32-5=27>>27.\n#### 27").toOption.get
    val body = Json.print(Json.fromText(pair.program)("formal"))
    assertEquals(Formal.run(body).toOption.get.answers("answer"), Vector("27"))
    assertEquals(Json.fromText(body)("ask")("answer"), Json.Arr(Vector(Json.Str("-"),
      Json.Arr(Vector(Json.Str("*"), Json.Num(8), Json.Num(4))), Json.Num(5))))
    assert(!pair.program.contains("32"), "an intermediate answer must be computed, not injected")

  test("ambiguous numeral reuse and wrong annotated answers are excluded from the training pool"):
    assert(GsmPrograms.compile("There are 8 units.", "<<8-3=5>>. <<5+2=7>>\n#### 7").isLeft)
    assert(GsmPrograms.compile("There are 8 and 4 units.", "<<8+4=13>>\n#### 13").isLeft)
    assert(GsmPrograms.compile("There are 8 and 4 units.", "<<8+4=12>>\n#### 13").isLeft)
