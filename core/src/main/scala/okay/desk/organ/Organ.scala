package okay.desk.organ

import okay.desk.calc.{Context, Dist, Fill, Language, Term, Ty}
import okay.inlay.{Cat, Compile, Forward, Guard, Interpret, Layer, Lookup, Many, Program, Rule, Val, Write}
import okay.scala3.*

/**
 * AN ORGAN (specs/organs.md §2): an exact reading rule over the WORDS of a question, as a program of okay-inlay's
 * DSL — so it runs as the interpreter (the reference) and as a transformer block compiled from it (the law:
 * equal). The organ says a TEMPLATE — the term's constructors by kind of hole — or nothing; the copied parts (a
 * value, a number, a name) stay the data's. An organ is induced from pairs (v0, `induce`) or written by the model
 * (v1); either way it serves only after the law.
 */
/** a cue: these words present, those absent → at this PATH of the term, this constructor */
final case class Cue(words: Set[String], unless: Set[String], path: String, constructor: String)

/** the term's constructors by path (`Organs.key`): `"" → Rows`, `6 → count`, … — a PARTIAL reading */
final case class Template(parts: Map[String, String]):
  def text: String = parts.toVector.sortBy(_._1).map((k, v) => s"$k=$v").mkString(",")
object Template:
  def parse(s: String): Template = Template(s.split(",").filter(_.contains("=")).map(kv => { val i = kv.indexOf('='); kv.take(i) -> kv.drop(i + 1) }).toMap)

final case class Organ(name: String, vocabulary: Vector[String], cues: Vector[Cue]):
  /** the paths the organ speaks about, each with the constructors it may say there */
  val paths: Vector[(String, Vector[String])] = cues.groupBy(_.path).toVector.sortBy(_._1).map((p, cs) => p -> cs.map(_.constructor).distinct.sorted)
  private val word = Cat("word", vocabulary :+ Organ.Other)
  private val one = Cat("one", Vector("1"))
  private val seen = Many("seen", vocabulary :+ Organ.Other)
  private def consOf(path: String) = paths.find(_._1 == path).map(_._2).getOrElse(Vector.empty)
  private val cueField: Vector[Cat] = cues.zipWithIndex.map((c, i) => Cat(s"c$i", consOf(c.path)))
  private val said: Vector[(String, Many)] = paths.map((p, cs) => p -> Many(s"t:$p", cs))

  /** THE PROGRAM: the words gathered into a set at every position (one head); a rule per cue writing its own
   * field; the fields of a path joined into the SET of constructors proposed there — one is a decision, more is
   * silence (two cues disagreeing), none is no opinion */
  val program: Program =
    val gather = Layer(heads = Seq(Lookup(Seq(seen -> word), Seq(one), Seq(one), all = true)))
    val decide = Layer(rules = cues.zip(cueField).map((c, f) => Rule(
      c.words.toVector.sorted.map(w => Guard.In(seen, w)) ++ c.unless.toVector.sorted.map(w => Guard.NotIn(seen, w)),
      Seq(Write.To(f, c.constructor)))))
    val join = Layer(rules = said.map((p, m) => Rule(Nil, Seq(Write.Union(m, cues.zip(cueField).collect { case (c, f) if c.path == p => f })))))
    Program(Seq(word, one), Seq(gather, decide, join), maxLen = Organ.MaxWords + 1)

  lazy val weights = Compile(program)

  /** a question's words as the program's records: the stem if in the vocabulary, else `other` */
  def inputs(words: Vector[String]): Vector[Interpret.Record] =
    words.take(Organ.MaxWords).map(w => Map("word" -> Val.C(Some(if vocabulary.contains(w) then w else Organ.Other)), "one" -> Val.C(Some("1"))))

  private def read(rs: Vector[Interpret.Record]): Option[Template] =
    rs.lastOption.map { r =>
      Template(said.flatMap((p, m) => r.get(m.name) match
        case Some(Val.M(vs)) if vs.size == 1 => Some(p -> vs.head)
        case _ => None).toMap)
    }.filter(_.parts.nonEmpty)
  /** the reference: the interpreter */
  def interpret(words: Vector[String]): Option[Template] =
    if words.isEmpty || cues.isEmpty then None else try read(Interpret.run(program, inputs(words))) catch case _: okay.inlay.Inexact => None
  /** the block: the weights compiled from the program */
  def forward(words: Vector[String]): Option[Template] =
    if words.isEmpty || cues.isEmpty then None else try read(Forward.run(program, weights, inputs(words))) catch case _: okay.inlay.Inexact => None

