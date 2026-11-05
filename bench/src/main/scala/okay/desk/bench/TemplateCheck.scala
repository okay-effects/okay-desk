package okay.desk.bench

import mlx.llm.{Chat, Models}
import okay.desk.skill.OnQwen

/** THE TEMPLATE'S LAW: the browser writes Qwen3's chat prompt by hand (`OnQwen.template`); mlx-scala renders the
 * model's Jinja template. The two must give the same tokens, or a skill trained on the JVM reads the wrong
 * positions in the page. `sbt "bench/runMain okay.desk.bench.TemplateCheck local/Qwen3-0.6B-4bit-cut19"` */
object TemplateCheck:
  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("mlx-community/Qwen3-0.6B-4bit")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val tok = chat.tokenizer
    def msg(role: String, content: String) = okay.effects.json.Json.obj("role" -> okay.effects.json.Json.Str(role),
      "content" -> okay.effects.json.Json.Str(content))
    val system = "You are a helper.\nTables:\n  table orders (id, total)"; val user = "How many orders? Сколько?"
    val theirs = chat.prompt(Vector(msg("system", system), msg("user", user)), Nil, Map("enable_thinking" -> false))
    val ours = tok.encode(OnQwen.template(system, user))
    println(s"mlx-scala: ${theirs.length} tokens; by hand: ${ours.length}; ${if theirs.sameElements(ours) then "THE SAME" else "DIFFERENT"}")
    if !theirs.sameElements(ours) then
      println("theirs:\n" + tok.decode(theirs.toIndexedSeq).replace("\n", "\\n") + "\nours:\n" + tok.decode(ours.toIndexedSeq).replace("\n", "\\n"))
      sys.exit(1)
