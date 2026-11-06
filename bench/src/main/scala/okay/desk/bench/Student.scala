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
 * THE STUDENT (specs/data.md §19): a model with fewer layers taught to give, after its last layer, the state the
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
    val lora = Lora(student, rank, Seq("q", "k", "v", "o", "gate", "up", "down"), Nil, alpha = 2f * rank, lr = lr)
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
    val dir = Path.of(sys.props("user.home"), ".cache", "okay-desk", "student")
    Files.createDirectories(dir)
    val out = dir.resolve(s"lora-${studentRepo.split('/').last}.safetensors")
    lora.save(out.toString)
    println(s"saved $out (DESK_ADAPTER=$dir)")
    keep.close()
    lora.close()
