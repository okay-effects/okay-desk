package okay.desk.bench

import java.nio.file.Path
import mlx.llm.{Adaptable, Chat, Lora, Model, Session}
import okay.desk.know.Formal
import okay.desk.skill.{OnQwen, Read}
import okay.effects.json.Json
import okay.llm.compute.OnMlx

/** Check tokenizer/template and native-vs-training-runtime agreement before interpreting a training result. */
object SemanticAudit:
  def main(args: Array[String]): Unit =
    val dir = Path.of(args.headOption.getOrElse(sys.props("user.home") +
      "/.cache/okay-desk/semantic-student/weights"))
    val chat = Chat.load(dir)
    val model = Model.load(dir, mapped = true)
    val adapter = sys.env.get("SEMANTIC_ADAPTER").map(p => Lora.attach(model.asInstanceOf[Adaptable], p))
    if sys.env.contains("SEMANTIC_TRAINING_OPS") then model.asInstanceOf[Adaptable].training(true)
    val pair = SemanticCorpus.pairs(461, 1).head
    def msg(role: String, text: String) = Json.obj("role" -> Json.Str(role), "content" -> Json.Str(text))
    val ids = chat.prompt(Vector(msg("system", Formal.instructions), msg("user", pair.question)), Nil,
      Map("enable_thinking" -> false))
    val r = ComputeReading(dir, 12, OnMlx())
    val native = r.answer(Read.Prompt(Formal.instructions, pair.question))
    println(s"prompt equality: ${ids.sameElements(native)}, lengths: ${ids.length}/${native.length}")
    println("template equality: " +
      (chat.tokenizer.decode(ids.toIndexedSeq) == OnQwen.template(Formal.instructions, pair.question)))
    println("question: " + pair.question + "\ngold: " + pair.program)
    val s = Session(model, ids, 128, chat.stops)
    try println("MLX: " + chat.tokenizer.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector))
    finally s.close()
    var prefix = native
    var generated = Vector.empty[Int]
    for _ <- 0 until 48 do
      val scores = r.answer(Read.Logits(prefix))
      val next = scores.indices.maxBy(scores(_))
      generated :+= next
      prefix = prefix :+ next
    println("NATIVE: " + r.answer(Read.Decode(generated)))
    model.close()
    adapter.foreach(_.close())
