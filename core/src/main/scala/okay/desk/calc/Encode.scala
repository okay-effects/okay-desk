package okay.desk.calc

import okay.desk.*
import okay.desk.Space.Schema
import okay.scala3.*
import Term.*

/**
 * LEVEL 2 DESCRIBED BY LEVEL 1 (specs/calculus.md §7): a typed term IS a word — the constructors chosen in order
 * (preorder); the tree is rebuilt from the word by replaying the grammar; the leftmost hole's candidates are the
 * letters allowed next, narrowed to one where the data decides (the exact fill as part of `next`); a level-2 fill
 * is a level-1 chooser; the denotation is an interpretation of the complete word. The laws: `tree(word(t)) = t`;
 * a level-2 refinement and the level-1 reading with the same sources take the same word.
 */
object Encode:
  type Ctx = (Schema, String)

  /** the word of a term: the constructors chosen, in order; a hole ends the word */
  def word(t: Term): Word.Letters = t match
    case Hole(_) => Vector.empty
    case Leaf(_, v) => Vector(v)
    case Con(_, name, args) => name +: args.flatMap(word)

  /** the term of a word: the grammar replayed from one hole of type Program */
  def tree(g: Grammar, w: Word.Letters): Term =
    w.foldLeft(Hole(Ty.Program): Term) { (t, letter) =>
      g.nextHole(t).map((ty, _, put) => put(g(ty, letter))).getOrElse(t) }

  /** the level-1 grammar of a level-2 grammar: the leftmost hole's candidates — one where the data decides */
  def next(g: Grammar, exact: Boolean): Word.Next[Ctx] = new Word.Next[Ctx]:
    def apply(ctx: Ctx, w: Word.Letters): Option[Vector[String]] =
      val t = tree(g, w)
      g.nextHole(t).map { (ty, _, _) =>
        val cs = g.constructors(ty)
        if !exact then cs else
          val d = run(Fill.exact[Pure](ty, cs, Context(ctx._1, ctx._2, t)))
          d match
            case Vector((only, _)) => Vector(only)
            case _ => cs
      }
    override def open(ctx: Ctx, w: Word.Letters): Boolean = g.nextHole(tree(g, w)).exists((ty, _, _) => g.open(ty))

  /** a level-2 fill as a level-1 chooser */
  def chooser[R[_]](g: Grammar, fill: Fill[R]): Chooser[(Ctx, Word.Letters), String, R] =
    Word.chooser((ctx, w, en) => g.nextHole(tree(g, w)).fold(pure[Vector[(String, Double)], R](Vector.empty))((ty, _, _) => fill(ty, en, Context(ctx._1, ctx._2, tree(g, w)))))

  /** the denotation as an interpretation of the complete word */
  def interp(g: Grammar): Word.Interp[Ctx, (Query, Table)] = new Word.Interp[Ctx, (Query, Table)]:
    def apply(ctx: Ctx, w: Word.Letters): Either[String, (Query, Table)] = Den.run(ctx._1, tree(g, w))
