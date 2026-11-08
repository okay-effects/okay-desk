package okay.desk

import okay.datalog.Fact
import okay.desk.calc.Language
import okay.desk.code.Code
import okay.inlay.domain.Kit

/** THE CODE DOMAIN (BACKLOG C): a tiny codebase as facts — the engine's answers by hand, the round trip of a point,
 * every phrase names its symbol and the copier finds it, the organ induced from the phrases reads them back */
class CodeSuite extends munit.FunSuite:
  // A.run calls B.step; B.step calls C.leaf; T.test calls A.run; Impl extends Base; Impl.step overrides Base.step
  val syms = Vector("p/A#", "p/A#run().", "p/B#", "p/B#step().", "p/C#leaf().", "t/T#", "t/T#test().", "p/Base#", "p/Base#step().", "p/Impl#", "p/Impl#step().")
  val facts = Vector(
    Fact("calls", Vector("p/A#run().", "p/B#step().")), Fact("calls", Vector("p/B#step().", "p/C#leaf().")),
    Fact("calls", Vector("t/T#test().", "p/A#run().")), Fact("calls", Vector("p/A#run().", "p/Base#step().")),
    Fact("extends", Vector("p/Impl#", "p/Base#")), Fact("overrides", Vector("p/Impl#step().", "p/Base#step().")),
    Fact("test", Vector("t/T#test().")), Fact("test", Vector("t/T#")),
    Fact("defined_in", Vector("p/A#run().", "p/A.scala")), Fact("in_module", Vector("p/A#run().", "core")))
  val s = Code.Schema(syms.map { x => val (sh, q) = Code.namesOf(x); Code.Sym(x, sh, q, if x.endsWith("#") then "class" else "method") },
    facts ++ syms.map(x => Fact("kind", Vector(x, if x.endsWith("#") then "class" else "method"))))
  def sym(x: String) = s.bySymbol(x)

  test("the engine by hand: callers are transitive, a change affects through dispatch, the tests for a change"):
    assertEquals(Code.run(Code.Point(Code.Rel.Callers, sym("p/C#leaf().")), s), Right(Vector("p/A#run().", "p/B#step().", "t/T#test().")))
    assertEquals(Code.run(Code.Point(Code.Rel.Calls, sym("p/A#run().")), s), Right(Vector("p/B#step().", "p/Base#step().")))
    assertEquals(Code.run(Code.Point(Code.Rel.Implementers, sym("p/Base#")), s), Right(Vector("p/Impl#")))
    assertEquals(Code.run(Code.Point(Code.Rel.Up, sym("p/Impl#step().")), s), Right(Vector("p/Base#step().")))
    // a change to Impl.step affects whoever calls Base.step (dispatch), and so the test
    assertEquals(Code.run(Code.Point(Code.Rel.Affects, sym("p/Impl#step().")), s), Right(Vector("p/A#run().", "t/T#test().")))
    assertEquals(Code.run(Code.Point(Code.Rel.Tests, sym("p/C#leaf().")), s), Right(Vector("t/T#test().")))
    assertEquals(Code.run(Code.Point(Code.Rel.DefinedIn, sym("p/A#run().")), s), Right(Vector("p/A.scala")))
    assertEquals(Code.run(Code.Point(Code.Rel.Kind, sym("p/A#")), s), Right(Vector("class")))

  test("a point round-trips through its parts; every phrase in both languages names the symbol, and the copier finds it"):
    val rnd = scala.util.Random(3)
    for p <- Code.enumerate(s) do
      assertEquals(Code.assemble(s, Code.parts(p)), Some(p))
      for lang <- Vector("en", "ru"); _ <- 1 to 3 do
        val q = Code.say(rnd, p, s, lang)
        assertEquals(Code.copier.copy(q, s, Code.slots(s)(1)), Some(p.symbol.symbol), q)

  test("the organ induced from the phrases reads the relation of every phrase back, by its weights"):
    val pairs = Kit.pairs(Code)(s, 900, 5, Vector("en", "ru"))
    val lang = (c: String) => if c == "ru" then Language.Ru.language else Language.En.language
    val organ = Code.induce(pairs, lang)
    // (the organ alone: where it speaks it is right; the rules behind it are the baseline the bench measures)
    val m = Kit.measure(Code)(s, pairs, Code.ByOrgan(organ, lang), Code.copier)
    println(s"  the code organ: ${organ.cues.size} cues; ${m.text}")
    assert(m.wrongAnswered == 0, m.text)
    assert(m.whole * 2 >= pairs.size, m.text)
    for p <- pairs do
      val ws = okay.desk.organ.Organ.words(p.question, lang(p.language))
      assertEquals(organ.forward(ws), organ.interpret(ws), p.question)
