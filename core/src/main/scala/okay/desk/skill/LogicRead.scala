package okay.desk.skill

import okay.desk.calc.{Term, Ty}
import okay.desk.exec.Wire
import okay.desk.logic.LogicGrammar
import okay.desk.skill.Extract.{Choice, Evidence, Reading}
import okay.scala3.*

/**
 * THE READING HEADS OF THE LOGIC GRAMMAR (specs/logic.md §5.4): a linear head over the student's state at the last
 * token of the prompt "task, then the constructors chosen so far" chooses the next constructor among the hole's
 * options. The grammar narrows every decision to what it offers — a predicate at a goal, a relation at an atom, a
 * variable or a constant at an argument — so the head never picks a letter the grammar would refuse, and a margin
 * below the calibrated one stops the reading: an open hole is a question for the person, not a guess.
 *
 * A constant is copied, and a copied constant is read by a POINTER as well as by the linear head: the query is the
 * state at the constant's hole, the keys are the states of the task's words in the SAME prompt, and the ontology's
 * own `Known` is the pointer's "none" key. The linear head says "a constant here"; the pointer says which one, from
 * the words themselves. A pointer margin below the calibrated one stops the reading too.
 *
 * The trainer is masked: only the hole's options compete, so the head learns the grammar's decisions rather than a
 * language of constructors. `steps` gives the training examples of a gold reading, one per hole.
 */
