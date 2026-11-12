package okay.desk.bench

import okay.desk.know.FactReader
import okay.desk.skill.Read
import okay.llm.compute.OnMlx
import java.nio.file.{Files, Path}

/** Inspect the installed readout on new text without retraining it. One source sentence per line. */
object FactReaderCheck:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: FactReaderCheck <sentences.txt>")
    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val reader = FactReader.parse(Files.readString(Path.of(home + "/.cache/okay-desk/facts-readers-small.json")))
      .fold(sys.error, identity)
    val on = ComputeReading(dir, reader.parameters.layer, OnMlx())
    Files.readString(Path.of(args(0))).linesIterator.filter(_.nonEmpty).foreach { text =>
      val out = Read.run(FactReader.features[Read](text), on).flatMap(reader.read("check", text, _))
      println(s"$text -> ${out.map(r => FactReader.meaning(r))}")
    }
