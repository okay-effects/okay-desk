package okay.desk.bench

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import mlx.{Arr, Dtype, Mlx, Scope}
import mlx.Mlx.*
import mlx.llm.{Chat, Lora, Model, Models, Qwen3}
import okay.effects.json.Json
import okay.desk.Prompt
import okay.desk.skill.Pairs

/**
 * THE STUDENT (specs/data.md §21): a model with fewer layers taught to give, after its last layer, the state the
 * teacher gives after the skill's layer — on the desk's prompts alone, nothing else asked of it. The contract between
 * a model and a skill is that state; a student that keeps it serves the same readers (or readers trained on it in
 * minutes). Three steps, each a command:
 *
 *  - `LayerInfluence` — how much each of the teacher's layers changes the state on the desk's prompts (1 − cosine of
 *    its input and output, ShortGPT's block influence): the layers that change it least are the first to go;
 *  - `Prune` — the layers kept (a list), renumbered: `local/<teacher>-p<n>`;
 *  - `Distill` — LoRA on every projection of the student, trained toward the teacher's states: the squared distance
 *    in units of the teacher's own spread per dimension (the readers z-score the same way), over every position of a
 *    prompt and its program. The adapter saved where `DeskBench` attaches it (DESK_ADAPTER).
 */
object Student:
  def msg(role: String, content: String) = Json.obj("role" -> Json.Str(role), "content" -> Json.Str(content))
  /** a pair as the readers see it: the page's prompt, then the program as the answer */
  def text(chat: Chat, p: Pairs.Pair): Array[Int] =
    chat.prompt(Vector(msg("system", Prompt.system(p.tables)), msg("user", p.question)), Nil,
      Map("enable_thinking" -> false)) ++ chat.tokenizer.encode(s"```json\n${p.program}\n```<|im_end|>")

  def load(repo: String): (Chat, Qwen3) =
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    (Chat.load(dir), Model.load(dir).asInstanceOf[Qwen3])

  /** each layer's residual output at every position of `ids` (the hook), as float arrays [n · d] */
  def outputs(model: Qwen3, ids: Array[Int]): Vector[Array[Float]] =
    val got = scala.collection.mutable.ArrayBuffer.empty[Array[Float]]
    model.hook = new Qwen3.Hook:
      def after(i: Int, h: Arr)(using Scope): Arr = { got += astype(h, Dtype.Float32).toFloats; h }
    val st = model.newState(); val s = new Scope
    try { model.hiddenStates(ints(ids, 1, ids.length)(using s), st, all = true)(using s).eval(); got.toVector }
    finally { s.close(); st.close(); model.hook = null }

object LayerInfluence:
  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("local/Qwen3-0.6B-4bit-cut19-vocab")
    val (chat, model) = Student.load(repo)
    val pairs = Pairs.many(500, 60)
    var sums: Array[Double] = null
    for p <- pairs do
      val ids = Student.text(chat, p)
      val outs = Student.outputs(model, ids)
      val d = outs.head.length / ids.length
      if sums == null then sums = new Array[Double](outs.size)
      for i <- 1 until outs.size do
        val (a, b) = (outs(i - 1), outs(i))
        var c = 0.0
        for t <- ids.indices do
          var (ab, aa, bb) = (0.0, 0.0, 0.0)
          for k <- t * d until (t + 1) * d do { ab += a(k) * b(k); aa += a(k) * a(k); bb += b(k) * b(k) }
          c += 1 - ab / math.sqrt(aa * bb)
        sums(i) += c / ids.length
    println(s"$repo: each layer's influence (1 − cos of its input and output, mean over ${pairs.size} prompts; " +
      "layer 0 always kept)")
    for i <- 1 until sums.length do println(f"  layer $i%2d: ${sums(i) / pairs.size}%.4f")
    println("least first: " + (1 until sums.length).sortBy(i => sums(i)).mkString(" "))
    model.close()

