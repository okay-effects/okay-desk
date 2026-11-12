package okay.desk.bench

import java.nio.file.Path
import mlx.{Dtype, Scope}
import mlx.Mlx.*

/** Preserve the backbone exactly; export each low-rank factor as 4-bit + 4-bit residual on a padded rank of 64. */
object SemanticExport:
  private val names = Map("q" -> "self_attn.q_proj", "k" -> "self_attn.k_proj", "v" -> "self_attn.v_proj",
    "o" -> "self_attn.o_proj", "gate" -> "mlp.gate_proj", "up" -> "mlp.up_proj", "down" -> "mlp.down_proj")
  def write(adapter: Path, out: Path): Unit =
    val scope = new Scope
    given Scope = scope
    try
      val all = loadSafetensors(adapter.toString)
      val scale = all("lora.scale").itemFloat
      def packed(prefix: String, dense: mlx.Arr): Vector[(String, mlx.Arr)] =
        val (q, s, b) = quantize(dense, 64, 4)
        val residue = subtract(dense, dequantize(q, s, b, 64, 4))
        val (qr, sr, br) = quantize(residue, 64, 4)
        Vector(prefix + ".weight" -> q, prefix + ".scales" -> s, prefix + ".biases" -> b,
          prefix + "r.weight" -> qr, prefix + "r.scales" -> sr, prefix + "r.biases" -> br)
      val tensors = all.keys.filter(_.endsWith(".a")).toVector.sorted.flatMap { key =>
        val id = key.stripSuffix(".a")
        val Array(layer, projection) = id.split('.')
        val a = all(id + ".a")
        val b = all(id + ".b")
        val rank = a.dim(1)
        require(rank <= 64, "browser adapter rank exceeds 64")
        val a0 = a.toFloats
        val b0 = b.toFloats
        val aFull = floats(Array.tabulate(64 * a.dim(0))(i =>
          if i / a.dim(0) < rank then a0((i % a.dim(0)) * rank + i / a.dim(0)) else 0f), 64, a.dim(0))
        val bFull = floats(Array.tabulate(b.dim(1) * 64)(i =>
          if i % 64 < rank then b0((i % 64) * b.dim(1) + i / 64) * scale else 0f), b.dim(1), 64)
        val prefix = s"model.layers.$layer.${names(projection)}"
        packed(prefix + ".a", astype(aFull, Dtype.Float32)) ++
          packed(prefix + ".b", astype(bFull, Dtype.Float32))
      }
      evalAll(tensors.map(_._2))
      saveSafetensors(out.toString, tensors, Map("format" -> "okay-low-rank-residual-v1"))
    finally scope.close()
  def main(args: Array[String]): Unit = write(Path.of(args(0)), Path.of(args(1)))
