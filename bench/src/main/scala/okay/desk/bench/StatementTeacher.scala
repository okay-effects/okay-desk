package okay.desk.bench

import java.nio.file.{Files, Path, StandardOpenOption}
import okay.desk.statement.{Con, Faults, Marked, Problem, Sentences, Writing}
import okay.desk.tasks.Drawn
import okay.effects.json.{Json, JsonText}
import scala.jdk.CollectionConverters.*

/**
 * A CORPUS OF LOGIC TASKS WRITTEN BY A TEACHER, JUDGED EXACTLY (specs/formalization.md §15). A drawn task of the
 * training split, its sentences marked (`Marked`), is given to the local large model, which writes it again as
 * another author would — in English, Russian, Ukrainian or Polish — keeping every mark's key. The rewrite's gold
 * statement is then known with no reader: the same frames over the same keys. It is kept only when
 *  - every line keeps its marks (the same keys, once each) and stays one sentence;
 *  - the exact side gives the rewrite's statement the task's result;
 *  - the JUDGE — the same large model, given the marks but not the frames — reads every line of the rewrite as
 *    the gold says (up to one mirror of the whole order, a reader's convention). How often it reads the
 *    original as its gold is its accuracy, recorded beside every verdict.
 * A rewrite rejected is data lost; a rewrite accepted wrongly is a wrong example: the judge errs on the side of
 * losing. Build time only; the model is local (okay-mlx's gateway).
 */
