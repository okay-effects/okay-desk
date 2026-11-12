package okay.desk.bench

import okay.desk.know.{Formal, Knowledge, Planning, Repair}
import okay.desk.skill.{NativeCompiler, Read, Reading}

/** Reference execution of the same model-authored tool loop as the browser, with synchronous exact tools. */
object SemanticExecution:
  final case class Answer(status: String, answers: Map[String, Vector[String]])
  final case class Report(result: Either[String, Answer], trace: Vector[String])
  def run(question: String, reader: Reading): Report =
    var history = Vector.empty[(String, String)]
    var continuation: Option[(Vector[Int], String)] = None
    var receipts = Map.empty[String, String]
    var trace = Vector.empty[String]
    var observations = Vector.empty[String]
    var repair = Repair()
    var stopped: Option[String] = None
    var round = 0
    while round < 16 do
      val output = Read.run(NativeCompiler.compile[Read](question, observations = observations,
        history = history, continuation = continuation), reader)
      output match
        case Left(error) => return Report(Left(error), trace)
        case Right(out) =>
          trace :+= out.text
          def reply(feedback: String): Unit =
            trace :+= feedback
            observations :+= feedback
            history :+= out.text -> feedback
            continuation = Some(out.sequence -> feedback)
          def retry(error: String): Unit =
            reply(error)
            repair.failed(out.text, error) match
              case Left(reason) => stopped = Some(reason)
              case Right(next) => repair = next
          Knowledge.authored(out.text) match
            case Left(error) => retry(error)
            case Right(Knowledge.Step.Eval(expression)) =>
              Formal.evaluate(expression, receipts) match
                case Left(error) => retry(error)
                case Right(value) =>
                  repair = Repair()
                  val id = s"r${receipts.size + 1}"
                  receipts = receipts.updated(id, value)
                  reply(s"Tool result $id = $value")
            case Right(Knowledge.Step.Return(id)) =>
              receipts.get(id) match
                case None => retry(s"Unknown result ID $id; use an existing tool receipt.")
                case Some(value) => return Report(Right(Answer("proved", Map("answer" -> Vector(value)))), trace)
            case Right(Knowledge.Step.Formal(program)) =>
              Formal.run(program) match
                case Left(error) => retry(error)
                case Right(result) => return Report(Right(Answer(result.status, result.answers)), trace)
            case Right(Knowledge.Step.Plan(program)) =>
              Planning.run(program) match
                case Left(error) => retry(error)
                case Right(result) => return Report(Right(Answer(result.status,
                  Map("moves" -> Vector(result.moves.size.toString)))), trace)
            case Right(other) => return Report(Left("model requested " + other.toString), trace)
      if stopped.nonEmpty then return Report(Left(stopped.get), trace)
      round += 1
    Report(Left("model exceeded the tool-step budget"), trace)
