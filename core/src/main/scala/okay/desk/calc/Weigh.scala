package okay.desk.calc

import okay.scala3.*

/**
 * THE ONE OPERATION AS AN EFFECT (specs/calculus.md §8): a hole is a pending operation `Of` — "a distribution
 * over these candidates of this type, from this context" — and a reading is a program that performs it
 * (`Strategy.program`, on the standard effects) until the term is closed or the weigh is empty or too close. The SOURCES are handlers of `Of`: the
 * data, the model's own tokens, a reader of states, the oracle, a person. A handler with no opinion FORWARDS the
 * operation to the handler outside it — the composition of sources is the nesting of handlers, nothing else. So
 * the calculus is the okay rule itself: the model's output is data (a term), the handlers run it.
 */
enum Weigh[X]:
  case Of(ty: Ty, candidates: Vector[String], ctx: Context) extends Weigh[Dist]

object Weigh:
  def pick[R[_]](ty: Ty, candidates: Vector[String], ctx: Context)(using In[Weigh, R]): Dist ! R =
    perform[Weigh](Of(ty, candidates, ctx))

  /** a source as the LAST handler: its opinion is the answer, empty included (the reading stops there) */
  def by[R[_], A](fill: Fill[R]): Handler[Weigh, R, A, A] =
    Handler.general[Weigh, R, A, A](pure)([X] => (op: Weigh[X], k: X => A ! R) => op match
      case Of(ty, cs, ctx) => fill(ty, cs, ctx).flatMap(k))

  /** a source FIRST: its opinion when it has one, else the operation forwarded to the handler outside */
  def first[R[_]: Has[Weigh], A](fill: Fill[R]): Handler[Weigh, R, A, A] =
    Handler.general[Weigh, R, A, A](pure)([X] => (op: Weigh[X], k: X => A ! R) => op match
      case p @ Of(ty, cs, ctx) => fill(ty, cs, ctx).flatMap(d => if d.nonEmpty then k(d) else perform[Weigh](p).flatMap(k)))

  /** no source at all: every pick empty — the data's narrowing alone (level 1 with no chooser) */
  def nobody[R[_], A]: Handler[Weigh, R, A, A] =
    Handler.tail[Weigh, R, A, A](pure)([X] => (op: Weigh[X]) => op match { case Of(_, _, _) => (Vector.empty: Dist) })
