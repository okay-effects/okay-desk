package okay.desk.bench

import mlx.llm.{Chat, Models}

/** the grammar without a model: known text fed token by token, what it allows after each — to see it, not guess */
object GrammarProbe:
  def main(args: Array[String]): Unit =
    val dir = Models.snapshot("mlx-community/Qwen3.5-4B-MLX-4bit").get
    val tok = Chat.load(dir).tokenizer
    val g = Grammar(tok, DeskBench.tables, tok.id("<|im_end|>"))
    def show(label: String): Unit =
      val ok = g.allowed.get
      val probe = Seq("\"", "\",", "\", ", "\", \"", ",", ", ", ", \"", "}", "\"}", " \"", "where", "group", "orders")
      println(f"$label%-28s allowed ${ok.length}%6d; " + probe.map(p => s"[$p]=${tok.encode(p).length == 1 && ok.contains(tok.encode(p)(0))}").mkString(" ") +
        s"; choices ${g.choices.map(_.map(a => "'" + tok.decode(a.toIndexedSeq) + "'").mkString(" | ")).getOrElse("none")}")
    show("start")
    val ids = tok.encode(args.headOption.getOrElse("{\"from\": \"orders\", \"where\": [{\"column\": \"status\", \"op\": \"=\", \"value\": \"unpaid\"}]}"))
    for id <- ids do
      g.took(id)
      show(s"after '${tok.decode(Seq(id))}'")
    println(s"out: ${g.out}; done ${g.done}")
