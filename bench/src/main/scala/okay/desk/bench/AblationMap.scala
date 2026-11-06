package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.desk.*
import okay.desk.skill.*

/** THE MAP BY COMPONENT in one process (okay-inlay specs/core.md §4.1): for each layer, the attention silenced and the
 * MLP silenced (`Ablate.make`), the readers trained on the variant, the sample's questions run — one line a variant
 * appended to bench/ablation.txt; the variant and its skill deleted after. One JVM, one MLX job: nothing left behind.
 * `nohup sbt -batch "bench/runMain okay.desk.bench.AblationMap local/Qwen3-0.6B-4bit-cut19 19 0 18" &` */
object AblationMap:
  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("local/Qwen3-0.6B-4bit-cut19"); val layer = args.lift(1).map(_.toInt).getOrElse(19)
    val from = args.lift(2).map(_.toInt).getOrElse(0); val until = args.lift(3).map(_.toInt).getOrElse(18)
    // (a forked run's working directory is bench/)
    val out = if java.nio.file.Files.isDirectory(java.nio.file.Path.of("bench")) then java.nio.file.Path.of("bench", "ablation.txt") else java.nio.file.Path.of("ablation.txt")
    def note(line: String): Unit =
      println(line); java.nio.file.Files.writeString(out, line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    val golds = Sample.questions.map(q => Sample.run(Query.parse(q.gold).toOption.get).toOption.get)
    def bench(variant: String): String =
      val dir = Models.snapshot(variant).getOrElse(sys.error(s"$variant not in the cache"))
      val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[Qwen3]
      try
        val t0 = System.nanoTime()
        val readers = Readers.On(Readers(), MlxReading(chat, model, layer))
        readers.train(Pairs.many(42, 600))
        var right = Map("en" -> 0, "ru" -> 0)
        for (q, i) <- Sample.questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
          val got = readers.program(Sample.tables, text).flatMap(Query.parse).flatMap(Sample.run)
          if got.exists(Sample.same(_, golds(i))) then right = right.updated(lang, right(lang) + 1)
        f"$variant%-46s readers | en: ${right("en")}/${Sample.questions.size} | ru: ${right("ru")}/${Sample.questions.size}  (${(System.nanoTime() - t0) / 1e9}%.0f s)"
      finally model.close()
    note(s"# ${java.time.LocalDateTime.now.toString.take(16)} $repo, layers $from..$until: a component silenced, the readers retrained (600 pairs), the sample")
    for l <- from to until; kind <- Seq("attn", "mlp") do
      val variant = Ablate.make(repo, kind, l)
      try note(bench(variant))
      catch case e: Throwable => note(s"$variant FAILED: ${e.toString.take(200)}")
      finally
        val cache = Models.hubCache.resolve("models--local--" + variant.stripPrefix("local/"))
        def rm(p: java.nio.file.Path): Unit = if java.nio.file.Files.isDirectory(p) then { java.nio.file.Files.list(p).forEach(rm); java.nio.file.Files.delete(p) } else java.nio.file.Files.deleteIfExists(p)
        rm(cache)
