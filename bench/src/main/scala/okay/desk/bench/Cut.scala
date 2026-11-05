package okay.desk.bench

import mlx.{Mlx, Scope}
import mlx.Mlx.*

/**
 * THE MODEL CUT (specs/data.md §14): the first L layers kept, the rest dropped — a model whose state after layer L
 * is the untouched model's (a layer never looks up), so the readers at layer L read exactly the same; the final norm
 * kept (the head is the embedding, tied). Written as a repository in the HuggingFace cache's layout
 * (`local/<name>-cut<L>`), the tokenizer's files linked, config.json with the layer count — `Model.load` loads it
 * and the bench runs on it. `sbt "bench/runMain okay.desk.bench.Cut mlx-community/Qwen3-0.6B-4bit 21"`.
 */
object Cut:
  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("mlx-community/Qwen3-0.6B-4bit")
    val keep = args.lift(1).map(_.toInt).getOrElse(21)
    val src = mlx.llm.Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val name = s"local/${repo.split('/').last}-cut$keep"
    val dst = mlx.llm.Models.hubCache.resolve("models--" + name.replace("/", "--")).resolve("snapshots").resolve("main")
    java.nio.file.Files.createDirectories(dst)
    Mlx.init()
    val s = new Scope
    given Scope = s
    try
      val all = loadSafetensors(src.resolve("model.safetensors").toString)
      val layerOf = "model\\.layers\\.(\\d+)\\..*".r
      val kept = all.filter { (n, _) => n match
        case layerOf(i) => i.toInt < keep
        case "lm_head.weight" | "lm_head.scales" | "lm_head.biases" => false
        case _ => true }
      saveSafetensors(dst.resolve("model.safetensors").toString, kept.toSeq.sortBy(_._1))
      val config = java.nio.file.Files.readString(src.resolve("config.json"))
        .replaceAll("\"num_hidden_layers\":\\s*\\d+", s"\"num_hidden_layers\": $keep")
      java.nio.file.Files.writeString(dst.resolve("config.json"), config)
      for f <- Seq("tokenizer.json", "tokenizer_config.json", "vocab.json", "merges.txt", "added_tokens.json", "special_tokens_map.json", "generation_config.json")
          if java.nio.file.Files.exists(src.resolve(f)) do
        java.nio.file.Files.copy(src.resolve(f), dst.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      val size = java.nio.file.Files.size(dst.resolve("model.safetensors"))
      println(f"$name: ${kept.size} of ${all.size} tensors, ${size / 1048576.0}%.0f MB (the source ${java.nio.file.Files.size(src.resolve("model.safetensors")) / 1048576.0}%.0f MB) at $dst")
    finally s.close()
