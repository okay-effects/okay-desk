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
 * A constant is copied: the head's choice is one of the ontology's constants, and the evidence is the first whole
 * word of the task that says it, or `Known` when the text does not (the ontology's own threshold). The contract
 * checks the copy; a wrong constant is a wrong-closed answer and the metric says so.
 *
 * The trainer is masked: only the hole's options compete, so the head learns the grammar's decisions rather than a
 * language of constructors. `steps` gives the training examples of a gold reading, one per hole.
 */
object LogicRead:
  /** the prompt: the task, then the constructors chosen so far — the head reads the next one */
  val System = "Read the task and write its program, one constructor at a time."
  def user(task: String, prefix: Vector[String]): String =
    task + "\n\nProgram: " + prefix.mkString(" ")

  final case class Features(last: Array[Float])

  /** one step of a gold word: the prefix, the hole's options, and the constructor the gold takes */
  final case class Step(prefix: Vector[String], options: Vector[String], target: String)

  final case class Parameters(layer: Int, mean: Vector[Float], std: Vector[Float], letters: Vector[String],
      weights: Vector[Vector[Float]], bias: Vector[Float], margin: Float) derives Wire

  /** the state after the task and the prefix: what the head reads at a hole */
  def features[R[_]](task: String, prefix: Vector[String])(using In[Read, R]): Features ! R =
    for
      ids <- Read.prompt[R](System, user(task, prefix))
      states <- Read.states[R](ids, Vector(ids.length - 1))
    yield Features(states.head)

  /** the gold word as steps: at each hole, the prefix before it and the grammar's options */
  def steps(g: LogicGrammar, gold: Reading): Vector[Step] =
    val letters = Extract.word(gold)
    var t: Term = Term.Hole(Ty.LogicT)
    val out = Vector.newBuilder[Step]
    for (letter, i) <- letters.zipWithIndex do
      g.nextHole(t) match
        case None => ()
        case Some((ty, _, put)) =>
          out += Step(letters.take(i), g.constructors(ty), letter)
          t = put(g.apply(ty, letter))
    out.result()

  def normalize(x: Array[Float], mean: Vector[Float], std: Vector[Float]): Array[Float] =
    x.indices.map(i => math.max(-4f, math.min(4f, (x(i) - mean(i)) / std(i)))).toArray

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

  def parameters(m: Masked, layer: Int, mean: Vector[Float], std: Vector[Float], margin: Float): Parameters =
    Parameters(layer, mean, std, m.letters, m.weights.toVector.map(_.toVector), m.bias.toVector, margin)

  final class Reader(val parameters: Parameters):
    private val d = parameters.mean.size
    private val index: Map[String, Int] = parameters.letters.zipWithIndex.toMap
    require(d > 0 && parameters.std.size == d && parameters.std.forall(s => s.isFinite && s > 0))
    require(parameters.weights.size == parameters.letters.size && parameters.weights.forall(_.size == d))
    require(parameters.bias.size == parameters.letters.size)
    require(parameters.margin.isFinite && parameters.margin >= 0)
    require((parameters.mean ++ parameters.weights.flatten ++ parameters.bias).forall(_.isFinite))

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

    /** the choice at one hole, from the state: None when the margin is not met. A copy cites the first whole-word
     * occurrence at or after `from`, as the gold reading does, so a repeated constant does not cite an earlier word */
    def decide(g: LogicGrammar, task: String, ty: Ty, f: Features, from: Int = 0): Option[Choice] =
      if f.last.length != d || !f.last.forall(_.isFinite) then None
      else
        val x = normalize(f.last, parameters.mean, parameters.std)
        val ranked = rank(x, g.constructors(ty))
        if ranked.isEmpty then None
        else if ranked.size == 1 then Some(Choice(ranked.head._1, evidence(task, ty, ranked.head._1, from)))
        else
          val (c1, s1) = ranked(0)
          val s2 = ranked(1)._2
          Option.when(s1 - s2 >= parameters.margin)(Choice(c1, evidence(task, ty, c1, from)))

    /** the reading the head wrote, closed or open: an open reading is an ABSTENTION — `Extract.replay` and
     * `g.closed` say so, and the caller asks the person rather than showing an answer */
    def read[R[_]](g: LogicGrammar, task: String)(using In[Read, R]): Reading ! R =
      def go(prefix: Vector[String], term: Term, choices: Vector[Choice], from: Int): Reading ! R =
        if choices.size >= 512 then pure(Reading(task, choices))
        else
          g.nextHole(term) match
            case None => pure(Reading(task, choices))
            case Some((ty, _, put)) =>
              features[R](task, prefix).flatMap { f =>
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
