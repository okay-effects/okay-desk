package okay.desk.bench

import java.nio.file.{Files, Path, StandardOpenOption}
import okay.desk.*
import okay.effects.json.Json
import scala.jdk.CollectionConverters.*

/**
 * THE FRAMES' TRAINING TEXTS (specs/frames.md §3): each a table (by its seed: `Domains.any`, or the grammar's), a
 * question and the frame's lines. Three sources:
 *  - the teacher's natural pairs (`Teacher pairs`) read back into frames (`Frame.of`);
 *  - the grammar's pairs (`Pairs.many`) read back the same way;
 *  - frames of the forms those have none of (a share, a change between months, keep, a week, a column against a
 *    column, a question outside), drawn over the domains' tables and said by templates in en and ru — then said
 *    again by the teacher in en/ru/uk/pl (`FRAME_TEACH=1`, two rewrites each, not judged: the frame is the
 *    template's).
 * `sbt "bench/runMain okay.desk.bench.FrameData <out.jsonl> <teacher pairs.jsonl> <synthetic count>"`
 */
object FrameData:
  final case class Item(tables: Map[String, Table], question: String, lines: Vector[String])

  private val monthsEn = Vector("January", "February", "March", "April", "May", "June")
  private val monthsGen = Vector("января", "февраля", "марта", "апреля", "мая", "июня")
  private val monthsNom = Vector("январь", "февраль", "март", "апрель", "май", "июнь")

  /** a frame of a form the programs have none of, over a domain's table, said in en and ru */
  def synthetic(seed: Long): Vector[Item] =
    val rnd = scala.util.Random(seed)
    val t = Domains.any(rnd)
    val d = Domains.all.find(_.table == t.name).get
    val tables = Map(t.name -> t)
    def pick[A](xs: Seq[A]): A = xs(rnd.nextInt(xs.size))
    val nums = d.columns.filter(c => Set(Domains.Role.Money, Domains.Role.Count, Domains.Role.Measure)(c.role))
    val cats = d.columns.filter(c => c.role == Domains.Role.Category || c.role == Domains.Role.Name)
    val hasDate = d.columns.exists(_.role == Domains.Role.Time)
    val (_, tg, _, _) = d.ru
    if nums.isEmpty || cats.isEmpty then Vector.empty
    else
      val c = pick(nums); val g = pick(cats); val v = pick(g.values)
      val (cn, cg, _) = c.ru; val (gn, _, gd) = g.ru
      val a = rnd.nextInt(5); val b = a + 1 + rnd.nextInt(5 - a)
      val (ma, mb) = (f"2026-${a + 1}%02d", f"2026-${b + 1}%02d")
      val n = c.low + rnd.nextInt(math.max(1, c.high - c.low)) * 3
      val forms = Vector("share", "share-count", "keep", "cannot") ++
        (if hasDate then Vector("change", "change-group", "month", "week") else Vector.empty) ++
        (if nums.size > 1 then Vector("columns") else Vector.empty)
      val head = Vector(s"table: ${t.name}")
      pick(forms) match
        case "share" =>
          val lines = head ++ Vector(s"answer: share sum ${c.name}", s"part: ${g.name} = $v", Frame.end)
          Vector(Item(tables, s"What share of the total ${c.name} is $v?", lines),
            Item(tables, s"Какая доля $cg приходится на $v?", lines))
        case "share-count" =>
          val lines = head ++ Vector("answer: share count", s"part: ${g.name} = $v", Frame.end)
          Vector(Item(tables, s"What part of the ${t.name} are $v?", lines),
            Item(tables, s"Какая часть $tg — $v?", lines))
        case "change" if hasDate =>
          val lines = head ++ Vector(s"answer: change sum ${c.name}", s"from: date month $ma", s"to: date month $mb",
            Frame.end)
          Vector(Item(tables, s"How did the total ${c.name} change from ${monthsEn(a)} to ${monthsEn(b)}?", lines),
            Item(tables, s"Как изменилась сумма $cg с ${monthsGen(a)} по ${monthsNom(b)}?", lines))
        case "change-group" if hasDate =>
          val lines = head ++ Vector(s"answer: change sum ${c.name}", s"from: date month $ma", s"to: date month $mb",
            s"group: ${g.name}", "order: answer desc", "limit: 1", Frame.end)
          Vector(Item(tables, s"Which ${g.name} grew the most in ${c.name} from ${monthsEn(a)} to ${monthsEn(b)}?",
            lines), Item(tables, s"По какому $gd $cn выросла сильнее всего с ${monthsGen(a)} по ${monthsNom(b)}?",
            lines))
        case "keep" =>
          val lines = head ++ Vector(s"answer: sum ${c.name}", s"group: ${g.name}", s"keep: answer > $n", Frame.end)
          Vector(Item(tables, s"Which ${g.name} have a total ${c.name} above $n?", lines),
            Item(tables, s"У каких значений «$gn» сумма $cg больше $n?", lines))
        case "week" =>
          val lines = head ++ Vector(s"answer: sum ${c.name}", s"group: ${g.name}", "group: week(date)",
            s"keep: answer > $n", Frame.end)
          Vector(Item(tables, s"Which ${g.name} had more than $n ${c.name} in a single week?", lines),
            Item(tables, s"Где за одну неделю набралось больше $n ($cg), по $gd?", lines))
        case "month" if hasDate =>
          val lines = head ++ Vector(s"answer: sum ${c.name}", "group: month(date)", Frame.end)
          Vector(Item(tables, s"Total ${c.name} by month.", lines), Item(tables, s"Сумма $cg по месяцам.", lines))
        case "columns" =>
          val o = pick(nums.filter(_ != c))
          val lines = head ++ Vector("answer: rows", s"where: ${c.name} > column ${o.name}", Frame.end)
          Vector(Item(tables, s"Rows where ${c.name} is greater than ${o.name}.", lines),
            Item(tables, s"Строки, где $cn больше, чем ${o.ru._1}.", lines))
        case _ =>
          val lines = head ++ Vector("answer: cannot", Frame.end)
          val (en, ru) = pick(Vector(
            (s"Why did the ${c.name} go up?", s"Почему $cn выросла?"),
            (s"What will the ${c.name} be next month?", s"Какой будет $cn в следующем месяце?"),
            (s"Will $v be more popular next year?", s"Станет ли $v популярнее в следующем году?"),
            (s"Why is $v so expensive?", s"Почему $v такой дорогой?"),
            (s"Should I choose $v?", s"Стоит ли мне выбрать $v?")))
          Vector(Item(tables, en, lines), Item(tables, ru, lines))

  def json(i: Item, seed: Long, source: String): String =
    Json.Obj(Vector("seed" -> Json.num(seed), "source" -> Json.Str(source), "question" -> Json.Str(i.question),
      "lines" -> Json.Arr(i.lines.map(Json.Str.apply)))).render

  def main(args: Array[String]): Unit =
    val out = Path.of(args(0)); val teacherPairs = args.lift(1); val n = args.lift(2).map(_.toInt).getOrElse(600)
    Files.deleteIfExists(out)
    def write(line: String) = Files.writeString(out, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    var counts = Map.empty[String, Int].withDefaultValue(0)
    // the teacher's natural pairs, read back into frames (their tables: the domains', by seed)
    for p <- teacherPairs.toVector.flatMap(f => TeacherBench.load(Path.of(f))) do
      val tables = Teacher.point(p.seed)._1
      Query.parse(p.program).toOption.flatMap(Frame.of(_, tables)).foreach { lines =>
        write(json(Item(tables, p.question, lines), p.seed, "teacher")); counts += "teacher" -> (counts("teacher") + 1) }
    // the grammar's pairs: their tables written out with them (generic tables, not the domains')
    val grammar = okay.desk.skill.Pairs.many(42, 1500).flatMap(p =>
      Query.parse(p.program).toOption.flatMap(Frame.of(_, p.tables)).map(l => Item(p.tables, p.question, l)))
    val gout = Path.of(out.toString.stripSuffix(".jsonl") + "-grammar.jsonl")
    Files.write(gout, grammar.map(i => Json.Obj(Vector("tables" -> Json.Arr(i.tables.values.toVector.map(t =>
      Json.Str(t.name + "\n" + (t.columns +: t.rows.map(_.map(_.text))).map(_.mkString("\t")).mkString("\n")))),
      "question" -> Json.Str(i.question), "lines" -> Json.Arr(i.lines.map(Json.Str.apply)))).render).asJava)
    counts += "grammar" -> grammar.size
    // the forms they have none of
    val teach = sys.env.get("FRAME_TEACH").contains("1")
    val m = Teacher.Model(sys.env.getOrElse("TEACHER_URL", "http://127.0.0.1:8090/v1"),
      sys.env.getOrElse("TEACHER_MODEL", "mlx-community/Qwen3.6-35B-A3B-4bit"))
    val langs = Vector("en", "ru", "uk", "pl")
    for seed <- 1L to n.toLong do
      val items = synthetic(seed * 104729)
      items.foreach { i => write(json(i, seed * 104729, "template")); counts += "template" -> (counts("template") + 1) }
      if teach && items.nonEmpty then
        val lang = langs((seed % 4).toInt)
        val said = Teacher.rewrites(m, items.head.tables, items.head.question, lang, k = 2)
        said.foreach { q =>
          write(json(items.head.copy(question = q), seed * 104729, "taught")); counts += "taught" -> (counts("taught") + 1) }
      if seed % 50 == 0 then println(s"synthetic $seed of $n: ${counts.toVector.sorted.mkString(", ")}")
    println(s"written $out and $gout: ${counts.toVector.sorted.mkString(", ")}")
