package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.{Arr, Dtype, Scope}
import mlx.Mlx.*
import mlx.llm.{Lora, Qwen3}
import okay.desk.tasks.Drawn

/**
 * THE CORE DISTILLED ON ALL IT READS (specs/core-next.md, step 3): `Distill`'s training — LoRA on every projection,
 * the student's last state toward the teacher's at 19 in the teacher's spread per dimension (z-MSE), every position —
 * on the three kinds of training text mixed (`CoreTexts`: data prompts, logic tasks, fact sentences), shuffled.
 * The adapter where `Merge` takes it. `sbt "bench/runMain okay.desk.bench.CoreDistill <student repo> <steps> <rank>
 * [lr]"`; DATA/TASKS/FACTS the shares in percent (50/35/15).
 */
object CoreDistill:
  def main(args: Array[String]): Unit =
    val studentRepo = args(0)
    val steps = args(1).toInt
    val rank = args(2).toInt
    val lr = args.lift(3).map(_.toFloat).getOrElse(2e-4f)
    def share(k: String, d: Int) = sys.env.get(k).map(_.toInt).getOrElse(d)
    // TEACHER: the model whose last state is taught (the cut at 19 by default; the old core to compress it)
    val (tChat, teacher) = Student.load(sys.env.getOrElse("TEACHER", CoreBench.teacherRepo))
    val (_, student) = Student.load(studentRepo)
    val tRead = MlxReading(tChat, teacher, teacher.layerCount)
    val last = student.layerCount - 1
    val rnd = scala.util.Random(5)
    val ru = sys.env.get("RU").map(_.toInt)
    // FACTS_TEACHER: a model whose last state the fact sentences are taught toward instead (the old core: its fact
    // states a reader separates; the teacher's own confuse polarity — specs/core-next.md)
    val factsTeacher = sys.env.get("FACTS_TEACHER").map { repo =>
      val (c, m) = Student.load(repo); MlxReading(c, m, m.layerCount) }
    val texts: Vector[(Array[Int], Boolean)] = rnd.shuffle(
      // RU: the percent of the data pairs in Russian, and as many Russian statements again as the tasks give
      (CoreTexts.data(tChat, 2000L, steps * share("DATA", 50) / 100, ru) ++
        CoreTexts.tasks(tChat, Drawn.Split.Train, steps * share("TASKS", 35) / 200) ++
        (if ru.isEmpty then Vector.empty else
          CoreTexts.tasksRu(tChat, Drawn.Split.Train, steps * share("TASKS", 35) / 100).drop(steps * share("TASKS", 35) / 200))).map(_ -> false) ++
        CoreTexts.facts(tChat, false, steps * share("FACTS", 15) / 100).map(_ -> true))
    def goal(ids: Array[Int], fact: Boolean): Vector[Array[Float]] =
      (if fact then factsTeacher.getOrElse(tRead) else tRead).states(ids, ids.indices.toVector)
    println(s"student $studentRepo (${student.layerCount} layers), ${texts.size} texts, LoRA rank $rank, lr $lr")
    val vs = texts.take(120).flatMap((ids, _) => tRead.states(ids, ids.indices.toVector))
    val d = vs.head.length
    val mean = Array.tabulate(d)(k => vs.map(_(k).toDouble).sum / vs.size)
    val std = Array.tabulate(d)(k => math.sqrt(vs.map(v => (v(k) - mean(k)) * (v(k) - mean(k))).sum / vs.size).max(1e-3))
    val lora = Lora(student, rank, Seq("q", "k", "v", "o", "gate", "up", "down"), Nil, alpha = 2f * rank, lr = lr)
    val keep = new Scope
    val invStd = floats(std.map(x => (1 / x).toFloat), 1, d)(using keep)
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
    for ((ids, fact), k) <- texts.zipWithIndex do
      val t = goal(ids, fact).flatten.toArray
      val s = new Scope
      val target = floats(t, ids.length, d)(using s)
      window :+= lora.stepWith(loss(ids, target))
      s.close()
      if (k + 1) % 200 == 0 then
        println(f"step ${k + 1}: z-MSE ${window.sum / window.size}%.4f (${(System.nanoTime() - t0) / 1e9}%.0f s)")
        window = Vector.empty
      if (k + 1) % 1000 == 0 then lora.save(out.toString)
    lora.save(out.toString)
    println(s"saved $out")
    keep.close()
    lora.close()
