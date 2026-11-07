package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.desk.*

/** the two table readers on the sample's questions, printed (§25): `sbt "bench/runMain okay.desk.bench.TableReadings"` */
object TableReadings:
  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("local/Qwen3-0.6B-4bit-cut19")
    val layer = scala.sys.env.getOrElse("DESK_LAYER", "19").toInt
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[Qwen3]
    val readers = MlxReading.ready(chat, model, layer, repo, Vector.empty)
    for q <- Sample.questions; text <- Vector(q.en, q.ru) do
      println(s"$text\n    ${readers.tableReadings(Sample.tables, text)}")
    model.close()