object Prune:
  /** `sbt "bench/runMain okay.desk.bench.Prune local/Qwen3-0.6B-4bit-cut19-vocab 0,1,2,3,5,8,11,14,16,18"` */
  def main(args: Array[String]): Unit =
    val repo = args(0)
    val keep = args(1).split(',').map(_.trim.toInt).toVector.sorted
    val src = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val name = s"$repo-p${keep.size}"
    val dst = Models.hubCache.resolve("models--" + name.replace("/", "--")).resolve("snapshots").resolve("main")
    Files.createDirectories(dst)
    Mlx.init()
    val s = new Scope
    given Scope = s
    try
      val all = loadSafetensors(src.resolve("model.safetensors").toString)
      val layerOf = "model\\.layers\\.(\\d+)\\.(.*)".r
      val kept = all.toSeq.flatMap { (n, a) => n match
        case layerOf(i, rest) => keep.indexOf(i.toInt) match
          case -1 => None
          case j => Some(s"model.layers.$j.$rest" -> a)
        case _ => Some(n -> a)
      }
      saveSafetensors(dst.resolve("model.safetensors").toString, kept.sortBy(_._1))
      for f <- Files.list(src).iterator.asScala if !f.toString.endsWith(".safetensors") do
        Files.copy(f, dst.resolve(f.getFileName.toString), StandardCopyOption.REPLACE_EXISTING)
      Files.writeString(dst.resolve("config.json"), Files.readString(src.resolve("config.json"))
        .replaceAll("\"num_hidden_layers\":\\s*\\d+", s"\"num_hidden_layers\": ${keep.size}"))
      Files.writeString(dst.resolve("layers.txt"), s"$repo: ${keep.mkString(",")}\n")
      println(f"$name: layers ${keep.mkString(",")} of $repo, ${Files.size(dst.resolve("model.safetensors")) / 1048576.0}%.0f MB")
    finally s.close()

