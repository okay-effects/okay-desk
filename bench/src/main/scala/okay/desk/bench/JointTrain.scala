package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.{Arr, Dtype, Scope}
import mlx.Mlx.*
import mlx.llm.{Lora, Qwen3}
import okay.desk.skill.{Read, Reading}
import okay.desk.statement.{Assemble, Reader, Writing}
import okay.desk.tasks.Drawn

/**
 * THE CORE TRAINED THROUGH THE HEADS (specs/core-joint.md §2): a LoRA on every projection of the core, taught by the
 * reader's own heads. Per text: the core reads it with the adapter as it stands (the page's `Reader.see`, echo as the
 * page); the gold decisions are assembled; the heads give the gradient of their loss with respect to the states
 * (`HeadGrads`: tags, frames); the adapter takes a step on
 *
 *     Σ ⟨h_θ, G⟩ + λ · z-MSE(h_θ, h_base)
 *
 * whose gradient is the heads' loss's (G constant) — plus a pull toward the base's own states, which the other
 * readers of the base still read. The heads are fixed: trained on split A of the corpus (`READ_SAVE` of a
 * `StatementRead` with `READ_TRAIN` = half); the adapter learns on split B and the teacher's rewrites — texts the
 * heads were not fitted to.
 *
 * `sbt "bench/runMain okay.desk.bench.JointTrain <base repo> <heads file> <out adapter> <epochs> <rank> <lr> <λ>"`;
 * JOINT_FROM / JOINT_TO: the Train items of split B (2000 / 4000); JOINT_TEACHER: the teacher's rewrites (jsonl).
 */
object JointTrain:
  private def env(k: String, d: Int) = sys.env.get(k).map(_.toInt).getOrElse(d)

  /** a reading that also remembers the last forward it was asked for: the ids and the positions read */
  private final class Recording(inner: Reading) extends Reading:
    var last: (Array[Int], Vector[Int]) = (Array.empty, Vector.empty)
    def answer[X](op: Read[X]): X = op match
      case Read.States(ids, at) => last = (ids, at); inner.answer(op)
      case _ => inner.answer(op)

  def main(args: Array[String]): Unit =
    val (repo, headsFile, out) = (args(0), Path.of(args(1)), Path.of(args(2)))
    val epochs = args(3).toInt
    val rank = args(4).toInt
    val lr = args(5).toFloat
    val lambda = args(6).toFloat
    val trained = Reader.parse(Files.readAllBytes(headsFile)).fold(e => sys.error(s"$headsFile: $e"), identity)
    val (chat, model) = Student.load(repo)
    val layers = model.layerCount
    val reading = Recording(MlxReading(chat, model, layers))
    val d = trained.heads.d
    val invStd = trained.std.map(1f / _)
    val texts: Vector[Writing.Stated] =
      Drawn.items(Drawn.Split.Train, env("JOINT_TO", 4000)).drop(env("JOINT_FROM", 2000))
        .flatMap(_.task.stated.toVector).flatMap(r => Vector(r.en, r.ru)) ++
        sys.env.get("JOINT_TEACHER").toVector.flatMap(p => StatementTeacher.load(Path.of(p)))
          .filter(_._1.split == "Train").map(_._2)
    println(s"base $repo ($layers layers), heads $headsFile (echo ${trained.echo}), ${texts.size} texts of split B" +
      s" and the teacher's; LoRA rank $rank, lr $lr, λ $lambda, $epochs epochs")
    val lora = Lora(model, rank, Seq("q", "k", "v", "o", "gate", "up", "down"), Nil, alpha = 2f * rank, lr = lr)
    val keep = new Scope
    val invStdArr = floats(invStd, 1, d)(using keep)
    val rnd = scala.util.Random(3)
    val t0 = System.nanoTime()
    var (window, skipped, done) = (Vector.empty[Float], 0, 0)
    for epoch <- 1 to epochs; s <- rnd.shuffle(texts) do
      // the base's states (no adapter), then the adapted reading and the heads' gradient
      model.lora = Map.empty
      val baseSeen = Read.run(Reader.see[Read](s.text, trained.norm, trained.echo), reading)
      val (ids, at) = reading.last
      val raw0 = MlxReading(chat, model, layers).states(ids, at)
      if done > 0 then { lora.attachTrained(); model.training(false) }
      val seen = if done > 0 then Read.run(Reader.see[Read](s.text, trained.norm, trained.echo), reading) else baseSeen
      val raw = if done > 0 then MlxReading(chat, model, layers).states(ids, at) else raw0
      model.lora = Map.empty
      Assemble.decisions(s.statement, s.text, seen.tokens) match
        case Left(_) => skipped += 1
        case Right(golden) =>
          val asked = StatementRead.Asked(golden.choices)
          val _ = Assemble.assemble(s.statement.problem, s.text, seen.tokens, golden.tags, asked)
          val (loss, gNorm) = HeadGrads.text(trained.heads, seen, golden.tags, asked.frames)
          // through the normalization: z = clip((h − mean)/std, ±4) — no gradient where clipped
          val g = Array.tabulate(at.size * d) { k =>
            val (p, j) = (k / d, k % d)
            val z = (raw(p)(j) - trained.mean(j)) * invStd(j)
            if z <= -4f || z >= 4f then 0f else gNorm(p)(j) * invStd(j)
          }
          val scope = new Scope
          val gArr = floats(g, at.size, d)(using scope)
          val h0 = floats(raw0.flatten.toArray, at.size, d)(using scope)
          val atArr = ints(at.toArray, at.size)(using scope)
          val _ = lora.stepWith {
            var hOut: Arr | Null = null
            model.hook = new Qwen3.Hook:
              def after(i: Int, h: Arr)(using Scope): Arr = { if i == layers - 1 then hOut = h; h }
            val st = model.newState()
            try
              model.hiddenStates(ints(ids, 1, ids.length), st, all = true)
              val hAt = take(reshape(astype(hOut.nn, Dtype.Float32), ids.length, d), atArr, 0)
              val surrogate = sum(reshape(multiply(hAt, gArr), -1), 0)
              val z = multiply(subtract(hAt, h0), invStdArr)
              val pull = divide(sum(reshape(multiply(z, z), -1), 0), scalar((at.size * d).toFloat))
              add(surrogate, multiply(pull, scalar(lambda)))
            finally
              st.close()
              model.hook = null
          }
          scope.close()
          window :+= loss
          done += 1
          if done % 100 == 0 then
            println(f"epoch $epoch, text $done: heads' loss ${window.sum / window.size}%.4f, skipped $skipped " +
              f"(${(System.nanoTime() - t0) / 1e9}%.0f s)")
            window = Vector.empty
          if done % 1000 == 0 then lora.save(out.toString)
    lora.save(out.toString)
    println(s"saved $out")
    keep.close(); lora.close()