object LogicRead:
  val System = "Read the task and write its program, one constructor at a time."
  /** the prompt: the chat template's assistant header, then `Program: ` and the constructors chosen so far, then the
   * hole's options — the head reads the state where the next constructor would be written, as a decoder continues
   * its own text, and the options as text give a small student the words to match the task against */
  def prompt(task: String, prefix: Vector[String], options: Vector[String] = Vector.empty): String =
    OnQwen.template(System, task) + "Program: " + prefix.mkString(" ") +
      (if options.isEmpty then "" else "\nChoices: " + options.mkString(" "))

  /** a word of the task, with its character span */
  final case class Word(text: String, from: Int, until: Int)

  /** where the task's words sit in the prompt's tokens: found once per task, the same for every prefix */
  final case class Layout(words: Vector[Word], at: Vector[Int])

  object Layout:
    val empty: Layout = Layout(Vector.empty, Vector.empty)

  /** the state at the hole, and the states of the task's words in the same prompt */
  final case class Features(last: Array[Float], words: Vector[Word] = Vector.empty,
      states: Vector[Array[Float]] = Vector.empty)

  /** one step of a gold word: the prefix, the hole's type and options, and the constructor the gold takes */
  final case class Step(prefix: Vector[String], options: Vector[String], target: String, ty: Ty)

  final case class Parameters(layer: Int, mean: Vector[Float], std: Vector[Float], letters: Vector[String],
      weights: Vector[Vector[Float]], bias: Vector[Float], margin: Float,
      pointerQuery: Vector[Vector[Float]] = Vector.empty, pointerKey: Vector[Vector[Float]] = Vector.empty,
      pointerNone: Vector[Float] = Vector.empty, pointerMargin: Float = 0f) derives Wire

  private val wordPattern = "[\\p{L}\\p{N}]+(?:[-’'][\\p{L}\\p{N}]+)*".r

  /** the task's words, whole: `orders-db` is one word, and a name's punctuation is not part of it */
  def wordsOf(text: String): Vector[Word] =
    wordPattern.findAllMatchIn(text).map(m => Word(m.matched, m.start, m.end)).toVector

  /** the prompt's token position of each of the task's words: `Layout.empty` when the task cannot be located or a
   * word has no token — the reader then falls back to the linear head */
  def layout[R[_]](task: String)(using In[Read, R]): Layout ! R =
    for
      ids <- Read.encode[R](prompt(task, Vector.empty))
      text <- Read.decode[R](ids.toVector)
      ends <- Read.each[Int, (Int, Int), R](ids.indices.toVector) { i =>
        Read.decode[R](ids.take(i + 1).toVector).map(s => (i, if text.startsWith(s) then s.length else -1))
      }
      base = text.indexOf(task)
      ws = wordsOf(task)
      at = ws.map(w => ends.find(_._2 >= base + w.until).map(_._1))
      result <-
        if base < 0 || ids.isEmpty || ids.length > 512 || ws.isEmpty || at.exists(_.isEmpty) then
          pure(Layout.empty)
        else pure(Layout(ws, at.flatten))
    yield result

  /** the state after the task and the prefix, and the states of the task's words in the same prompt: what the head
   * and the pointer read at a hole */
  def features[R[_]](task: String, prefix: Vector[String], layout: Layout = Layout.empty,
      options: Vector[String] = Vector.empty)(using In[Read, R]): Features ! R =
    for
      ids <- Read.encode[R](prompt(task, prefix, options))
      states <- Read.states[R](ids, layout.at :+ (ids.length - 1))
    yield Features(states.last, layout.words, states.dropRight(1))

  /** the options shown to the student at a hole: the goal's predicates as text — the words to match the question
   * against — while the grammar's other holes are structural and their options would only lengthen the prompt */
  def shown(g: LogicGrammar, ty: Ty): Vector[String] =
    if ty == Ty.GoalT then g.constructors(ty) else Vector.empty

  /** the gold word as steps: at each hole, the prefix before it and the grammar's options */
  def steps(g: LogicGrammar, gold: Reading): Vector[Step] =
    val letters = Extract.word(gold)
    var t: Term = Term.Hole(Ty.LogicT)
    val out = Vector.newBuilder[Step]
    for (letter, i) <- letters.zipWithIndex do
      g.nextHole(t) match
        case None => ()
        case Some((ty, _, put)) =>
          out += Step(letters.take(i), g.constructors(ty), letter, ty)
          t = put(g.apply(ty, letter))
    out.result()

  def normalize(x: Array[Float], mean: Vector[Float], std: Vector[Float]): Array[Float] =
    x.indices.map(i => math.max(-4f, math.min(4f, (x(i) - mean(i)) / std(i)))).toArray

  /** the pointer's input scale (`Readers`' `8/sqrt(d)`): the bilinear maps start at `1/sqrt(d)`, so a unit-norm
   * state would score near zero and learn too slowly, while a raw normalized state made the gradient diverge */
  def scale(x: Array[Float], d: Int): Array[Float] =
    val s = 8f / math.sqrt(d).toFloat
    x.map(_ * s)

  /** the constants the pointer may choose: the hole's options that the task says, each with the occurrence the
   * evidence would cite, and the state of that word in the prompt */
  def copiesAt(g: LogicGrammar, task: String, ty: Ty, from: Int, words: Vector[Word],
      states: Vector[Array[Float]]): Vector[(String, Extract.Span, Array[Float])] =
    def state(span: Extract.Span): Option[Array[Float]] =
      val i = words.indexWhere(w => w.from == span.start && w.until == span.end)
      Option.when(i >= 0 && i < states.size)(states(i))
    g.constructors(ty).filter(c => Extract.copies(ty, c)).flatMap { c =>
      Extract.find(task, c, from).orElse(Extract.find(task, c))
        .flatMap(span => state(span).map(s => (c, span, s)))
    }

  /** a masked softmax over the grammar's letters: only the hole's options compete */
  final class Masked(val letters: Vector[String], d: Int):
    private val index: Map[String, Int] = letters.zipWithIndex.toMap
    val weights: Array[Array[Float]] = Array.fill(letters.size)(Array.fill(d)(0f))
    val bias: Array[Float] = Array.fill(letters.size)(0f)

    def score(k: Int, x: Array[Float]): Float =
      var s = bias(k)
      var i = 0
      while i < d do { s += weights(k)(i) * x(i); i += 1 }
      s

    /** the options ranked by their score, best first: what the head would choose at a hole */
    def scores(x: Array[Float], options: Vector[String]): Vector[(String, Float)] =
      options.flatMap(c => index.get(c).map(k => c -> score(k, x))).sortBy(-_._2)

    /** one pass of mini-batch gradient descent; an example whose target is not a letter is skipped */
    def train(examples: Vector[(Array[Float], Vector[String], String)], epochs: Int, lr: Float,
        seed: Int = 1, batch: Int = 32): Unit =
      val rnd = scala.util.Random(seed)
      for _ <- 0 until epochs do
        val order = rnd.shuffle(examples)
        var from = 0
        while from < order.size do
          val slice = order.slice(from, from + batch)
          val gw = Array.fill(letters.size)(Array.fill(d)(0f))
          val gb = Array.fill(letters.size)(0f)
          var n = 0
          for (x, options, target) <- slice if index.contains(target) do
            val ks = options.flatMap(index.get)
            val scored = ks.map(k => k -> score(k, x))
            val m = scored.map(_._2).max
            val es = scored.map((k, v) => k -> math.exp((v - m).toDouble).toFloat)
            val z = es.map(_._2).sum
            for (k, ev) <- es do
              val g = ev / z - (if letters(k) == target then 1f else 0f)
              gb(k) += g
              var i = 0
              while i < d do { gw(k)(i) += g * x(i); i += 1 }
            n += 1
          if n > 0 then
            val rate = lr / n
            for k <- letters.indices do
              bias(k) -= rate * gb(k)
              var i = 0
              while i < d do { weights(k)(i) -= rate * (gw(k)(i) + 1e-3f * weights(k)(i)); i += 1 }
          from += batch

  def parameters(m: Masked, pointer: Probes.Pointer, layer: Int, mean: Vector[Float], std: Vector[Float],
      margin: Float, pointerMargin: Float): Parameters =
    Parameters(layer, mean, std, m.letters, m.weights.toVector.map(_.toVector), m.bias.toVector, margin,
      pointer.wq.toVector.map(_.toVector), pointer.wk.toVector.map(_.toVector), pointer.none.toVector, pointerMargin)

  final class Reader(val parameters: Parameters):
    private val d = parameters.mean.size
    private val index: Map[String, Int] = parameters.letters.zipWithIndex.toMap
    require(d > 0 && parameters.std.size == d && parameters.std.forall(s => s.isFinite && s > 0))
    require(parameters.weights.size == parameters.letters.size && parameters.weights.forall(_.size == d))
    require(parameters.bias.size == parameters.letters.size)
    require(parameters.margin.isFinite && parameters.margin >= 0)
    require(parameters.pointerMargin.isFinite && parameters.pointerMargin >= 0)
    require((parameters.mean ++ parameters.weights.flatten ++ parameters.bias ++ parameters.pointerNone ++
      parameters.pointerQuery.flatten ++ parameters.pointerKey.flatten).forall(_.isFinite))

    /** the pointer rebuilt from its `Wire` weights; absent when the head has none (a linear reader) */
    private val pointer: Option[Probes.Pointer] =
      val k = parameters.pointerNone.size
      if k == 0 then None
      else
        require(parameters.pointerQuery.size == k && parameters.pointerQuery.forall(_.size == d))
        require(parameters.pointerKey.size == k && parameters.pointerKey.forall(_.size == d))
        val p = Probes.Pointer(d, k)
        for i <- 0 until k do
          Array.copy(parameters.pointerQuery(i).toArray, 0, p.wq(i), 0, d)
          Array.copy(parameters.pointerKey(i).toArray, 0, p.wk(i), 0, d)
          p.none(i) = parameters.pointerNone(i)
        Some(p)

    private def score(k: Int, x: Array[Float]): Float =
      var s = parameters.bias(k)
      var i = 0
      while i < d do { s += parameters.weights(k)(i) * x(i); i += 1 }
      s

    private def rank(x: Array[Float], options: Vector[String]): Vector[(String, Float)] =
      options.flatMap(c => index.get(c).map(k => c -> score(k, x))).sortBy(-_._2)

    private def evidence(task: String, ty: Ty, con: String, from: Int): Option[Evidence] =
      if !Extract.copies(ty, con) then None
      else
        Extract.find(task, con, from).orElse(Extract.find(task, con))
          .map(Evidence.Text.apply).orElse(Some(Evidence.Known))

    /** a copied constant: the head said "a constant", the pointer says which of the task's words, and `Known` is
     * the pointer's "none" key. A pointer margin below the calibrated one stops the reading. */
    private def pointed(g: LogicGrammar, task: String, ty: Ty, con: String, x: Array[Float], from: Int,
        f: Features): Option[Choice] =
      val fallback = Some(Choice(con, evidence(task, ty, con, from)))
      if f.states.isEmpty || f.words.size != f.states.size then fallback
      else
        pointer match
          case None => fallback
          case Some(p) =>
            val candidates = LogicRead.copiesAt(g, task, ty, from, f.words, f.states)
            if candidates.isEmpty then fallback
            else
              val keys = candidates.map(c => scale(normalize(c._3, parameters.mean, parameters.std), d))
              val scores = p.scores(scale(x, d), keys)
              val ranked = scores.indices.sortBy(i => -scores(i))
              if ranked.size < 2 || scores(ranked(0)) - scores(ranked(1)) < parameters.pointerMargin then None
              else if ranked(0) == candidates.size then fallback
              else
                val (c, span, _) = candidates(ranked(0))
                Some(Choice(c, Some(Evidence.Text(span))))

    /** the choice at one hole, from the state: None when the margin is not met. A copy cites the first whole-word
     * occurrence at or after `from`, as the gold reading does, so a repeated constant does not cite an earlier word */
    def decide(g: LogicGrammar, task: String, ty: Ty, f: Features, from: Int = 0): Option[Choice] =
      if f.last.length != d || !f.last.forall(_.isFinite) then None
      else
        val x = normalize(f.last, parameters.mean, parameters.std)
        val ranked = rank(x, g.constructors(ty))
        if ranked.isEmpty then None
        else
          val c1 = ranked.head._1
          val taken =
            if ranked.size == 1 then true
            else ranked.head._2 - ranked(1)._2 >= parameters.margin
          if !taken then None
          else if !Extract.copies(ty, c1) then Some(Choice(c1, None))
          else pointed(g, task, ty, c1, x, from, f)

    /** the reading the head wrote, closed or open: an open reading is an ABSTENTION — `Extract.replay` and
     * `g.closed` say so, and the caller asks the person rather than showing an answer */
    def read[R[_]](g: LogicGrammar, task: String, layout: Layout = Layout.empty)(using In[Read, R]): Reading ! R =
      def go(prefix: Vector[String], term: Term, choices: Vector[Choice], from: Int): Reading ! R =
        if choices.size >= 512 then pure(Reading(task, choices))
        else
          g.nextHole(term) match
            case None => pure(Reading(task, choices))
            case Some((ty, _, put)) =>
              features[R](task, prefix, layout, LogicRead.shown(g, ty)).flatMap { f =>
                decide(g, task, ty, f, from) match
                  case None => pure(Reading(task, choices))
                  case Some(c) =>
                    val next = c.evidence.collect { case Evidence.Text(s) => s.end }.getOrElse(from)
                    go(prefix :+ c.con, put(g.apply(ty, c.con)), choices :+ c, next)
              }
      go(Vector.empty, Term.Hole(Ty.LogicT), Vector.empty, 0)

  def parse(text: String): Either[String, Reader] =
    Wire.decode[Parameters](text).flatMap { p =>
      try Right(Reader(p)) catch case e: Exception => Left("invalid logic reader: " + e.getMessage)
    }
