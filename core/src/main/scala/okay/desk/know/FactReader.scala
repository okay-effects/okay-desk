package okay.desk.know

import okay.desk.exec.Wire
import okay.desk.skill.{Probes, Read}
import okay.scala3.*

/** Learned read interface to exact inference: choose a constructor and copy two argument spans, never generate
 * facts as prose. Reading is fallible; the source span and interpreted rule remain visible beside the proof. */
object FactReader:
  val Kinds = Vector("fact", "not-fact", "every", "every-not", "other")
  val Roles = Vector("outside", "a", "b")
  val System = "Read the statement. Identify its logical relation and the exact argument spans."
  final case class Word(text: String, from: Int, until: Int)
  final case class Span(from: Int, until: Int) derives Wire
  final case class Reading(source: String, quote: String, kind: String, a: Span, b: Span) derives Wire
  final case class Features(words: Vector[Word], last: Array[Float], states: Vector[Array[Float]])
  final case class Parameters(layer: Int, mean: Vector[Float], std: Vector[Float],
      kindWeights: Vector[Vector[Float]], kindBias: Vector[Float],
      roleWeights: Vector[Vector[Float]], roleBias: Vector[Float],
      kindMargin: Float, roleMargin: Float) derives Wire

  def words(text: String): Vector[Word] =
    "[\\p{L}\\p{N}]+(?:[-’'][\\p{L}\\p{N}]+)*".r.findAllMatchIn(text)
      .map(m => Word(m.matched, m.start, m.end)).toVector

  /** Whole named arguments are candidates; the model ranks them rather than dropping pieces of a name. */
  def namedSpans(text: String): Vector[(Span, Vector[Int])] =
    val ws = words(text)
    val out = Vector.newBuilder[(Span, Vector[Int])]
    var i = 0
    while i < ws.size do
      if !ws(i).text.head.isUpper then i += 1
      else
        val start = i
        var end = i
        var searching = true
        while searching && end + 1 < ws.size do
          def gap(a: Int, b: Int): Boolean = text.substring(ws(a).until, ws(b).from).matches("[\\s\\u00a0\\u202f]+")
          if gap(end, end + 1) && ws(end + 1).text.head.isUpper then end += 1
          else if end + 2 < ws.size && Set("of", "the")(ws(end + 1).text) &&
              gap(end, end + 1) && gap(end + 1, end + 2) && ws(end + 2).text.head.isUpper then end += 2
          else searching = false
        out += Span(ws(start).from, ws(end).until) -> (start to end).toVector
        i = end + 1
    out.result()

  def sentences(source: Knowledge.Source): Vector[Knowledge.Quote] =
    SourceText.sentences(source.text).iterator.filter(_.endsWith("."))
      .filter(s => s.nonEmpty && s.length <= 600 && words(s).size <= 48)
      .map(Knowledge.Quote(source.id, _)).toVector

  def features[R[_]](sentence: String)(using In[Read, R]): Option[Features] ! R =
    for
      ids <- Read.prompt[R](System, sentence)
      decoded <- Read.decode[R](ids.toVector)
      ends <- Read.each[Int, (Int, Int), R](ids.indices.toVector) { i =>
        Read.decode[R](ids.take(i + 1).toVector).map(s => (i, if decoded.startsWith(s) then s.length else -1))
      }
      base = decoded.indexOf(sentence)
      ws = words(sentence)
      at = ws.map(w => ends.find(_._2 >= base + w.until).map(_._1))
      result <-
        if base < 0 || ids.length > 256 || ws.isEmpty || at.exists(_.isEmpty) then pure[Option[Features], R](None)
        else Read.states[R](ids, at.flatten :+ (ids.length - 1)).map(s =>
          Some(Features(ws, s.last, s.dropRight(1))))
    yield result

  def normalize(x: Array[Float], mean: Vector[Float], std: Vector[Float]): Array[Float] =
    x.indices.map(i => math.max(-4f, math.min(4f, (x(i) - mean(i)) / std(i)))).toArray
  def roleFeatures(local: Array[Float], global: Array[Float]): Array[Float] =
    local ++ global ++ local.zip(global).map((a, b) => a * b / 4f)

  def kindFeatures(global: Array[Float], states: Vector[Array[Float]]): Array[Float] =
    global ++ global.indices.map(i => states.map(_(i)).sum / states.size).toArray ++
      global.indices.map(i => states.map(_(i)).max).toArray

  final class Reader(val parameters: Parameters, val kinds: Vector[String] = Kinds,
      val kindMargins: Map[String, Float] = Map.empty):
    private val d = parameters.mean.size
    require(kindMargins.forall((k, v) => kinds.contains(k) && v.isFinite && v >= 0))
    require(parameters.layer > 0 && parameters.kindMargin.isFinite && parameters.kindMargin >= 0 &&
      parameters.roleMargin.isFinite && parameters.roleMargin >= 0)
    require(d > 0 && parameters.std.size == d && parameters.std.forall(x => x.isFinite && x > 0))
    require(parameters.kindWeights.size == kinds.size && parameters.kindWeights.forall(_.size == 3 * d))
    require(parameters.roleWeights.size == Roles.size && parameters.roleWeights.forall(_.size == 3 * d))
    require(parameters.kindBias.size == kinds.size && parameters.roleBias.size == Roles.size)
    require((parameters.mean ++ parameters.kindWeights.flatten ++ parameters.kindBias ++
      parameters.roleWeights.flatten ++ parameters.roleBias).forall(_.isFinite))
    val kind = Probes.Softmax(kinds, 3 * d)
    val roles = Probes.Softmax(Roles, 3 * d)
    for i <- kinds.indices do
      Array.copy(parameters.kindWeights(i).toArray, 0, kind.w(i), 0, 3 * d)
      kind.b(i) = parameters.kindBias(i)
    for i <- Roles.indices do
      Array.copy(parameters.roleWeights(i).toArray, 0, roles.w(i), 0, 3 * d)
      roles.b(i) = parameters.roleBias(i)

    private def best(scores: Array[Float]): (Int, Float) =
      val ranked = scores.indices.sortBy(i => -scores(i))
      ranked(0) -> (scores(ranked(0)) - scores(ranked(1)))

    def confidence(f: Features): (String, Float) =
      val global = normalize(f.last, parameters.mean, parameters.std)
      val (index, margin) = best(kind.scores(kindFeatures(global,
        f.states.map(normalize(_, parameters.mean, parameters.std)))))
      kinds(index) -> margin

    def read(source: String, sentence: String, f: Features): Option[Reading] =
      if !SourceText.assertive(sentence) || f.words.isEmpty || f.words != words(sentence) ||
          f.states.size != f.words.size ||
          f.last.length != d || f.states.exists(_.length != d) ||
          !f.last.forall(_.isFinite) || f.states.exists(s => !s.forall(_.isFinite)) then return None
      val global = normalize(f.last, parameters.mean, parameters.std)
      val local = f.states.map(normalize(_, parameters.mean, parameters.std))
      val (constructor, margin) = best(kind.scores(kindFeatures(global, local)))
      if Relations.kinds.contains(kinds(constructor)) && kinds(constructor) != "other" &&
          !Relations.evidenced(kinds(constructor), sentence) then return None
      if kinds(constructor) == "other" ||
          margin < kindMargins.getOrElse(kinds(constructor), parameters.kindMargin) then return None
      val roleScores = f.states.map { state =>
        roles.scores(roleFeatures(normalize(state, parameters.mean, parameters.std), global))
      }
      val tagged = roleScores.map(best)
      val binary = Relations.kinds.contains(kinds(constructor)) && kinds(constructor) != "other"
      if !binary && tagged.exists(_._2 < parameters.roleMargin) then return None
      def span(role: Int): Option[Span] = if binary then
        val candidates = namedSpans(sentence).map { (span, at) =>
          val score = at.map(i => roleScores(i)(role) - roleScores(i).indices
            .filterNot(_ == role).map(roleScores(i)(_)).max).sum / at.size
          span -> score
        }.sortBy(-_._2)
        candidates.headOption.filter { (_, score) =>
          score > 0 && (candidates.size == 1 || score - candidates(1)._2 >= parameters.roleMargin)
        }.map(_._1)
      else
        val found = tagged.indices.filter(i => tagged(i)._1 == role).toVector
        if found.isEmpty || found != (found.head to found.last).toVector ||
            found.exists(i => tagged(i)._2 < parameters.roleMargin) then None
        else Some(Span(f.words(found.head).from, f.words(found.last).until))
      for
        a <- span(1)
        b <- span(2)
        reading = Reading(source, sentence, kinds(constructor), a, b)
        _ <- meaning(reading).toOption
      yield reading

  /** Only copied, disjoint word spans and registered constructors become inference inputs. This checks provenance
   * and the constructor's shape; it does not claim to prove the model's semantic reading of natural language. */
  def meaning(r: Reading): Either[String, Deduction.Meaning] =
    if !SourceText.assertive(r.quote) then return Left("source scope needs a modal or temporal reading")
    val ws = words(r.quote)
    def copied(s: Span): Option[String] =
      Option.when(s.from >= 0 && s.until > s.from && s.until <= r.quote.length &&
        ws.exists(_.from == s.from) && ws.exists(_.until == s.until))(
        r.quote.slice(s.from, s.until).toLowerCase)
        .filter(s => s.length <= 80 && !s.exists(".,?!\n".contains(_)))
    def completeName(s: Span): Boolean =
      val at = ws.indexWhere(_.from == s.from)
      val end = ws.indexWhere(_.until == s.until)
      def adjacent(i: Int, j: Int): Boolean = i >= 0 && j < ws.size &&
        ws(i).text.head.isUpper && ws(j).text.head.isUpper &&
        r.quote.substring(ws(i).until, ws(j).from).matches("[\\s\\u00a0\\u202f]+")
      at >= 0 && end >= at &&
        !(adjacent(at - 1, at) && !Set("The", "A", "An")(ws(at - 1).text)) && !adjacent(end, end + 1)
    for
      a <- copied(r.a).toRight("reader's first argument is not an exact word span")
      b <- copied(r.b).toRight("reader's second argument is not an exact word span")
      _ <- Either.cond(r.a.until <= r.b.from || r.b.until <= r.a.from, (), "reader's argument spans overlap")
      result <- r.kind match
        case "fact" => Right(Deduction.Meaning.Fact(Deduction.Atom(a, b)))
        case "not-fact" => Right(Deduction.Meaning.Fact(Deduction.Atom(a, b, negative = true)))
        case "every" => Right(Deduction.Meaning.Every(a, b, negative = false))
        case "every-not" => Right(Deduction.Meaning.Every(a, b, negative = true))
        case k if Relations.kinds.contains(k) && k != "other" =>
          val negative = k.startsWith("not-")
          Either.cond(completeName(r.a) && completeName(r.b) && Relations.evidenced(k, r.quote),
            Deduction.Meaning.Fact(Deduction.Atom(Relations.name(a), Relations.name(b), negative,
              k.stripPrefix("not-"))), "reader needs complete named arguments and a source predicate cue")
        case _ => Left("unregistered fact/rule constructor")
    yield result

  /** Specialized relation readout and established class readout must agree on class constructors/spans. */
  def combined(primary: Reader, classes: Reader, source: String, text: String, features: Features): Option[Reading] =
    primary.read(source, text, features).filter { r =>
      !Kinds.dropRight(1).contains(r.kind) || classes.read(source, text, features).contains(r)
    }

  def parse(text: String, kinds: Vector[String] = Kinds): Either[String, Reader] =
    Wire.decode[Parameters](text).flatMap { p =>
      try Right(Reader(p, kinds)) catch case e: Exception => Left("invalid fact reader: " + e.getMessage)
    }
