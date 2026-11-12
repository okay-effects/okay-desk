package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.skill.Read
import okay.desk.statement.{Reader, Said}

/** one text read by a saved reader: its answer or its doubt, and its reading (a development aid) */
object ReadOne:
  def main(args: Array[String]): Unit =
    val trained = Reader.parse(Files.readAllBytes(Path.of(args(0)))).fold(sys.error(_), identity)
    val dir = Path.of(sys.props("user.home") +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main")
    val reading = ComputeReading(dir, 12, ComputeReading.device.getOrElse(okay.llm.compute.OnMlx()))
    val text = Files.readString(Path.of(args(1)))
    val r = Reader.read(trained, Read.run(Reader.see[Read](text, trained.norm, trained.echo), reading))
    val (said, read) = Said.words(text, r, Reader.russian(text))
    println(s"SAID: $said\nNEWS: ${r.news}\nLEAST: ${r.least}\n${read.getOrElse("")}")
