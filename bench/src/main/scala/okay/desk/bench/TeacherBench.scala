package okay.desk.bench

import java.nio.file.{Files, Path}
import mlx.llm.{Chat, Model, Models}
import okay.desk.*
import okay.desk.skill.*
import okay.effects.json.{Json, JsonText}
import scala.jdk.CollectionConverters.*

/**
 * THE TEACHER'S PAIRS, MEASURED (specs/teacher.md §3): readers trained on the space × the phrases (A) against the
 * same plus the teacher's pairs (B) — measured by constructor on held-out TEACHER pairs (natural wording, the
 * seeds the training never saw: seed % 5 == 0) and on the sample's 13 in English and Russian. The readers are
 * trained in memory and never saved: the skill file in ~/.cache/okay-desk is the page's, not this bench's.
 * TEACHER_PAIRS (default data/teacher-pairs.jsonl), TEACHER_READER_MODEL, DESK_LAYER, DESK_PAIRS.
 */
object TeacherBench:
  final case class Taught(seed: Long, lang: String, question: String, program: String)

  def load(path: Path): Vector[Taught] =
    Files.readAllLines(path).asScala.toVector.flatMap(l => JsonText.parse(l).toOption).flatMap { j =>
      for
        seed <- j.field("seed").collect { case Json.Num(n) => n.toLong }
        lang <- j.field("lang").collect { case Json.Str(s) => s }
        q <- j.field("question").collect { case Json.Str(s) => s }
        p <- j.field("program").collect { case Json.Str(s) => s }
      yield Taught(seed, lang, q, p)
    }

  def pair(t: Taught): Pairs.Pair = Pairs.Pair(Teacher.schema(t.seed), t.question, t.program)

  def main(args: Array[String]): Unit =
    val taught = load(Path.of(sys.env.getOrElse("TEACHER_PAIRS", "data/teacher-pairs.jsonl")))
    val (test, train) = taught.partition(_.seed % 5 == 0)
    println(s"teacher pairs: ${taught.size} (train ${train.size}, held-out ${test.size}); " +
      taught.groupBy(_.lang).toVector.sortBy(_._1).map((l, v) => s"$l ${v.size}").mkString(", "))
    val repo = sys.env.getOrElse("TEACHER_READER_MODEL", "local/Qwen3-0.6B-4bit-cut19")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true)
    val layer = sys.env.getOrElse("DESK_LAYER", "19").toInt
    val base = Pairs.many(42, sys.env.getOrElse("DESK_PAIRS", "1500").toInt)
    val golds = Sample.questions.map(q => Sample.run(Query.parse(q.gold).toOption.get).toOption.get)
    def same(a: Table, b: Table) = a.rows.map(_.map(_.text).sorted).sortBy(_.mkString) ==
      b.rows.map(_.map(_.text).sorted).sortBy(_.mkString)
    for (name, pairs) <- Vector("A: the space × the phrases" -> base,
        "B: + the teacher's pairs (twice)" -> (base ++ train.map(pair) ++ train.map(pair))) do
      val t0 = System.nanoTime()
      val r = Readers.On(Readers(), MlxReading(chat, model, layer))
      r.train(pairs)
      println(f"\n$name — ${pairs.size} pairs, trained in ${(System.nanoTime() - t0) / 1e9}%.0f s")
      println("held-out teacher pairs, by constructor:")
      r.measureByConstructor(test.map(pair))
      for lang <- Vector("ru", "en", "uk") do
        val some = test.filter(_.lang == lang)
        if some.nonEmpty then
          val right = some.count(t => r.program(Teacher.schema(t.seed), t.question).flatMap(Query.parse).toOption
            .zip(Query.parse(t.program).toOption).exists((a, b) => run(Teacher.schema(t.seed), a).toOption
              .zip(run(Teacher.schema(t.seed), b).toOption).exists(same)))
          println(s"  held-out $lang: $right of ${some.size} answered as the program")
      var en = 0; var ru = 0
      def right(text: String, i: Int) =
        r.program(Sample.tables, text).flatMap(Query.parse).flatMap(Sample.run).exists(same(_, golds(i)))
      for (q, i) <- Sample.questions.zipWithIndex do
        if right(q.en, i) then en += 1
        if right(q.ru, i) then ru += 1
      println(s"the sample: en $en/${Sample.questions.size}, ru $ru/${Sample.questions.size}")
    model.close()

  def run(tables: Map[String, Table], q: Query): Either[String, Table] = q match
    case r: Query.Relational => Exact.run(tables, r)
    case l: Query.Logical => Logic.run(tables, l)
