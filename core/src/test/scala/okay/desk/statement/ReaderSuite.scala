package okay.desk.statement

import okay.desk.tasks.Tasks

/** the statement reader's file and its words, with no model: what is written is read back, a gold statement is
 * said sentence by sentence, and a doubt is said as no answer */
class ReaderSuite extends munit.FunSuite:
  test("a reader written to a file is read back the same"):
    val heads = Reader.Heads(8)
    heads.arrays.zipWithIndex.foreach((a, i) => a.indices.foreach(j => a(j) = i + j / 10f))
    val known = Reader.Known()
    known.words ++= Set("lives", "живёт")
    known.pairs += "lives in"
    val t = Reader.Trained(Array.fill(8)(0.5f), Array.fill(8)(2f), heads, known,
      Map("en tag" -> 1.5f, "ru frame" -> 2f), echo = 2)
    val back = Reader.parse(Reader.write(t)).fold(fail(_), identity)
    assertEquals(back.heads.arrays.map(_.toVector), heads.arrays.map(_.toVector))
    assertEquals((back.mean.toVector, back.std.toVector), (t.mean.toVector, t.std.toVector))
    assertEquals((back.known.words.toSet, back.known.pairs.toSet, back.bars), (Set("lives", "живёт"), Set("lives in"),
      t.bars))
    assertEquals(back.echo, 2)
    assert(Reader.parse(Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)).isLeft)

  test("a statement is said sentence by sentence in the text's words, an answer in its names, a doubt as none"):
    val stated = Tasks.entries.flatMap(_.task.stated).filter(_.en.statement.problem == Problem.Assign)
    assert(stated.size > 80, stated.size)
    for r <- stated; (s, ru) <- Vector(r.en -> false, r.ru -> true) do
      val lines = Said.lines(s.text, s.statement, ru)
      assertEquals(lines.size, s.statement.sentences.count(_.frames.nonEmpty))
      assert(lines.forall(l => !l.drop(l.indexOf("→")).contains("?")), lines.mkString("\n"))
      val result = Faults.result(s.statement, s.text).fold(fail(_), identity)
      val shown = Reader.Reading(Some(s.statement), Some(result), Map.empty, (0, 0), None)
      val (said, read) = Said.words(s.text, shown, ru)
      assert(read.nonEmpty && said.nonEmpty && !Said.noAnswer.exists(said.contains), said)
      val asked = shown.copy(doubt = Some(Reader.Doubt.Unsure(s.statement.sentences.head.at)))
      assert(!asked.shown && Said.noAnswer.exists(Said.words(s.text, asked, ru)._1.contains))
    val one = stated.head.en
    println(Said.words(one.text, Reader.Reading(Some(one.statement), Faults.result(one.statement, one.text).toOption,
      Map.empty, (0, 0), None), false))
    val two = stated.last.ru
    println(Said.words(two.text, Reader.Reading(Some(two.statement), Faults.result(two.statement, two.text).toOption,
      Map.empty, (0, 0), None), true))
