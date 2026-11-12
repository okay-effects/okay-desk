package okay.desk.statement

import okay.desk.tasks.{Drawn, Tasks}

/**
 * A STATEMENT FROM ITS OWN DECISIONS (specs/formalization.md §4), with no model anywhere: the tags of the tokens
 * and the answers a reader is asked for give back the statement they were taken from — for every task of the drawn
 * corpus in both languages, and for the part of the collection whose frames copy their pieces in the text's order.
 * The tokens here are words with the space before them and punctuation apart, as a model's tokenizer mostly cuts.
 */
class AssembleSuite extends munit.FunSuite:
  def tokens(text: String): Vector[Span] =
    "\\s*(?:[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}])".r.findAllMatchIn(text).map(m => Span(m.start, m.end)).toVector

  test("every drawn task is assembled from its decisions, in both languages"):
    val items = Drawn.Split.values.toVector.flatMap(Drawn.items(_, 60))
    for i <- items; read <- i.task.stated.toVector; s <- Vector(read.en, read.ru) do
      val got = Assemble.decisions(s.statement, s.text, tokens(s.text)).map(d =>
        Assemble.assemble(s.statement.problem, s.text, tokens(s.text), d.tags, d.choices))
      assertEquals(got, Right(Right(s.statement)), s"${i.family} ${i.seed}: ${s.text}")

  test("the collection: what is in this reader's reach, and why the rest is not"):
    val stated = for t <- Tasks.entries.map(_.task); read <- t.stated.toVector; s <- Vector(read.en, read.ru)
      yield t.kind -> Assemble.decisions(s.statement, s.text, tokens(s.text))
    val reach = stated.groupBy(_._1).view.mapValues(v => (v.count(_._2.isRight), v.size)).toVector.sortBy(_._1.ordinal)
    println(reach.map((k, v) => s"$k ${v._1}/${v._2}").mkString(", "))
    println(stated.collect { case (_, Left(why)) => why.takeWhile(_ != '«') }.groupBy(identity).view
      .mapValues(_.size).toVector.sortBy(-_._2).mkString("\n"))
    assert(stated.count(_._2.isRight) * 2 > stated.size)
