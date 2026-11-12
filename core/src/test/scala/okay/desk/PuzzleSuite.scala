package okay.desk

import okay.desk.calc.Language
import okay.desk.puzzle.*
import okay.inlay.domain.Kit

/** PUZZLES (specs/puzzles.md §5): the classic solved with its trace; a clue removed or negated; the classic text
 * read in English and Russian into the fifteen constraints by the data alone; every phrase reads back (GF) */
class PuzzleSuite extends munit.FunSuite:
  val p = Puzzle.classic

  test("the classic puzzle: one solution, the Japanese owns the zebra, the Norwegian drinks water; the trace names clue 9 for the Norwegian"):
    Solver.solve(p) match
      case Solver.Outcome.One(g, trace) =>
        assertEquals(Solver.answer(p, g).map(_._2), Some("Japanese"))
        assertEquals(g(Ref("nation", "Norwegian")), g(Ref("drink", "water")))
        assertEquals(g(Ref("nation", "Norwegian")), 1)
        assert(trace.exists(s => s.ref == Ref("nation", "Norwegian") && s.clue == 9), trace.take(5).toString)
        assert(trace.exists(s => s.ref == Ref("colour", "blue") && s.position == 2), trace.toString)
        println(s"  the classic: ${trace.size} steps, ${trace.count(_.clue == -1)} suppositions; the zebra's owner ${Solver.answer(p, g).map(_._2).get}")
        println(Solver.table(p, g).map(_.mkString(" | ")).mkString("    ", "\n    ", ""))
      case other => fail(other.toString)

  test("a clue removed: several solutions and the values that differ; a clue negated: none, the clashing clues named"):
    Solver.solve(p.copy(constraints = p.constraints.patch(13, Nil, 1))) match
      case Solver.Outcome.Several(n, _, differing) => assert(n > 1); assert(differing.nonEmpty); println(s"  without clue 14: $n solutions, differing in ${differing.map(_.show).mkString(", ")}")
      case other => fail(other.toString)
    val broken = p.copy(constraints = p.constraints :+ Constraint.Same(Ref("nation", "Englishman"), Ref("colour", "green")))
    Solver.solve(broken) match
      case Solver.Outcome.None(clashing) => assert(clashing.contains(1) || clashing.contains(15), clashing.toString); println(s"  the Englishman in the green house too: no solution; the clashing clues $clashing")
      case other => fail(other.toString)

  test("the classic text, English and Russian, read into the fourteen constraints by the words and the copier"):
    for (text, lang) <- Vector((PuzzleText.classicEn, "en"), (PuzzleText.classicRu, "ru")) do
      val parsed = PuzzleText.parse(text, PuzzleDomain.rules)
      assertEquals(parsed.problems, Vector.empty)
      val unread = parsed.clues.filter(_.read.isEmpty)
      assert(unread.isEmpty, s"$lang unread: ${unread.map(c => s"${c.text} → ${c.form} ${c.values}").mkString("; ")}")
      // the same constraints up to the sorts' names (the header's) and the order of a symmetric pair
      def norm(c: Constraint): (String, Vector[String]) = c match
        case Constraint.Same(a, b) => ("same", Vector(a.value, b.value).sorted)
        case Constraint.Differ(a, b) => ("differ", Vector(a.value, b.value).sorted)
        case Constraint.Adjacent(a, b) => ("adjacent", Vector(a.value, b.value).sorted)
        case Constraint.At(a, n) => ("at", Vector(a.value, n.toString))
        case Constraint.Middle(a) => ("middle", Vector(a.value))
        case Constraint.Left(a, b) => ("left", Vector(a.value, b.value))
        case Constraint.RightNext(a, b) => ("rightNext", Vector(a.value, b.value))
        case Constraint.NotAt(a, n) => ("notAt", Vector(a.value, n.toString))
        case Constraint.Apart(a, b, d) => ("apart", Vector(a.value, b.value).sorted :+ d.toString)
      assertEquals(parsed.clues.flatMap(_.read).map(norm).toSet, p.constraints.map(norm).toSet, s"$lang: ${parsed.clues.map(c => c.text + " → " + c.read).mkString("\n")}")
      assertEquals(parsed.question.map(_.of.value), Some("zebra"), lang)
      Solver.solve(parsed.puzzle) match
        case Solver.Outcome.One(g, _) => assertEquals(Solver.answer(parsed.puzzle, g).map(_._2), Some("Japanese"))
        case other => fail(other.toString)
      println(s"  $lang: ${parsed.clues.size} clues read, the question ${parsed.question}")

  test("GF: every phrase of 400 random constraints in four languages reads back to its constraint by the words and the copier"):
    val s = PuzzleDomain.Schema(p)
    val pairs = Kit.pairs(PuzzleDomain)(s, 400, 3, Vector("en", "ru", "uk", "pl"))
    val m = Kit.measure(PuzzleDomain)(s, pairs, PuzzleDomain.rules, PuzzleDomain.copier)
    println(s"  the phrases: ${m.text}")
    val wrong = pairs.filter(pr => Kit.read(PuzzleDomain)(s, pr.question, PuzzleDomain.rules, PuzzleDomain.copier).point != Some(pr.point))
    assert(wrong.isEmpty, wrong.take(6).map(pr => s"${pr.question} → ${Kit.read(PuzzleDomain)(s, pr.question, PuzzleDomain.rules, PuzzleDomain.copier).parts} (gold ${PuzzleDomain.parts(pr.point)})").mkString("\n"))
    // an organ induced from the phrases reads them too, never wrong where it speaks
    val lang = (c: String) => if c == "ru" then Language.Ru.language else Language.En.language
    val organ = PuzzleDomain.induce(pairs, lang)
    val mo = Kit.measure(PuzzleDomain)(s, pairs, PuzzleDomain.ByOrgan(organ, lang), PuzzleDomain.copier)
    println(s"  the organ: ${organ.cues.size} cues; ${mo.text}")
    assertEquals(mo.wrongAnswered, 0)
