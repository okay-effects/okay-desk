package okay.desk.bench

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import mlx.{Arr, Dtype, Mlx, Scope}
import mlx.Mlx.*
import mlx.llm.{Chat, Model, Models}
import okay.desk.tasks.Drawn

/**
 * A NARROWER CORE (specs/core-next.md, step 2): each layer's MLP keeps its `keep` strongest neurons — the strength
 * of a neuron its mean |activation| over the training texts of the three kinds times the norm of its column in
 * `down_proj` (what it adds to the residual). The forward is the model's own, written out here so that the
 * activations can be seen; it is checked against mlx-scala's forward at the last layer before anything is cut.
 * `gate`/`up` keep their rows as they are (their 4-bit groups run along the input); `down` is unpacked, its columns
 * taken and packed again. The result: `local/<name>`, its config's intermediate size `keep`.
 *
 * `sbt "bench/runMain okay.desk.bench.CoreNarrow <model dir> <keep> <name>"`
 */
object CoreNarrow:
  def main(args: Array[String]): Unit =
    val (src, keep, name) = (Path.of(args(0)), args(1).toInt, args(2))
    require(keep % 64 == 0, "keep a multiple of the quantization group (64)")
    Mlx.init()
    val chat = Chat.load(src)
    val config = okay.llm.qwen.Qwen3Config.parse(Files.readString(src.resolve("config.json")))
    val (layers, hidden, heads, kvHeads, hd) = (config.layers, config.hidden, config.heads, config.kvHeads, config.headDim)
    val own = new Scope
    given Scope = own
    val w = loadSafetensors(src.resolve("model.safetensors").toString)
    def lin(x: Arr, p: String)(using Scope): Arr =
      quantizedMatmul(x, w(s"$p.weight"), w(s"$p.scales"), w(s"$p.biases"), true, 64, 4)
    def norm(x: Arr, p: String)(using Scope): Arr = rmsNorm(x, w(p), config.eps)
    // RU: the percent of the data pairs in Russian, and the Russian statements twice
    val ru = sys.env.get("RU").map(_.toInt)
    val texts = CoreTexts.data(chat, 11L, 120, ru) ++ CoreTexts.tasks(chat, Drawn.Split.Train, 60) ++
      (if ru.isEmpty then Vector.empty else CoreTexts.tasksRu(chat, Drawn.Split.Train, 120).drop(60)) ++
      CoreTexts.facts(chat, false, 80)
    println(s"$src: $layers layers, MLP ${config.intermediate} → $keep; ${texts.size} texts")
    val strength = Array.fill(layers)(new Array[Double](config.intermediate))
    var positions = 0L
    // the check: this forward's last state against mlx-scala's, on the first text
    val reference =
      val m = Model.load(src)
      try MlxReading(chat, m, layers).states(texts.head, texts.head.indices.toVector) finally m.close()
    for (ids, t) <- texts.zipWithIndex do
      val s = new Scope
      given Scope = s
      val n = ids.length
      val emb = w("model.embed_tokens.weight")
      val rows = take(emb, ints(ids, n), 0)
      var h = reshape(astype(dequantize(rows, take(w("model.embed_tokens.scales"), ints(ids, n), 0),
        take(w("model.embed_tokens.biases"), ints(ids, n), 0), 64, 4), Dtype.Float32), 1, n, hidden)
      for i <- 0 until layers do
        val p = s"model.layers.$i"
        val x = norm(h, s"$p.input_layernorm.weight")
        def split(t: Arr, count: Int) = transpose(reshape(t, 1, n, count, hd), 0, 2, 1, 3)
        val q = rope(transpose(rmsNorm(reshape(lin(x, s"$p.self_attn.q_proj"), 1, n, heads, hd),
          w(s"$p.self_attn.q_norm.weight"), config.eps), 0, 2, 1, 3), hd, false, config.ropeTheta, 1f, 0)
        val k = rope(transpose(rmsNorm(reshape(lin(x, s"$p.self_attn.k_proj"), 1, n, kvHeads, hd),
          w(s"$p.self_attn.k_norm.weight"), config.eps), 0, 2, 1, 3), hd, false, config.ropeTheta, 1f, 0)
        val v = split(lin(x, s"$p.self_attn.v_proj"), kvHeads)
        val o = attention(q, k, v, (1 / math.sqrt(hd)).toFloat, causal = n > 1)
        val a = add(h, lin(reshape(transpose(o, 0, 2, 1, 3), 1, n, heads * hd), s"$p.self_attn.o_proj"))
        val m = norm(a, s"$p.post_attention_layernorm.weight")
        val act = multiply(silu(lin(m, s"$p.mlp.gate_proj")), lin(m, s"$p.mlp.up_proj"))
        val mag = sum(sqrt(multiply(act, act)), 1).eval().toFloats   // [1, inter] summed over positions
        for j <- mag.indices do strength(i)(j) += mag(j)
        h = add(a, lin(act, s"$p.mlp.down_proj"))
      if t == 0 then
        val mine = h.eval().toFloats
        val diff = reference.flatten.zip(mine).map((a, b) => math.abs(a - b)).max
        val scale = reference.flatten.map(math.abs).max
        println(f"the check: this forward against mlx-scala's at layer $layers: max |diff| $diff%.4f of $scale%.1f")
        require(diff <= 0.02 * scale, "the written-out forward is not the model's: nothing cut")
      positions += n
      s.close()
      if (t + 1) % 50 == 0 then println(s"  ${t + 1}/${texts.size} texts")
    // the columns' norms of down_proj, and the neurons kept per layer
    val out = scala.collection.mutable.LinkedHashMap.empty[String, Arr]
    for (k, a) <- w.toSeq.sortBy(_._1) if !k.contains(".mlp.") do out(k) = a
    for i <- 0 until layers do
      val p = s"model.layers.$i.mlp"
      val down = dequantize(w(s"$p.down_proj.weight"), w(s"$p.down_proj.scales"), w(s"$p.down_proj.biases"), 64, 4)
      val d32 = astype(down, Dtype.Float32)
      val colNorm = sqrt(sum(multiply(d32, d32), 0)).eval().toFloats
      val score = strength(i).indices.map(j => strength(i)(j) / positions * colNorm(j))
      val kept = score.indices.sortBy(j => -score(j)).take(keep).sorted.toArray
      val dropped = score.indices.filterNot(kept.toSet).map(score).sum / score.sum
      println(f"layer $i: kept $keep of ${score.size}; the dropped carried ${dropped * 100}%.1f%% of the strength")
      val idx = ints(kept, kept.length)
      for proj <- Seq("gate_proj", "up_proj"); part <- Seq("weight", "scales", "biases") do
        out(s"$p.$proj.$part") = take(w(s"$p.$proj.$part"), idx, 0)
      val (dw, ds, db) = quantize(astype(take(down, idx, 1), w(s"$p.down_proj.scales").dtype), 64, 4)
      out(s"$p.down_proj.weight") = dw; out(s"$p.down_proj.scales") = ds; out(s"$p.down_proj.biases") = db
    val repo = s"local/$name"
    val dst = Models.hubCache.resolve("models--" + repo.replace("/", "--")).resolve("snapshots").resolve("main")
    Files.createDirectories(dst)
    evalAll(out.values.toSeq)
    saveSafetensors(dst.resolve("model.safetensors").toString, out.toSeq)
    for f <- Files.list(src).iterator.asScala if !f.toString.endsWith(".safetensors") do
      Files.copy(f, dst.resolve(f.getFileName.toString), StandardCopyOption.REPLACE_EXISTING)
    Files.writeString(dst.resolve("config.json"), Files.readString(src.resolve("config.json"))
      .replaceAll("\"intermediate_size\":\\s*\\d+", s"\"intermediate_size\": $keep"))
    println(f"$repo: ${Files.size(dst.resolve("model.safetensors")) / 1048576.0}%.0f MB at $dst")
    own.close()
