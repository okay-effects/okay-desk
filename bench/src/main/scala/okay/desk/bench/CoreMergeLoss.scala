package okay.desk.bench

import java.nio.file.Path
import mlx.llm.{Lora, Model}

/**
 * WHAT THE MERGE COSTS (specs/core-next.md): a distilled core's distance to its reference (z-MSE of the last state,
 * the reference's own spread, held-out texts by kind) three ways — the narrowed base alone, the base with its adapter
 * as trained (unmerged, full precision), the adapter merged into the weights at 4 bits. If the merged is far from
 * the unmerged, the 4-bit merge loses what the training gained.
 * `sbt "bench/runMain okay.desk.bench.CoreMergeLoss <reference repo> <base repo> <merged repo>"`
 */
object CoreMergeLoss:
  def main(args: Array[String]): Unit =
    val (refRepo, baseRepo, mergedRepo) = (args(0), args(1), args(2))
    val (rChat, ref) = Student.load(refRepo)
    val rRead = MlxReading(rChat, ref, ref.layerCount)
    val texts = CoreTexts.heldOut(rChat)
    val targets = texts.map((k, ts) => k -> ts.map(ids => rRead.states(ids, ids.indices.toVector)))
    ref.close()
    def distance(label: String, model: Model, layers: Int): Unit =
      val read = MlxReading(rChat, model, layers)
      val line = texts.zip(targets).map { case ((kind, ts), (_, tt)) =>
        val all = tt.flatten
        val d = all.head.length
        val mean = Array.tabulate(d)(k => all.map(_(k).toDouble).sum / all.size)
        val std = Array.tabulate(d)(k => math.sqrt(all.map(v => (v(k) - mean(k)) * (v(k) - mean(k))).sum / all.size)
          .max(1e-3))
        var (sum, n) = (0.0, 0L)
        for (ids, t) <- ts.zip(tt); (tv, sv) <- t.zip(read.states(ids, ids.indices.toVector)); k <- 0 until d do
          val z = (sv(k) - tv(k)) / std(k); sum += z * z; n += 1
        f"$kind ${sum / n}%.4f"
      }
      println(s"$label: z-MSE to $refRepo — ${line.mkString(", ")}")
    val (_, base) = Student.load(baseRepo)
    distance("base", base, base.layerCount)
    val adapter = Path.of(sys.props("user.home"), ".cache", "okay-desk", "student",
      s"lora-${baseRepo.split('/').last}.safetensors")
    val s = Lora.attach(base.asInstanceOf[mlx.llm.Adaptable], adapter.toString)
    distance("base + adapter (unmerged)", base, base.layerCount)
    s.close(); base.close()
    val (_, merged) = Student.load(mergedRepo)
    distance("merged at 4 bits", merged, merged.layerCount)
    merged.close()
