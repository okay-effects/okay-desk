package okay.desk

import okay.desk.calc.*
import okay.scala3.*

/** THE READING ON THE STANDARD EFFECTS (specs/calculus.md §9): the one program, two handlers of `Among` — greedy
 * is the first way (the same term as `Refine.greedy`), every way is the space (the same points as `Space.enumerate`) */
class StrategySuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = new Grammar(s)
  def ctx(text: String) = Context(s, text, Term.Hole(Ty.Program))

  test("greedy on the standard effects: the same term as the function form, the steps the same holes"):
    for q <- Sample.questions; text <- Vector(q.en, q.ru) do
      val (steps, term) = Strategy.greedy(g, ctx(text), Fill.exact[Pure])
      val byFunction = run(Refine.greedy[Pure](g, Fill.exact, ctx(text)))
      if g.closed(byFunction.term) then assertEquals(term, Some(byFunction.term), text)
      else
        assertEquals(term, None, text)
        assertEquals(steps.lastOption.map(_._1), byFunction.unsure.map(_._1), s"$text: the hole no source decided")

  test("every way with a uniform weigh IS the space: the same points as Space.enumerate"):
    // (the smaller schema: orders alone, so the enumeration is seconds — the law is about the sets, not the size)
    val small = Space.Schema(Map("orders" -> Sample.orders))
    val gs = new Grammar(small)
    val byReading = Strategy.every(gs, Context(small, "", Term.Hole(Ty.Program))).flatMap(t => Den.program(small, t).toOption).toSet
    val byEnumeration = Space.enumerate(small).toSet
    println(s"  every way: ${byReading.size} points by the reading, ${byEnumeration.size} by the enumeration")
    val onlyReading = byReading -- byEnumeration; val onlyEnumeration = byEnumeration -- byReading
    onlyReading.take(3).foreach(p => println(s"    only by the reading: $p"))
    onlyEnumeration.take(3).foreach(p => println(s"    only by the enumeration: $p"))
    assertEquals(onlyEnumeration, Set.empty[Space.Program])
    assertEquals(onlyReading, Set.empty[Space.Program])
