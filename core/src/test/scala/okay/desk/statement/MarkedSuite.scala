package okay.desk.statement

import okay.desk.tasks.{Drawn, Tasks}

/** a stated text taken apart into marked sentences and written again is the same text and statement */
class MarkedSuite extends munit.FunSuite:
  test("every stated task of the corpus and the collection, in both languages, is its marked sentences"):
    val drawn = Drawn.Split.values.toVector.flatMap(Drawn.items(_, 60)).map(_.task)
    val all = (drawn ++ Tasks.entries.map(_.task)).flatMap(_.stated).filter(_.en.statement.problem == Problem.Assign)
    assert(all.size > 300, all.size)
    for r <- all; s <- Vector(r.en, r.ru) do
      val again = Marked.stated(s.statement.problem, Marked.of(s))
      assertEquals(again.text, s.text)
      assertEquals(again.statement, s.statement, s.text)
    val one = Marked.of(Tasks.entries.flatMap(_.task.stated).head.en)
    one.take(4).foreach(x => println(s"${x.words.trim} :: ${x.frames}"))
    println(Marked.keys(one(2).words))
