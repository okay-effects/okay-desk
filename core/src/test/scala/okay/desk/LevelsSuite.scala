package okay.desk

import okay.desk.calc.*
import okay.desk.Space.Program
import okay.scala3.*

/** THE LEVELS (specs/calculus.md §7): each level's own laws, and each described by the one below — the choice
 * calculus (0), the word calculus (1), typed terms (2), the space and its phrases (3) */
class LevelsSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = new Grammar(s)
  val suite = new CalculusSuite
  val gold: Vector[Program] = suite.gold
  val points: Vector[Program] = gold ++ suite.randoms
  val ctxEn = (s, "")

  test("level 0: a run replayed by its own moves is the same run; a run is bounded"):
    val c = Word.choice(Encode.next(g, exact = false))
    for p <- points.take(50) do
      val w = Encode.word(Den.term(s, p))
      val r1 = run(Choice.run(c, Choice.replay[(Encode.Ctx, Word.Letters), String, Pure](w, _._2.size), (ctxEn, Vector.empty)))
      assert(r1.terminal && r1.moves == w, s"$p: ${r1.moves} stopped ${r1.stopped}")
      val r2 = run(Choice.run(c, Choice.replay[(Encode.Ctx, Word.Letters), String, Pure](w, _._2.size), (ctxEn, Vector.empty), bound = 2))
      assert(!r2.terminal && r2.moves.size == 2, s"$p: ${r2.moves} stopped ${r2.stopped}")

  test("level 1: exactness — where `next` narrows to one letter along a gold word, it is the gold's; a complete word interprets"):
    val next = Encode.next(g, exact = true)
    for (p, q) <- gold.zip(Sample.questions); text <- Vector(q.en, q.ru) do
      val w = Encode.word(Den.term(s, p))
      assert(Word.narrowsRight(next, (s, text), w), s"$text: $w")
      assert(Encode.interp(g)((s, text), w).isRight)

  test("level 2 by level 1: a term is its word and back; a level-2 refinement and a level-1 reading take the same word"):
    for p <- points do
      val t = Den.term(s, p)
      assertEquals(Encode.tree(g, Encode.word(t)), t)
    val next = Encode.next(g, exact = true)
    for q <- Sample.questions; text <- Vector(q.en, q.ru) do
      val l2 = run(Refine.greedy[Pure](g, Fill.exact, Context(s, text, Term.Hole(Ty.Program))))
      // (level 1 with the exact facts inside `next` and NO chooser: the chooser never has an opinion)
      val l1 = run(Word.read(next, Word.chooser[Encode.Ctx, Pure]((_, _, _) => pure(Vector.empty)), (s, text)))
      assertEquals(l1.moves, Encode.word(l2.term), s"$text: level 1 ${l1.moves} vs level 2 ${l2.term.show}")
    // the oracle at level 1 reaches every point
    for p <- points do
      val w = Encode.word(Den.term(s, p))
      val r = run(Word.read(Encode.next(g, exact = false), Word.oracle[Encode.Ctx, Pure](w), ctxEn))
      assert(r.terminal && r.moves == w, s"$p")

  test("level 3 by level 2: a point is a complete word; its phrase, read by the data alone, never contradicts it"):
    val next = Encode.next(g, exact = true)
    val rnd = new scala.util.Random(5)
    var closed = 0; var contradicted = Vector.empty[String]
    for p <- points do
      val w = Encode.word(Den.term(s, p))
      assert(Encode.next(g, exact = false)((s, ""), w).isEmpty, s"$p: not complete")
      assertEquals(Encode.interp(g)((s, ""), w).map(_._2.rows.toSet), Space.compile(p, Sample.tables).flatMap(Sample.run).map(_.rows.toSet))
      for ru <- Vector(false, true) do
        val text = Phrase.say(rnd, p, s, ru)
        Word.contradiction(next, (s, text), w).foreach((i, got, want) => contradicted = contradicted :+ s"$text → at $i: $got, gold $want ($w)")
        val r = run(Word.read(next, Word.chooser[Encode.Ctx, Pure]((_, _, _) => pure(Vector.empty)), (s, text)))
        if r.terminal && r.moves == w then closed += 1
    println(s"  level 3: ${points.size} points phrased twice: the data alone closes $closed of ${points.size * 2} to the point; contradictions ${contradicted.size}")
    contradicted.take(40).foreach(c => println(s"    $c"))
    assertEquals(contradicted.size, 0)
