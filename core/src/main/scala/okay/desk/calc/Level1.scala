package okay.desk.calc

import okay.scala3.*

/**
 * LEVEL 1 — THE WORD CALCULUS (specs/calculus.md §7): a reading is a WORD over an alphabet that changes at every
 * step. The state is a context (whatever the reading is about: here a question over tables) and the word so far;
 * `next` gives the letters allowed after the word — or none: the word is complete. The exact facts live in `next`:
 * when the context decides a letter (a name written in the question), `next` returns ONE letter and no chooser is
 * asked — exactness is a property of the grammar, not a strategy. Interpretations take a complete word to a
 * domain: a table (the engines), a sentence (an answer), an effect (an action).
 *
 * An instance of level 0: states (ctx, word), moves letters, step = append, enabled = next. Its own laws: `next`
 * narrowing to one letter is right (checked against gold words); a complete word interprets; the same word from
 * the same choices (level 0's determinism).
 */
object Word:
  type Letters = Vector[String]
  /** the alphabet after a word: None when the word is complete */
  trait Next[C]:
    def apply(ctx: C, word: Letters): Option[Vector[String]]
    /** an open alphabet after the word: the letters given are examples, any letter is allowed */
    def open(ctx: C, word: Letters): Boolean = false
  /** an interpretation of a complete word */
  trait Interp[C, I]:
    def apply(ctx: C, word: Letters): Either[String, I]

  /** the word calculus as a choice calculus */
  def choice[C](next: Next[C]): Choice[(C, Letters), String] = new Choice[(C, Letters), String]:
    def enabled(s: (C, Letters)): Vector[String] = next(s._1, s._2).getOrElse(Vector.empty)
    def step(s: (C, Letters), m: String): (C, Letters) = (s._1, s._2 :+ m)
    override def open(s: (C, Letters)): Boolean = next.open(s._1, s._2)

  /** a chooser over letters from a function of the context, the word and the letters allowed */
  def chooser[C, R[_]](f: (C, Letters, Vector[String]) => Vector[(String, Double)] ! R): Chooser[(C, Letters), String, R] =
    new Chooser[(C, Letters), String, R]:
      def apply(s: (C, Letters), en: Vector[String]): Vector[(String, Double)] ! R = f(s._1, s._2, en)

  /** the oracle of level 1: the gold word's letter at the word's length */
  def oracle[C, R[_]](gold: Letters): Chooser[(C, Letters), String, R] =
    chooser((_, w, _) => pure(gold.lift(w.size).map(l => Vector((l, 0.0))).getOrElse(Vector.empty)))

  /** a reading: the run of the choice calculus from the empty word */
  def read[C, R[_]](next: Next[C], choose: Chooser[(C, Letters), String, R], ctx: C, margin: Double = 0.0): Choice.Run[(C, Letters), String] ! R =
    Choice.run(choice(next), choose, (ctx, Vector.empty), margin)

  /** THE LAW OF EXACTNESS: wherever `next` narrows to one letter along the gold word, that letter is the gold's */
  def narrowsRight[C](next: Next[C], ctx: C, gold: Letters): Boolean = contradiction(next, ctx, gold).isEmpty
  /** the first place the law fails: the position, the letter narrowed to, the gold's letter */
  def contradiction[C](next: Next[C], ctx: C, gold: Letters): Option[(Int, String, String)] =
    gold.indices.collectFirst(Function.unlift { i =>
      next(ctx, gold.take(i)) match
        case Some(Vector(only)) if only != gold(i) => Some((i, only, gold(i)))
        case _ => None
    }).orElse(if next(ctx, gold).isEmpty then None else Some((gold.size, "more", "end")))
