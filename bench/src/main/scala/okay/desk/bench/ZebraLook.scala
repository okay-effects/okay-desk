package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.skill.Read
import okay.desk.statement.{Arg, Con, Reader, Said}
import okay.effects.json.JsonText
import scala.jdk.CollectionConverters.*

/** a look at a few ZebraLogic puzzles as the reader reads them: its lists, and the compiler's word (a development
 * aid for §17's zero) */
object ZebraLook:
  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val gold = Files.readAllLines(Path.of(sys.env("ZEBRA_GOLD"))).asScala.toVector
      .flatMap(l => JsonText.parse(l).toOption).take(sys.env.get("ZEBRA_COUNT").map(_.toInt).getOrElse(4))
    val trained = Reader.parse(Files.readAllBytes(Path.of(sys.env("ZEBRA_READER")))).fold(sys.error(_), identity)
    val dir = Path.of(home + "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main")
    val reading = ComputeReading(dir, 12, ComputeReading.device.getOrElse(okay.llm.compute.OnMlx()))
    for g <- gold do
      val t = ZebraRead.text(g("puzzle").string)
      val r = Reader.read(trained, Read.run(Reader.see[Read](t, trained.norm, trained.echo), reading))
      println(s"=== ${g("id").string}\n$t")
      r.statement.foreach { s =>
        s.frames.filter(_.con == Con.Attribute).foreach(f =>
          println("LIST: " + f.args.collect { case m: Arg.Mention => m.at.in(t) }.mkString(" | ")))
        println(Said.lines(t, s, false).filterNot(_.contains("a list:")).take(8).mkString("\n")) }
      println(s"DOUBT: ${r.doubt}")
