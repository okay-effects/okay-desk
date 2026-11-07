package okay.desk.calc

import okay.scala3.*

/**
 * LEVEL 0 — THE CHOICE CALCULUS (specs/calculus.md §7): the simplest level, nothing about words, types or programs.
 * A state, the moves ENABLED in it (none: the state is terminal), a STEP by a move, and a CHOOSER that weighs the
 * enabled moves. A run is the loop "weigh the enabled moves, take the best, step" until terminal — or until the
 * chooser has no opinion or two moves are too close: the run STOPS in a non-terminal state, which is a value (an
 * abstention), not a failure. Every other level is an instance: it says what the states, the moves and the
 * enabling are; the chooser is what a model (or a reader, or a person) is at every level.
 *
 * Its own laws: a run takes at most `bound` steps; the same chooser gives the same run (determinism); a run that
 * ends terminal is a run no step of which left the enabled moves (well-formed by construction).
 */
trait Choice[S, M]:
  /** the moves enabled in the state; empty = terminal */
  def enabled(s: S): Vector[M]
  def step(s: S, m: M): S
  /** an OPEN state: the enabled moves are examples, any move the chooser names is allowed (a number compared against) */
  def open(s: S): Boolean = false

/** a chooser: a distribution over the enabled moves, EMPTY = no opinion */
trait Chooser[S, M, R[_]]:
  def apply(s: S, enabled: Vector[M]): Vector[(M, Double)] ! R

object Choice:
  /** a run: the states passed, and why it ended — terminal, or stopped at a state with the moves it could not choose among */
  final case class Run[S, M](last: S, moves: Vector[M], stopped: Option[Vector[M]]):
    def terminal: Boolean = stopped.isEmpty

  def run[S, M, R[_]](c: Choice[S, M], choose: Chooser[S, M, R], s0: S, margin: Double = 0.0, bound: Int = 64): Run[S, M] ! R =
    def loop(s: S, taken: Vector[M], n: Int): Run[S, M] ! R =
      val en = c.enabled(s)
      if en.isEmpty then pure(Run(s, taken, None))
      else if n >= bound then pure(Run(s, taken, Some(en)))
      else if en.size == 1 then loop(c.step(s, en.head), taken :+ en.head, n + 1) // no choice: no chooser
      else choose(s, en).flatMap { d =>
        val sorted = d.filter((m, _) => c.open(s) || en.contains(m)).sortBy(-_._2)
        if sorted.isEmpty then pure(Run(s, taken, Some(en)))
        else if sorted.size >= 2 && sorted(0)._2 - sorted(1)._2 < margin then pure(Run(s, taken, Some(sorted.take(2).map(_._1))))
        else loop(c.step(s, sorted.head._1), taken :+ sorted.head._1, n + 1)
      }
    loop(s0, Vector.empty, 0)

  /** a chooser that takes a given sequence (an oracle at level 0: the moves of a known run); `position` counts the
   * moves taken from the state, since a move with no alternative is taken without the chooser */
  def replay[S, M, R[_]](moves: Vector[M], position: S => Int): Chooser[S, M, R] = new Chooser[S, M, R]:
    def apply(s: S, en: Vector[M]): Vector[(M, Double)] ! R =
      pure(moves.lift(position(s)).map(x => Vector((x, 0.0))).getOrElse(Vector.empty))
