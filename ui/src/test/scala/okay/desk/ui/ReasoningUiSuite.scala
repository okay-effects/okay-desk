package okay.desk.ui

import okay.business.scala3.*
import okay.desk.know.Knowledge
import okay.desk.know.Arithmetic
import okay.desk.know.{Deduction, FactReader}
import Knowledge.{Quote, Step}
import okay.desk.puzzle.{PuzzleText, World}
import okay.http.{Fetch, Response}

class ReasoningUiSuite extends munit.FunSuite:
  type Row[X] = (Ui + Fetch + Disk + Model + Clipboard)[X]
  type NoModel[X] = (Ui + Fetch + Disk + Clipboard)[X]
  type NoDisk[X] = (Ui + Fetch + Clipboard)[X]
  type NoFetch[X] = (Ui + Clipboard)[X]
  val copied = scala.collection.mutable.Buffer.empty[String]
  val disk = scala.collection.mutable.Map.empty[String, String]
  var requests = Vector.empty[String]
  var status = 200
  var synthesis = "off"
  var drafts = 0
  val ask = "Ask anything: a question, a problem, or data to reason about"
  val passage = "Rain falls from clouds."
  val wiki = """{"curtimestamp":"2026-11-08T16:00:00Z","query":{"pages":[
    |{"pageid":42,"lastrevid":7,"title":"Rain","extract":"Rain falls from clouds."}]}}
    |""".stripMargin

  var dataWords = okay.desk.skill.Words.none
  var dataPrograms = Map.empty[String, String]
  var dataQuestions = Vector.empty[String]
  var readFacts: Vector[Quote] => Either[String, Vector[FactReader.Reading]] = _ => Right(Vector.empty)
  var phone = false
  var loaded = Vector.empty[String]
  def play(acts: Act*): Played[Unit] =
    val model = Model.answeredBy[Row, NoModel, Unit](
      name => { loaded :+= name; Right("test model") },
      (_, q) => { dataQuestions :+= q; dataPrograms.get(q).toRight("unused") },
      (q, sources, _) =>
        if Arithmetic.expression(q).nonEmpty then Right(Knowledge.baseline(q, sources))
        else if Knowledge.isConstraints(q) then Right(Step.Constraints(q))
        else if Deduction.plan(q, sources).exists(_.evidence.nonEmpty) then Right(Knowledge.baseline(q, sources))
        else if sources.isEmpty then Right(Step.Search(Vector("rain")))
        else if synthesis != "off" then
          drafts += 1
          Knowledge.parse("""{"synthesize":[{"text":"Clouds release rain.","kind":"fact",
            |"evidence":[{"source":"en:42:7","quote":"Rain falls from clouds."}]}]}""".stripMargin)
        else Right(Step.Answer(Vector(Quote(sources.head.id, passage)))),
      quotes => readFacts(quotes),
      words = dataWords
    )(App.main[Row](Nil, phone))
    val kept = Disk.inMemory[NoModel, NoDisk, Unit](disk)(model)
    def fetched(url: String): Response =
      requests :+= url
      if status != 200 then Response(status)
      else if url.startsWith("https://query.wikidata.org/") then
        val (id, value, label) =
          if url.contains("France") then ("Q142", "Q90", "Paris")
          else ("Q183", "Q64", "Berlin")
        Response(
          200,
          Map.empty,
          s"""{"results":{"bindings":[{
          |"entity":{"value":"http://www.wikidata.org/entity/$id"},
          |"value":{"value":"http://www.wikidata.org/entity/$value"},
          |"label":{"value":"$label"},"retrieved":{"value":"2026-11-08T15:00:00Z"}}]}}
          |""".stripMargin
        )
      else if synthesis == "deduce" then
        Response(
          200,
          Map.empty,
          """{"curtimestamp":"2026-11-08T16:00:00Z","query":{"pages":[
          |{"pageid":50,"lastrevid":9,"title":"Taxonomy",
          |"extract":"Moby is a whale. Every whale is a mammal. Every mammal is an animal."}]}}""".stripMargin
        )
      else Response(200, Map.empty, wiki)
    val fetchedProgram = handle(
      kept,
      Handler.Tail[Fetch, NoFetch, Unit, Unit](
        a => pure(a),
        [X] =>
          (op: Fetch[X]) =>
            op match
              case Fetch.Call(_, url, _, _, answer) => answer(fetched(url))
              case Fetch.Send(base, r, answer)      => answer(fetched(Fetch.target(base, r)))
      )
    )
    Play(steps(copiedTo[NoFetch, Ui, Unit](copied)(fetchedProgram)), acts*)

  override def beforeEach(context: BeforeEach): Unit =
    disk.clear()
    copied.clear()
    dataWords = okay.desk.skill.Words.none
    dataPrograms = Map.empty
    dataQuestions = Vector.empty
    readFacts = _ => Right(Vector.empty)
    requests = Vector.empty
    status = 200
    synthesis = "off"
    drafts = 0
    phone = false
    loaded = Vector.empty

  def shown(p: Played[Unit]): String = p.at.get.parts.mkString(" ")

  test("one chat solves constraints and answers a general question using collected and checked evidence"):
    val p = play(
      Act.press("Ask", ask -> "2 + 2"),
      Act.press("Ask", ask -> PuzzleText.worldExample),
      Act.press("Use online sources"),
      Act.press("Ask", ask -> "Why does rain fall?"),
      Act.press("Ask", ask -> "Why does rain fall?")
    )
    assert(p.problems.isEmpty, p.problems.toString)
    val text = shown(p)
    assert(text.contains("2 + 2 = 4"), text)
    assert(text.contains("France — dog."), text)
    assert(text.contains(passage) && text.contains("curid=42&oldid=7"), text)
    // the permission's question, answered, is not left in the chat
    assert(!text.contains("needs external evidence"), text)
    assertEquals(requests.count(_.startsWith("https://query.wikidata.org/")), 2)
    assertEquals(requests.count(_.contains("wikipedia.org")), 1)
    assert(!p.at.get.buttons.exists(_.label == "Puzzles"))

  test("online research requires one permission in the chat and subsequent questions reuse it"):
    val pending = play(Act.press("Ask", ask -> "Why does rain fall?"))
    assert(pending.at.get.buttons.exists(_.label == "Use online sources"))
    assertEquals(requests, Vector.empty)
    val offline = play(Act.press("Ask", ask -> "Why does rain fall?"), Act.press("Stay offline"))
    assert(shown(offline).contains("available local evidence does not settle"))
    assert(!shown(offline).contains("needs external evidence"), shown(offline))
    assertEquals(requests, Vector.empty)

  test("an offline visit can use cached general evidence and structured world facts"):
    disk(Reasoning.file("Why does rain fall?")) = Knowledge.cache(
      "Why does rain fall?",
      Vector(
        Knowledge.Source(
          "en:42:7",
          "Rain",
          "https://en.wikipedia.org/w/index.php?curid=42&oldid=7",
          passage,
          "2026-11-08"
        )
      )
    )
    disk(Puzzles.factsFile) = World.json(World.example)
    val p = play(Act.press("Ask", ask -> "Why does rain fall?"), Act.press("Ask", ask -> PuzzleText.worldExample))
    assert(shown(p).contains(passage) && shown(p).contains("France — dog."))
    assertEquals(requests, Vector.empty)

  test("network failure leaves a question unsettled without inventing facts"):
    status = 503
    val p = play(Act.press("Ask", ask -> PuzzleText.worldExample), Act.press("Use online sources"))
    assert(shown(p).contains("HTTP 503"), shown(p))
    assert(!shown(p).contains("France — dog."))
    assert(!disk.contains(Puzzles.factsFile))

  test("opened tables do not capture unrelated questions or arithmetic"):
    disk("orders.csv") = "id,amount\n1,2\n"
    val p = play(
      Act.press("Ask", ask -> "Why does rain fall?"),
      Act.press("Use online sources"),
      Act.press("Ask", ask -> "2 + 2")
    )
    assert(shown(p).contains(passage) && shown(p).contains("2 + 2 = 4"), shown(p))

  test("the phone researches premises and rules and renders only the compiled conclusion with its proof"):
    phone = true
    synthesis = "deduce"
    val p = play(Act.press("Ask", ask -> "Is Moby an animal?"), Act.press("Use online sources"))
    val text = shown(p)
    assert(text.contains("Proved: moby is an animal.") && text.contains("Exact inlay Horn inference"), text)
    assert(text.contains("Every whale is a mammal.") && text.contains("Every mammal is an animal."), text)
    assert(!text.contains("model-reviewed support"), text)
    assert(text.contains("curid=50&oldid=9"), text)
    assertEquals(loaded, Vector.empty)

  test("free prose never bypasses the inference machine, even with real quotations"):
    for mode <- Vector("accept", "numeric", "draft", "repair") do
      disk.clear()
      synthesis = mode
      val p = play(Act.press("Ask", ask -> "Why does rain fall?"), Act.press("Use online sources"))
      assert(shown(p).contains("insufficient to settle"), shown(p))
      assert(!shown(p).contains("Clouds release rain."), shown(p))
      assert(!shown(p).contains("10 clouds") && !shown(p).contains("The Sun produces rain."), shown(p))

  test("a missing local implication requests research; unknown sourced results retain their read premises"):
    synthesis = "deduce"
    val p = play(Act.press("Ask", ask -> "Moby is a whale. Is Moby an animal?"), Act.press("Use online sources"))
    assert(shown(p).contains("Proved: moby is an animal."), shown(p))
    assertEquals(requests.count(_.contains("wikipedia.org")), 1)
    disk.clear()
    readFacts = _ => Right(Vector.empty)
    val unknown = play(Act.press("Ask", ask -> "Is Moby a fish?"), Act.press("Use online sources"))
    assert(shown(unknown).contains("Unknown: the premises do not establish"), shown(unknown))
    assert(shown(unknown).contains("Read premise/rule") && shown(unknown).contains("curid=50&oldid=9"), shown(unknown))

  test("the same input handles local rules, explicit negative facts and exact arithmetic without online sources"):
    val p = play(
      Act.press(
        "Ask",
        ask ->
          "Moby is a whale. Every whale is a mammal. Every mammal is an animal. Is Moby an animal?"
      ),
      Act.press("Ask", ask -> "Moby is not a fish. Is Moby a fish?"),
      Act.press("Ask", ask -> "2 + 2")
    )
    val text = shown(p)
    assert(text.contains("Proved: moby is an animal.") && text.contains("Refuted: moby is a fish."), text)
    assert(text.contains("2 + 2 = 4"), text)
    assertEquals(requests, Vector.empty)

  test("example buttons use the common chat; independent calculations produce separately checked results"):
    val infer = play(Act.press("Examples"), Act.press("Try an inference"))
    assert(shown(infer).contains("Proved: moby is an animal."), shown(infer))
    disk.clear()
    readFacts = _ => Right(Vector.empty)
    val grid = play(Act.press("Examples"), Act.press("Try a grid problem"))
    assert(shown(grid).contains("Alice — dog."), shown(grid))
    disk.clear()
    readFacts = _ => Right(Vector.empty)
    val numbers = play(Act.press("Examples"), Act.press("Two calculations"))
    assert(shown(numbers).contains("2 + 2 = 4") && shown(numbers).contains("3 * 7 = 21"), shown(numbers))

  test("new source language loads the student and feeds model readings into the common inference tool"):
    val sentence = "Zeno belongs to the class whale."
    var asked = Vector.empty[Quote]
    readFacts = quotes =>
      asked ++= quotes
      Right(quotes.filter(_.text == sentence).map(q =>
        FactReader.Reading(q.source, q.text, "fact", FactReader.Span(0, 4), FactReader.Span(26, 31))))
    val p = play(Act.press("Ask", ask ->
      (sentence + " Every whale is an animal. Is Zeno an animal?")))
    assert(asked.exists(q => q.source == "input:question" && q.text == sentence))
    assertEquals(loaded, Vector(Model.default))
    assert(shown(p).contains("Proved: zeno is an animal."), shown(p))
    assert(shown(p).contains("Model read:"), shown(p))
    assert(requests.isEmpty)

  test("examples have their own screen and returning preserves the active conversation"):
    val empty = play()
    val labels = empty.at.get.buttons.map(_.label)
    assert(labels.contains("Examples"))
    assert(!labels.contains("Try an inference") && !labels.contains("Copy chat") && !labels.contains("Clear chat"))
    val examples = play(Act.press("Examples"))
    assertEquals(examples.at.get.title, "okay-desk — examples")
    assert(examples.at.get.buttons.exists(_.label == "Try an inference"))
    val back = play(Act.press("Ask", ask -> "2 + 2"), Act.press("Examples"), Act.press("← Back to the chat"))
    assert(shown(back).contains("2 + 2 = 4"), shown(back))

  test("copy exports the conversation and proof; clearing resets pending research without revoking permission"):
    val p = play(Act.press("Examples"), Act.press("Try an inference"), Act.press("Copy chat"),
      Act.press("Ask", ask -> "Why does rain fall?"), Act.press("Use online sources"), Act.press("Clear chat"))
    assertEquals(copied.size, 1)
    assert(copied.head.contains(App.inferenceExample) && copied.head.contains("Proved: moby is an animal."))
    assert(copied.head.contains("Every whale is a mammal.") &&
      copied.head.contains("conditional on the cited readings"))
    assert(!shown(p).contains("Proved:") && !shown(p).contains("Why does rain fall?"), shown(p))
    assert(shown(p).contains("online sources enabled"), shown(p))
    assert(!p.at.get.buttons.exists(b => b.label == "Copy chat" || b.label == "Clear chat" ||
      b.label == "Use online sources"))
    val pending = play(Act.press("Ask", ask -> "Why do clouds form?"), Act.press("Clear chat"))
    assert(!pending.at.get.buttons.exists(_.label == "Use online sources"))

  test("a localized GDP question reaches data readers before the unloaded model and keeps the computed part"):
    phone = true
    val question = "Где ВВП растет быстрее всего и почему?"
    disk("gdp.csv") = "Country Name,Year,Value\nAlpha,2020,100\nAlpha,2021,150\nBeta,2020,100\nBeta,2021,110\n"
    dataWords = okay.desk.skill.Words(tables = Map("gdp" -> "ввп"))
    dataPrograms = Map(question ->
      """{"from":"gdp","group":["Country Name"],"aggregate":[
        |{"fn":"growth","column":"Value","by":"Year","as":"growth"}],"order":["-growth"]}""".stripMargin)
    val p = play(Act.press("Ask", ask -> "Why does rain fall?"), Act.press("Ask", ask -> question))
    assertEquals(dataQuestions, Vector(question))
    assertEquals(loaded, Vector(Model.default))
    assert(requests.isEmpty, requests.toString)
    assert(!p.at.get.buttons.exists(_.label == "Use online sources"))
    assert(shown(p).contains("Alpha") && shown(p).contains("0.5"), shown(p))
    assert(shown(p).contains("не устанавливает экономические причины"), shown(p))
    assert(shown(p).contains("это не годовой темп"), shown(p))

  test("a header-free house grid answers both questions in the chat without loading weights or fetching facts"):
    phone = true
    val clues = PuzzleText.classicRu.linesIterator.filter(l => l.nonEmpty && !l.startsWith("#") &&
      !l.contains(":") && !l.trim.startsWith("Кто")).mkString("\n")
    val text = "На улице стоят пять домов.\n" + clues + "\nКто пьёт воду? Кто держит зебру?\n" +
      "Каждый из пяти домов окрашен в свой цвет, а их жители — разных национальностей, " +
      "владеют разными животными, пьют разные напитки и курят разные марки сигарет."
    val p = play(Act.press("Ask", ask -> text))
    assert(shown(p).contains("Норвежец — вода.") && shown(p).contains("Японец — зебра."), shown(p))
    assert(shown(p).contains("Read categories"), shown(p))
    assert(requests.isEmpty && loaded.isEmpty)
    assert(!p.at.get.buttons.exists(_.label == "Use online sources"))
