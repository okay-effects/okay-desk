package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Session}
import okay.desk.*
import okay.desk.skill.*

/**
 * THE BENCH: sample tables (orders, customers, edges); questions in English and Russian, each with its gold
 * program (run by the exact handlers: the gold result); for each model, the page's loop — the prompt, the program
 * from the answer, checked and run, one retry with the problem in words — and the result compared to the gold as a
 * set of rows. Printed per model: right at once, right after the retry, wrong result, no program; by language.
 */
object DeskBench:
  export Sample.*

  def main(args: Array[String]): Unit =
    val repos = scala.sys.env.getOrElse("DESK_MODELS", "mlx-community/Qwen3-0.6B-4bit,mlx-community/Qwen3.5-0.8B-MLX-4bit," +
      "mlx-community/Qwen3-1.7B-4bit,mlx-community/Qwen3.5-2B-MLX-4bit,mlx-community/Qwen3.5-4B-MLX-4bit").split(",").toVector
    val golds = questions.map(q => run(Query.parse(q.gold).toOption.get).toOption.get)
    println(s"${questions.size} questions × 2 languages; gold results computed")
    val system = Prompt.system(tables)
    val summary = Vector.newBuilder[String]
    for repo <- repos do
      Models.snapshot(repo) match
        case None => println(s"$repo: not in the cache, skipped")
        case Some(dir) =>
          val chat = Chat.load(dir); val model = Model.load(dir, mapped = true); val tok = chat.tokenizer
          // an adapter trained by `Train`, if DESK_ADAPTER names its directory (the file lora-<model>.safetensors there)
          val adapter = scala.sys.env.get("DESK_ADAPTER").map(d => s"$d/lora-${repo.split('/').last}.safetensors")
            .filter(f => java.nio.file.Files.exists(java.nio.file.Path.of(f)))
            .map(f => { println(s"adapter: $f"); mlx.llm.Lora.attach(model.asInstanceOf[mlx.llm.Adaptable], f) })
          def msg(role: String, content: String) = okay.effects.json.Json.obj("role" -> okay.effects.json.Json.Str(role),
            "content" -> okay.effects.json.Json.Str(content))
          val end = tok.id("<|im_end|>")
          def complete(ms: Vector[(String, String)], max: Int, masked: Boolean): String =
            val prompt = chat.prompt(ms.map(msg), Nil, Map("enable_thinking" -> false))
            val port = if masked then Grammar(tok, tables, end) else null
            val s = Session(model, prompt, max, chat.stops, port = port)
            try
              val toks = Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
              if masked then "```json\n" + port.out.toString + "\n```" else tok.decode(toks)
            finally s.close()
          for masked <- Seq(false, true) if !scala.sys.env.contains("DESK_SKIP_GENERATION") do {
          var at = Map("en" -> 0, "ru" -> 0); var retried = Map("en" -> 0, "ru" -> 0)
          var wrong = Map("en" -> 0, "ru" -> 0); var none = Map("en" -> 0, "ru" -> 0)
          val t0 = System.nanoTime()
          for (q, i) <- questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
            val answer = complete(Vector("system" -> system, "user" -> text), 500, masked)
            def attempt(a: String): Either[String, Table] =
              Prompt.program(a).left.map(_ => "no program").flatMap(Query.parse).flatMap(run)
            attempt(answer) match
              case Right(t) if same(t, golds(i)) => at = at.updated(lang, at(lang) + 1)
              case first =>
                val problem = first.left.getOrElse("the result differs from what the question asks")
                if first.left.exists(_ == "no program") then none = none.updated(lang, none(lang) + 1)
                else
                  val again = complete(Vector("system" -> system, "user" -> text, "assistant" -> answer,
                    "user" -> s"That program failed: $problem. Reply with the corrected JSON only."), 500, masked)
                  attempt(again) match
                    case Right(t) if same(t, golds(i)) => retried = retried.updated(lang, retried(lang) + 1)
                    case _ => wrong = wrong.updated(lang, wrong(lang) + 1)
                      if scala.sys.env.contains("DESK_VERBOSE") then println(s"  [$lang ${i + 1}] ${answer.take(300).replace("\n", " ")}")
          val n = questions.size
          val line = f"$repo%-42s ${if masked then "masked" else "free  "} | en: ${at("en")}/$n at once, +${retried("en")} after the retry, ${wrong("en")} wrong, ${none("en")} none" +
            f" | ru: ${at("ru")}/$n, +${retried("ru")}, ${wrong("ru")} wrong, ${none("ru")} none | ${(System.nanoTime() - t0) / 1e9}%.0f s"
          println(line); summary += line
          }
          // the third arm (specs/data.md §7): the readers and the assembler, the model untouched — DESK_READERS=1
          if scala.sys.env.contains("DESK_READERS") then model match
            case q3: mlx.llm.Qwen3 =>
              val t1 = System.nanoTime()
              val readers = MlxReading.ready(chat, q3, scala.sys.env.getOrElse("DESK_LAYER", "20").toInt, repo, Pairs.many(42, scala.sys.env.getOrElse("DESK_PAIRS", "1500").toInt, legacy = scala.sys.env.contains("DESK_LEGACY")))
              if !scala.sys.env.contains("DESK_SKIP_MEASURE") then readers.measure(Pairs.many(43, 200, legacy = scala.sys.env.contains("DESK_LEGACY")))
              if scala.sys.env.contains("DESK_CONSTRUCTORS") then readers.measureByConstructor(Pairs.many(44, scala.sys.env("DESK_CONSTRUCTORS").toInt, legacy = scala.sys.env.contains("DESK_LEGACY")))
              println(f"readers trained (${(System.nanoTime() - t1) / 1e9}%.0f s)")
              var right = Map("en" -> 0, "ru" -> 0); var fails = Vector.empty[String]
              for (q, i) <- questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
                val got = readers.program(tables, text).flatMap(Query.parse).flatMap(run)
                if got.exists(same(_, golds(i))) then right = right.updated(lang, right(lang) + 1)
                else fails = fails :+ s"[$lang ${i + 1}] ${readers.program(tables, text).fold(e => e, identity)}"
              val line = f"$repo%-42s readers | en: ${right("en")}/${questions.size} | ru: ${right("ru")}/${questions.size}"
              println(line); summary += line
              if scala.sys.env.contains("DESK_VERBOSE") then fails.foreach(f => println(s"  $f"))
            case _ => println(s"$repo: readers need a Qwen3 (the hook)")
          adapter.foreach(_.close())
          model.close()
    println("\nmodel | en: right at once, after the retry, wrong, no program | ru | time")
    summary.result().foreach(println)
