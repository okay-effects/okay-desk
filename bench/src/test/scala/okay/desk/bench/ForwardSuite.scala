package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.llm.compute.OnMlx

/** THE LAW of the one forward on the JVM: the page's own program (`Qwen3Compute` through `OnQwen`) on Apple's GPU
 * says what mlx-scala's forward says on the cut model — the residual after layer 19 at every position and the next
 * token. FORWARD=1 to run (the model and the native library needed). */
class ForwardSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  test("the page's program on OnMlx equals mlx-scala's forward on the cut model"):
    assume(sys.env.contains("FORWARD"), "FORWARD=1 to run")
    val repo = "local/Qwen3-0.6B-4bit-cut19"
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[Qwen3]
    val theirs = MlxReading(chat, model, 19)
    val mine = ComputeReading(dir, 19, OnMlx())
    val ids = chat.tokenizer.encode("How many orders are unpaid? The table orders has columns id, customer, amount, status.")
    val a = mine.states(ids, ids.indices.toVector); val b = theirs.states(ids, ids.indices.toVector)
    val diffs = a.zip(b).map((x, y) => x.zip(y).map((p, q) => math.abs(p - q)).max)
    val norms = b.map(_.map(math.abs).max)
    println(f"max abs diff per position: ${diffs.map(d => f"$d%.3f").mkString(" ")}; the states' max ${norms.max}%.1f")
    assert(diffs.zip(norms).forall((d, n) => d < 0.02f * n + 0.05f), "the residual after layer 19 differs")
    val la = mine.logits(ids); val lb = theirs.logits(ids)
    assertEquals(la.zipWithIndex.maxBy(_._1)._2, lb.zipWithIndex.maxBy(_._1)._2, "the next token differs")
    assertEquals(mine.prompt("s", "u").toVector, theirs.prompt("s", "u").toVector, "the template differs")
    model.close()