object Organ:
  val Other = "other"
  val MaxWords = 40

  /** the words of a question as an organ sees them: lower-cased, letters only, stemmed by the language */
  def words(question: String, lang: Language): Vector[String] =
    question.toLowerCase.split("[^\\p{L}\\p{N}]+").toVector.filter(_.nonEmpty).map(lang.stem)

  /**
   * INDUCTION (specs/organs.md §3), RIPPER-shaped, PER PATH: the pairs that have the path are the universe; for each
   * constructor there, a cue is grown by the literal (a word present, or absent) with the best FOIL gain until no
   * counter-example is covered, PRUNED on a held-out third of the pairs (kept only if right on nine of ten it fires
   * on there), kept if it covers `minCover`; the covered set aside; again. Deterministic: ties to the earlier word.
   */
  def induce(name: String, pairs: Vector[(Vector[String], Template)], minCover: Int = 2, maxWords: Int = 4, prune: Boolean = true): Organ =
    val sets = pairs.map((ws, t) => (ws.toSet, t))
    val (grow, hold) = if prune && sets.size >= 6 then sets.zipWithIndex.partition(_._2 % 3 != 2) match { case (a, b) => (a.map(_._1), b.map(_._1)) } else (sets, Vector.empty)
    val vocab = sets.flatMap(_._1).groupBy(identity).view.mapValues(_.size).toVector.filter(_._2 >= minCover).map(_._1).sorted
    def gain(pos: Vector[Set[String]], neg: Vector[Set[String]], holds: Set[String] => Boolean): Double =
      val p1 = pos.count(holds); val n1 = neg.count(holds)
      if p1 == 0 then Double.NegativeInfinity
      else p1 * (math.log((p1 + 1e-9) / (p1 + n1 + 1e-9)) - math.log((pos.size + 1e-9) / (pos.size + neg.size + 1e-9)))
    val cues = Vector.newBuilder[Cue]
    for path <- sets.flatMap(_._2.parts.keys).distinct.sorted do
      val here = grow.flatMap((ws, t) => t.parts.get(path).map(c => (ws, c)))
      for con <- here.map(_._2).distinct.sorted do
        var pos = here.filter(_._2 == con).map(_._1)
        // (the counter-examples: every other pair — a different constructor here, or no such path in its term at all)
        val neg = grow.filterNot((_, t) => t.parts.get(path).contains(con)).map(_._1)
        var rounds = 0
        while pos.size >= minCover && rounds < 6 do
          rounds += 1
          var in = Set.empty[String]; var out = Set.empty[String]
          def holds(s: Set[String]) = in.subsetOf(s) && out.forall(w => !s.contains(w))
          var p = pos; var n = neg
          while n.exists(holds) && in.size + out.size < maxWords do
            val best = vocab.flatMap(w => Vector((w, true, gain(p, n, s => holds(s) && s.contains(w))),
              (w, false, gain(p, n, s => holds(s) && !s.contains(w))))).maxByOption(_._3)
            best match
              case Some((w, true, g)) if g > Double.NegativeInfinity => in += w
              case Some((w, false, g)) if g > Double.NegativeInfinity => out += w
              case _ => n = Vector.empty
            p = p.filter(holds); n = n.filter(holds)
          val fires = hold.filter((ws, _) => holds(ws))
          val precise = fires.isEmpty || fires.count((_, t) => t.parts.get(path).contains(con)) * 10 >= fires.size * 9
          if n.isEmpty && p.size >= minCover && in.nonEmpty && precise then
            cues += Cue(in, out, path, con)
            pos = pos.filterNot(holds)
          else pos = Vector.empty
    val all = cues.result()
    Organ(name, all.flatMap(c => c.words ++ c.unless).distinct.sorted, all)

/** THE EFFECT: an organ called by name with the question's words; the handlers run the interpreter or the weights */
enum Organs[X]:
  case Call(organ: String, words: Vector[String]) extends Organs[Option[Template]]

