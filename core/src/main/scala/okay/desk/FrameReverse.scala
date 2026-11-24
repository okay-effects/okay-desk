package okay.desk

import okay.desk.skill.Read
import okay.scala3.*

/**
 * THE REVERSE READING, A SECOND OPINION (SPRINT T7; BACKLOG M.6; no training): the forward reading chooses a frame
 * by `P(frame | question)`; the reverse weighs the QUESTION given each candidate frame said back in the question's
 * language (`FrameSay`), `P(question | said frame)`. The candidates: the forward's frame, and at its least sure line
 * the runner-up read on from there. Where the two directions prefer different frames, the reading is in doubt — a
 * person is asked instead of an answer given (guarantees.md §6.1: doubt from a second, independent opinion).
 */
object FrameReverse:
  /** the reverse prompt: the tables, the frame in words; the question is what follows */
  def prompt(tables: Map[String, Table], said: String): (String, String) =
    ("Write the question a person asked, which these lines answer over these tables. Reply with the question only.",
      tables.values.toVector.sortBy(_.name).map(_.describe).mkString("\n") + s"\n\nLines: $said")

  /** the mean log-probability of the question's tokens after the frame said */
  def weigh[R[_]](tables: Map[String, Table], question: String, lines: Vector[String], lang: String)(
      using In[Read, R]): Double ! R =
    val (system, user) = prompt(tables, FrameSay.say(lines, lang))
    for
      context <- Read.prompt[R](system, user)
      q <- Read.encode[R](question.trim)
      end <- Read.encode[R]("<|im_end|>").map(_.last)
      scores <- Read.score[R](context, Vector(q :+ end))
    yield scores.head

  /** the candidates: the forward's frame, and the runner-up at its least sure line read on (none where every line
   * had one option); `readOn` reads a frame on from the lines given */
  def candidates[R[_]](first: FrameReading.Reading, readOn: Vector[String] => FrameReading.Reading ! R)
      : Vector[Vector[String]] ! R =
    val at = first.margins.indices.filter(i => first.runnersUp.lift(i).flatten.nonEmpty)
      .minByOption(first.margins)
    at.fold(pure[Vector[Vector[String]], R](Vector(first.lines))) { i =>
      readOn(first.lines.take(i) :+ first.runnersUp(i).get).map(other =>
        if other.lines == first.lines then Vector(first.lines) else Vector(first.lines, other.lines)) }

  /** the verdict: the frame the reverse prefers among the candidates, and whether it is the forward's */
  final case class Verdict(best: Vector[String], agrees: Boolean, weights: Vector[Double])

  def judge[R[_]](tables: Map[String, Table], question: String, lang: String, frames: Vector[Vector[String]])(
      using In[Read, R]): Verdict ! R =
    Read.each[Vector[String], Double, R](frames)(f => weigh[R](tables, question, f, lang)).map { ws =>
      val best = ws.indices.maxBy(ws)
      Verdict(frames(best), best == 0, ws) }
