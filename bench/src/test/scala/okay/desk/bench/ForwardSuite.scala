package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.llm.qwen.{Qwen3Config, Qwen3Forward, Safetensors}

/** THE LAW of the plain forward: on the cut model, the residual after layer 19 and the next-token logits equal
 * mlx-scala's on a short prompt (the plain forward is slow: tens of tokens). FORWARD=1 to run (the model needed). */
class ForwardSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  test("the plain forward equals mlx-scala on the cut model"):
    assume(sys.env.contains("FORWARD"), "FORWARD=1 to run")
    val repo = "local/Qwen3-0.6B-4bit-cut19"
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[Qwen3]
    val mlx = MlxReading(chat, model, 19)
    val st = Safetensors(java.nio.file.Files.readAllBytes(dir.resolve("model.safetensors")))
    val config = Qwen3Config.parse(java.nio.file.Files.readString(dir.resolve("config.json")))
    val plain = Qwen3Forward(st, config)
    val ids = chat.tokenizer.encode("How many orders are unpaid? The table orders has columns id, customer, amount, status.")
    println(s"${ids.length} tokens; config $config")
    val t0 = System.nanoTime()
    val (states, last) = plain.run(ids, Set(19))
    println(f"plain forward: ${(System.nanoTime() - t0) / 1e9}%.1f s")
    val mine = states(19); val theirs = mlx.states(ids, ids.indices.toVector)
    val diffs = mine.zip(theirs).map((a, b) => a.zip(b).map((x, y) => math.abs(x - y)).max)
    val norms = theirs.map(b => b.map(math.abs).max)
    println(f"max abs diff per position: ${diffs.map(d => f"$d%.3f").mkString(" ")}; the states' max ${norms.max}%.1f")
    assert(diffs.zip(norms).forall((d, n) => d < 0.02f * n + 0.05f), "the residual after layer 19 differs")
    val lg = plain.logits(last); val lt = mlx.logits(ids)
    assertEquals(lg.zipWithIndex.maxBy(_._1)._2, lt.zipWithIndex.maxBy(_._1)._2, "the next token differs")
    println(s"next token: '${chat.tokenizer.decode(Seq(lg.zipWithIndex.maxBy(_._1)._2))}' on both")
    model.close()