object Distill:
  /** `sbt "bench/runMain okay.desk.bench.Distill local/…-cut19-vocab 19 local/…-cut19-vocab-p10 2000 32"` */
  def main(args: Array[String]): Unit =
    val (teacherRepo, layer, studentRepo) = (args(0), args(1).toInt, args(2))
    val steps = args.lift(3).map(_.toInt).getOrElse(2000)
    val rank = args.lift(4).map(_.toInt).getOrElse(32)
    val lr = args.lift(5).map(_.toFloat).getOrElse(2e-4f)
    val (tChat, teacher) = Student.load(teacherRepo)
    val (sChat, student) = Student.load(studentRepo)
    val tRead = MlxReading(tChat, teacher, layer)
    val last = student.layerCount - 1
    // the teacher's spread per dimension, at the skill's layer, over held-in prompts
    val sample = Pairs.many(900, 40).map(p => Student.text(tChat, p))
    val vs = sample.flatMap(ids => tRead.states(ids, ids.indices.toVector))
    val d = vs.head.length
    val mean = Array.tabulate(d)(k => vs.map(_(k).toDouble).sum / vs.size)
    val std = Array.tabulate(d)(k => math.sqrt(vs.map(v => (v(k) - mean(k)) * (v(k) - mean(k))).sum / vs.size).max(1e-3))
    println(s"teacher $teacherRepo at layer $layer: the spread from ${vs.size} positions; student $studentRepo " +
      s"(${student.layerCount} layers), LoRA rank $rank on every projection")
    // THE COMPONENTS THE MAP REMOVED (their output's scales all zero) get no adapter: an adapter on a silenced o_proj or
    // down_proj would grow a low-rank copy of the component back, and the merge would keep it
    val silenced: Set[String] =
      val dir = Models.snapshot(studentRepo).get
      val st = okay.llm.qwen.Safetensors(Files.readAllBytes(dir.resolve("model.safetensors")))
      (0 until student.layerCount).flatMap { i =>
        def zero(out: String) = st.has(out + ".weight") && (st.floats(out + ".scales").forall(_ == 0f) ||
          (st.words(out + ".weight").forall(_ == 0) && st.floats(out + ".biases").forall(_ == 0f)))
        (if zero(s"model.layers.$i.self_attn.o_proj") then Seq(s"$i.q", s"$i.k", s"$i.v", s"$i.o") else Nil) ++
          (if zero(s"model.layers.$i.mlp.down_proj") then Seq(s"$i.gate", s"$i.up", s"$i.down") else Nil)
      }.toSet
    if silenced.nonEmpty then println(s"no adapter on the removed components: ${silenced.toVector.sorted.mkString(" ")}")
    val lora = Lora(student, rank, Seq("q", "k", "v", "o", "gate", "up", "down"), Nil, alpha = 2f * rank, lr = lr, except = silenced)
    val keep = new Scope
    val invStd = floats(std.map(x => (1 / x).toFloat), 1, d)(using keep)
    val pairs = Pairs.many(2000, steps)
    def loss(ids: Array[Int], target: Arr)(using s: Scope): Arr =
      var out: Arr | Null = null
      student.hook = new Qwen3.Hook:
        def after(i: Int, h: Arr)(using Scope): Arr = { if i == last then out = h; h }
      val st = student.newState()
      try
        student.hiddenStates(ints(ids, 1, ids.length), st, all = true)
        val o = reshape(astype(out.nn, Dtype.Float32), ids.length, d)
        val z = multiply(subtract(o, target), invStd)
        divide(sum(reshape(multiply(z, z), -1), 0), scalar((ids.length * d).toFloat))
      finally
        st.close()
        student.hook = null
    val dir = Path.of(sys.props("user.home"), ".cache", "okay-desk", "student")
    Files.createDirectories(dir)
    val out = dir.resolve(s"lora-${studentRepo.split('/').last}.safetensors")
    val t0 = System.nanoTime()
    var window = Vector.empty[Float]
    for (p, k) <- pairs.zipWithIndex do
      val ids = Student.text(sChat, p)
      val tIds = Student.text(tChat, p)
      require(ids.sameElements(tIds), "the student's tokens differ from the teacher's")
      val t = tRead.states(tIds, tIds.indices.toVector).flatten.toArray
      val s = new Scope
      val target = floats(t, ids.length, d)(using s)
      window :+= lora.stepWith(loss(ids, target))
      s.close()
      if (k + 1) % 100 == 0 then
        println(f"step ${k + 1}: z-MSE ${window.sum / window.size}%.4f (${(System.nanoTime() - t0) / 1e9}%.0f s)")
        window = Vector.empty
      // a checkpoint every 500 steps: a run killed at 5,300 of 6,000 kept nothing
      if (k + 1) % 500 == 0 then lora.save(out.toString)
    lora.save(out.toString)
    println(s"saved $out (DESK_ADAPTER=$dir)")
    keep.close()
    lora.close()

object Merge:
  /** the student and its adapter as one model: `local/<student>-m<bits>` (mlx-scala's LoraMerge, every linear layer
   * quantized again at `bits`). `sbt "bench/runMain okay.desk.bench.Merge local/…-p12 4"` */
  def main(args: Array[String]): Unit =
    val (repo, bits) = (args(0), args.lift(1).map(_.toInt).getOrElse(4))
    val src = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val adapter = Path.of(sys.props("user.home"), ".cache", "okay-desk", "student",
      s"lora-${repo.split('/').last}.safetensors")
    val name = s"$repo-m$bits"
    val dst = Models.hubCache.resolve("models--" + name.replace("/", "--")).resolve("snapshots").resolve("main")
    mlx.llm.LoraMerge.qwen3(src, adapter, dst, bits)
    println(f"$name: ${Files.size(dst.resolve("model.safetensors")) / 1048576.0}%.0f MB at $dst")

/**
 * A LOOPED STUDENT (SPRINT, the improvements of 2026-11-24; Inkling 2's middle layers run three times): the model's
 * layers applied as `map` says — each application the weights of a layer of the source — so a layer's weights serve
 * several applications (mlx-scala's `okay_layer_map`). Only the layers named are written, renumbered; the
 * applications keep their own cache and their own LoRA, which training then fits (the relaxed recursive transformer's
 * way: shared weights, a small adapter an application).
 * `sbt "bench/runMain okay.desk.bench.Loop mlx-community/Qwen3-0.6B-4bit pairs"` — `pairs`: application i the
 * weights of layer 2·(i/2) (half the layers' weights); or a list of 28 layer numbers.
 */
