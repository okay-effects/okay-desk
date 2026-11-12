package okay.desk.bench

import java.nio.file.Path
import mlx.llm.{Chat, Model, Session}
import okay.desk.Prompt
import okay.desk.know.{Formal, Knowledge}
import okay.desk.skill.NativeCompiler
import okay.effects.json.Json

/** Reference experiment: intact small LM with explicit deliberation and the same exact tool protocol. */
object SemanticThinking:
  def main(args: Array[String]): Unit =
    val dir = Path.of(args.headOption.getOrElse(sys.props("user.home") + "/.cache/okay-desk/semantic-full28"))
    val chat = Chat.load(dir)
    val model = Model.load(dir, mapped = true)
    def msg(role: String, text: String) = Json.obj("role" -> Json.Str(role), "content" -> Json.Str(text))
    var correct = 0
    val cases = SemanticCheck.cases.take(args.lift(1).map(_.toInt).getOrElse(3))
    for (question, expected) <- cases do
      var messages = Vector(msg("system", Formal.instructions + NativeCompiler.actions), msg("user", question))
      var receipts = Map.empty[String, String]
      var answer: Option[String] = None
      var round = 0
      while answer.isEmpty && round < 8 do
        val ids = chat.prompt(messages, Nil, Map("enable_thinking" -> true))
        val session = Session(model, ids, 2048, chat.stops)
        val text = try chat.tokenizer.decode(Iterator.continually(session.next()).takeWhile(_.isDefined).flatten.toVector)
          finally session.close()
        println(s"$question\n$text")
        val step = if text.contains("<think>") && !text.contains("</think>") then Left("deliberation budget exceeded")
          else Prompt.program(text).flatMap(Knowledge.authored)
        val feedback = step match
          case Right(Knowledge.Step.Eval(expression)) => Formal.evaluate(expression, receipts) match
            case Left(error) => error
            case Right(value) =>
              val id = s"r${receipts.size + 1}"
              receipts = receipts.updated(id, value)
              s"Tool result $id = $value"
          case Right(Knowledge.Step.Return(id)) if receipts.contains(id) =>
            answer = receipts.get(id)
            "done"
          case Right(Knowledge.Step.Formal(p)) => Formal.run(p) match
            case Right(result) if result.status == "proved" =>
              answer = result.answers.values.headOption.flatMap(_.headOption)
              "done"
            case other => other.toString
          case other => other.toString
        messages ++= Vector(msg("assistant", text), msg("user", feedback))
        round += 1
      val passed = answer.contains(expected)
      if passed then correct += 1
      println(s"${if passed then "PASS" else "FAIL"} expected=$expected actual=$answer")
    model.close()
    println(s"INTACT LM WITH DELIBERATION: $correct/${cases.size}")
