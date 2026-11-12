package okay.desk.statement

import okay.desk.skill.{LogicRead, Probes, Read}
import okay.desk.statement.Assemble.{Choices, Tag}
import okay.scala3.*

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets.UTF_8

/**
 * THE STATEMENT READER (specs/formalization.md §10–§12): a task's text to its statement by heads over a frozen
 * model's states — a tag at every token, a sentence's frames, a pointer from a mention to the thing it names, the
 * attribute a question asks for — and the assembler's rules (`Assemble`). The model generates nothing and solves
 * nothing: the statement goes to the exact solvers.
 *
 * THE GATE (§8, §12.1). An answer is shown only when the statement compiles and settles on one result, every word
 * outside the names was met in training (and every pair of neighbouring ones), and the least margin of each kind
 * of decision is over its bar. Otherwise the reader asks, about the sentence it could not read. Out of the
 * wordings it was taught a margin says nothing; the words do.
 *
 * One program for the bench (MLX) and the page (WebGPU, WebGL2): `Read` is the only way to the model.
 */
object Reader:
  val system = "State the task: its things, its conditions and its question."
  private val (stop, depth) = ("stop", 3)
  val cons: Vector[Con] = Con.of(Problem.Assign)
  val tagNames: Vector[String] = Tag.values.toVector.map(_.toString)
  val frameNames: Vector[String] = cons.map(_.toString) :+ stop
  /** the kinds of decision a margin is kept for */
  val kinds: Vector[String] = Vector("tag", "frame", "link", "pick")

  /** a text as the model read it: its tokens' places in the text and the (normalized) state at each */
  final case class Seen(text: String, tokens: Vector[Span], states: Vector[Array[Float]]):
    val sentences: Vector[Span] = Sentences.of(text)
    /** what a pointer was given for some words of this text: one array however many examples point at it */
    val keys = scala.collection.mutable.Map.empty[Span, Array[Float]]
    /** these words as a pointer reads them: the mean of their tokens' states — a name said in another form keeps
     * its first tokens, and the form is in its last */
    def over(at: Span): Array[Float] =
      val mine = tokens.indices.filter { i =>
        val c = Assemble.core(text, tokens(i))
        c.from < c.until && c.from < at.until && at.from < c.until }
      val mean = new Array[Float](states.head.length)
      for i <- mine; j <- mean.indices do mean(j) += states(i)(j) / mine.size
      mean
    private def inside(s: Span): Vector[Int] =
      tokens.indices.toVector.filter(i => tokens(i).from < s.until && s.from < tokens(i).until)
    /** an attribute as a question reads it: the states of the sentence that lists it */
    def listing(at: Span): Vector[Array[Float]] =
      inside(sentences.find(s => s.from <= at.from && at.from < s.until).getOrElse(at)).map(states)
    /** the states of a sentence's tokens */
    def each(sentence: Int): Vector[Array[Float]] = inside(sentences(sentence)).map(states)
    /** a token as its tag is read: its state, then the state at the end of its word. A state knows only what came
     * before it — at the first piece of «Таня» and of «Тот» it is the same letter, and only the word's end tells
     * a name from a pronoun */
    lazy val reads: Vector[Array[Float]] =
      def goesOn(j: Int): Boolean =
        val at = tokens(j).from
        at > 0 && at == tokens(j - 1).until && text(at).isLetterOrDigit && text(at - 1).isLetterOrDigit
      val ends = new Array[Int](tokens.size)
      for i <- tokens.indices.reverse do ends(i) = if i + 1 < tokens.size && goesOn(i + 1) then ends(i + 1) else i
      tokens.indices.toVector.map(i => states(i) ++ states(ends(i)))

  /**
   * a text read by the model: the prompt's tokens placed in the text, the states at those of the task.
   *
   * `echo` (1, 2): THE TASK IS SAID TWICE and read at its second saying. The model is causal — a state knows only what
   * came before it, so a name's first piece is read before the name is whole and a sentence's first words
   * before its verb; at the second saying every token has the whole task behind it. One model, no new weights:
   * twice the tokens of one forward.
   */
  def see[R[_]](text: String, norm: Array[Float] => Array[Float], echo: Int = 0)(using In[Read, R])
      : Seen ! R =
    for
      // echo 1: said twice; echo 2: said, then asked to be repeated and repeated — the form of Springer et al.
      // ("Repetition improves language model embeddings", ICLR 2025), who name the asking a key ingredient
      ids <- Read.prompt[R](system, echo match
        case 1 => text + "\n\n" + text
        case 2 => text + "\n\nRepeat the task word for word.\n\n" + text
        case _ => text)
      full <- Read.decode[R](ids.toVector)
      prefixes <- Read.each(ids.indices.toVector)(i => Read.decode[R](ids.take(i + 1).toVector))
      base = if echo > 0 then full.lastIndexOf(text) else full.indexOf(text)
      places =
        var prev = 0
        prefixes.map { s =>
          val end = if full.startsWith(s) then s.length else prev
          val place = (math.max(prev, base) - base, math.min(end, base + text.length) - base)
          prev = end
          place }
      at = places.indices.toVector.filter(i => base >= 0 && places(i)._1 < places(i)._2)
      states <- if at.isEmpty then pure[Vector[Array[Float]], R](Vector.empty)
        else Read.states[R](ids, at)
    yield Seen(text, at.map(i => Span(places(i)._1, places(i)._2)), states.map(norm))

  /** the best of some scores and its margin over the second: a choice among one is sure */
  def top(scores: Seq[Float]): (Int, Float) =
    val best = scores.indices.maxBy(scores)
    val rest = scores.indices.filter(_ != best).map(scores)
    (best, if rest.isEmpty then Float.MaxValue else scores(best) - rest.max)

  /** a step of Adam on one array of parameters */
  private final class Adam(size: Int, lr: Float, decay: Float = 0.01f):
    private val (m, v) = (new Array[Float](size), new Array[Float](size))
    private var t = 0
    def step(p: Array[Float], g: Array[Float]): Unit =
      t += 1
      val (c1, c2) = (1 - math.pow(0.9, t).toFloat, 1 - math.pow(0.999, t).toFloat)
      var j = 0
      while j < size do
        m(j) = 0.9f * m(j) + 0.1f * g(j)
        v(j) = 0.999f * v(j) + 0.001f * g(j) * g(j)
        p(j) -= lr * ((m(j) / c1) / (math.sqrt(v(j) / c2).toFloat + 1e-8f) + decay * p(j))
        j += 1

  /**
   * A SENTENCE'S FRAME, BY WHERE THE SENTENCE IS LOOKED AT: each look weighs the sentence's tokens (a softmax over
   * one learned direction) and takes their weighted mean; the frame is linear over the looks and the state at the
   * sentence's end. A relation is said by a word or two ("to the LEFT of", «ровно на этаж ВЫШЕ») among twenty that
   * describe who it relates: in the plain mean of the tokens that word is a twentieth, and a linear head over the
   * mean confuses the mirrored pairs — Before and After, JustBefore and JustAfter (99.6% held-out from 600 tasks,
   * every error of that kind; four looks: 0 wrong of 8,402, §11). A look that weighs every token alike is the mean.
   */
  final class Gaze(n: Int, d: Int, looks: Int = 4, lr: Float = 1e-3f, seed: Int = 1):
    private val rnd = scala.util.Random(seed)
    private val c = 8f / math.sqrt(d).toFloat
    private val wide = (looks + 1) * d
    private val u = Array.fill(looks)(Array.fill(d)(rnd.nextGaussian().toFloat * 1e-3f))
    private val w = Array.fill(n)(new Array[Float](wide))
    private val b = new Array[Float](n)
    private lazy val (stepU, stepW, stepB) =
      (Vector.fill(looks)(Adam(d, lr)), Vector.fill(n)(Adam(wide, lr)), Adam(n, lr, 0f))
    /** every parameter, in a fixed order: what a file keeps */
    def arrays: Vector[Array[Float]] = u.toVector ++ w.toVector :+ b
    private def dot(a: Array[Float], b: Array[Float], from: Int = 0): Float =
      var (s, i) = (0f, 0)
      while i < b.length do { s += a(from + i) * b(i); i += 1 }
      s
    /** the weights of each look over the tokens, and what the frame is read from */
    private def look(ts: Vector[Array[Float]]): (Array[Array[Float]], Array[Float]) =
      val x = new Array[Float](wide)
      val a = Array.tabulate(looks) { h =>
        val z = ts.map(t => c * dot(u(h), t))
        val e = z.map(v => math.exp((v - z.max).toDouble).toFloat)
        val weights = e.map(_ / e.sum).toArray
        for (t, k) <- ts.zipWithIndex do
          var j = 0
          while j < d do { x(h * d + j) += c * weights(k) * t(j); j += 1 }
        weights }
      for j <- 0 until d do x(looks * d + j) = c * ts.last(j)
      (a, x)
    def scores(ts: Vector[Array[Float]]): Vector[Float] =
      val x = look(ts)._2
      Vector.tabulate(n)(k => dot(w(k), x) + b(k))
    def train(xs: Vector[(Vector[Array[Float]], Int)], epochs: Int): Unit =
      for _ <- 1 to epochs; batch <- rnd.shuffle(xs).grouped(32) do
        val (gu, gw, gb) = (Array.fill(looks)(new Array[Float](d)), Array.fill(n)(new Array[Float](wide)),
          new Array[Float](n))
        for (ts, y) <- batch do
          val (a, x) = look(ts)
          val sc = Vector.tabulate(n)(k => dot(w(k), x) + b(k))
          val e = sc.map(s => math.exp((s - sc.max).toDouble).toFloat)
          val dx = new Array[Float](wide)
          for k <- 0 until n do
            val g = (e(k) / e.sum - (if k == y then 1f else 0f)) / batch.size
            gb(k) += g
            var j = 0
            while j < wide do { gw(k)(j) += g * x(j); dx(j) += g * w(k)(j); j += 1 }
          for h <- 0 until looks do
            // the loss by each token's weight, then by its score under the softmax
            val da = ts.map(t => c * dot(dx, t, h * d))
            val mean = da.indices.map(k => a(h)(k) * da(k)).sum
            for (t, k) <- ts.zipWithIndex do
              val dz = c * a(h)(k) * (da(k) - mean)
              var j = 0
              while j < d do { gu(h)(j) += dz * t(j); j += 1 }
        for h <- 0 until looks do stepU(h).step(u(h), gu(h))
        for k <- 0 until n do stepW(k).step(w(k), gw(k))
        stepB.step(b, gb)

  /**
   * WHICH ATTRIBUTE A QUESTION ASKS FOR: every token of the question is set against every token of the sentence
   * that lists an attribute, each takes the one it fits best, and the attribute scores as the question's tokens do
   * on average. What is asked is said by a word or two ("which plant", "grow") that the listing says too ("each
   * grows a different plant"); pooled into one state, either side drowns them. Both sides are seen through ONE map:
   * the fit is of a word to the same word said again, and a map per side learns the tasks it was shown instead
   * (held-out 84% against 98% from the same 870 examples, §10). A question also describes the thing it asks about
   * ("the person who comes by bicycle") in the words of another attribute: the data closes that one.
   */
  final class Asking(d: Int, k: Int = 64, seed: Int = 1):
    private type States = Vector[Array[Float]]
    private val rnd = scala.util.Random(seed)
    private val w = Array.fill(k)(Array.fill(d)(rnd.nextGaussian().toFloat / math.sqrt(d).toFloat))
    def arrays: Vector[Array[Float]] = w.toVector
    private def dot(a: Array[Float], b: Array[Float]): Float =
      var (s, i) = (0f, 0)
      while i < a.length do { s += a(i) * b(i); i += 1 }
      s
    private def seen(states: States): Vector[Array[Float]] = states.map(s => w.map(dot(_, s)))
    /** per attribute and token of the question: the listing's token that fits it best, and how well */
    private def fits(pq: Vector[Array[Float]], pk: Vector[Vector[Array[Float]]]): Vector[Vector[(Float, Int)]] =
      pk.map(listing => pq.map(q => listing.indices.map(u => (dot(q, listing(u)), u)).max))
    def scores(question: States, listings: Vector[States]): Vector[Float] =
      fits(seen(question), listings.map(seen)).map(_.map(_._1).sum / question.size)
    def train(xs: Vector[(States, Vector[States], Int)], epochs: Int, lr: Float): Unit =
      def add(g: Array[Array[Float]], by: Array[Float], state: Array[Float]): Unit =
        for i <- 0 until k if by(i) != 0f do
          var j = 0
          while j < d do { g(i)(j) += by(i) * state(j); j += 1 }
      for _ <- 1 to epochs; batch <- rnd.shuffle(xs).grouped(32) do
        val g = Array.fill(k)(new Array[Float](d))
        for (question, listings, y) <- batch do
          val (pq, pk) = (seen(question), listings.map(seen))
          val best = fits(pq, pk)
          val score = best.map(_.map(_._1).sum / question.size)
          val e = score.map(s => math.exp((s - score.max).toDouble).toFloat)
          val byQ = Vector.fill(question.size)(new Array[Float](k))
          for a <- listings.indices do
            val off = (e(a) / e.sum - (if a == y then 1f else 0f)) / (batch.size * question.size)
            val byK = scala.collection.mutable.Map.empty[Int, Array[Float]]
            for t <- question.indices do
              val u = best(a)(t)._2
              val mine = byK.getOrElseUpdate(u, new Array[Float](k))
              for i <- 0 until k do { byQ(t)(i) += off * pk(a)(u)(i); mine(i) += off * pq(t)(i) }
            for (u, by) <- byK do add(g, by, listings(a)(u))
          for t <- question.indices do add(g, byQ(t), question(t))
        for i <- 0 until k do
          var j = 0
          while j < d do { w(i)(j) -= lr * g(i)(j); j += 1 }

  /** `seed`: where the heads start and the order they are taught in — the same seed, the same heads */
  final class Heads(val d: Int, seed: Int = 1):
    val tags = LogicRead.Masked(tagNames, 2 * d)
    val frames = Vector.fill(depth)(Gaze(frameNames.size, d, seed = seed))
    val (link, pick) = (Probes.Pointer(d), Asking(d, seed = seed))
    /** every parameter, in a fixed order: what a file keeps, and what a file is read into */
    def arrays: Vector[Array[Float]] = tags.weights.toVector ++ Vector(tags.bias) ++ frames.flatMap(_.arrays) ++
      link.wq.toVector ++ link.wk.toVector ++ Vector(link.none) ++ pick.arrays
    private def scaled(x: Array[Float]): Array[Float] = LogicRead.scale(x, d)
    private def key(seen: Seen, at: Span): Array[Float] = seen.keys.getOrElseUpdate(at, scaled(seen.over(at)))

    def tag(x: Array[Float]): (Tag, Float) =
      val (best, margin) = top(tagNames.indices.map(tags.score(_, x)))
      (Tag.fromOrdinal(best), margin)
    def frame(seen: Seen, sentence: Int, k: Int): (Option[Con], Float) =
      if k >= depth then (None, Float.MaxValue)
      else
        val (best, margin) = top(frames(k).scores(seen.each(sentence)))
        (cons.lift(best), margin)
    /** the frames of a sentence's place k, the most likely first ("stop": None) */
    def ranked(seen: Seen, sentence: Int, k: Int): Vector[Option[Con]] =
      if k >= depth then Vector(None)
      else frames(k).scores(seen.each(sentence)).zipWithIndex.sortBy(-_._1).map((_, i) => cons.lift(i))
    def frameClass(con: Option[Con]): Int = frameNames.indexOf(con.fold(stop)(_.toString))
    def linkKeys(seen: Seen, at: Span, earlier: Vector[Span]): (Array[Float], Vector[Array[Float]]) =
      (key(seen, at), earlier.map(key(seen, _)))
    def link(seen: Seen, at: Span, earlier: Vector[Span]): (Option[Span], Float) =
      val (q, keys) = linkKeys(seen, at, earlier)
      val (best, margin) = top(link.scores(q, keys).toIndexedSeq)
      (earlier.lift(best), margin)
    def pickKeys(seen: Seen, sentence: Int, attributes: Vector[Span])
        : (Vector[Array[Float]], Vector[Vector[Array[Float]]]) =
      (seen.each(sentence).map(scaled), attributes.map(seen.listing(_).map(scaled)))
    def pick(seen: Seen, sentence: Int, attributes: Vector[Span]): (Int, Float) =
      val (q, keys) = pickKeys(seen, sentence, attributes)
      if keys.isEmpty then (0, Float.MaxValue)
      else top(pick.scores(q, keys))

  /** the heads' own answers, each with its margin and the sentence it was given in */
  final class ByHeads(seen: Seen, heads: Heads) extends Choices:
    var margins = Vector.empty[(String, Float, Int)]
    private def within(at: Span): Int = seen.sentences.indexWhere(s => s.from <= at.from && at.from < s.until)
    private def note[A](kind: String, sentence: Int, answer: (A, Float)): A =
      margins :+= (kind, answer._2, sentence)
      answer._1
    /** each frame decided: its sentence, its place, its margin */
    var frameAt = Vector.empty[(Int, Int, Float)]
    def frame(sentence: Int, k: Int): Option[Con] =
      val (con, margin) = heads.frame(seen, sentence, k)
      frameAt :+= (sentence, k, margin)
      note("frame", sentence, (con, margin))
    def link(at: Span, earlier: Vector[Span]): Option[Span] =
      note("link", within(at), heads.link(seen, at, earlier))
    def pick(sentence: Int, attributes: Vector[Span]): Int =
      note("pick", sentence, heads.pick(seen, sentence, attributes))

  /** the heads' answers with some frames given: a candidate reading of the sentences the heads were unsure of */
  private final class Forced(seen: Seen, heads: Heads, force: Map[(Int, Int), Option[Con]]) extends Choices:
    def frame(sentence: Int, k: Int): Option[Con] =
      force.getOrElse((sentence, k), heads.frame(seen, sentence, k)._1)
    def link(at: Span, earlier: Vector[Span]): Option[Span] = heads.link(seen, at, earlier)._1
    def pick(sentence: Int, attributes: Vector[Span]): Int = heads.pick(seen, sentence, attributes)._1

  /**
   * THE WORDS A READER WAS TAUGHT ON, outside every name: a text with a word, or a pair of neighbouring words,
   * that training never had is new to the reader, whatever its margins say (§12.1: on 1200 texts of families never
   * met no answer is shown; a distance in the states separated nothing).
   */
  final class Known(val words: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty,
      val pairs: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty):
    /** the words outside the names with their tokens' places, and the pairs of neighbours among them */
    private def outside(seen: Seen, tags: Vector[Tag]): (Vector[(String, Int)], Vector[(String, Int)]) =
      def word(i: Int): String = seen.tokens(i).in(seen.text).trim.toLowerCase
      val at = seen.tokens.indices.toVector.filter(i => tags(i) == Tag.Outside && word(i).exists(_.isLetter))
      (at.map(i => word(i) -> i), at.filter(i => at.contains(i + 1)).map(i => (word(i) + " " + word(i + 1)) -> i))
    def learn(seen: Seen, tags: Vector[Tag]): Unit =
      val (ws, ps) = outside(seen, tags)
      words ++= ws.map(_._1)
      pairs ++= ps.map(_._1)
    /** what of a text was never met: the places (tokens) of its new words and of its new pairs */
    def news(seen: Seen, tags: Vector[Tag]): (Vector[Int], Vector[Int]) =
      val (ws, ps) = outside(seen, tags)
      (ws.filterNot((w, _) => words(w)).map(_._2), ps.filterNot((p, _) => pairs(p)).map(_._2))

  /** a trained reader: where the states are centred, the heads, the words met, and the least margin of each kind
   * of decision an answer is shown at, by language ("en tag") */
  final case class Trained(mean: Array[Float], std: Array[Float], heads: Heads, known: Known,
      bars: Map[String, Float], echo: Int = 0):
    def norm(x: Array[Float]): Array[Float] =
      Array.tabulate(x.length)(i => math.max(-4f, math.min(4f, (x(i) - mean(i)) / std(i))))

  /** why no answer is shown */
  enum Doubt:
    /** words of this sentence were never met in training */
    case Unfamiliar(sentence: Span, words: Vector[String])
    /** a decision of this sentence is under its bar */
    case Unsure(sentence: Span)
    /** no statement comes out, or it does not compile: the assembler's or the compiler's word */
    case NoStatement(why: String)

  /** a text read: the statement when one came out, the result when it compiled, the least margin of each kind of
   * decision, how many words and pairs were new — and the doubt, if the answer is not to be shown */
  final case class Reading(statement: Option[Statement], result: Option[Vector[String]], least: Map[String, Float],
      news: (Int, Int), doubt: Option[Doubt], settled: Vector[Span] = Vector.empty):
    def shown: Boolean = doubt.isEmpty && result.nonEmpty

  def russian(text: String): Boolean = text.exists(c => c >= 'а' && c <= 'я')

  def read(trained: Trained, seen: Seen): Reading =
    val heads = trained.heads
    val tagged = seen.reads.map(heads.tag)
    val tags = tagged.map(_._1)
    val by = ByHeads(seen, heads)
    val read = Assemble.assemble(Problem.Assign, seen.text, seen.tokens, tags, by)
    // a grid with no question: the answer is the whole grid, a line a position (§17)
    val result = read.flatMap(s => Faults.result(s, seen.text) match
      case Left("no question") => whole(s, seen.text)
      case other => other)
    def sentenceOf(token: Int): Int = seen.sentences.indexWhere(s =>
      s.from <= seen.tokens(token).from && seen.tokens(token).from < s.until)
    // a token inside a word another began decides nothing: a piece is whole words (`Assemble.words`)
    val margins = tagged.zipWithIndex.collect { case ((_, m), i) if !Assemble.within(seen.text, seen.tokens(i)) =>
      ("tag", m, sentenceOf(i)) } ++ by.margins
    val least = margins.groupMapReduce(_._1)(_._2)(math.min)
    val (words, pairs) = trained.known.news(seen, tags)
    val language = if russian(seen.text) then "ru" else "en"
    def bar(kind: String): Float = trained.bars.getOrElse(s"$language $kind", 0f)
    def sentence(i: Int): Span = seen.sentences.lift(i).getOrElse(Span(0, seen.text.length))
    val doubt: Option[Doubt] = (read, result) match
      case (Left(why), _) => Some(Doubt.NoStatement(why))
      case (_, Left(why)) => Some(Doubt.NoStatement(why))
      case (_, Right(_)) if words.nonEmpty || pairs.nonEmpty =>
        val at = (words ++ pairs).min
        // a token is a piece of a word: what is said to the person is the whole words it lies in
        def whole(from: Int, until: Int): String =
          val text = seen.text
          val a = Iterator.iterate(from)(_ - 1).dropWhile(i => i > 0 && text(i - 1).isLetterOrDigit).next()
          val b = Iterator.iterate(until)(_ + 1).dropWhile(i => i < text.length && text(i).isLetterOrDigit).next()
          text.substring(a, b).trim
        val said = (words.map(i => whole(seen.tokens(i).from, seen.tokens(i).until)) ++ pairs.map(i =>
          whole(seen.tokens(i).from, seen.tokens(i + 1).until))).distinct
        Some(Doubt.Unfamiliar(sentence(sentenceOf(at)), said.take(4)))
      case (_, Right(_)) =>
        margins.filter((k, m, _) => m < bar(k)).minByOption(_._2).map((_, _, i) => Doubt.Unsure(sentence(i)))
    // THE EXACT SIDE INSIDE THE READING (specs/formalization.md §8 point 1, §16): where the only unsure decisions
    // are a few sentences' frames, every reading of them among the heads' three most likely is solved; when all the
    // readings that give one answer give the SAME answer — the others refused, or none or several — that reading
    // is the answer, and the sentences it decided are said
    val unsure = margins.filter((k, m, _) => m < bar(k))
    val open = by.frameAt.filter(_._3 < bar("frame"))
    val settled: Option[(Statement, Vector[String], Vector[Span])] =
      if !doubt.exists(_.isInstanceOf[Doubt.Unsure]) || !unsure.forall(_._1 == "frame") || open.isEmpty ||
        open.size > 3 then None
      else
        val choices = open.map((i, k, _) => heads.ranked(seen, i, k).take(3).map(c => (i, k) -> c))
        val combos = choices.foldLeft(Vector(Vector.empty[((Int, Int), Option[Con])]))((acc, cs) =>
          for a <- acc; c <- cs yield a :+ c)
        val definite = combos.flatMap { combo =>
          val s = Assemble.assemble(Problem.Assign, seen.text, seen.tokens, tags, Forced(seen, heads, combo.toMap))
          s.toOption.flatMap(st => Faults.result(st, seen.text).toOption.filter(r => !Faults.undecided(r))
            .map(r => (st, r))) }
        Option.when(definite.nonEmpty && definite.map(_._2).distinct.size == 1)(
          (definite.head._1, definite.head._2, open.map((i, _, _) => sentence(i)).distinct))
    settled match
      case Some((st, r, spans)) =>
        Reading(Some(st), Some(r), least, (words.size, pairs.size), None, spans)
      case None => Reading(read.toOption, result.toOption, least, (words.size, pairs.size), doubt)

  /** a statement with no question solved whole: a line a position, its things in the order of the lists */
  def whole(s: Statement, text: String): Either[String, Vector[String]] =
    import okay.desk.puzzle.{Ref, Solver}
    Assign.compile(s, text, whole = true).flatMap {
      case okay.desk.tasks.Gold.Grid(p) => Solver.solve(p, 2) match
        case Solver.Outcome.One(grid, _) =>
          Right((1 to p.positions).toVector.map(k => s"$k: " + p.sorts.flatMap(so =>
            so.values.find(v => grid.get(Ref(so.name, v)).contains(k))).mkString(", ")))
        case Solver.Outcome.Several(_, _, _) => Right(Vector("several"))
        case Solver.Outcome.None(_) => Right(Vector("none"))
      case _ => Left("no question") }

  /** a result in the text's names: "none" and "several" are answers and are said as such (§8, point 4) */
  def answer(text: String, s: Statement, result: Vector[String], ru: Boolean): String =
    // a thing first said in another case («выше Романа») is named by its form that the other forms begin with
    def name(r: String): String = s.things.find(t => Statement.same(t.in(text), r)).fold(r) { thing =>
      val forms = s.mentions.filter(_.of == thing).map(_.at.in(text)).distinct
      forms.find(f => forms.exists(g => g != f && g.startsWith(f))).getOrElse(thing.in(text)) }
    result.map {
      case "several" => if ru then "Условия не определяют ответ: подходит несколько расстановок"
        else "The clues do not decide it: more than one arrangement fits"
      case "none" => if ru then "Условия противоречат друг другу: ни одна расстановка не подходит"
        else "The clues contradict each other: no arrangement fits"
      case r if r.contains('|') =>
        val names = r.split('|').toVector.map(name)
        if ru then s"Условия не определяют ответ: ${names.mkString(" или ")}"
        else s"The clues do not decide it: ${names.mkString(" or ")}"
      case "true" => if ru then "Да" else "Yes"
      case "false" => if ru then "Нет" else "No"
      case r => name(r) }.mkString(if result.forall(_.matches("^\\d+: .*")) then "\n" else "; ")

  // ---- a reader as a file: little-endian, a count before every array and every string ----

  def write(t: Trained): Array[Byte] =
    val floats = Vector(t.mean, t.std) ++ t.heads.arrays
    val strings = Vector(t.known.words.toVector.sorted.mkString("\n"), t.known.pairs.toVector.sorted.mkString("\n"),
      t.bars.toVector.sorted.map((k, v) => s"$k=$v").mkString("\n")).map(_.getBytes(UTF_8))
    val out = ByteBuffer.allocate(16 + floats.map(4 + 4 * _.length).sum + strings.map(4 + _.length).sum)
      .order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(magic).putInt(t.echo).putInt(t.heads.d).putInt(floats.size)
    for a <- floats do { out.putInt(a.length); a.foreach(out.putFloat) }
    for s <- strings do { out.putInt(s.length); out.put(s) }
    out.array()

  private val (first, magic) = (0x31524b4f, 0x32524b4f) // "OKR1" (no flags), "OKR2"

  def parse(bytes: Array[Byte]): Either[String, Trained] =
    try
      val in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val version = in.getInt()
      if version != magic && version != first then Left("not a statement reader's file")
      else
        val echo = if version == magic then in.getInt() & 3 else 0
        val heads = Heads(in.getInt())
        val n = in.getInt()
        val floats = Vector.fill(n) {
          val a = new Array[Float](in.getInt())
          a.indices.foreach(i => a(i) = in.getFloat())
          a }
        def string(): String = { val b = new Array[Byte](in.getInt()); in.get(b); String(b, UTF_8) }
        def lines(): Vector[String] = string().split("\n").toVector.filter(_.nonEmpty)
        val into = heads.arrays
        if floats.size != into.size + 2 || floats.drop(2).zip(into).exists((a, b) => a.length != b.length) then
          Left("a statement reader of another shape")
        else
          floats.drop(2).zip(into).foreach((a, b) => System.arraycopy(a, 0, b, 0, a.length))
          val known = Known(scala.collection.mutable.Set.from(lines()), scala.collection.mutable.Set.from(lines()))
          val bars = lines().map { l => val i = l.lastIndexOf('='); l.take(i) -> l.drop(i + 1).toFloat }.toMap
          Right(Trained(floats(0), floats(1), heads, known, bars, echo))
    catch case e: Exception => Left(s"a statement reader's file not read: ${e.getMessage}")
