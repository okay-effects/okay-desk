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
              if d.isEmpty || d.exists(_._1 == Fill.refuse) then Vector.empty
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

  /** the row a source lives in when the reading runs in `R`: the row's effects first, the reading's after (`Weigh`
   * handled out of it) — the caller builds its fills here, concretely, so no evidence is summoned for an abstract
   * row (that was the page's `Place.Last` mismatch: a `Sub` for an abstract `R` taken for a row of one effect) */
  type Rest[R[_]] = [X] =>> (R + Choose + Writer % Step)[X]

  /** GREEDY IN A ROW: the same as `greedy`, the source effectful (the model's tokens need `Read`): the row's own
   * effects come FIRST and the reading's last; a handler of an effect that is not the first takes it out of the row
   * as written, so after the three handlers the row is `R` again, with no renaming */
  def greedyIn[R[_]](g: Rules, ctx: Context, fill: Fill[Rest[R]], margin: Double = 0.0): (List[Step], Option[Term]) ! R =
    type Row[X] = (R + Choose + Weigh + Writer % Step)[X]
    val p: Term ! Row = program[Row](g, ctx, margin)
    val weighed: Term ! (R + Choose + Writer % Step) = handle(p, Weigh.by[R + Choose + Writer % Step, Term](fill))
    val first: Option[Term] ! (R + Writer % Step) = Choose.first(weighed)
    Writer.run[Step, R + Writer % Step, R, Option[Term]](first)

  /** ONE HOLE WEIGHED in a row (the dialogue asks it of the hole a reading stopped at, for the candidates to offer) */
  def weighOnce[R[_]](fill: Fill[Rest[R]], ty: Ty, cs: Vector[String], ctx: Context): Dist ! R =
    val d: Dist ! (R + Choose + Writer % Step) = fill(ty, cs, ctx)
    val first: Option[Dist] ! (R + Writer % Step) = Choose.first(d)
    Writer.run[Step, R + Writer % Step, R, Option[Dist]](first).map(_._2.getOrElse(Vector.empty))
