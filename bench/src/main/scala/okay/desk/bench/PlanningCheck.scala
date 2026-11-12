package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.know.{Knowledge, Planning}
import okay.effects.json.Json
import okay.llm.compute.OnMlx
import okay.llm.qwen.Qwen3Config

/** Full finite-transition agreement, including unreachable states, not just agreement on the number of moves. */
object PlanningCheck:
  final case class Case(name: String, question: String, gold: String, aliases: Map[String, String])
  def grounded(program: String, aliases: Map[String, String]): String =
    val fs = Json.fromText(program).record.map {
      case ("labels", values) => "labels" -> Json.Obj(values.record.map { (key, value) =>
        key -> Json.Str(aliases.getOrElse(value.string, value.string))
      })
      case other => other
    }
    Json.print(Json.Obj(fs))
  def cases: Vector[Case] =
    val file = Path.of(sys.props("user.dir")).getParent
      .resolve("core/src/test/resources/planning/development.json")
    Json.fromText(Files.readString(file)).list.map { row =>
      val aliases = row.field("entityAliases").map(_.record.map((k, v) => k -> v.string).toMap)
        .getOrElse(Map.empty[String, String])
      Case(row("name").string, row("question").string, Json.print(row("gold")), aliases)
    }.toVector

  def main(args: Array[String]): Unit =
    val dir = Path.of(args(0))
    val r = ComputeReading(dir, Qwen3Config.parse(Files.readString(dir.resolve("config.json"))).layers, OnMlx())
    var exact = 0
    var answer = 0
    val reports = cases.take(args.lift(2).map(_.toInt).getOrElse(cases.size)).map { task =>
      val gold = Knowledge.authored(task.gold).toOption.collect { case Knowledge.Step.Plan(p) => p }.get
      val expected = Planning.run(gold, includeSemantics = true).fold(sys.error, identity)
      val report = SemanticExecution.run(task.question, r)
      val plan = report.trace.flatMap(s => Knowledge.authored(s).toOption.collect {
        case Knowledge.Step.Plan(p) => p
      }).lastOption.flatMap(p => Planning.run(grounded(p, task.aliases), includeSemantics = true).toOption)
      val matched = plan.exists(_.semantics == expected.semantics)
      val count = plan.exists(p => p.status == expected.status && p.moves.size == expected.moves.size)
      if matched then exact += 1
      if count then answer += 1
      println(s"${task.name}: full semantics=$matched, count=$count\n${task.question}\n" +
        report.trace.mkString("\n") + "\n" + report.result)
      Json.obj("name" -> Json.Str(task.name), "question" -> Json.Str(task.question),
        "matched" -> Json.Bool(matched), "moveCountMatched" -> Json.Bool(count),
        "expectedMoves" -> Json.Num(expected.moves.size), "gold" -> Json.fromText(task.gold),
        "execution" -> Json.Str(report.result.toString),
        "trace" -> Json.Arr(report.trace.map(Json.Str(_))))
    }
    Files.writeString(Path.of(args(1)), Json.print(Json.Arr(reports)))
    println(s"FULL FINITE SEMANTICS: $exact/${reports.size}; MOVE-COUNT AGREEMENT: $answer/${reports.size}")
