package okay.desk.tasks

/**
 * THE COLLECTION OF LOGIC TASKS, CHECKED: every task's gold statement, run by its exact solver, gives the result the
 * task states — and the classics give the answers a person knows them by.
 */
class TasksSuite extends munit.FunSuite:
  lazy val tasks: Vector[Task] = Tasks.entries.map(_.task)
  def named(title: String): Task = tasks.find(_.title.en == title).getOrElse(fail(s"no task '$title'"))

  test("every task's statement gives its stated result on the exact solver"):
    val failed = tasks.flatMap(_.check.left.toOption)
    assert(failed.isEmpty, failed.mkString("\n"))

  test("the classics have the answers they are known by"):
    val known = List(
      "The zebra puzzle" -> "Japanese",
      "The zebra puzzle: who drinks water?" -> "Norwegian",
      "The zebra puzzle without its last clue" -> "several",
      "The zebra puzzle with a clue too many" -> "none",
      "Einstein's riddle: who keeps the fish?" -> "German",
      "The wolf, the goat and the cabbage" -> "7",
      "Jugs of 3 and 5 litres" -> "7",
      "The tower of Hanoi: 3 disks" -> "7",
      "Three guards and three robbers" -> "11",
      "Three glasses, two at a time" -> "unreachable",
      "SEND + MORE = MONEY" -> "10652",
      "8 queens" -> "15863724")
    known.foreach((title, result) => assertEquals(named(title).gold.run(named(title).text.en), Right(Vector(result))))

  test("a collection, not a handful: many tasks in every class, each made once"):
    assert(tasks.size >= 200, tasks.size)
    Kind.values.foreach(k => assert(tasks.count(_.kind == k) >= 10, s"$k: ${tasks.count(_.kind == k)}"))
    assertEquals(Tasks.kinds.map(_._2).sum, tasks.size)
    assertEquals(tasks.map(_.title.en).distinct.size, tasks.size)
    assertEquals(tasks.map(_.title.ru).distinct.size, tasks.size)
    assertEquals(tasks.map(_.text.en).distinct.size, tasks.size)
    assert(tasks.count(_.kind == Kind.Einstein) >= 30)
    assertEquals(Tasks.entries.map(_.kind), Tasks.entries.map(_.kind).sortBy(_.ordinal))

  test("every task is written in both languages, with an answer and a result"):
    tasks.foreach { t =>
      val words = List(t.title, t.text, t.answer)
      assert(words.forall(w => w.en.trim.nonEmpty && w.ru.trim.nonEmpty), t.title.en)
      assert(t.text.ru.exists(c => c >= 'а' && c <= 'я'), s"${t.title.en}: the Russian text")
      assert(t.expect.nonEmpty, t.title.en) }

  test("a task over data names the tables to open; the others need none"):
    tasks.foreach(t => assertEquals(t.opens.nonEmpty, t.kind == Kind.Data, t.title.en))
