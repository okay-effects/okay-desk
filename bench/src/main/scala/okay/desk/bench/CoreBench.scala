package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.{Chat, Model}
import okay.effects.json.Json
import okay.desk.*
import okay.desk.know.FactReader
import okay.desk.skill.{Pairs, Readers}
import okay.desk.statement.Reader
import okay.desk.tasks.Drawn

/**
 * THE TEXTS A CORE READS (specs/core-next.md): the three kinds the page asks a core to read — the desk's data prompts
 * with their programs, the logic tasks as the statement reader states them, the fact sentences as the fact reader
 * reads them — each as the model's tokens. Training texts from the training splits only; held-out ones from the
 * test splits. The sealed set is never drawn here.
 */
object CoreTexts:
  private def msg(role: String, content: String) = Json.obj("role" -> Json.Str(role), "content" -> Json.Str(content))
  private def prompt(chat: Chat, system: String, user: String): Array[Int] =
    chat.prompt(Vector(msg("system", system), msg("user", user)), Nil, Map("enable_thinking" -> false))

  /** `ru`: the percent of the pairs in Russian (None: each language by chance) */
  def data(chat: Chat, seed: Long, n: Int, ru: Option[Int] = None): Vector[Array[Int]] = ru match
    case None => Pairs.many(seed, n).map(Student.text(chat, _))
    case Some(p) => (Pairs.many(seed, n * p / 100, Some(true)) ++ Pairs.many(seed + 1, n - n * p / 100, Some(false)))
      .map(Student.text(chat, _))
  /** the Russian statements alone */
  def tasksRu(chat: Chat, split: Drawn.Split, n: Int): Vector[Array[Int]] =
    Drawn.items(split, n).flatMap(_.task.stated.toVector).map(r => prompt(chat, Reader.system, r.ru.text))
  def tasks(chat: Chat, split: Drawn.Split, n: Int): Vector[Array[Int]] =
    Drawn.items(split, n).flatMap(_.task.stated.toVector).flatMap(r => Vector(r.en.text, r.ru.text))
      .map(prompt(chat, Reader.system, _))
  def facts(chat: Chat, held: Boolean, n: Int): Vector[Array[Int]] =
    val (forms, seed, names) =
      if held then (FactReaderBench.test, 3, Vector("Zeno", "South Port", "Лена", "Нора"))
      else (FactReaderBench.train, 1, Vector("Moby", "Alice", "Bob", "New York", "Белла", "Моби", "green box", "whale"))
    FactReaderBench.pairs(forms, n, seed, names).map(p => prompt(chat, FactReader.System, p.text))

  /** the held-out texts, by kind */
  def heldOut(chat: Chat): Vector[(String, Vector[Array[Int]])] = Vector(
    "data" -> data(chat, 777_777L, 80), "tasks" -> tasks(chat, Drawn.Split.Test, 40), "facts" -> facts(chat, true, 60))

/**
 * THE COMPARISON OF CORES, part one (the rest by `scripts/core-bench.sh`): for a model directory and its last layer —
 *
 *  - the contract: the z-scored squared distance of its last state to the teacher's state at 19 (cut19), per kind of
 *    held-out text (the teacher's own spread per dimension, as `Distill` trains it);
 *  - the sample with the teacher's skill file unchanged: the 13 questions in English and Russian, right of 13.
 *
 * `sbt "bench/runMain okay.desk.bench.CoreBench <model dir> <layers>"`
 */
object CoreBench:
  val teacherRepo = "local/Qwen3-0.6B-4bit-cut19-vocab"
  val skillFile: Path = Path.of(sys.props("user.home"), ".cache", "okay-desk", "readers-Qwen3-0.6B-4bit-cut19-19.skill")

  def main(args: Array[String]): Unit =
    val dir = Path.of(args(0))
    val layers = args(1).toInt
    val chat = Chat.load(dir)
    val model = Model.load(dir)
    // LORA: an adapter put on the model as trained (unmerged) — the core shipped as a 4-bit base and its adapter
    sys.env.get("LORA").foreach(p => mlx.llm.Lora.attach(model.asInstanceOf[mlx.llm.Adaptable], p))
    val (tChat, teacher) = Student.load(teacherRepo)
    val tRead = MlxReading(tChat, teacher, 19)
    val sRead = MlxReading(chat, model, layers)
    println(s"core: $dir ($layers layers), ${Files.size(dir.resolve("model.safetensors")) / 1048576} MB of weights")
    // THE CONTRACT
    for (kind, texts) <- CoreTexts.heldOut(tChat) do
      val pairs = texts.map { ids =>
        val t = tRead.states(ids, ids.indices.toVector)
        val s = sRead.states(ids, ids.indices.toVector)
        (t, s)
      }
      val all = pairs.flatMap(_._1)
      val d = all.head.length
      val mean = Array.tabulate(d)(k => all.map(_(k).toDouble).sum / all.size)
      val std = Array.tabulate(d)(k => math.sqrt(all.map(v => (v(k) - mean(k)) * (v(k) - mean(k))).sum / all.size).max(1e-3))
      var (sum, count) = (0.0, 0L)
      for (t, s) <- pairs; (tv, sv) <- t.zip(s); k <- 0 until d do
        val z = (sv(k) - tv(k)) / std(k); sum += z * z; count += 1
      println(f"contract $kind%-5s: z-MSE ${sum / count}%.4f over ${texts.size} texts")
    // THE SAMPLE with the teacher's skill unchanged
    val skill = Readers(); skill.load(Files.readAllBytes(skillFile))
    val on = Readers.On(skill, sRead)
    val golds = Sample.questions.map(q => Sample.run(Query.parse(q.gold).toOption.get).toOption.get)
    var right = Map("en" -> 0, "ru" -> 0)
    for (q, i) <- Sample.questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
      val got = on.program(Sample.tables, text).flatMap(Query.parse).flatMap(Sample.run)
      if got.exists(Sample.same(_, golds(i))) then right = right.updated(lang, right(lang) + 1)
    println(s"sample, the teacher's skill: en ${right("en")}/${Sample.questions.size}, " +
      s"ru ${right("ru")}/${Sample.questions.size}")
    model.close(); teacher.close()