object Loop:
  def main(args: Array[String]): Unit =
    val repo = args(0)
    val src = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val layers = "\"num_hidden_layers\":\\s*(\\d+)".r.findFirstMatchIn(Files.readString(src.resolve("config.json")))
      .map(_.group(1).toInt).get
    // `middle:a:b`: the layers from a until b looped in pairs, the edges their own (Inkling loops the middle only)
    val middle = "middle:(\\d+):(\\d+)".r
    val map = args(1) match
      case "pairs" => Vector.tabulate(layers)(i => 2 * (i / 2))
      case middle(a, b) => Vector.tabulate(layers)(i =>
        if i >= a.toInt && i < b.toInt then a.toInt + 2 * ((i - a.toInt) / 2) else i)
      case list => list.split(',').map(_.trim.toInt).toVector
    val unique = map.distinct.sorted
    val name = s"$repo-loop${unique.size}x${map.size}"
    val dst = Models.hubCache.resolve("models--" + name.replace("/", "--")).resolve("snapshots").resolve("main")
    Files.createDirectories(dst)
    Mlx.init()
    val s = new Scope
    given Scope = s
    try
      val all = loadSafetensors(src.resolve("model.safetensors").toString)
      val layerOf = "model\\.layers\\.(\\d+)\\.(.*)".r
      val kept = all.toSeq.flatMap { (n, a) => n match
        case layerOf(i, rest) => unique.indexOf(i.toInt) match
          case -1 => None
          case j => Some(s"model.layers.$j.$rest" -> a)
        case _ => Some(n -> a)
      }
      saveSafetensors(dst.resolve("model.safetensors").toString, kept.sortBy(_._1))
      for f <- Files.list(src).iterator.asScala if !f.toString.endsWith(".safetensors") do
        Files.copy(f, dst.resolve(f.getFileName.toString), StandardCopyOption.REPLACE_EXISTING)
      // the applications as many as before; each names its weights by the renumbered layer
      val renumbered = map.map(unique.indexOf)
      Files.writeString(dst.resolve("config.json"), Files.readString(src.resolve("config.json"))
        .replaceFirst("\\{", s"{\n  \"okay_layer_map\": [${renumbered.mkString(", ")}],"))
      Files.writeString(dst.resolve("layers.txt"), s"$repo: applications ${map.mkString(",")}\n")
      println(f"$name: ${map.size} applications over ${unique.size} layers' weights (${unique.mkString(",")}), " +
        f"${Files.size(dst.resolve("model.safetensors")) / 1048576.0}%.0f MB")
    finally s.close()

/**
 * A LOOPED STUDENT'S ADAPTER STARTED WHERE THE MODEL WAS (Relaxed Recursive Transformers, Bae et al. 2024): an
 * application that uses another layer's weights gets, as its LoRA's first values, the best rank-r approximation of
 * the difference between its own layer's weights and those it now uses — so the looped model starts near the model
 * it was cut from, not broken (loop14x28 began at loss 5.18 against 0.57). The approximation: a random range of the
 * difference, two power iterations, made orthonormal (A = Q), B = Qᵀ·ΔWᵀ over the LoRA's scale. The adapter is
 * written as `Lora.save` writes one; `FRAME_INIT` starts `FrameTrain` from it.
 * `sbt "bench/runMain okay.desk.bench.LoopInit mlx-community/Qwen3-0.6B-4bit <looped repo> 32 <out.safetensors>"`
 */