object Organs:
  def call[R[_]](organ: String, words: Vector[String])(using In[Organs, R]): Option[Template] ! R =
    perform[Organs](Call(organ, words))

  def byInterpreter[R[_], A](organs: Vector[Organ]): Handler[Organs, R, A, A] =
    Handler.tail[Organs, R, A, A](pure)([X] => (op: Organs[X]) => op match
      case Call(name, words) => (organs.find(_.name == name).flatMap(_.interpret(words)): Option[Template]))
  def byWeights[R[_], A](organs: Vector[Organ]): Handler[Organs, R, A, A] =
    Handler.tail[Organs, R, A, A](pure)([X] => (op: Organs[X]) => op match
      case Call(name, words) => (organs.find(_.name == name).flatMap(_.forward(words)): Option[Template]))

  /** THE ORGANS AS A SOURCE of `Weigh`: the template of the first organ with an opinion on the question decides
   * each hole of its kinds — one candidate, as the data does — and nothing else */
  def fill[R[_]: Has[Organs]](names: Vector[String]): Fill[R] = new Fill[R]:
    private var cache: Option[(String, Option[Template])] = None
    private def template(ctx: Context): Option[Template] ! R = cache match
      case Some((q, t)) if q == ctx.question => pure(t)
      case _ =>
        val lang = ctx.languages.headOption.getOrElse(Language.En.language)
        val ws = Organ.words(ctx.question, lang)
        names.foldLeft(pure[Option[Template], R](None))((acc, n) => acc.flatMap {
          case some @ Some(_) => pure(some)
          case None => call[R](n, ws)
        }).map { t => cache = Some((ctx.question, t)); t }
    def apply(ty: Ty, cs: Vector[String], ctx: Context): Dist ! R =
      // (the hole is the term's leftmost open place; a value hole is the data's)
      if ty.isInstanceOf[Ty.ValueT] then pure(Vector.empty)
      else template(ctx).map(_.flatMap(_.parts.get(key(pathOf(ctx.term)))).filter(cs.contains).map(c => Vector((c, 0.0))).getOrElse(Vector.empty))

  /** a template's key for a hole: its PATH in the term (the argument's place, e.g. `2` the first condition's
   * column, `2.0` its operator, `8` the order) — unambiguous where two holes share a type */
  def pathOf(term: Term): Vector[Int] = term match
    case Term.Hole(_) => Vector.empty
    case Term.Leaf(_, _) => Vector.empty
    case Term.Con(_, _, args) =>
      args.zipWithIndex.collectFirst(Function.unlift { (a, i) => Option.when(!a.closed)(i +: pathOf(a)) }).getOrElse(Vector(args.size))
  def key(path: Vector[Int]): String = path.mkString(".")

  /** the template of a closed term: its constructors by path, the copied values left out — they are the data's */
  def templateOf(term: Term): Template =
    def walk(t: Term, path: Vector[Int]): Vector[(String, String)] = t match
      case Term.Leaf(ty, v) => if ty.isInstanceOf[Ty.ValueT] then Vector.empty else Vector((key(path), v))
      case Term.Con(_, name, args) => (key(path), name) +: args.zipWithIndex.flatMap((a, i) => walk(a, path :+ i))
      case Term.Hole(_) => Vector.empty
    Template(walk(term, Vector.empty).toMap)

/** AN ORGAN AS A FILE (specs/organs.md, BACKLOG 16: a portable artifact — the cues are data; symdex reads the same
 * file with an evaluator of its own, the law being that the two agree): `{"name", "vocabulary", "cues": [{"words",
 * "unless", "path", "constructor"}]}` */
object OrganFile:
  import okay.effects.json.Json
  def json(o: Organ): String =
    def strs(xs: Iterable[String]) = Json.Arr(xs.toVector.sorted.map(Json.Str(_)))
    Json.print(Json.obj("name" -> Json.Str(o.name), "vocabulary" -> strs(o.vocabulary),
      "cues" -> Json.Arr(o.cues.map(c => Json.obj("words" -> strs(c.words), "unless" -> strs(c.unless),
        "path" -> Json.Str(c.path), "constructor" -> Json.Str(c.constructor))))))
  def parse(text: String): Either[String, Organ] =
    def strs(j: Option[Json]): Vector[String] = j match { case Some(Json.Arr(xs)) => xs.collect { case Json.Str(s) => s }.toVector; case _ => Vector.empty }
    def str(j: Option[Json]): String = j match { case Some(Json.Str(s)) => s; case _ => "" }
    try Json.fromText(text) match
      case Json.Obj(fs) =>
        val m = fs.toMap
        val cues = m.get("cues") match
          case Some(Json.Arr(cs)) => cs.toVector.collect { case Json.Obj(f) =>
            val c = f.toMap; Cue(strs(c.get("words")).toSet, strs(c.get("unless")).toSet, str(c.get("path")), str(c.get("constructor"))) }
          case _ => Vector.empty
        Right(Organ(str(m.get("name")), strs(m.get("vocabulary")), cues))
      case _ => Left("an object expected")
    catch case e: Exception => Left(e.getMessage)
