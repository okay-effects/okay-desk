package okay.desk.learn

import okay.desk.calc.{Language, Term}
import okay.desk.organ.{Cue, Organ, Organs, Template}

/**
 * THE PERSON'S LESSONS (BACKLOG A 1, B 11; Okay!Chat's `Support.Remembered`): «надо было: по странам» — a correction
 * or a choice at a hole is a pair (the question's stems → at this path, this constructor), and the pairs are CUES of
 * an organ the person owns: the same words next time decide the same hole before any rule, by the same block as
 * every organ, with the same law. A lesson is exact (its words are the question's own, all of them), so it fires on
 * the same wording and on wordings that contain it; it is never pruned — the person said so — and «не запоминай»
 * takes it back. Kept as a file (`OrganFile`) beside the data on the page.
 */
object Lessons:
  final case class Lesson(words: Set[String], path: String, constructor: String):
    def cue: Cue = Cue(words, Set.empty, path, constructor, sure = true)

  /** a lesson from a question and the hole it was answered at (the term the reading stopped at, the choice) */
  def of(question: String, lang: Language, at: Term, choice: String): Lesson =
    Lesson(Organ.words(question, lang).toSet, Organs.key(Organs.pathOf(at)), choice)

  /** the person's organ: every lesson a sure cue */
  def organ(lessons: Vector[Lesson], name: String = "lessons"): Organ =
    val cues = lessons.map(_.cue).distinct
    Organ(name, cues.flatMap(_.words).distinct.sorted, cues)

  /** a lesson taken back: «не запоминай» — the lessons of this question's words removed */
  def forget(lessons: Vector[Lesson], question: String, lang: Language): Vector[Lesson] =
    val ws = Organ.words(question, lang).toSet
    lessons.filterNot(_.words == ws)

  /** the lessons as the organ's file, and back */
  def json(lessons: Vector[Lesson]): String = okay.desk.organ.OrganFile.json(organ(lessons))
  def parse(text: String): Vector[Lesson] =
    okay.desk.organ.OrganFile.parse(text).toOption.toVector.flatMap(_.cues.map(c => Lesson(c.words, c.path, c.constructor)))

  /** what a lesson decides on a question, by the block: for the law */
  def decides(lessons: Vector[Lesson], question: String, lang: Language): Option[Template] =
    organ(lessons).forward(Organ.words(question, lang))
