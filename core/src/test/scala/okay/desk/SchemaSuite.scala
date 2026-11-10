package okay.desk

import okay.desk.calc.Term
import okay.desk.logic.{Compile, Corpus, LogicGrammar, Schema}
import okay.desk.skill.Extract

/**
 * THE SCHEMA'S LAWS (specs/logic.md §5.6): a schema is the program with its constants abstracted, so instantiating
 * it with the program's own constants gives the program back; the word is the reading with the constants abstracted;
 * a constant the grammar does not offer at the hole is refused; and the corpus' schema library from Train covers
 * every Test schema — the measurement that says the current benchmark tests slots, not form.
 */
class SchemaSuite extends munit.FunSuite:
  val splits: Vector[Corpus.Split] = Vector(Corpus.Split.Train, Corpus.Split.Validate, Corpus.Split.Test)

  def items(split: Corpus.Split): Vector[Corpus.Item] = Corpus.items(split, 8, seed = 7)

  def term(i: Corpus.Item): Term =
    val p = okay.datalog.Parse.program(i.rules).fold(fail(_), identity)
    val a = okay.datalog.Parse.atom(i.goal).fold(fail(_), identity)
    Compile.ofProgram(p, a, i.ontology).fold(fail(_), identity)

  def grammar(i: Corpus.Item): LogicGrammar = LogicGrammar(i.ontology)
  def gold(i: Corpus.Item): Extract.Reading =
    Extract.gold(grammar(i), i.text, term(i)).fold(fail(_), identity)

  test("a schema instantiates back to the program it came from"):
    for split <- splits; i <- items(split) do
      val g = grammar(i)
      val t = term(i)
      val s = Schema.of(t)
      assertEquals(Schema.instantiate(g, s, Schema.values(t)), Right(t), i.text)
      assert(g.accepts(t), s"${i.family}: not a point: ${t.show}")

  test("the schema's word is the reading with the constants abstracted"):
    for split <- splits; i <- items(split) do
      val constants = i.ontology.constants.values.flatten.toSet
      val expected = Extract.word(gold(i)).map(w => if constants.contains(w) then "#" else w)
      assertEquals(Schema.word(term(i)), expected, i.text)

  test("the slots are the constants, in order, and the library keeps one schema per word"):
    for split <- splits do
      val ts = items(split).map(term)
      val schemas = ts.map(Schema.of)
      for (s, t) <- schemas.zip(ts) do assertEquals(s.size, Schema.values(t).size)
      assertEquals(Schema.library(ts).map(_.word).distinct.size, Schema.library(ts).size)
      for s <- schemas do assert(Schema.covered(Schema.library(ts), s))

  test("a constant the grammar does not offer at the hole is refused"):
    val i = items(Corpus.Split.Train).head
    val g = grammar(i)
    val s = Schema.of(term(i))
    val values = Schema.values(term(i))
    assert(Schema.instantiate(g, s, values.dropRight(1)).isLeft, s"${i.text}: a short fill was accepted")
    val bogus = values.updated(0, "no-such-constant")
    assert(Schema.instantiate(g, s, bogus).isLeft, s"${i.text}: a bogus constant was accepted")

  test("the schema library from Train covers every Test schema"):
    val train = Schema.library(items(Corpus.Split.Train).map(term))
    val test = items(Corpus.Split.Test).map(i => Schema.of(term(i)))
    for (s, i) <- test.zip(items(Corpus.Split.Test)) do
      assert(Schema.covered(train, s), s"${i.family}: Test schema not in Train: ${Schema.name(s)}")
    assert(train.size <= 17, s"the library is ${train.size} schemas")
