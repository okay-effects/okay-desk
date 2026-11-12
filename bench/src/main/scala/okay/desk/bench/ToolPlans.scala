package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.know.Formal
import okay.effects.json.Json

/** Convert already checked scalar programs into next-action lessons with exact tool feedback. */
object ToolPlans:
  def compile(question: String, program: String): Vector[SemanticCorpus.Pair] =
    val body = Json.fromText(program)("formal")
    require(body("vars").record.isEmpty && body("where").list.isEmpty, "scalar programs only")
    val goal = body("ask").record.head._2
    var lessons = Vector.empty[SemanticCorpus.Pair]
    var history = Vector.empty[(String, String)]
    var receipts = Map.empty[String, String]
    var shared = Map.empty[Json, String]
    def operation(expr: Json): String =
      require(receipts.size < 15, "tool plan exceeds the runtime step budget")
      val text = Json.print(Json.obj("eval" -> expr))
      lessons :+= SemanticCorpus.Pair(question, text, history)
      val value = Formal.evaluate(Json.print(expr), receipts).fold(e => throw IllegalArgumentException(e), identity)
      val id = s"r${receipts.size + 1}"
      receipts = receipts.updated(id, value)
      history :+= text -> s"Tool result $id = $value"
      id
    def visit(expr: Json): Json = expr match
      case Json.Arr(Json.Str(op) +: args) =>
        shared.get(expr) match
          case Some(id) => Json.Str(id)
          case None =>
            val lowered = Json.Arr(Json.Str(op) +: args.map(visit))
            val id = operation(lowered)
            shared = shared.updated(expr, id)
            Json.Str(id)
      case other => other
    val last = visit(goal) match
      case Json.Str(id) => id
      case other => operation(other)
    lessons :+ SemanticCorpus.Pair(question, Json.print(Json.obj("result" -> Json.Str(last))), history)

  def main(args: Array[String]): Unit =
    val source = Path.of(args(0))
    require(!source.toString.endsWith(".heldout"), "held-out questions must not become training lessons")
    val groups = Files.readString(source).linesIterator.filter(_.nonEmpty).toVector.flatMap { line =>
      val row = Json.fromText(line)
      try Some(compile(row("question").string, row("program").string))
      catch case _: IllegalArgumentException => None
    }
    val lessons = groups.flatten
    val encoded = lessons.map { pair => Json.print(Json.obj("question" -> Json.Str(pair.question),
      "program" -> Json.Str(pair.program), "history" -> Json.Arr(pair.history.map((program, feedback) =>
        Json.obj("program" -> Json.Str(program), "feedback" -> Json.Str(feedback)))))) }
    Files.writeString(Path.of(args(1)), encoded.mkString("", "\n", "\n"))
    println(s"tool-plan training: ${groups.size} questions, ${lessons.size} next-action lessons")
