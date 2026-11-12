package okay.desk.ui

import okay.business.scala3.*
import okay.desk.Sample
import okay.desk.statement.{Faults, Problem, Reader, Span}
import okay.desk.tasks.{Gold, Kind, Tasks}
import okay.http.{Fetch, Response}

/**
 * THE COLLECTION OF LOGIC TASKS ON THE EXAMPLES SCREEN, played as a person would: it is not on the chat; its pages
 * are turned, a class is chosen, the language changed. A task shows no answer, only two buttons. "Answer": the task
 * alone, an answer typed and checked. "Solve": the task asked in the chat (its tables opened first), and what the
 * desk says checked against the exact answer — right, not right, or no answer.
 */
class TaskListSuite extends munit.FunSuite:
  type Row[X] = (Ui + Fetch + Disk + Model + Clipboard)[X]
  type NoModel[X] = (Ui + Fetch + Disk + Clipboard)[X]
  type NoDisk[X] = (Ui + Fetch + Clipboard)[X]
  type NoFetch[X] = (Ui + Clipboard)[X]
  def csv(t: okay.desk.Table): String = (t.columns +: t.rows.map(_.map(_.text))).map(_.mkString(",")).mkString("\n")
  val files = Map("sample/orders.csv" -> Sample.orders, "sample/customers.csv" -> Sample.customers,
    "sample/edges.csv" -> Sample.edges)
  var fetched = Vector.empty[String]
  def read(url: String): Response =
    fetched :+= url
    files.get(url).fold(Response(404))(t => Response(200, Map.empty, csv(t)))
  def texts(parts: List[Part]): List[String] = parts.flatMap {
    case Part.Text(t) => List(t)
    case Part.Notice(_, t) => List(t)
    case Part.Section(title, inner) => title :: texts(inner)
    case Part.Block(_, title, inner) => title.toList ++ texts(inner)
    case Part.Code(lines, _, title) => title.toList ++ lines
    case _ => Nil }
  def text(p: Played[Unit]): String = texts(p.at.get.parts).mkString("\n")
  def labels(p: Played[Unit]): List[String] = p.at.get.buttons.map(_.label)
  val first = Tasks.entries.head.task
  val data = Tasks.of(Some(Kind.Data)).map(_.task)
  def program(title: String): String = data.find(_.title.en == title).map(_.gold) match
    case Some(Gold.Table(p)) => p
    case _ => fail(s"no data task: $title")
  def kind(k: Kind): String = s"${k.title.en} (${Tasks.of(Some(k)).size})"
  val ask = "Ask anything: a question, a problem, or data to reason about"
  /** the statement reader of a test: none unless a test sets one */
  var reader: String => Option[Reader.Reading] = _ => None
  val stated = Tasks.entries.map(_.task).filter(_.stated.exists(_.en.statement.problem == Problem.Assign))
  /** a reader that reads every stated task as its gold statement, with this doubt */
  def golden(doubt: Span => Option[Reader.Doubt]): String => Option[Reader.Reading] = text =>
    stated.flatMap(_.stated).flatMap(r => Vector(r.en, r.ru)).find(_.text == text).map(s =>
      Reader.Reading(Some(s.statement), Faults.result(s.statement, s.text).toOption, Map.empty, (0, 0),
        doubt(s.statement.sentences.head.at)))

  /** the desk played with a model that writes these programs for these questions, and none for any other */
  def run(app: Unit ! Row, programs: Map[String, String], acts: Act*): Played[Unit] =
    fetched = Vector.empty
    val p = Model.answeredBy[Row, NoModel, Unit](_ => Right("test model"),
      (_, q) => programs.get(q).toRight("no program"), state = reader)(app)
    val kept = Disk.inMemory[NoModel, NoDisk, Unit](scala.collection.mutable.Map.empty[String, String])(p)
    val byTable = handle(kept, Handler.Tail[Fetch, NoFetch, Unit, Unit](a => pure(a), [X] => (op: Fetch[X]) => op match
      case Fetch.Call(_, url, _, _, answer) => answer(read(url))
      case Fetch.Send(base, r, answer) => answer(read(Fetch.target(base, r)))))
    Play(steps(copiedTo[NoFetch, Ui, Unit](scala.collection.mutable.Buffer.empty[String])(byTable)), acts*)
  def solved(programs: Map[String, String], acts: Act*): Played[Unit] = run(App.main[Row](Nil), programs, acts*)
  def play(acts: Act*): Played[Unit] = solved(Map.empty, acts*)

  test("the collection is on the examples screen, not on the chat: a task shows two buttons and no answer"):
    assert(!text(play()).contains("Logic tasks"))
    val p = play(Act.press("Examples"))
    assert(text(p).contains("Logic tasks"), text(p))
    assert(text(p).contains(s"Page 1 of 24 · tasks 1–10 of ${Tasks.entries.size}"), text(p))
    assert(text(p).contains(s"1. ${first.title.en}"))
    assertEquals(labels(p).count(_ == "Answer"), 10)
    assertEquals(labels(p).count(_ == "Solve"), 10)
    assert(!texts(p.at.get.parts).contains(first.answer.en), "the answer is not shown")
    assert(!text(p).contains("Check"))

  test("pages are turned, a class chosen, the language changed"):
    val second = play(Act.press("Examples"), Act.press("Next →"))
    assert(text(second).contains("Page 2 of 24 · tasks 11–20"), text(second))
    assert(labels(second).contains("← Previous"))
    val truth = play(Act.press("Examples"), Act.press("Next →"), Act.press(kind(Kind.Truth)))
    val pages = TaskList.pages(TaskList.View(kind = Some(Kind.Truth)))
    assert(text(truth).contains(s"Page 1 of $pages · tasks 1–10 of ${Tasks.of(Some(Kind.Truth)).size}"), text(truth))
    val russian = play(Act.press("Examples"), Act.press("Русский"))
    assert(text(russian).contains(s"1. ${first.title.ru}"), text(russian))
    assertEquals(labels(russian).count(_ == "Ответить"), 10)
    assertEquals(labels(russian).count(_ == "Решить"), 10)
    assert(labels(russian).contains("English"))

  test("Answer: the task alone and whole, what a person types is checked — a wrong try does not give the answer away"):
    val open = play(Act.press("Examples"), Act.press("Answer"))
    first.text.en.linesIterator.map(_.trim).filter(_.nonEmpty).foreach(line => assert(text(open).contains(line), line))
    assertEquals(labels(open), List("← Back to the tasks", "Check", "Solve"))
    assert(!text(open).contains("✓") && !text(open).contains("✗"))
    val wrong = play(Act.press("Examples"), Act.press("Answer"), Act.press("Check", "Your answer" -> "a walrus"))
    assert(text(wrong).contains("Not right ✗ — «a walrus». Try again, or let the desk solve it."), text(wrong))
    assert(!text(wrong).contains("the answer:") && !text(wrong).contains("✓"), "the answer is not given away")
    val right = play(Act.press("Examples"), Act.press("Answer"), Act.press("Check", "Your answer" -> "a walrus"),
      Act.press("Check", "Your answer" -> first.answer.en))
    assert(text(right).contains(s"Right ✓ — the answer: ${first.answer.en}"), text(right))
    assert(!text(right).contains("✗"))
    val back = play(Act.press("Examples"), Act.press("Next →"), Act.press("Answer"), Act.press("← Back to the tasks"))
    assert(text(back).contains("Page 2 of 24"), text(back))

  test("Answer in Russian: the task, the form and the check are Russian"):
    val p = play(Act.press("Examples"), Act.press("Русский"), Act.press("Ответить"),
      Act.press("Проверить", "Ваш ответ" -> first.answer.ru))
    assert(text(p).contains(s"Верно ✓ — ответ: ${first.answer.ru}"), text(p))
    assertEquals(labels(p), List("← К задачам", "Проверить", "Решить"))

  test("Solve: the task is asked in the chat; a desk that does not answer is told so, with the exact answer"):
    val task = Tasks.of(Some(Kind.Deduction)).head.task
    val asked = play(Act.press("Examples"), Act.press(kind(Kind.Deduction)), Act.press("Solve"))
    assert(text(asked).contains(task.text.en.linesIterator.next().trim), text(asked))
    assert(!text(asked).contains("Logic tasks") && !text(asked).contains("Check:"), text(asked))
    assert(labels(asked).contains("Stay offline"), labels(asked).toString)
    val done = play(Act.press("Examples"), Act.press(kind(Kind.Deduction)), Act.press("Solve"),
      Act.press("Stay offline"))
    assert(text(done).contains(s"Check: no answer ✗ — the exact answer (${task.gold.solver}): ${task.answer.en}"),
      text(done))

  test("Solve a task over data: its tables are opened, the desk's table is right by the exact answer"):
    val task = data.head
    val p = solved(Map(task.text.en -> program(task.title.en)), Act.press("Load the model"), Act.press("Examples"),
      Act.press(kind(Kind.Data)), Act.press("Solve"))
    assertEquals(fetched.toSet, files.keySet)
    assert(text(p).contains(s"Check: right ✓ — the exact answer (tables): ${task.answer.en}"), text(p))
    val russian = solved(Map(task.text.ru -> program(task.title.en)), Act.press("Load the model"),
      Act.press("Examples"), Act.press(kind(Kind.Data)), Act.press("Русский"), Act.press("Решить"))
    assert(text(russian).contains(s"Проверка: верно ✓ — точный ответ (таблицы): ${task.answer.ru}"), text(russian))

  test("Solve a task over data: another program's table is not right — the number among other rows does not pass"):
    val task = data.head
    assertEquals(task.expect, Vector("4"))
    for other <- List("Customers from UA: how many", "Orders per status", "Orders over 100") do
      val p = solved(Map(task.text.en -> program(other)), Act.press("Load the model"), Act.press("Examples"),
        Act.press(kind(Kind.Data)), Act.press("Solve"))
      assert(text(p).contains(s"Check: not right ✗ — the exact answer (tables): ${task.answer.en}"), other + text(p))

  test("every task over data, formalized as its gold statement, is solved right in the chat"):
    val programs = data.flatMap(t => Vector(t.text.en -> program(t.title.en), t.text.ru -> program(t.title.en))).toMap
    for (t, at) <- data.zipWithIndex; ru <- List(false, true) do
      val state = App.State(model = Some("test model"), place = "examples",
        tasks = TaskList.View(kind = Some(Kind.Data), russian = ru, open = Some(at)))
      val q = run(App.loop[Row](state), programs, Act.press(if ru then "Решить" else "Solve"))
      assert(text(q).contains(if ru then "Проверка: верно ✓" else "Check: right ✓"), t.title.en + "\n" + text(q))

  test("a cleared chat ends the task unchecked"):
    val p = play(Act.press("Examples"), Act.press(kind(Kind.Deduction)), Act.press("Solve"), Act.press("Clear chat"),
      Act.press("Ask", ask -> "2 + 2"))
    assert(text(p).contains("4"), text(p))
    assert(!text(p).contains("Check:"), text(p))

  test("Solve a stated task with a statement reader: the answer of its statement is right, the reading is shown"):
    reader = golden(_ => None)
    try
      var undecided = 0
      for t <- stated; ru <- List(false, true) do
        val at = Tasks.entries.indexWhere(_.task == t)
        val state = App.State(place = "examples", tasks = TaskList.View(russian = ru, open = Some(at)))
        val q = run(App.loop[Row](state), Map.empty, Act.press(if ru then "Решить" else "Solve"))
        assert(text(q).contains(if ru then "Прочитано так:" else "Read as:"), text(q))
        if Faults.undecided(t.expect) then undecided += 1
        assert(text(q).contains(if ru then "Проверка: верно ✓" else "Check: right ✓"), t.title.en + "\n" + text(q))
      println(s"stated tasks: ${stated.size}; with an answer that is none or several: ${undecided / 2}")
    finally reader = _ => None

  test("Solve a stated task the reader doubts: the doubt is said about its sentence, the check says no answer"):
    reader = golden(at => Some(Reader.Doubt.Unfamiliar(at, Vector("lives in"))))
    try
      val at = Tasks.entries.indexWhere(_.task == stated.head)
      val q = run(App.loop[Row](App.State(place = "examples", tasks = TaskList.View(open = Some(at)))), Map.empty,
        Act.press("Solve"))
      assert(text(q).contains("I was not taught this wording, so I do not answer: «"), text(q))
      assert(text(q).contains("Check: no answer ✗"), text(q))
    finally reader = _ => None

  test("the page opened by its logic link: the tasks the model reads, one press solves — the model loaded first"):
    reader = golden(_ => None)
    try
      val opened = run(App.main[Row](Nil, phone = true, logic = Some(true)), Map.empty)
      assert(!text(opened).contains("Example questions") && labels(opened).contains("Chat: ask anything"))
      assert(text(opened).contains("Логические задачи") && text(opened).contains("сама она ничего не решает"),
        text(opened))
      assert(text(opened).contains(s"задачи 1–10 из ${stated.size}"), text(opened))
      assert(labels(opened).contains(s"Читает модель (${stated.size})"), labels(opened).toString)
      val solved = run(App.main[Row](Nil, phone = true, logic = Some(true)), Map.empty, Act.press("Решить"))
      assert(text(solved).contains("Прочитано так:") && text(solved).contains("Проверка: верно ✓"), text(solved))
      val english = run(App.main[Row](Nil, logic = Some(false)), Map.empty, Act.press("Next →"))
      assert(text(english).contains(s"tasks 11–20 of ${stated.size}") && !text(english).contains("Example questions"),
        text(english))
      val all = run(App.main[Row](Nil, logic = Some(false)), Map.empty, Act.press(s"All (${Tasks.entries.size})"))
      assert(text(all).contains("Example questions") && text(all).contains("Page 1 of 24"), text(all))
    finally reader = _ => None

  test("the plain page: two buttons and the question; the model is chosen by itself and said once it is wanted"):
    reader = golden(_ => None)
    try
      val opened = run(App.main[Row](Nil, phone = true, chooser = false), Map.empty)
      assertEquals(labels(opened), List("Data: open files", "Examples", "Ask"))
      assert(!text(opened).contains("Model:") && !text(opened).contains("not loaded yet"), text(opened))
      assert(!text(opened).contains("THE MODEL") && !text(opened).contains("online sources off"), text(opened))
      val at = Tasks.entries.indexWhere(_.task == stated.head)
      val solved = run(App.loop[Row](App.State(chooser = false, place = "examples",
        tasks = TaskList.View(open = Some(at)))), Map.empty, Act.press("Solve"))
      assert(text(solved).contains("Model: Qwen3 0.6B student") && text(solved).contains("test model") &&
        text(solved).contains("Check: right ✓"), text(solved))
      assert(!labels(solved).contains("Load the model") && !labels(solved).contains("Unload model"), labels(solved))
    finally reader = _ => None

  test("a task typed in the chat goes to the statement reader: the answer and its reading"):
    reader = golden(_ => None)
    try
      val t = stated.find(t => !Faults.undecided(t.expect)).get
      val p = run(App.main[Row](Nil, chooser = false), Map.empty, Act.press("Ask", ask -> t.text.en))
      assert(text(p).contains("Read as:") && text(p).contains(t.answer.en.stripPrefix("the ")), text(p))
    finally reader = _ => None
