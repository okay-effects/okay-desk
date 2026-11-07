package okay.desk.calc

import okay.scala3.*

/**
 * THE ONE OPERATION AS AN EFFECT (specs/calculus.md §8): a hole is a pending operation `Pick` — "a distribution
 * over these candidates of this type, from this context" — and a reading is a program of `Choose`: the leftmost
 * hole picked until the term is closed or the pick is empty or too close. The SOURCES are handlers of `Pick`: the
 * data, the model's own tokens, a reader of states, the oracle, a person. A handler with no opinion FORWARDS the
 * operation to the handler outside it — the composition of sources is the nesting of handlers, nothing else. So
 * the calculus is the okay rule itself: the model's output is data (a term), the handlers run it.
 */
enum Choose[X]:
  case Pick(ty: Ty, candidates: Vector[String], ctx: Context) extends Choose[Dist]

object Choose:
  def pick[R[_]](ty: Ty, candidates: Vector[String], ctx: Context)(using In[Choose, R]): Dist ! R =
    perform[Choose](Pick(ty, candidates, ctx))

  /** REFINEMENT AS A PROGRAM: the same loop as `Refine.greedy`, the pick performed instead of a fill called */
  def refine[R[_]: Has[Choose]](g: Grammar, ctx0: Context, margin: Double = 0.0, limit: Int = 40): Refine.Done ! R =
    def loop(ctx: Context, steps: Vector[(Ty, String)], n: Int): Refine.Done ! R =
      g.nextHole(ctx.term) match
        case None => pure(Refine.Done(ctx.term, steps, None))
        case Some(_) if n >= limit => pure(Refine.Done(ctx.term, steps, None))
        case Some((ty, _, put)) =>
          val cs = g.constructors(ty)
          if cs.isEmpty then pure(Refine.Done(ctx.term, steps, Some((ty, cs))))
          else if cs.size == 1 then loop(ctx.copy(term = put(g(ty, cs.head))), steps :+ (ty, cs.head), n + 1)
          else pick(ty, cs, ctx).flatMap { d =>
            if d.isEmpty then pure(Refine.Done(ctx.term, steps, Some((ty, cs))))
            else
              val sorted = d.sortBy(-_._2)
              if sorted.size >= 2 && sorted(0)._2 - sorted(1)._2 < margin then pure(Refine.Done(ctx.term, steps, Some((ty, sorted.take(2).map(_._1)))))
              else loop(ctx.copy(term = put(g(ty, sorted.head._1))), steps :+ (ty, sorted.head._1), n + 1)
          }
    loop(ctx0, Vector.empty, 0)

  /** a source as the LAST handler: its opinion is the answer, empty included (the reading stops there) */
  def by[R[_], A](fill: Fill[R]): Handler[Choose, R, A, A] =
    Handler.general[Choose, R, A, A](pure)([X] => (op: Choose[X], k: X => A ! R) => op match
      case Pick(ty, cs, ctx) => fill(ty, cs, ctx).flatMap(k))

  /** a source FIRST: its opinion when it has one, else the operation forwarded to the handler outside */
  def first[R[_]: Has[Choose], A](fill: Fill[R]): Handler[Choose, R, A, A] =
    Handler.general[Choose, R, A, A](pure)([X] => (op: Choose[X], k: X => A ! R) => op match
      case p @ Pick(ty, cs, ctx) => fill(ty, cs, ctx).flatMap(d => if d.nonEmpty then k(d) else perform[Choose](p).flatMap(k)))

  /** no source at all: every pick empty — the data's narrowing alone (level 1 with no chooser) */
  def nobody[R[_], A]: Handler[Choose, R, A, A] =
    Handler.tail[Choose, R, A, A](pure)([X] => (op: Choose[X]) => op match { case Pick(_, _, _) => (Vector.empty: Dist) })
