package okay.desk.bench

import mlx.llm.{Chat, Model, Models}
import okay.desk.*
import okay.desk.skill.*

/** THE STUDENT BY THE MAP (okay-inlay specs/core.md §11, the next step): the sets the map named — FREE, NEAR (free
 * for English, a question for Russian), RU (Russian's alone) — silenced together, and each variant measured whole on
 * the sample two ways: with the TEACHER'S SKILL FILE UNCHANGED (the contract kept as is?) and with the readers
 * retrained on the variant. `sbt "bench/runMain okay.desk.bench.StudentByMap"`; the lines to bench/ablation.txt. */
object StudentByMap:
  val free = Seq("attn" -> 1, "attn" -> 9, "attn" -> 16, "mlp" -> 16)
  val near = Seq("attn" -> 3, "attn" -> 5, "attn" -> 7, "attn" -> 8, "mlp" -> 6, "mlp" -> 7, "mlp" -> 8, "mlp" -> 9, "mlp" -> 12, "mlp" -> 17, "mlp" -> 18)
  val ru = Seq("attn" -> 2, "attn" -> 4, "attn" -> 10, "attn" -> 12, "attn" -> 18, "mlp" -> 3, "mlp" -> 4, "mlp" -> 11, "mlp" -> 15)
  val sets: Vector[(String, Seq[(String, Int)])] = Vector("free" -> free, "free+near" -> (free ++ near), "free+ru" -> (free ++ ru),
    "free+near+ru" -> (free ++ near ++ ru))

  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("local/Qwen3-0.6B-4bit-cut19"); val layer = 19
    val only = args.lift(1).map(_.split(",").toSet)
    val out = if java.nio.file.Files.isDirectory(java.nio.file.Path.of("bench")) then java.nio.file.Path.of("bench", "ablation.txt") else java.nio.file.Path.of("ablation.txt")
    def note(line: String): Unit =
      println(line); java.nio.file.Files.writeString(out, line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    val golds = Sample.questions.map(q => Sample.run(Query.parse(q.gold).toOption.get).toOption.get)
    val teacherSkill = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(sys.props("user.home"), ".cache", "okay-desk", s"readers-${repo.stripPrefix("local/")}-$layer.skill"))
    def score(readers: Readers.On): String =
      var right = Map("en" -> 0, "ru" -> 0)
      for (q, i) <- Sample.questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
        val got = readers.program(Sample.tables, text).flatMap(Query.parse).flatMap(Sample.run)
        if got.exists(Sample.same(_, golds(i))) then right = right.updated(lang, right(lang) + 1)
      s"en: ${right("en")}/12 | ru: ${right("ru")}/12"
    note(s"# ${java.time.LocalDateTime.now.toString.take(16)} the student by the map: sets silenced together, the teacher's skill unchanged / retrained")
    for (name, parts) <- sets if only.forall(_.contains(name)) do
      val variant = Ablate.silence(repo, parts, "no-" + name.replace("+", "-"))
      val mb = parts.map((k, _) => if k == "attn" then 3.1 else 4.7).sum
      val dir = Models.snapshot(variant).get
      val chat = Chat.load(dir); val model = Model.load(dir, mapped = true)
      try
        val t0 = System.nanoTime()
        val reading = MlxReading(chat, model, layer)
        val kept = Readers(); kept.load(teacherSkill)
        val asIs = score(Readers.On(kept, reading))
        val fresh = Readers.On(Readers(), reading); fresh.train(Pairs.many(42, 600))
        val retrained = score(fresh)
        note(f"$name%-14s (${parts.size} components, $mb%.0f MB less) | the teacher's skill: $asIs | retrained: $retrained  (${(System.nanoTime() - t0) / 1e9}%.0f s)")
      catch case e: Throwable => note(s"$name FAILED: ${e.toString.take(200)}")
      finally
        model.close()
        val cache = Models.hubCache.resolve("models--local--" + variant.stripPrefix("local/"))
        def rm(p: java.nio.file.Path): Unit = if java.nio.file.Files.isDirectory(p) then { java.nio.file.Files.list(p).forEach(rm); java.nio.file.Files.delete(p) } else java.nio.file.Files.deleteIfExists(p)
        rm(cache)
