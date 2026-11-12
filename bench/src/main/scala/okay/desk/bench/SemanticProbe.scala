package okay.desk.bench

import java.nio.file.Path
import okay.desk.skill.Read
import okay.llm.compute.OnMlx

/** Native student generation, with no task dictionary, reader heads or symbolic text parser. */
object SemanticProbe:
  def main(args: Array[String]): Unit =
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", sys.props("user.home") +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val config = okay.llm.qwen.Qwen3Config.parse(java.nio.file.Files.readString(dir.resolve("config.json")))
    val r = ComputeReading(dir, config.layers, OnMlx())
    val system = "Translate the user's problem into JSON for an exact calculator. " +
      "Return only {\"calculate\":\"expression\"}. Do not calculate the answer. " +
      "Example: There are 4 red balls and 7 blue balls. How many balls? -> {\"calculate\":\"4+7\"}"
    val question = args.headOption.getOrElse("У Лены 8 яблок. Она отдала 3 яблока. Сколько яблок осталось?")
    var ids = r.answer(Read.Prompt(system, question))
    val out = Vector.newBuilder[Int]
    val stops = Vector("<|im_end|>", "<|endoftext|>")
      .flatMap(s => r.answer[Array[Int]](Read.Encode(s)).toVector).toSet
    var done = false
    for _ <- 0 until 64 if !done do
      val logits = r.answer(Read.Logits(ids))
      val next = logits.indices.maxBy(logits(_))
      if stops(next) then done = true
      else
        out += next
        ids = ids :+ next
        print(r.answer(Read.Decode(Vector(next))))
        Console.flush()
    println("\nOUTPUT: " + r.answer(Read.Decode(out.result())))
