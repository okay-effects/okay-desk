package okay.desk

import okay.desk.calc.*
import okay.desk.learn.{Book, Paradigm}
import okay.desk.organ.{Organ, OrganFile, Template}

/** LEARNING ON PURPOSE (BACKLOG D): a book's dictionary read into a language the data uses at once; its examples
 * into pairs and questions; the organ of the pairs; the paradigm block equals the table; an organ survives its file */
class LearnSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = new Grammar(s)
  val book = Book.parse("""
    # the sample
    table orders: purchases, deals; ru: покупки, сделки
    column amount: total, price; ru: цена, стоимость
    column status: state; ru: состояние
    value unpaid: ru: не оплачено
    example: How many purchases are unpaid?
    example: Сколько покупок не оплачено?
    example: Total price per customer, largest first.
    example: Show the first 3
  """)

  test("the dictionary read: the data alone closes the examples that write its words, leaves the rest as questions, induces an organ"):
    val lesson = Book.read(s, book)
    println(s"  the book: ${lesson.text}")
    assert(lesson.pairs.exists(_._1 == "How many purchases are unpaid?"), lesson.pairs.map(_._1).toString)
    assert(lesson.pairs.exists(_._1 == "Сколько покупок не оплачено?"), lesson.pairs.map(_._1).toString)
    assert(lesson.pairs.exists(_._1.startsWith("Total price")), lesson.pairs.map(_._1).toString)
    assert(lesson.questions.exists(_._1 == "Show the first 3"), lesson.questions.toString)
    // without the dictionary "purchases" names nothing: the same example stays open — the book made the difference
    val without = Book.read(s, book.copy(tables = Map.empty, columns = Map.empty, values = Map.empty))
    assert(!without.pairs.exists(_._1 == "How many purchases are unpaid?"), without.pairs.map(_._1).toString)
    // the pairs' terms are the gold: "How many purchases are unpaid?" is the sample's first question
    val gold = (new CalculusSuite).gold.head
    assertEquals(lesson.pairs.find(_._1 == "How many purchases are unpaid?").map(p => Den.program(s, p._2)), Some(Right(gold)))
    // the organ speaks on its pairs, by its weights, and every part is the gold's
    for (q, t) <- lesson.pairs do
      val ws = Organ.words(q, if q.exists(c => c >= 'а' && c <= 'я') then Language.Ru.language else Language.En.language)
      val said = lesson.organ.forward(ws)
      assertEquals(said, lesson.organ.interpret(ws), q)
      for (k, v) <- said.map(_.parts).getOrElse(Map.empty) do assertEquals(Some(v), okay.desk.organ.Organs.templateOf(t).parts.get(k), s"$q at $k")

  test("the paradigm: the block equals the table on every name and case, in Russian; Ukrainian and Polish from their files"):
    def lang(code: String): Language =
      val file = Vector(s"web/page/lang/$code.json", s"../web/page/lang/$code.json", s"../../web/page/lang/$code.json")
        .map(java.nio.file.Path.of(_)).find(java.nio.file.Files.exists(_)).getOrElse(fail(s"no lang/$code.json"))
      Language.parse(java.nio.file.Files.readString(file)).fold(e => fail(e), identity)
    for l <- Vector(Language.Ru.language, lang("uk"), lang("pl")) do
      val p = Paradigm(l)
      for (n, c, f) <- p.entries do
        assertEquals(p.forward(n, c), Some(f), s"${l.code} $n $c")
        assertEquals(p.interpret(n, c), Some(f), s"${l.code} $n $c")
      assertEquals(p.forward("nothing", "gen"), None)
      println(s"  the paradigm of ${l.code}: ${p.entries.size} forms, ${p.weights.params} parameters, the block equals the table")

  test("an organ written to a file and read back is the same organ: the same answers on random words"):
    val gold = (new CalculusSuite).gold.map(p => Den.term(s, p))
    val pairs = Sample.questions.zip(gold).map((q, t) => (Organ.words(q.en, Language.En.language), okay.desk.organ.Organs.templateOf(t)))
    val organ = Organ.induce("sample-en", pairs, minCover = 1, prune = false)
    val back = OrganFile.parse(OrganFile.json(organ)).fold(e => fail(e), identity)
    assertEquals(back.cues.toSet, organ.cues.toSet)
    val rnd = scala.util.Random(2)
    for _ <- 1 to 60 do
      val ws = Vector.fill(1 + rnd.nextInt(8))((organ.vocabulary :+ "x")(rnd.nextInt(organ.vocabulary.size + 1)))
      assertEquals(back.forward(ws), organ.forward(ws), ws.mkString(" "))

  test("the person's lessons: a choice at a hole decides the same wording next time before the data; forgotten on request"):
    import okay.desk.learn.Lessons
    val q = "Show the first 3 orders"
    val ctx = Context(s, q, Term.Hole(Ty.Program))
    // the reading stops at the order column; the person chooses amount
    val stopped = okay.scala3.run(Dialogue.read[okay.scala3.Pure](g, ctx, Fill.exact[Strategy.Rest[okay.scala3.Pure]])) match
      case Dialogue.Outcome.Open(ty, at, cs, _) => assert(ty.isInstanceOf[Ty.OrderT], s"stopped at $ty with $cs"); assert(cs.contains("amount"), s"$ty: $cs"); at
      case other => fail(other.toString)
    val lessons = Vector(Lessons.of(q, Language.En.language, stopped, "amount"))
    assertEquals(Lessons.decides(lessons, q, Language.En.language), Some(Template(Map("8" -> "amount"))))
    assertEquals(Lessons.decides(lessons, "Show the first 3 orders now", Language.En.language), Some(Template(Map("8" -> "amount"))))
    assertEquals(Lessons.decides(lessons, "Show orders", Language.En.language), None)
    // as the first source: the same question now closes, ordered by amount
    type P[X] = Strategy.Rest[okay.scala3.Pure][X]
    val fill = Fill.first[P](okay.desk.organ.OrganSource.source[P](Vector(Lessons.organ(lessons))), Fill.exact[P])
    // the lesson decided the order column; the direction is the NEXT question — the dialogue goes on from there
    okay.scala3.run(Dialogue.read[okay.scala3.Pure](g, ctx, fill)) match
      case Dialogue.Outcome.Open(ty, at, _, _) => assertEquals(ty, Ty.DirT); assert(Encode.word(at).contains("amount"), Encode.word(at).toString)
      case other => fail(other.toString)
    val back = Lessons.parse(Lessons.json(lessons))
    assertEquals(back, lessons)
    assertEquals(Lessons.forget(lessons, q, Language.En.language), Vector.empty)
    assertEquals(Dialogue.Act.of("не запоминай", Vector(Language.Ru.language)), Dialogue.Act.Forget)
    assertEquals(Dialogue.Act.of("forget that", Vector(Language.En.language)), Dialogue.Act.Forget)
