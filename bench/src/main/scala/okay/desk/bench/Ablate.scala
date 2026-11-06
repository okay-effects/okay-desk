package okay.desk.bench

import mlx.{Mlx, Scope}
import mlx.Mlx.*

/**
 * A COMPONENT SILENCED (okay-inlay specs/core.md §4.1): the cut model with one layer's attention (its o_proj) or
 * MLP (its down_proj) giving zero — the scales and biases of that linear zeroed, so it adds nothing to the residual;
 * the rest untouched. Written as `local/<name>-no<attn|mlp><L>` in the HuggingFace cache's layout; the readers are
 * trained on it again and the bench run (scripts/ablate.sh): which components the understanding needs.
 * `sbt "bench/runMain okay.desk.bench.Ablate local/Qwen3-0.6B-4bit-cut19 mlp 7"`.
 */
object Ablate:
  def main(args: Array[String]): Unit =
    val repo = args(0); val kind = args(1); val layer = args(2).toInt
    val src = mlx.llm.Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val name = s"${repo.stripPrefix("local/")}-no$kind$layer"
    val dst = mlx.llm.Models.hubCache.resolve("models--local--" + name).resolve("snapshots").resolve("main")
    java.nio.file.Files.createDirectories(dst)
    val target = s"model.layers.$layer." + (if kind == "attn" then "self_attn.o_proj" else "mlp.down_proj")
    Mlx.init()
    val s = new Scope
    given Scope = s
    try
      val all = loadSafetensors(src.resolve("model.safetensors").toString)
      val changed = all.map { (n, a) =>
        if n == s"$target.scales" || n == s"$target.biases" then n -> zeros(a.shape, a.dtype) else n -> a }
      saveSafetensors(dst.resolve("model.safetensors").toString, changed.toSeq.sortBy(_._1))
      for f <- Seq("config.json", "tokenizer.json", "tokenizer_config.json", "vocab.json", "merges.txt", "added_tokens.json", "special_tokens_map.json", "generation_config.json")
          if java.nio.file.Files.exists(src.resolve(f)) do
        java.nio.file.Files.copy(src.resolve(f), dst.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      println(s"local/$name: $target silenced, at $dst")
    finally s.close()
