package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.know.{Formal, Knowledge}
import okay.effects.json.Json
import okay.llm.compute.OnMlx

/** Answer agreement on the held-out data file. This does not certify whole-formalization fidelity. */
object SemanticCorpusCheck:
  def main(args: Array[String]): Unit =
    val data = Path.of(args(0))
    require(data.toString.endsWith(".heldout"), "evaluation needs the separately held-out split")
    val n = args.lift(1).map(_.toInt).getOrElse(32)
    val offset = args.lift(2).map(_.toInt).getOrElse(0)
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", sys.props("user.home") +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val r = ComputeReading(dir, 12, OnMlx())
    var correct = 0
    val rows = Files.readString(data).linesIterator.filter(_.nonEmpty).drop(offset).take(n).toVector
    for (line, i) <- rows.zipWithIndex do
      val row = Json.fromText(line)
      val question = row("question").string
      val expected = Knowledge.authored(row("program").string).flatMap {
        case Knowledge.Step.Formal(p) => Formal.run(p)
        case _ => Left("gold is not a formal program")
      }.toOption.get.answers.values.toVector.sortBy(_.mkString("|"))
      val report = SemanticExecution.run(question, r)
      val result = report.result
      val passed = result.exists(x => x.status == "proved" &&
        x.answers.values.toVector.sortBy(_.mkString("|")) == expected)
      if passed then correct += 1
      println(s"${i + 1}/${rows.size} ${if passed then "PASS" else "FAIL"}: $question\n" +
        s"${report.trace.mkString("\n")}\n$result; gold=$expected")
    println(s"HELD-OUT ANSWER AGREEMENT: $correct/${rows.size}")