object StatementTeacher:
  val languages = Map("en" -> "English", "ru" -> "Russian", "uk" -> "Ukrainian", "pl" -> "Polish")

  private def teacherRule(language: String) = s"""You rewrite logic puzzles as a different person would write them,
in $language. The input is numbered lines `N| sentence`. Some words are marked `[key|words]`: they name a thing (a
person, a value) — or `[#|3]`, a number. Output exactly the same numbered lines `N| rewritten sentence`, one for each
input line, and nothing else.
Rules:
- Keep the meaning of every line exactly: the same facts, the same relation, the same direction (who is before or
  after, left or right, taller or shorter, ahead or behind), nothing added, nothing left out.
- Do NOT translate the input sentence by sentence. Write each line as you would, in a construction of your own:
  turn it around ("A lives left of B" — "B has A somewhere on the left"), make the thing the subject or the
  object, use a verb the input does not use, say "neighbours" as "next door", "side by side", "adjacent";
  describe the setting in your own way. Vary the constructions from line to line, the way a puzzle book in
  $language would.
- Every marker of an input line appears in your line exactly once with the SAME key, as `[key|words]`, where words
  are how that thing is named IN $language, in the form your sentence needs (case, number). Translate the words
  inside the markers too; only brand names stay as they are. People's names: their usual $language form. Never
  write a marked thing without its marker; add no markers of your own; never change a key.
- A number stays a number with its marker: `[#|3]`.
- One sentence per line. A list keeps every item. A line with no markers stays without them. Never move a thing
  from one line to another.
- A line that begins with a clue number like `4. ` keeps it."""

  private val judgeRule = """You read a logic puzzle and write down what each numbered line states, as relations over
the marked things. A marked thing is `[key|words]`: use its KEY only. A number `[#|3]` is written "#3".
The things stand on positions 1..n as the puzzle numbers them (houses from the left, places in a race, floors from
the bottom, order of arrival). For a scale with no numbering (height, weight, age), a smaller position is a smaller
amount: shorter, lighter, younger.
Relations:
["List", k1, k2, ...]   the line lists things of one kind (each of them on a different position)
["Same", a, b]          a and b are on the same position (the same person, house, place)
["Differ", a, b]        a and b are not on the same position
["Either", a, b, c]     a is with b or with c
["Before", a, b]        a is on a smaller position than b, somewhere
["After", a, b]         a is on a larger position than b, somewhere
["JustBefore", a, b]    a is on the position right before b
["JustAfter", a, b]     a is on the position right after b
["Beside", a, b]        a and b are on neighbouring positions, in either order
["At", a, "#n"]         a is on position n
["Middle", a]           a is on the middle position
["Ask", kind, a]        the question asks which thing goes with a; kind is the kind of the ANSWER — the part
                        before "/" of the keys of the things that could answer it (never a's own kind):
                        "Which pet does [nation/Dane|the Dane] keep?"   ["Ask", "pet", "nation/Dane"]
                        "Who keeps the [pet/zebra|zebra]?"              ["Ask", "nation", "pet/zebra"]
                        (a person is the answer: the kind of the people's keys in this task)
["First"]               the question asks what is on the smallest position; ["Last"]: on the largest
["Whether"]             the question asks whether the next relation of the same line is so
The first argument is the thing the sentence says something about, the second what it is compared with:
  "[a|A] lives immediately to the left of [b|B]"  ["JustBefore", "a", "b"]
  "[a|A] lives right after [b|B]"                 ["JustAfter", "a", "b"]
  "[a|A] lives somewhere to the right of [b|B]"   ["After", "a", "b"]    (no "immediately": not Just…)
  "[a|A] lives in house [#|3]"                    ["At", "a", "#3"]      (a number is a position: always At)
  "[a|A] and [b|B] are neighbours"                ["Beside", "a", "b"]
  "[a|A] does not drink [b|B]"                    ["Differ", "a", "b"]
A fact is ONE relation: never write several alternatives for it ("neighbours" is Beside, not JustBefore and
JustAfter). A question line has its question relation: "Who …?" or "Which …?" is Ask.
The lines before the numbered ones are the setting: read them for the order, write nothing for them.
Write EVERY numbered line, even one with nothing to say ([]).
Output one JSON object {"1": [relations of line 1], "2": [...], ...} and nothing else."""

  // ---- the gold as the judge would write it

  type Relation = Vector[String]

  /** the relations a task states, line by line, in the judge's words */
  def gold(sentences: Vector[Marked.Sentence]): Vector[Vector[Relation]] =
    val lists = sentences.flatMap(s =>
      s.frames.filter(_.con == Con.Attribute).map(f => Marked.keys(s.words)(f.marks.head)))
    sentences.map { s =>
      val keys = Marked.keys(s.words)
      s.frames.map { f =>
        val args = f.marks.map(keys)
        f.con match
          case Con.Attribute => "List" +: args
          case Con.Ask => Vector("Ask", lists.lift(f.picks.headOption.getOrElse(0)).getOrElse("?")) ++ args
          case c => c.toString +: args } }

  private val mirror = Map("Before" -> "After", "After" -> "Before", "JustBefore" -> "JustAfter",
    "JustAfter" -> "JustBefore", "First" -> "Last", "Last" -> "First")

  /**
   * a relation as compared: symmetric ones in one order, a list as a set, a list named by any of its items, a thing
   * "the same as" a number at that position — what means one thing is written one way
   */
  private def canonical(r: Relation, middle: Option[Int]): Relation = r match
    case Vector("Same", a, n) if n.startsWith("#") => Vector("At", a, n)
    case Vector("Same", n, a) if n.startsWith("#") => Vector("At", a, n)
    case Vector("Middle", a) if middle.nonEmpty => Vector("At", a, s"#${middle.get}")
    // "a after b" is "b before a": one direction is written
    case Vector("After", a, b) => Vector("Before", b, a)
    case Vector("JustAfter", a, b) => Vector("JustBefore", b, a)
    case Vector("Same" | "Differ" | "Beside", a, b) => r.take(1) ++ Vector(a, b).sorted
    case Vector("Either", a, b, c) => Vector("Either", a) ++ Vector(b, c).sorted
    case "List" +: items => "List" +: items.sorted
    // the question names the KIND asked: a key's part before "/"
    case Vector("Ask", l, a) => Vector("Ask", l.takeWhile(_ != '/'), a)
    case _ => r

  private val arity = Map("Same" -> 2, "Differ" -> 2, "Either" -> 3, "Before" -> 2, "After" -> 2,
    "JustBefore" -> 2, "JustAfter" -> 2, "Beside" -> 2, "At" -> 2, "Middle" -> 1, "Ask" -> 2, "First" -> 0,
    "Last" -> 0, "Whether" -> 0)

  /**
   * a reading in its canonical form, line by line: what is malformed left out; "right before and right after" of
   * one pair is Beside; a Differ that an order of the same pair implies is not a fact of its own
   */
  def normal(x: Vector[Vector[Relation]], gold: Vector[Vector[Relation]]): Vector[Vector[Relation]] =
    val n = gold.flatten.collectFirst { case "List" +: items => items.size }
    val middle = n.filter(_ % 2 == 1).map(k => (k + 1) / 2)
    x.map { line =>
      val rs = line.filter(r => r.headOption.exists(h => h == "List" || arity.get(h).contains(r.size - 1)))
        .map(canonical(_, middle)).distinct
      def pair(r: Relation): Set[String] = r.drop(1).toSet
      val ordered = rs.filter(r => Set("Before", "JustBefore", "Beside").contains(r.head)).map(pair)
      val both = rs.filter(_.head == "JustBefore").map(pair).groupBy(identity).collect {
        case (p, ps) if ps.size == 2 => p }.toSet
      val merged = rs.filterNot(r => r.head == "JustBefore" && both(pair(r))) ++
        both.toVector.map(p => "Beside" +: p.toVector.sorted)
      merged.filterNot(r => r.head == "Differ" && ordered.contains(pair(r))).distinct.sortBy(_.mkString(" ")) }

  def same(a: Vector[Vector[Relation]], b: Vector[Vector[Relation]], gold: Vector[Vector[Relation]]): Boolean =
    a.size == b.size && normal(a, gold) == normal(b, gold)

  /** a reading with what is not over the task's marks left out (the judge sometimes lists "house" in an intro) */
  def only(x: Vector[Vector[Relation]], keys: Set[String]): Vector[Vector[Relation]] =
    def number(a: String): Boolean = a.startsWith("#") && a.length > 1 && a.drop(1).forall(_.isDigit)
    // a question's kind is a key's part, not a key
    x.map(_.filter(r => r.zipWithIndex.drop(1).forall((a, i) => keys(a) || number(a) || r.head == "Ask" && i == 1)))

  def mirrored(x: Vector[Vector[Relation]]): Vector[Vector[Relation]] =
    x.map(_.map(r => r.headOption.flatMap(mirror.get).fold(r)(m => m +: r.tail)))

  /** a result in the things' keys: each language names them with its own words */
  def keyed(s: Writing.Stated, result: Either[String, Vector[String]]): Either[String, Vector[String]] =
    def key(name: String): String =
      s.keys.collectFirst { case (words, k) if okay.desk.statement.Statement.same(words, name) => k }.getOrElse(name)
    result.map(_.map(_.split('|').toVector.map(key).sorted.mkString("|")).sorted)

  /** where a reading and the gold differ, line by line (for the judge's errors) */
  def differences(r: Vector[Vector[Relation]], truth: Vector[Vector[Relation]]): Vector[String] =
    normal(r, truth).zip(normal(truth, truth)).zipWithIndex.collect { case ((a, b), i) if a != b =>
      s"${i + 1}: ${a.map(_.mkString("(", " ", ")")).mkString} for ${b.map(_.mkString("(", " ", ")")).mkString}" }

  // ---- asking and reading answers

  /** the numbered lines of an answer, by number */
  def numbered(answer: String): Map[Int, String] =
    answer.linesIterator.flatMap { l =>
      val m = "^\\s*(\\d+)\\s*\\|\\s?(.*)$".r.findFirstMatchIn(l)
      m.map(x => x.group(1).toInt -> x.group(2).trim) }.toMap

  /**
   * the judge's reading: the lines that state nothing are given first as the setting, unnumbered — numbered, a
   * judge shifted its answers by one — and the others numbered in turn
   */
  /** the judge's last answer as it gave it (to see what did not parse) */
  var lastAnswer = ""
  def judged(m: Teacher.Model, sentences: Vector[Marked.Sentence]): Option[Vector[Vector[Relation]]] =
    val stating = sentences.indices.filter(sentences(_).frames.nonEmpty).toVector
    val setting = sentences.indices.filterNot(stating.contains).map(sentences(_).words.trim).mkString("\n")
    val asked = setting + "\n\n" + stating.zipWithIndex.map((i, n) => s"${n + 1}| ${sentences(i).words.trim}")
      .mkString("\n")
    // a judge that writes a thing's words, or a key's part after "/", for its key: the key it means
    val marks = sentences.flatMap(s => "\\[(!?)([^\\]|]*)\\|([^\\]]*)\\]".r.findAllMatchIn(s.words)
      .map(x => (x.group(2), x.group(3)))).filter(_._1 != "#")
    val byWords: Map[String, String] = (marks.map((k, w) => w.toLowerCase -> k) ++
      marks.map((k, _) => k.drop(k.indexOf('/') + 1).toLowerCase -> k)).toMap
    def key(s: String): String =
      if marks.exists(_._1 == s) then s
      else if s.nonEmpty && s.forall(_.isDigit) then "#" + s
      else if !byWords.contains(s.toLowerCase) && "\\d+$".r.findFirstIn(s).nonEmpty then
        "#" + "\\d+$".r.findFirstIn(s).get
      else byWords.getOrElse(s.toLowerCase, s)
    lastAnswer = m.ask(judgeRule, asked, 3000, temperature = 0)
    Teacher.json(lastAnswer).collect { case o: Json.Obj => o }.map { o =>
      val read = stating.zipWithIndex.map((i, n) => i -> o.field((n + 1).toString).collect { case Json.Arr(rs) =>
        rs.collect { case Json.Arr(xs) => xs.zipWithIndex.collect {
          case (Json.Str(s), 0) => s
          case (Json.Str(s), 1) if xs.headOption.contains(Json.Str("Ask")) => s
          case (Json.Str(s), _) => key(s)
          case (Json.Num(v), _) => "#" + v.toBigInt.toString } } }.getOrElse(Vector.empty)).toMap
      sentences.indices.toVector.map(read.getOrElse(_, Vector.empty)) }

  /**
   * a teacher's line as the sentence `s`: it keeps the sentence's marks and is one sentence, else why not. A number
   * the teacher marked where the original has none ("floors 1 to 4" in a setting) is plain words again.
   */
  def rewrittenLine(s: Marked.Sentence, written: String): Either[String, Marked.Sentence] =
    val was = Marked.keys(s.words)
    val line = "\\[([^\\]|]*)\\|([^\\]]*)\\]".r.replaceAllIn(written, x =>
      java.util.regex.Matcher.quoteReplacement(
        if x.group(1) == "#" && !was.contains("#" + x.group(2).trim) then x.group(2) else x.matched))
    val now = Marked.keys(line)
    val plain = Writing.plain(Writing.noted(line))
    if was.sorted != now.sorted then Left(s"marks ${was.mkString(",")} became ${now.mkString(",")}")
    else if line.isEmpty || Sentences.of(plain).size != 1 then Left("not one sentence")
    else
      // the frames over the same keys at their new places; a key said twice takes its occurrences in turn
      val used = scala.collection.mutable.Map.empty[String, Int]
      val index = was.map { k =>
        val n = used.getOrElse(k, 0)
        used(k) = n + 1
        now.indices.filter(now(_) == k)(n) }
      val frames = s.frames.map(f => f.copy(marks = f.marks.map(index)))
      val gap = s.words.drop(s.words.trim.length + s.words.indexWhere(!_.isWhitespace).max(0))
      val lead = s.words.takeWhile(_.isWhitespace)
      Right(Marked.Sentence(lead + line + gap, frames))

  /** a teacher's lines as the task's sentences, all of them, else the first reason why not */
  def rewritten(original: Vector[Marked.Sentence], answer: String): Either[String, Vector[Marked.Sentence]] =
    val got = numbered(answer)
    original.zipWithIndex.foldLeft[Either[String, Vector[Marked.Sentence]]](Right(Vector.empty)) {
      case (Left(why), _) => Left(why)
      case (Right(done), (s, i)) =>
        got.get(i + 1).toRight(s"line ${i + 1} missing").flatMap(rewrittenLine(s, _))
          .left.map(why => s"line ${i + 1}: $why").map(done :+ _) }

  /**
   * THE LOOP OF REPAIR: the teacher writes the task; each line is kept when its marks hold, its words are in the
   * language, and the judge reads it as the gold says (in one direction of the order for the whole task); the
   * lines not kept are written again — the kept ones given as they are — up to `rounds` times. The task is kept
   * when every line is, and the exact side gives it the task's result.
   */
  def teach(m: Teacher.Model, original: Vector[Marked.Sentence], task: okay.desk.tasks.Task.Read, language: String,
      truth: Vector[Vector[Relation]], result: Either[String, Vector[String]], rounds: Int = 3)
      : Either[String, Vector[Marked.Sentence]] =
    val keys = original.flatMap(s => Marked.keys(s.words)).toSet
    // the teacher sees opaque keys (t1, t2…): a key that reads as a word ("tenant/Egor") it translated
    val things = original.flatMap(s => "\\[!?([^\\]|#]*)\\|".r.findAllMatchIn(s.words).map(_.group(1))).distinct
    val (toId, fromId) = (things.zipWithIndex.map((k, i) => k -> s"t${i + 1}").toMap,
      things.zipWithIndex.map((k, i) => s"t${i + 1}" -> k).toMap)
    def swap(words: String, by: Map[String, String]): String =
      "\\[(!?)([^\\]|]*)\\|".r.replaceAllIn(words, x =>
        java.util.regex.Matcher.quoteReplacement(s"[${x.group(1)}${by.getOrElse(x.group(2), x.group(2))}|"))
    def lines(ss: Vector[Marked.Sentence]): String =
      ss.zipWithIndex.map((s, i) => s"${i + 1}| ${swap(s.words.trim, toId)}").mkString("\n")
    var kept = Vector.fill[Option[Marked.Sentence]](original.size)(None)
    var why = "no round"
    var round = 0
    while round < rounds && kept.exists(_.isEmpty) do
      round += 1
      val open = kept.indices.filter(kept(_).isEmpty)
      val asked = if round == 1 then lines(original) else
        "Already written (keep these as they are, for the context):\n" +
          kept.zipWithIndex.collect { case (Some(s), i) => s"${i + 1}| ${swap(s.words.trim, toId)}" }.mkString("\n") +
          "\n\nWrite these lines again, in another way than before:\n" +
          open.map(i => s"${i + 1}| ${swap(original(i).words.trim, toId)}").mkString("\n")
      val got = numbered(m.ask(teacherRule(languages(language)), asked, 2400, temperature = 0.9))
        .view.mapValues(swap(_, fromId)).toMap
      val candidate = kept.zipWithIndex.map { (k, i) =>
        k.toRight("kept").orElse(got.get(i + 1).toRight("missing").flatMap(rewrittenLine(original(i), _))) }
      val whole = candidate.zipWithIndex.map((c, i) => c.toOption.getOrElse(original(i)))
      val english = untranslatedMarks(candidate.flatMap(_.toOption), task, language).toSet
      def inLanguage(s: Marked.Sentence): Boolean =
        "\\[(!?)([^\\]|]*)\\|([^\\]]*)\\]".r.findAllMatchIn(s.words).forall(x => !english(x.group(3)))
      val read = judged(m, whole).map(only(_, keys)).getOrElse(whole.map(_ => Vector.empty))
      val (direct, other) = (normal(read, truth), normal(mirrored(read), truth))
      val gold = normal(truth, truth)
      val way = if direct.zip(gold).count(_ == _) >= other.zip(gold).count(_ == _) then direct else other
      kept = candidate.zipWithIndex.map { (c, i) =>
        c.toOption.filter(s => inLanguage(s) && (original(i).frames.isEmpty || way(i) == gold(i))) }
      why = candidate.zipWithIndex.collect { case (Left(w), i) if w != "kept" => s"line ${i + 1}: $w" }.headOption
        .orElse(Option.when(english.nonEmpty)(s"marks left in English: ${english.take(3).mkString(", ")}"))
        .getOrElse("the judge reads a line otherwise")
    if kept.exists(_.isEmpty) then Left(s"after $round rounds, ${kept.count(_.isEmpty)} lines: $why")
    else
      val sentences = kept.flatten
      val again = Marked.stated(Problem.Assign, sentences)
      if keyed(again, Faults.result(again.statement, again.text)) != result then
        Left("the exact side gives another result")
      else Right(sentences)

  /**
   * the marked words a rewrite in another language left as the English said them, where the task's Russian says
   * them otherwise (a brand is the same in both, so it may stay): in Cyrillic, a mark with no Cyrillic letter; in
   * Polish, a mark equal to its English word while the Russian translates it — a person's name excepted
   */
  def untranslatedMarks(sentences: Vector[Marked.Sentence], task: okay.desk.tasks.Task.Read, language: String)
      : Vector[String] =
    if language == "en" then Vector.empty
    else
      def words(s: Writing.Stated): Map[String, String] = s.keys.map((w, k) => k -> w).toMap
      val (en, ru) = (words(task.en), words(task.ru))
      def cyrillic(w: String): Boolean = w.exists(c => c >= 'Ѐ' && c <= 'ӿ')
      val marks = sentences.flatMap(s => "\\[(!?)([^\\]|]*)\\|([^\\]]*)\\]".r.findAllMatchIn(s.words)
        .map(x => (x.group(2), x.group(3)))).filter(_._1 != "#")
      marks.collect { case (k, w) if ru.get(k).exists(cyrillic) && (
          if language == "pl" then en.get(k).exists(_.equalsIgnoreCase(w)) && en.get(k).exists(_.head.isLower)
          else !cyrillic(w)) => w }.distinct

  // ---- the run

  final case class Kept(family: String, seed: Long, split: String, language: String, sentences: Vector[Marked.Sentence])

  private def toJson(k: Kept): Json = Json.Obj(Vector("family" -> Json.Str(k.family),
    "seed" -> Json.num(k.seed.toDouble),
    "split" -> Json.Str(k.split), "language" -> Json.Str(k.language),
    "sentences" -> Json.Arr(k.sentences.map(s => Json.Obj(Vector("words" -> Json.Str(s.words),
      "frames" -> Json.Arr(s.frames.map(f => Json.Obj(Vector("con" -> Json.Str(f.con.toString),
        "picks" -> Json.Arr(f.picks.map(p => Json.num(p.toDouble))),
        "marks" -> Json.Arr(f.marks.map(p => Json.num(p.toDouble)))))))))))))

  /** the kept rewrites of a file: each its language, family, split and stated text */
  def load(path: Path): Vector[(Kept, Writing.Stated)] =
    if !Files.exists(path) then Vector.empty
    else Files.readAllLines(path).asScala.toVector.flatMap(l => JsonText.parse(l).toOption).flatMap { j =>
      j.field("sentences").map { ss =>
        val sentences = ss.list.map(s => Marked.Sentence(s("words").string, s("frames").list.map(f =>
          Writing.Said(Con.valueOf(f("con").string), 0, f("picks").list.map(_.int), f("marks").list.map(_.int)))))
        val ordered = sentences.map(inOrder)
        val kept = Kept(j("family").string, j("seed").long, j("split").string, j("language").string, ordered)
        kept -> Marked.stated(Problem.Assign, ordered) } }

  /**
   * a sentence's frames with their pieces in the text's order — the order the assembler gives roles in: a rewrite
   * that turns a clue around ("B has A just before it") states the same with the frame turned too (JustAfter of B
   * and A for JustBefore of A and B); the pieces of a symmetric frame are put in order; the frames of a sentence
   * follow one another as their pieces do
   */
  def inOrder(s: Marked.Sentence): Marked.Sentence =
    val turned = Map(Con.Before -> Con.After, Con.After -> Con.Before, Con.JustBefore -> Con.JustAfter,
      Con.JustAfter -> Con.JustBefore)
    val symmetric = Set(Con.Same, Con.Differ, Con.Beside)
    val frames = s.frames.map { f =>
      f.marks match
        case Vector(a, b) if a > b && turned.contains(f.con) => f.copy(con = turned(f.con), marks = Vector(b, a))
        case Vector(a, b) if a > b && symmetric(f.con) => f.copy(marks = Vector(b, a))
        case Vector(a, b, k) if f.con == Con.Between && a > b => f.copy(marks = Vector(b, a, k))
        case Vector(a, b, c) if f.con == Con.Either && b > c => f.copy(marks = Vector(a, c, b))
        case _ => f }
    // frames with pieces in the order of their first piece; one with none keeps its place before the next
    val placed = frames.zipWithIndex.sortBy((f, i) => (f.marks.minOption.getOrElse(
      frames.drop(i).flatMap(_.marks.minOption).headOption.getOrElse(Int.MaxValue)), i)).map(_._1)
    s.copy(frames = placed)

  def main(args: Array[String]): Unit =
    val m = Teacher.Model(sys.env.getOrElse("TEACHER_URL", "http://127.0.0.1:8090/v1"),
      sys.env.getOrElse("TEACHER_MODEL", "mlx-community/Qwen3.6-35B-A3B-4bit"))
    val split = Drawn.Split.valueOf(sys.env.getOrElse("TEACH_SPLIT", "Train"))
    val (from, until) = (args.lift(0).fold(0)(_.toInt), args.lift(1).fold(10)(_.toInt))
    val langs = args.lift(2).fold(Vector("en", "ru", "uk", "pl"))(_.split(",").toVector)
    val out = Path.of(args.lift(3).getOrElse(sys.props("user.home") +
      "/work/my/okay-desk-private/teacher/statements.jsonl"))
    Files.createDirectories(out.getParent)
    val log = Path.of(out.toString + ".log")
    val done = if Files.exists(log) then Files.readAllLines(log).asScala.map(_.split("\t").take(3).mkString("\t")).toSet
      else Set.empty[String]
    def note(path: Path, line: String): Unit =
      Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    val reasons = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val t0 = System.nanoTime()
    // TEACH_MODE=judge: the judge alone on the originals of these tasks — its accuracy by line and by task
    if sys.env.get("TEACH_MODE").contains("judge") then
      var (lines, linesRight, tasks, tasksRight) = (0, 0, 0, 0)
      for item <- Drawn.items(split, until).drop(from); stated <- item.task.stated do
        val original = Marked.of(stated.en)
        val truth = gold(original)
        val keys = original.flatMap(s => Marked.keys(s.words)).toSet
        val read = judged(m, original).map(only(_, keys)).getOrElse(truth.map(_ => Vector.empty))
        if read.forall(_.isEmpty) then println("  NOT READ: " + lastAnswer.take(1500).replace("\n", " / "))
        val best = Vector(read, mirrored(read)).minBy(differences(_, truth).size)
        val wrong = differences(best, truth)
        lines += truth.count(_.nonEmpty); linesRight += truth.count(_.nonEmpty) - wrong.size
        tasks += 1; if wrong.isEmpty then tasksRight += 1
        println(s"${item.family} ${item.seed}: ${wrong.size} lines wrong ${wrong.take(3).mkString("; ")}")
      println(s"the judge on originals: lines $linesRight of $lines, whole tasks $tasksRight of $tasks")
      return
    for item <- Drawn.items(split, until).drop(from); stated <- item.task.stated do
      val original = Marked.of(stated.en)
      val truth = gold(original)
      val result = keyed(stated.en, Faults.result(stated.en.statement, stated.en.text))
      // the judge, judged on the original: how often it reads a task as its gold — its accuracy, recorded
      val keys = original.flatMap(s => Marked.keys(s.words)).toSet
      def agrees(r: Vector[Vector[Relation]]): Boolean =
        val mine = only(r, keys)
        same(mine, truth, truth) || same(mirrored(mine), truth, truth)
      val onOriginal = judged(m, original).map(only(_, keys))
      val readable = onOriginal.exists(agrees)
      if !readable then onOriginal.foreach(o => println("  the judge on the original: " +
        differences(o, truth).take(4).mkString("; ")))
      for language <- langs if !done(s"${item.family}\t${item.seed}\t$language") do
        val verdict = teach(m, original, stated, language, truth, result)
        val why = verdict.fold(identity, _ => "kept")
        reasons(why.takeWhile(_ != ':').replaceAll("line \\d+", "line")) += 1
        verdict.foreach(s => note(out, toJson(Kept(item.family, item.seed, split.toString, language, s)).render))
        note(log, s"${item.family}\t${item.seed}\t$language\t$why\tjudge on the original: $readable")
        println(f"${item.family} ${item.seed} $language: $why  (${(System.nanoTime() - t0) / 6e10}%.1f min)")
    println("by outcome: " + reasons.toVector.sortBy(-_._2).map((r, n) => s"$n × $r").mkString("; "))
