package okay.desk.calc

import okay.scala3.*
import okay.scala3.std.{Choose, Writer}

/**
 * THE READING ON THE STANDARD EFFECTS (specs/calculus.md §9): the branching is okay-scala's own `Choose.among`
 * (multi-shot nondeterminism), the steps are a `Writer`, the only NEW operation is `Weigh.Of`. The program is one;
 * the STRATEGY is how `Among` is handled: `Choose.first` takes the best-weighed way — greedy; `Choose.all` with a
 * uniform weigh takes every way — the ENUMERATION OF THE SPACE, which is thus the nondeterministic semantics of
 * the same reading, not a second piece of code; a beam is a third handler of the same program. An abstention is
 * `Choose.none`: no way on from here; the steps written say where.
 */
object Strategy:
  type Step = (Ty, Term)

  /** THE PROGRAM: the leftmost hole, its candidates, their weighing, a choice among them in the weighed order */
  def program[R[_]: Has[Choose]: Has[Weigh]: Has[Writer % Step]](g: Rules, ctx0: Context, margin: Double = 0.0, limit: Int = 60): Term ! R =
    def loop(ctx: Context, n: Int): Term ! R =
      g.nextHole(ctx.term) match
        case None => pure(ctx.term)
        case Some(_) if n >= limit => Choose.none
        case Some((ty, _, put)) =>
          val cs = g.constructors(ty)
          val ordered: Vector[String] ! R =
            if cs.size <= 1 then pure(cs)
            else Weigh.pick[R](ty, cs, ctx).map { d =>
              if d.isEmpty then Vector.empty
              else
                val sorted = d.sortBy(-_._2)
                if sorted.size >= 2 && sorted(0)._2 - sorted(1)._2 < margin then Vector.empty else sorted.map(_._1)
            }
          for
            ways <- ordered
            _ <- Writer.tell[Step, R]((ty, ctx.term))
            c <- Choose.among[String, R](ways.toList)
            next <- loop(ctx.copy(term = put(g(ty, c))), n + 1)
          yield next
    loop(ctx0, 0)

  /** GREEDY: the first way, weighed by the source — the term, or None with the steps up to the hole no source decided */
  def greedy(g: Rules, ctx: Context, fill: Fill[Pure], margin: Double = 0.0): (List[Step], Option[Term]) =
    type Row[X] = (Choose + Weigh + Writer % Step)[X]
    val p: Term ! Row = program[Row](g, ctx, margin)
    val weighed: Term ! (Choose + Writer % Step) = handle(p, Weigh.by[Choose + Writer % Step, Term](Fill.lift(fill)))
    val first: Option[Term] ! (Pure + Writer % Step) = Choose.first(weighed)
    run(Writer.run[Step, Pure + Writer % Step, Pure, Option[Term]](first))

  /** EVERY WAY, weighed uniformly: the enumeration of the space by the reading itself */
  def every(g: Rules, ctx: Context): List[Term] =
    val uniform: Fill[Pure] = new Fill[Pure]:
      def apply(ty: Ty, cs: Vector[String], c: Context): Dist ! Pure = pure(cs.map(x => (x, 0.0)))
    type Row[X] = (Choose + Weigh + Writer % Step)[X]
    val p: Term ! Row = program[Row](g, ctx)
    val weighed: Term ! (Choose + Writer % Step) = handle(p, Weigh.by[Choose + Writer % Step, Term](Fill.lift(uniform)))
    val all: List[Term] ! (Pure + Writer % Step) = Choose.all(weighed)
    run(Writer.run[Step, Pure + Writer % Step, Pure, List[Term]](all))._2
