package okay.desk.tasks

import okay.desk.statement.{Budget, Faults, Statement}
import Drawn.Split

/**
 * THE CORPUS, CHECKED (specs/formalization.md §7 step 3), with no model anywhere: a drawn task is exact (the solver's
 * result is the answer it was drawn from), its statement in both languages is of its text and compiles to that
 * result, and the splits keep what they promise — no text in two of them, no held-out family in training, no task
 * of the collection anywhere.
 */
class DrawnSuite extends munit.FunSuite:
  lazy val splits: Map[Split, Vector[Drawn.Item]] =
    Map(Split.Train -> 240, Split.Validate -> 40, Split.Test -> 40, Split.Unseen -> 60,
      Split.Sealed -> 40).map((s, n) => s -> Drawn.items(s, n))
  lazy val all: Vector[Drawn.Item] = splits.values.flatten.toVector

  test("a drawn task is exact, and its statement in both languages gives the same result"):
    assertEquals(all.size, 420)
    all.foreach { i =>
      assertEquals(i.task.check, Right(()), i.task.title.en)
      val read = i.task.stated.getOrElse(fail(s"${i.family}: not stated"))
      Vector(read.en, read.ru).foreach { s =>
        def key(name: String): String = s.keys.collectFirst {
          case (words, k) if Statement.same(words, name) => k.drop(k.indexOf('/') + 1) }.getOrElse(name)
        val got = Faults.result(s.statement, s.text).map(_.map(_.split('|').toVector.map(key).sorted.mkString("|")))
        assertEquals(got, Right(i.task.expect.sorted), s"${i.family} ${i.seed}") } }

  test("the splits are disjoint, the held-out families are in no other split, the collection is in none"):
    val texts = splits.view.mapValues(_.map(_.task.text.en).toSet).toMap
    for a <- Split.values; b <- Split.values if a.ordinal < b.ordinal do
      assert((texts(a) & texts(b)).isEmpty, s"$a and $b")
    val held = Drawn.families.filter(_.held).map(_.name).toSet
    assertEquals(splits(Split.Unseen).map(_.family).toSet, held)
    assert(all.filter(_.split != Split.Unseen).forall(i => !held(i.family)))
    val apart = Drawn.sealedFamilies.map(_.name).toSet
    assertEquals(splits(Split.Sealed).map(_.family).toSet, apart)
    assert(all.filter(_.split != Split.Sealed).forall(i => !apart(i.family)))
    val collection = Tasks.entries.map(_.task.text.en).toSet
    assert(all.forall(i => !collection(i.task.text.en)))

  test("the same tasks whenever asked, a longer list beginning with a shorter one"):
    assertEquals(Drawn.items(Split.Train, 30).map(_.task.text), splits(Split.Train).take(30).map(_.task.text))

  test("from three positions to the collection's largest: no drawn statement is longer than the collection's"):
    val decisions = all.flatMap(i => i.task.stated.map(r => Budget.of(r.en.statement, r.en.text).decisions))
    println(s"decisions of a drawn task: median ${decisions.sorted.apply(decisions.size / 2)}, max ${decisions.max}")
    assert(decisions.max <= 150, decisions.max)
    assert(decisions.sorted.apply(decisions.size / 2) <= 60, decisions.sorted.apply(decisions.size / 2))

  test("no two things of a setting share a name, in either language"):
    for (name, s) <- Settings.all ++ SealedSet.settings do
      val items = s.features.flatMap(_.items.map(_.name))
      for names <- Vector(s.people.map(_.en) ++ items.map(_.en), s.people.map(_.ru) ++ items.map(_.ru)) do
        val twice = names.map(_.toLowerCase).groupBy(identity).filter(_._2.size > 1).keys
        assert(twice.isEmpty, s"$name: ${twice.mkString(", ")}")
