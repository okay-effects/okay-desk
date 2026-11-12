package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.llm.compute.OnMlx
import okay.llm.qwen.Qwen3Config
import okay.desk.skill.{Read, Reading}

/** Reproduce a reported task through the actual native model, without a parser fallback or a supplied answer. */
object SemanticQuestion:
  def main(args: Array[String]): Unit =
    require(args.length == 2, "SemanticQuestion MODEL_DIRECTORY QUESTION_FILE")
    val dir = Path.of(args(0))
    val config = Qwen3Config.parse(Files.readString(dir.resolve("config.json")))
    val reader = ComputeReading(dir, config.layers, OnMlx())
    val question = Files.readString(Path.of(args(1)))
    var lastDecoded = ""
    val watched = new Reading:
      def answer[X](op: Read[X]): X =
        val result = reader.answer(op)
        op match
          case _: Read.Decode => lastDecoded = result.asInstanceOf[String]
          case _ => ()
        result
    val report = SemanticExecution.run(question, watched)
    println(report.trace.mkString("\n"))
    println(report.result)
    if report.result.isLeft then println("UNEXECUTED MODEL DRAFT:\n" + lastDecoded)
