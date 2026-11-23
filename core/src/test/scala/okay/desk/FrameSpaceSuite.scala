package okay.desk

import okay.desk.tasks.Everyday
import okay.frames.{Laws, Reach, Reading}

/** THE FRAME AS AN OKAY-FRAMES SPACE: the library's laws on Everyday's gold frames — every one reached as the page
 * offers its options, said and parsed back in English and Russian, no two said alike; a reading driven by the gold
 * (an oracle) reads it back */
class FrameSpaceSuite extends munit.FunSuite:
  val golds: Vector[(Map[String, Table], String, FrameTerm)] = Everyday.questions.flatMap { q =>
    val tables = Map(q.table -> Everyday.tables(q.table))
    Everyday.frame(q).toVector.flatMap(lines => Vector((tables, q.en, FrameTerm(lines)), (tables, q.ru, FrameTerm(lines))))
  }

  test("reach: every gold frame offered line by line, as the page prunes too — but the one FrameLaws knows"):
    val all = Reach.of(FrameSpace.all)(golds)
    assertEquals(all.reached, all.texts, all.refused.toString + all.examples)
    val page = Reach.of(FrameSpace.page)(golds)
    // «By how much did my balance change each month?»: its `from` a condition on a text value the question never names
    assert(page.texts - page.reached <= 2, page.refused.toString + page.examples)

  test("the round trip and the distinct words, in English and Russian"):
    for (tables, _, t) <- golds; l <- Vector("en", "ru") do
      assertEquals(Laws.roundTrip(FrameSpace.all)(tables, t, Vector(l)), None)
    for (tables, group) <- golds.groupBy(_._1); l <- Vector("en", "ru") do
      assertEquals(Laws.distinct(FrameSpace.all)(tables, group.map(_._3).distinct, l), None)

  test("a reading driven by the gold reads it back (the space's choices close to the term)"):
    for (tables, text, t) <- golds.take(20) do
      val gold = FrameSpace.all.path(tables, t)
      val read = Reading.run(FrameSpace.all)(tables, text)((r, h) =>
        h.options.map(o => if gold.lift(r.chosen.size).exists(_.option == o) then 1.0 else 0.0))
      assertEquals(read.meaning, Some(t), text)
