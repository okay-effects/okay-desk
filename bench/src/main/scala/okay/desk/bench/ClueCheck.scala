package okay.desk.bench

import okay.desk.statement.{Con, Faults, Problem, Statement}
import okay.desk.tasks.{Drawn, Tasks}

/**
 * IS EVERY CLUE NEEDED? (specs/core-joint.md §10) — the premise of a check by the exact side: in a task made well,
 * each clue matters — dropped, the answer is no longer the same one; a clue misread often matters no more (or
 * contradicts). Measured on the GOLD statements, no model: per set, the texts whose every clue is needed, and how
 * many clues are not. `sbt "bench/runMain okay.desk.bench.ClueCheck"`
 */
object ClueCheck:
  /** the clues a statement's answer does not need: dropping one leaves the same definite answer */
  def redundant(s: Statement, text: String): Option[(Int, Int)] =
    Faults.result(s, text).toOption.filterNot(Faults.undecided).map { answer =>
      val clues = for
        (sentence, i) <- s.sentences.zipWithIndex
        (f, j) <- sentence.frames.zipWithIndex if !notClues(f.con)
      yield (i, j)
      val spare = clues.count { (i, j) =>
        val at = s.sentences(i)
        val dropped = s.copy(sentences = s.sentences.updated(i, at.copy(frames = at.frames.patch(j, Nil, 1))))
        Faults.result(dropped, text).toOption.contains(answer)
      }
      (clues.size, spare)
    }
  private val notClues = Set(Con.Attribute, Con.Ask, Con.First, Con.Last, Con.Whether)

  def main(args: Array[String]): Unit =
    def report(name: String, stated: Vector[(String, okay.desk.statement.Writing.Stated)]): Unit =
      val got = stated.flatMap((lang, s) => redundant(s.statement, s.text).map(lang -> _))
      for lang <- Vector("en", "ru") do
        val mine = got.filter(_._1 == lang).map(_._2)
        if mine.nonEmpty then
          val all = mine.count(_._2 == 0)
          println(f"$name%-12s $lang: ${mine.size}%4d texts, every clue needed in $all%4d " +
            f"(${100.0 * all / mine.size}%.1f%%); clues ${mine.map(_._1).sum}, of them spare ${mine.map(_._2).sum}")
    def drawn(split: Drawn.Split, n: Int) = Drawn.items(split, n).flatMap(_.task.stated.toVector)
      .flatMap(r => Vector("en" -> r.en, "ru" -> r.ru))
    report("Test", drawn(Drawn.Split.Test, 300))
    report("Unseen", drawn(Drawn.Split.Unseen, 300))
    report("Sealed", drawn(Drawn.Split.Sealed, 300))
    report("Collection", Tasks.entries.flatMap(_.task.stated.toVector)
      .filter(_.en.statement.problem == Problem.Assign).flatMap(r => Vector("en" -> r.en, "ru" -> r.ru)))
