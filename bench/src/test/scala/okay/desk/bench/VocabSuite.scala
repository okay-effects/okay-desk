package okay.desk.bench

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.desk.Sample
import okay.desk.skill.Pairs

/** THE SMALLER VOCABULARY'S LAW (`Vocab`): the desk's prompts (pairs never used to choose it) and the bench's questions
 * encode to the same tokens renumbered, and the states at the skill's layer are the same, exactly; held-out prose
 * (topics the vocabulary never saw) — how many of its words split otherwise. Runs when both models are in the cache */
class VocabSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  private val repo = "local/Qwen3-0.6B-4bit-cut19"

  test("the same tokens renumbered, the same states; held-out prose measured"):
    val (a, b) = (Models.snapshot(repo), Models.snapshot(s"$repo-vocab"))
    assume(a.isDefined && b.isDefined, s"$repo and its -vocab are not both in the cache")
    val map = Files.readAllLines(b.get.resolve("vocab-map.tsv")).asScala.map(_.split('\t')).map(x =>
      x(0).toInt -> x(1).toInt).toMap
    val (ca, cb) = (Chat.load(a.get), Chat.load(b.get))
    val (ma, mb) = (Model.load(a.get).asInstanceOf[Qwen3], Model.load(b.get).asInstanceOf[Qwen3])
    val (ra, rb) = (MlxReading(ca, ma, 19), MlxReading(cb, mb, 19))
    val prompts = Pairs.many(77, 40).map(p => (okay.desk.Prompt.system(p.tables), p.question)) ++
      Sample.questions.flatMap(q => Seq(q.en, q.ru)).map(q => (okay.desk.Prompt.system(Sample.tables), q))
    var worst = 0f
    for (sys, q) <- prompts do
      val (ia, ib) = (ra.prompt(sys, q), rb.prompt(sys, q))
      assertEquals(ib.toVector, ia.toVector.map(map), s"tokens of: $q")
      val at = Vector(0, ia.length / 2, ia.length - 1)
      for (x, y) <- ra.states(ia, at).zip(rb.states(ib, at)) do
        worst = worst max x.indices.map(i => math.abs(x(i) - y(i))).max
    println(s"${prompts.size} prompts: the same tokens; the states' largest difference $worst")
    assertEquals(worst, 0f)
    // held-out prose: the words split otherwise
    val held = Files.list(VocabCorpus.dir).iterator.asScala.filter(_.getFileName.toString.startsWith("held-"))
      .map(Files.readString).toVector
    for lang <- Seq("en", "ru") do
      val words = held.filter(t => (lang == "ru") == t.exists(c => c >= 'а' && c <= 'я'))
        .flatMap(_.split("\\s+")).filter(_.nonEmpty).distinct
      val changed = words.count(w => cb.tokenizer.encode(" " + w).toVector != ca.tokenizer.encode(" " + w).toVector.map(map.getOrElse(_, -1)))
      val (ta, tb) = (words.map(w => ca.tokenizer.encode(" " + w).length).sum,
        words.map(w => cb.tokenizer.encode(" " + w).length).sum)
      println(f"held-out $lang: ${words.size} distinct words, $changed split otherwise " +
        f"(${changed * 100.0 / words.size}%.1f%%), tokens ${ta} → ${tb}")
    ma.close()
    mb.close()
