package okay.desk.bench

import mlx.llm.{Adaptable, Chat, Lora, Model, Models, Qwen35}
import okay.desk.Prompt
import okay.desk.skill.Pairs

/**
 * A SMALL MODEL TRAINED ON THE PAIRS (specs/data.md §5): LoRA on MLX (mlx-scala), each example the page's prompt
 * for the pair's tables, the question, and the gold program as the answer in a ```json block. The adapter saved to
 * ~/.cache/okay-desk/lora-<model>.safetensors; `DeskBench` attaches it with DESK_ADAPTER. `sbt "bench/runMain
 * okay.desk.bench.Train mlx-community/Qwen3-0.6B-4bit 600"`.
 */
object Train:
  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("mlx-community/Qwen3-0.6B-4bit")
    val n = args.lift(1).map(_.toInt).getOrElse(600)
    val epochs = args.lift(2).map(_.toInt).getOrElse(1)
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val loaded = Model.load(dir, mapped = true); val tok = chat.tokenizer
    val model = loaded.asInstanceOf[Adaptable]
    val end = tok.id("<|im_end|>")
    val pairs = Pairs.many(seed = 42, n)
    println(s"$repo: ${pairs.size} pairs (${pairs.count(_.question.exists(c => c >= 'а' && c <= 'я'))} in Russian); e.g.\n  " +
      pairs.take(3).map(p => s"${p.question}\n    → ${p.program}").mkString("\n  "))
    def msg(role: String, content: String) = okay.effects.json.Json.obj("role" -> okay.effects.json.Json.Str(role),
      "content" -> okay.effects.json.Json.Str(content))
    val projs = loaded match
      case _: Qwen35 => Seq("o", "out", "down")
      case _ => Seq("q", "v", "o")
    val lora = Lora(model, rank = 8, projs = projs, lr = 2e-4f)
    val rnd = new scala.util.Random(3)
    val t0 = System.nanoTime()
    for e <- 1 to epochs do
      var sum = 0f; var k = 0
      for p <- rnd.shuffle(pairs) do
        val prompt = chat.prompt(Vector(msg("system", Prompt.system(p.tables)), msg("user", p.question)), Nil,
          Map("enable_thinking" -> false))
        val answer = tok.encode(s"```json\n${p.program}\n```") :+ end
        sum += lora.step(prompt ++ answer, prompt.length); k += 1
        if k % 100 == 0 then println(f"epoch $e, $k/${pairs.size}: mean loss ${sum / k}%.4f (${(System.nanoTime() - t0) / 1e9}%.0f s)")
      println(f"epoch $e: mean loss ${sum / k}%.4f")
    val out = java.nio.file.Path.of(sys.props("user.home"), ".cache", "okay-desk", s"lora-${repo.split('/').last}.safetensors")
    java.nio.file.Files.createDirectories(out.getParent)
    lora.save(out.toString)
    println(s"saved $out")
    lora.close(); loaded.close()
