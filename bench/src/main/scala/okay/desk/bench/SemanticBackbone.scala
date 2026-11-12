package okay.desk.bench

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import mlx.Scope
import mlx.Mlx.*
import okay.effects.json.Json

/** Research candidate: keep layers across the full language model, including its final decoding layers.
  * Reuse the existing reduced vocabulary byte-for-byte. No original cache or deployed backbone is overwritten.
  */
object SemanticBackbone:
  def main(args: Array[String]): Unit =
    val home = Path.of(sys.props("user.home"))
    val full = home.resolve(".cache/huggingface/hub/models--mlx-community--Qwen3-0.6B-4bit/snapshots/main")
    val vocabulary = home.resolve(".cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab/snapshots/main")
    val out = Path.of(args.headOption.getOrElse(home.resolve(".cache/okay-desk/semantic-full-depth12").toString))
    val keep = args.lift(1).getOrElse("0,1,2,5,8,11,14,17,20,23,26,27").split(',').map(_.toInt).toVector
    require(keep == keep.distinct.sorted && keep.head == 0 && keep.last == 27, "retain ordered full-depth layers")
    val scope = new Scope
    given Scope = scope
    try
      val source = loadSafetensors(full.resolve("model.safetensors").toString)
      val reduced = loadSafetensors(vocabulary.resolve("model.safetensors").toString)
      val layer = "model\\.layers\\.(\\d+)\\.(.*)".r
      val tensors = source.toVector.flatMap {
        case (layer(i, rest), value) =>
          val target = keep.indexOf(i.toInt)
          Option.when(target >= 0)(s"model.layers.$target.$rest" -> value)
        case (name, value) if !name.startsWith("model.embed_tokens.") && !name.startsWith("lm_head.") =>
          Some(name -> value)
        case _ => None
      } ++ reduced.toVector.filter(_._1.startsWith("model.embed_tokens."))
      Files.createDirectories(out)
      for file <- Files.list(vocabulary).iterator.asScala if !file.toString.endsWith(".safetensors") &&
          file.getFileName.toString != "config.json" do
        Files.copy(file, out.resolve(file.getFileName), StandardCopyOption.REPLACE_EXISTING)
      val config = Json.fromText(Files.readString(vocabulary.resolve("config.json")))
      val fields = config.record.map {
        case ("num_hidden_layers", _) => "num_hidden_layers" -> Json.Num(keep.size)
        case other => other
      }
      Files.writeString(out.resolve("config.json"), Json.print(Json.Obj(fields)))
      Files.writeString(out.resolve("layers.txt"), s"full Qwen3 0.6B: ${keep.mkString(",")}\n")
      saveSafetensors(out.resolve("model.safetensors").toString, tensors.sortBy(_._1), Map("format" -> "mlx"))
      println(s"full-depth candidate: $out; ${keep.size} layers: ${keep.mkString(",")}")
    finally scope.close()
