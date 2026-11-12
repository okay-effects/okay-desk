package okay.desk.bench

import java.nio.file.{Files, Path, StandardOpenOption}
import mlx.llm.{Chat, Model, Session}
import okay.desk.Prompt
import okay.desk.know.{Knowledge, Planning}
import okay.effects.json.Json

/** Offline teacher supervision, filtered by a fresh formalization's full transition semantics. */
object PlanningParaphrases:
  def main(args: Array[String]): Unit =
    require(args.length >= 2, "PlanningParaphrases TEACHER_DIRECTORY OUTPUT_JSONL [COUNT]")
    val _ = mlx.Mlx.setCacheLimit(256L * 1024 * 1024)
    val dir = Path.of(args(0))
    val chat = Chat.load(dir)
    val model = Model.load(dir, mapped = true)
    val out = Path.of(args(1))
    require(!Files.exists(out), "keep the provenance of earlier teacher runs")
    def generate(system: String, user: String, limit: Int): String =
      val messages = Vector("system" -> system, "user" -> user).map((role, text) =>
        Json.obj("role" -> Json.Str(role), "content" -> Json.Str(text)))
      val ids = chat.prompt(messages, Nil, Map("enable_thinking" -> false))
      val session = Session(model, ids, limit, chat.stops)
      try chat.tokenizer.decode(Iterator.continually(session.next()).takeWhile(_.isDefined).flatten.toVector).trim
      finally session.close()
    val rewrite = """Rewrite this problem as a natural narrative in the SAME language. Preserve every entity,
      |initial and target position, capacity, permitted empty trips, and each unsafe pair without its supervisor.
      |Use different sentence structures and word forms, avoid the original template. Do not add or omit conditions.
      |Output only the rewritten question, not a solution, JSON, introduction or commentary.""".stripMargin
    var accepted = 0
    try
      val tasks = PlanningCorpus.pairs(9147, 200).filter(_.program.contains("\"labels\""))
        .take(args.lift(2).map(_.toInt).getOrElse(24))
      tasks.zipWithIndex.foreach { (task, i) =>
        val question = generate(rewrite, task.question, 450)
        val definition = Json.fromText(task.program)("plan")
        val roster = Json.print(Json.obj("vars" -> definition("vars"), "labels" -> definition("labels")))
        val verification = generate("Use ONLY the plan tool. Output one JSON object with outer key plan.\n" +
          Planning.instructions + "\nExpressions: [operator,arguments...] with = != and or not implies + -.\n" +
          "For example [\"or\",[\"!=\",\"a\",\"b\"],[\"=\",\"f\",\"a\"]] allows equal a,b only with f present.",
          question + "\nUse this variable naming roster: " + roster +
            ". Encode left as 0 and right as 1. Derive actions, conditions, start and goal from the text.", 700)
        val expected = Planning.run(Json.print(definition), true).fold(sys.error, _.semantics)
        val actual = Prompt.program(verification).flatMap(Knowledge.authored).toOption.collect {
          case Knowledge.Step.Plan(p) => p
        }.flatMap(p => Planning.run(p, true).toOption)
        val matched = actual.exists(_.semantics == expected)
        val row = Json.obj("question" -> Json.Str(question), "program" -> Json.Str(task.program),
          "sourceQuestion" -> Json.Str(task.question), "teacher" -> Json.Str(dir.toString),
          "verification" -> Json.Str(verification), "verified" -> Json.Bool(matched))
        val audit = Path.of(args(1) + ".audit")
        Files.writeString(audit, Json.print(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        if matched then
          Files.writeString(out, Json.print(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
          accepted += 1
        println(s"teacher ${i + 1}/${tasks.size}: accepted=$matched ($accepted total)\n$question\n$verification")
      }
    finally model.close()
    println(s"Accepted $accepted paraphrases; self-verification is a filter, not proof of language faithfulness.")
