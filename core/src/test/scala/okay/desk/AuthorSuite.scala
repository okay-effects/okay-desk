package okay.desk

import okay.desk.calc.*
import okay.desk.learn.Author
import okay.desk.organ.{Organ, Organs, Template}

/** THE MODEL'S PROPOSALS, CHECKED (BACKLOG B 12): what the model writes is parsed as a cue, the law and the oracle
 * decide — here on hand-written proposals, the model's own come through `bench/AuthorBench` */
class AuthorSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val gold = (new CalculusSuite).gold.map(p => Den.term(s, p))
  val pairs = Sample.questions.zip(gold).map((q, t) => (Organ.words(q.en, Language.En.language), Organs.templateOf(t)))

  test("a proposal parsed, lawful and precise is accepted; one that fires on a pair meaning another constructor is not"):
    val own = Organ.words("How many orders are unpaid?", Language.En.language)
    val good = Author.parse("""Sure: {"words": ["how", "man"], "unless": []}""", "6", "count", own.toSet).fold(e => fail(e), identity)
    assert(Author.lawful(good, own))
    assertEquals(Author.check(good, own, pairs), Right(()))
    // "order" alone means a count here and a sum elsewhere: the oracle refuses it
    val greedy = Author.parse("""{"words": ["order"], "unless": []}""", "6", "count", own.toSet).fold(e => fail(e), identity)
    assert(Author.check(greedy, own, pairs).isLeft)
    // words not of the question are dropped; none left is no cue
    assert(Author.parse("""{"words": ["banana"]}""", "6", "count", own.toSet).isLeft)
    assert(Author.parse("no json here", "6", "count", own.toSet).isLeft)
    val organ = Author.organ(Vector(Author.Proposal("q", "6", "count", "", Some(good), accepted = true, "accepted")))
    assertEquals(organ.forward(own), Some(Template(Map("6" -> "count"))))