object LoopInit:
  private val projs = Vector("q" -> "self_attn.q_proj", "k" -> "self_attn.k_proj", "v" -> "self_attn.v_proj",
    "o" -> "self_attn.o_proj", "gate" -> "mlp.gate_proj", "up" -> "mlp.up_proj", "down" -> "mlp.down_proj")

  /** the columns of `y` ([n, k], row-major) made orthonormal, in place (modified Gram–Schmidt) */
  private def orthonormal(y: Array[Float], n: Int, k: Int): Unit =
    for j <- 0 until k do
      for p <- 0 until j do
        var dot = 0.0
        var i = 0
        while i < n do { dot += y(i * k + j).toDouble * y(i * k + p); i += 1 }
        i = 0
        while i < n do { y(i * k + j) = (y(i * k + j) - dot * y(i * k + p)).toFloat; i += 1 }
      var norm = 0.0
      var i = 0
      while i < n do { norm += y(i * k + j).toDouble * y(i * k + j); i += 1 }
      val inv = if norm > 0 then 1.0 / math.sqrt(norm) else 0.0
      i = 0
      while i < n do { y(i * k + j) = (y(i * k + j) * inv).toFloat; i += 1 }

  def main(args: Array[String]): Unit =
    val (srcRepo, loopRepo, rank, out) = (args(0), args(1), args(2).toInt, args(3))
    val alpha = 16f // Lora's default, FrameTrain's
    val scale = alpha / rank
    val src = Models.snapshot(srcRepo).getOrElse(sys.error(s"$srcRepo not in the cache"))
    val loop = Models.snapshot(loopRepo).getOrElse(sys.error(s"$loopRepo not in the cache"))
    // the source layer each application uses (Loop's layers.txt: "<repo>: applications 0,0,2,…")
    val map = Files.readString(loop.resolve("layers.txt")).split("applications ")(1).trim.split(',').map(_.toInt)
    val quant = Json.fromText(Files.readString(src.resolve("config.json")))("quantization")
    val (groupSize, bits) = (quant("group_size").int, quant("bits").int)
    Mlx.init()
    val s = new Scope
    given Scope = s
    val rnd = new scala.util.Random(7)
    try
      val w = loadSafetensors(src.resolve("model.safetensors").toString)
      def dense(layer: Int, proj: String): Arr =
        val p = s"model.layers.$layer.$proj"
        astype(dequantize(w(s"$p.weight"), w(s"$p.scales"), w(s"$p.biases"), groupSize, bits), Dtype.Float32)
      val tensors = scala.collection.mutable.ArrayBuffer.empty[(String, Arr)]
      var approximated = 0
      for i <- map.indices; (short, proj) <- projs do
        val own = dense(i, proj)
        val (outDim, inDim) = (own.dim(0), own.dim(1))
        if map(i) == i then
          // its own weights: the usual start (A random, B zero)
          val std = 1f / math.sqrt(inDim).toFloat
          tensors += s"$i.$short.a" -> floats(Array.fill(inDim * rank)(rnd.nextGaussian().toFloat * std), inDim, rank)
          tensors += s"$i.$short.b" -> zeros(Seq(rank, outDim), Dtype.Float32)
        else
          val dwT = transpose(subtract(own, dense(map(i), proj)), 1, 0) // [in, out]: x·dwT is the missing part
          val omega = floats(Array.fill(outDim * rank)(rnd.nextGaussian().toFloat), outDim, rank)
          var y = matmul(dwT, omega).toFloats // [in, rank]
          for _ <- 0 until 2 do
            orthonormal(y, inDim, rank)
            val z = matmul(transpose(dwT, 1, 0), floats(y, inDim, rank)) // [out, rank]
            y = matmul(dwT, z).toFloats
          orthonormal(y, inDim, rank)
          val a = floats(y, inDim, rank)
          val b = divide(matmul(transpose(a, 1, 0), dwT), scalar(scale)) // [rank, out]
          tensors += s"$i.$short.a" -> a.eval()
          tensors += s"$i.$short.b" -> b.eval()
          approximated += 1
      tensors += "lora.scale" -> scalar(scale)
      saveSafetensors(out, tensors.toSeq, Map("kind" -> "lora", "rank" -> rank.toString, "alpha" -> alpha.toString))
      println(s"$out: $approximated projections started at the rank-$rank approximation of their layer's difference")
    finally s.close()
