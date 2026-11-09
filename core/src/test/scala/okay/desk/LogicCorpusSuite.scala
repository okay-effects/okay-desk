package okay.desk

import okay.desk.calc.{Encode, Term}
import okay.desk.logic.{Compile, Corpus, LogicGrammar}
import okay.desk.skill.Extract
import okay.desk.skill.Extract.Evidence

/**
 * THE CORPUS' LAWS (specs/logic.md §5.3): every generated text says its gold program, the gold reading replays to
 * it, every constant is either copied from the text or the ontology's own knowledge, every answer is the engine's
 * and is not empty, and the splits share no sentence pattern. The generator is a teacher: these laws check the
 * corpus, not a reader.
 */
class LogicCorpusSuite extends munit.FunSuite:
  val splits: Vector[Corpus.Split] = Vector(Corpus.Split.Train, Corpus.Split.Validate, Corpus.Split.Test)

  def items(split: Corpus.Split): Vector[Corpus.Item] = Corpus.items(split, 8, seed = 7)

  def grammar(i: Corpus.Item): LogicGrammar = LogicGrammar(i.ontology)
  def term(i: Corpus.Item): Term = Compile.ofProgram(program(i), atom(i), i.ontology).fold(fail(_), identity)
  def program(i: Corpus.Item): okay.datalog.Program = okay.datalog.Parse.program(i.rules).fold(fail(_), identity)
  def atom(i: Corpus.Item): okay.datalog.Atom = okay.datalog.Parse.atom(i.goal).fold(fail(_), identity)
  def gold(i: Corpus.Item): Extract.Reading = Extract.gold(grammar(i), i.text, term(i)).fold(fail(_), identity)

  test("every split carries the four families, eight items each"):
    for split <- splits do
      val all = items(split)
      assertEquals(all.size, 32)
      assertEquals(all.groupBy(_.family).view.mapValues(_.size).toMap,
        Map("planning" -> 8, "numbers" -> 8, "kinship" -> 8, "data" -> 8))

  test("every generated text is a point of the grammar and its gold reading replays to its term"):
    for split <- splits; i <- items(split) do
      val g = grammar(i)
      val tm = term(i)
      assert(g.accepts(tm), s"${i.family}: not a point: ${tm.show}\n${i.text}")
      val r = gold(i)
      assertEquals(Extract.word(r), Encode.word(tm), i.text)
      assertEquals(Extract.replay(g, r), Right(tm), i.text)
      for c <- r.choices do c.evidence.foreach {
        case Evidence.Text(s) =>
          assert(i.text.substring(s.start, s.end).equalsIgnoreCase(c.con), s"${i.text}: $s is not ${c.con}")
        case _ => ()
      }

  test("a constant is copied from the text, except the threshold the ontology knows"):
    for split <- splits; i <- items(split) do
      for c <- gold(i).choices do
        if c.evidence.contains(Evidence.Known) then
          assertEquals(c.con, "18", s"${i.family}: ${c.con} is not copied\n${i.text}")
          assertEquals(i.family, "numbers")
      if i.family != "numbers" then
        assert(!gold(i).choices.exists(_.evidence.contains(Evidence.Known)), s"${i.text}: a known constant")

  test("every answer is the engine's, and no question is empty"):
    for split <- splits; i <- items(split) do
      val (_, result) = Compile.run(i.tables, term(i)).fold(e => fail(s"${i.text}: $e"), identity)
      assert(result.rows.nonEmpty, s"${i.text}: empty answer")

  test("the splits share no sentence pattern"):
    for (family, bySplit) <- Corpus.templates do
      for a <- splits; b <- splits if a != b do
        val shared = bySplit(a).toSet & bySplit(b).toSet
        assert(shared.isEmpty, s"$family: $a and $b share ${shared.mkString(", ")}")

  test("generation is deterministic"):
    for split <- splits do assertEquals(items(split), Corpus.items(split, 8, seed = 7))
