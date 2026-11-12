package okay.desk.ui

import okay.business.scala3.*
import okay.desk.know.Knowledge
import okay.http.Fetch

/** The UI transports the original task to a language-model effect, then executes only its formal output. */
class AuthorUiSuite extends munit.FunSuite:
  type Row[X] = (Ui + Fetch + Disk + Model + Clipboard)[X]
  type NoModel[X] = (Ui + Fetch + Disk + Clipboard)[X]
  type NoDisk[X] = (Ui + Fetch + Clipboard)[X]
  type NoFetch[X] = (Ui + Clipboard)[X]

  def run(question: String, proposed: Vector[String] => Knowledge.Step, rounds: Int = 1,
      reply: Option[String] = None, asExample: Boolean = false, failure: Option[String] = None): App.State =
    var received = Vector.empty[String]
    var loads = 0
    val initial = App.State(phone = true, modelName = Model.interpreter)
    val program = Model.answeredBy[Row, NoModel, App.State](
      _ => { loads += 1; Right("native language model on WebGL2") },
      (_, _) => fail("legacy data reader must not interpret a task"),
      (text, _, observations) =>
        received :+= text
        failure.fold[Either[String, Knowledge.Step]](Right(proposed(observations)))(Left(_))
    )(App.step[Row](initial, App.Msg.Ask(question)).flatMap { state =>
      reply.fold(pure[App.State, Row](state))(text =>
        App.step[Row](state, if asExample then App.Msg.RunExample(text) else App.Msg.Ask(text)))
    })
    val disk = Disk.inMemory[NoModel, NoDisk, App.State](scala.collection.mutable.Map.empty)(program)
    val noFetch = handle(disk, Handler.Tail[Fetch, NoFetch, App.State, App.State](a => pure(a),
      [X] => (_: Fetch[X]) => fail("this formal program needs no network")))
    val done = Play(steps(copiedTo[NoFetch, Ui, App.State](scala.collection.mutable.Buffer.empty)(noFetch)))
    assertEquals(received, if asExample then Vector(question, reply.get) else Vector.fill(rounds)(question))
    assertEquals(loads, 1)
    done.result.get

  test("an unfamiliar theme reaches the model intact and uses its model instead of clue parsing"):
    val question = "An archive has 6 shelves with 8 scrolls each. It loans 5 scrolls. How many scrolls remain?"
    val model = """{"vars":{},"where":[],"ask":{"scrolls":["-",["*",6,8],5]}}"""
    val result = run(question, _ => Knowledge.Step.Formal(model))
    assert(result.log.exists(_.program.contains(model)))
    assert(result.log.exists(_.text == "scrolls: 43"))
    assert(!result.log.exists(_.text.contains("Read categories")))

  test("ambiguity is a model-authored clarification, not an invented formal premise"):
    val result = run("How long will the journey take?", _ => Knowledge.Step.Clarify("What distance and speed apply?"))
    assertEquals(result.log.last.text, "What distance and speed apply?")
    assert(!result.log.exists(_.program.nonEmpty))

  test("a model-authored transition definition reaches the exact planning handler unchanged"):
    val definition = """{"vars":{"energy":[0,1,2,3]},"start":[0],"goal":[3],"safe":[],
      |"moves":[{"set":{"energy":["+","energy",1]}}]}""".stripMargin
    val result = run("A battery has four levels and can advance one level per step. Reach level three.",
      _ => Knowledge.Step.Plan(definition))
    assert(result.log.exists(_.program.contains(definition)))
    assert(result.log.exists(_.text.contains("Shortest plan: 3 steps")))
    assert(result.log.exists(_.program.exists(_.contains("reachable"))))

  test("dialogue keywords also reach the language model without a lexical act gate"):
    val result = run("Спасибо", _ => Knowledge.Step.Clarify("Do you have another question?"))
    assertEquals(result.log.last.text, "Do you have another question?")

  test("a clarification reply resumes the original task instead of becoming a new isolated question"):
    val result = run("How long will the journey take?", observations =>
      if observations.isEmpty then Knowledge.Step.Clarify("What distance and speed apply?")
      else
        assertEquals(observations.last, "User clarification: 120 km, 40 km/h")
        Knowledge.Step.Formal("""{"vars":{},"where":[],"ask":{"hours":["/",120,40]}}"""),
      rounds = 2, reply = Some("120 km, 40 km/h"))
    assertEquals(result.log.last.text, "hours: 3")
    assertEquals(result.reasoning.question, "How long will the journey take?")
    assertEquals(result.reasoning.clarification, None)

  test("an explicitly selected example starts a new task while a clarification is pending"):
    val result = run("How long will the journey take?", _ => Knowledge.Step.Clarify("Which premises apply?"),
      rounds = 2, reply = Some("A new unrelated example."), asExample = true)
    assertEquals(result.reasoning.question, "A new unrelated example.")

  test("the model chooses exact CPU operations, receives their values and selects the final receipt"):
    val result = run("An archive has 6 shelves of 8 scrolls. It loans 5. How many remain?", observations =>
      observations.size match
        case 0 => Knowledge.Step.Eval("""["*",6,8]""")
        case 1 =>
          assertEquals(observations.last, "Tool result r1 = 48")
          Knowledge.Step.Eval("""["-","r1",5]""")
        case _ =>
          assertEquals(observations.last, "Tool result r2 = 43")
          Knowledge.Step.Return("r2"), rounds = 3)
    assertEquals(result.reasoning.receipts, Map("r1" -> "48", "r2" -> "43"))
    assertEquals(result.log.last.text, "Answer: 43")
    assert(result.log.last.program.exists(_.contains("r2")))

  test("an unchanged invalid tool request stops after three attempts without a final numeric answer"):
    val result = run("Find the average of five counts.", observations =>
      if observations.nonEmpty then assert(observations.last.contains("/ expects 2 arguments, received 5"))
      Knowledge.Step.Eval("""["/",35,46,27,61,31]"""), rounds = 3)
    assert(result.log.last.text.contains("same invalid program 3 times"))
    assert(result.reasoning.receipts.isEmpty)
    assert(!result.log.exists(_.text.startsWith("Answer:")))

  test("a decoder or memory failure ends the turn once and preserves the reported question"):
    val message = "WebGL2 memory budget reached; the model was unloaded"
    val result = run("A task that did not finish.", _ => Knowledge.Step.Unsupported("unused"),
      failure = Some(message))
    assertEquals(result.model, None)
    assertEquals(result.log.head.text, "A task that did not finish.")
    assertEquals(result.log.last.text, message)
    assert(!result.log.exists(_.text.startsWith("Answer:")))

  test("model repairs receive tool feedback and can still finish with the exact answer"):
    val result = run("Find the average of five counts.", observations =>
      observations.size match
        case 0 => Knowledge.Step.Eval("""["/",35,46,27,61,31]""")
        case 1 => Knowledge.Step.Eval("""["/",["+",35,46,27,61,31],5]""")
        case _ => Knowledge.Step.Return("r1"), rounds = 3)
    assertEquals(result.log.last.text, "Answer: 40")
    assertEquals(result.reasoning.repair.attempts, 0)
