package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.know.Formal
import okay.effects.json.Json
import okay.llm.compute.OnMlx

/** Premise/goal mutations are evaluated as groups: one lucky answer cannot pass a group.
  * This is a development gate, not a proof of general understanding or an independent blind benchmark.
  */
object SemanticAcceptance:
  final case class Task(question: String, program: String)
  final case class Group(name: String, tasks: Vector[Task])
  private def task(question: String, variables: String, conditions: String, goal: String): Task =
    Task(question, s"""{"vars":$variables,"where":$conditions,"ask":{"answer":$goal}}""")
  val groups: Vector[Group] = Vector(
    Group("change the requested quantity", Vector(
      task("В баке было 13 литров. Израсходовали 4 литра. Сколько осталось?", "{}", "[]", """["-",13,4]"""),
      task("В баке было 13 литров. Израсходовали 4 литра. Сколько израсходовали?", "{}", "[]", "4"))),
    Group("change an operation", Vector(
      task("A colony had 17 robots and received 6 more. How many robots does it have now?",
        "{}", "[]", """["+",17,6]"""),
      task("A colony had 17 robots and sent 6 away. How many robots does it have now?",
        "{}", "[]", """["-",17,6]"""))),
    Group("retain every arithmetic premise", Vector(
      task("There are 4 trays with 7 samples each. Then 3 samples are removed. How many samples remain?",
        "{}", "[]", """["-",["*",4,7],3]"""),
      task("There are 4 trays with 7 samples each. Then 5 samples are removed. How many samples remain?",
        "{}", "[]", """["-",["*",4,7],5]"""))),
    Group("do not invent a missing premise", Vector(
      task("Целое k от 1 до 5. k больше 3 и меньше 5. Чему равно k?",
        """{"k":[1,2,3,4,5]}""", """[[">","k",3],["<","k",5]]""", "\"k\""),
      task("Целое k от 1 до 5. k больше 3. Чему равно k?",
        """{"k":[1,2,3,4,5]}""", """[[">","k",3]]""", "\"k\""))),
    Group("implication does not imply its converse", Vector(
      task("p and q are Boolean. p is true. If p then q. Is q true?",
        """{"p":[false,true],"q":[false,true]}""", """["p",["implies","p","q"]]""", "\"q\""),
      task("p and q are Boolean. q is true. If p then q. Is p true?",
        """{"p":[false,true],"q":[false,true]}""", """["q",["implies","p","q"]]""", "\"p\""))),
    Group("negation and contradictory premises", Vector(
      task("p is Boolean. p is not true. What is p?", """{"p":[false,true]}""",
        """[["not","p"]]""", "\"p\""),
      task("p is Boolean. p is true and p is not true. What is p?", """{"p":[false,true]}""",
        """["p",["not","p"]]""", "\"p\""))))

  private def values(answers: Map[String, Vector[String]]): Vector[Vector[String]] =
    answers.values.map(_.sorted).toVector.sortBy(_.mkString("|"))
  def agrees(answer: SemanticExecution.Answer, expected: Formal.Result): Boolean =
    answer.status == expected.status && values(answer.answers) == values(expected.answers)
  private def answerJson(status: String, answers: Map[String, Vector[String]]): Json =
    Json.obj("status" -> Json.Str(status), "answers" -> Json.Obj(answers.toVector.sortBy(_._1).map {
      (name, xs) => name -> Json.Arr(xs.map(Json.Str(_)))
    }))
  private def digest(path: Path): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    try
      val buffer = new Array[Byte](65536)
      var count = input.read(buffer)
      while count >= 0 do
        md.update(buffer, 0, count)
        count = input.read(buffer)
    finally input.close()
    md.digest().map(b => f"${b & 255}%02x").mkString

  def main(args: Array[String]): Unit =
    require(args.length == 2, "SemanticAcceptance MODEL_DIRECTORY REPORT_JSON")
    val dir = Path.of(args(0))
    val config = okay.llm.qwen.Qwen3Config.parse(Files.readString(dir.resolve("config.json")))
    val reader = ComputeReading(dir, config.layers, OnMlx())
    var passedGroups = 0
    var passedTasks = 0
    val reports = groups.map { group =>
      val tasks = group.tasks.map { t =>
        val expected = Formal.run(t.program).fold(e => sys.error(e), identity)
        val started = System.nanoTime()
        val report = SemanticExecution.run(t.question, reader)
        val elapsed = (System.nanoTime() - started) / 1e9
        val passed = report.result.exists(agrees(_, expected))
        if passed then passedTasks += 1
        println(s"${if passed then "PASS" else "FAIL"}: ${t.question}\n${report.trace.mkString("\n")}")
        Json.obj("question" -> Json.Str(t.question), "goldProgram" -> Json.fromText(t.program),
          "passed" -> Json.Bool(passed), "expected" -> answerJson(expected.status, expected.answers),
          "result" -> report.result.fold(e => Json.obj("error" -> Json.Str(e)),
            a => answerJson(a.status, a.answers)), "seconds" -> Json.Num(elapsed),
          "trace" -> Json.Arr(report.trace.map(Json.Str(_))))
      }
      val passed = tasks.forall(_("passed") == Json.Bool(true))
      if passed then passedGroups += 1
      Json.obj("name" -> Json.Str(group.name), "passed" -> Json.Bool(passed), "tasks" -> Json.Arr(tasks))
    }
    val artifacts = Vector("model.safetensors", "config.json", "tokenizer.json").map(dir.resolve) ++
      sys.env.get("SEMANTIC_NATIVE_ADAPTER").map(Path.of(_)).toVector
    Files.writeString(Path.of(args(1)), Json.print(Json.obj(
      "evaluation" -> Json.Str("development mutations; not whole-formalization fidelity"),
      "modelDirectory" -> Json.Str(dir.toString), "backend" -> Json.Str("OnMlx"),
      "artifacts" -> Json.Arr(artifacts.map(p => Json.obj("path" -> Json.Str(p.toString),
        "sha256" -> Json.Str(digest(p))))), "groups" -> Json.Arr(reports))))
    val totalTasks = groups.map(_.tasks.size).sum
    println(s"MUTATION GROUPS: $passedGroups/${groups.size}; task agreement: $passedTasks/$totalTasks")
