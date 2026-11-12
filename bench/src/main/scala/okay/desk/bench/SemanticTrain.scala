package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.{Adaptable, Chat, Lora, Model}
import mlx.Dtype
import mlx.Mlx.*
import okay.desk.skill.NativeCompiler
import okay.effects.json.Json

/** Train native program authoring on the workstation; export ordinary 4-bit weights for browser inference. */
object SemanticTrain:
  def main(args: Array[String]): Unit =
    // The workstation's allocator must not retain every differently sized training buffer indefinitely.
    val _ = setCacheLimit(256L * 1024 * 1024)
    val src = Path.of(sys.env.getOrElse("SMALL_DIR", sys.props("user.home") +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val steps = args.headOption.map(_.toInt).getOrElse(512)
    val out = Path.of(sys.env.getOrElse("SEMANTIC_OUT", sys.props("user.home") +
      "/.cache/okay-desk/semantic-student-v2"))
    Files.createDirectories(out)
    val chat = Chat.load(src)
    val model = Model.load(src, mapped = true)
    val lora = Lora(model.asInstanceOf[Adaptable], rank = 16, projs = Seq("q", "k", "v", "o", "gate", "up", "down"),
      lr = sys.env.get("SEMANTIC_LR").map(_.toFloat).getOrElse(3e-4f))
    sys.env.get("SEMANTIC_RESTORE").foreach(lora.restoreParameters)
    val synthetic = SemanticCorpus.pairs(461, steps)
    val extra = sys.env.get("SEMANTIC_PAIRS").map { filename =>
      require(!filename.endsWith(".heldout"), "held-out tasks must not be used for training")
      Files.readString(Path.of(filename)).linesIterator.filter(_.trim.nonEmpty).map { line =>
        val row = Json.fromText(line)
        val history = row.field("history").collect { case Json.Arr(xs) =>
          xs.map(j => j("program").string -> j("feedback").string).toVector
        }.getOrElse(Vector.empty)
        SemanticCorpus.Pair(row("question").string, row("program").string, history)
      }.toVector
    }.getOrElse(Vector.empty)
    val rnd = new scala.util.Random(112)
    val pairs = synthetic.zipWithIndex.map { (pair, i) =>
      if extra.nonEmpty && i % 4 != 0 then extra(rnd.nextInt(extra.size)) else pair
    }
    println(s"training tasks: ${pairs.size}, external pool: ${extra.size}, synthetic share: " +
      (if extra.isEmpty then "100%" else "25%"))
    val end = chat.tokenizer.id("<|im_end|>")
    val t0 = System.nanoTime()
    var totalLoss = 0.0
    for (pair, i) <- pairs.zipWithIndex do
      val prompt = chat.tokenizer.encode(NativeCompiler.promptText(pair.question, pair.history))
      val answer = chat.tokenizer.encode(pair.program) :+ end
      val ids = prompt ++ answer
      val prefixWeight = sys.env.get("SEMANTIC_PREFIX_WEIGHT").map(_.toFloat).getOrElse(1f)
      val weights = answer.zipWithIndex.map { (id, position) =>
        val token = chat.tokenizer.decode(Vector(id))
        val weight = if token.exists(_.isDigit) || Vector("+", "-", "*", "/", "<", "=").exists(token.contains)
          then 5f else 1f
        // Choosing the tool and state schema must not disappear in an average over hundreds of copied tokens.
        weight * (if position < 8 then prefixWeight else 1f)
      }
      val loss = lora.stepWith {
        val state = model.newState()
        try
          val logits = astype(model.logitsAll(ints(ids, 1, ids.length), state), Dtype.Float32)
          val vocabulary = logits.dim(-1)
          val pred = reshape(slice(logits, Seq(0, prompt.length - 1, 0), Seq(1, ids.length - 1, vocabulary)),
            answer.length, vocabulary)
          val target = ints(answer, answer.length, 1)
          val ce = subtract(logsumexp(pred, 1), reshape(takeAlong(pred, target, 1), answer.length))
          divide(sum(multiply(ce, floats(weights, answer.length)), 0), scalar(weights.sum))
        finally
          state.close()
      }
      totalLoss += loss
      if (i + 1) % 16 == 0 then
        println(f"${i + 1}/$steps: loss=$loss%.4f mean=${totalLoss / (i + 1)}%.4f " +
          f"elapsed=${(System.nanoTime - t0) / 1e9}%.1fs")
      if (i + 1) % 128 == 0 || i + 1 == steps then
        lora.save(out.resolve("adapter.safetensors").toString)
        println(s"checkpoint: $out")
    lora.close()
    model.close()
    SemanticExport.write(out.resolve("adapter.safetensors"), out.resolve("native-adapter.safetensors"))
    println(s"exported native adapter: $out")
