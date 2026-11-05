package okay.desk.bench

import mlx.llm.{Chat, Models}
import okay.desk.skill.Pairs

/** the names' positions in a prompt, seen: the token decoded at each */
object PromptProbe:
  def main(args: Array[String]): Unit =
    val dir = Models.snapshot("mlx-community/Qwen3-0.6B-4bit").get
    val chat = Chat.load(dir); val tok = chat.tokenizer
    val p = Pairs.many(42, 3).head
    val pr = okay.desk.skill.Read.run(okay.desk.skill.Probes.prompted[okay.desk.skill.Read](p), MlxReading(chat, mlx.llm.Model.load(dir, mapped = true).asInstanceOf[mlx.llm.Qwen3], 20))
    val full = tok.decode(pr.ids.toIndexedSeq)
    println(s"prompt ${pr.ids.length} tokens; system found at ${full.indexOf(okay.desk.Prompt.system(p.tables).take(40))}")
    pr.names.foreach((n, i) => println(f"  $n%-12s at $i%4d: '${if i >= 0 && i < pr.ids.length then tok.decode(Seq(pr.ids(i))) else "?"}'"))
