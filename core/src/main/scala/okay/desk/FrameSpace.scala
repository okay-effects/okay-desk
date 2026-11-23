package okay.desk

import okay.frames.{Choice, Hole, Space}

/** a frame read: its lines, `end` the last (specs/frames.md §2) — the meaning; its program is `Frame.query`'s */
final case class FrameTerm(lines: Vector[String])

/**
 * THE FRAME AS A SPACE OF MEANINGS (okay-frames `Space`, its specs/frames.md §6): over the open tables, a frame's
 * lines are holes filled in order — the first a table, the second an answer, every other a line of the sections
 * after (`Frame.next`); `pruned` offers what the page offers (`FrameReading.pruned`: unwritten numbers, the single
 * number's order/limit/keep). The words are `FrameSay`'s, in English and Russian, with their exact parser.
 *
 * The meaning is the frame, not its compiled `Query`: two paths to one program differ in an alias (`answer`,
 * `total`), and the law that a path closes to its term would not hold on the query. One definition of what is
 * offered — the reader weighs these options (`FrameReading`), the laws measure them (`Reach.of`, `FrameLaws`).
 */
final class FrameSpace(pruned: Boolean) extends Space[Map[String, Table], FrameTerm]:
  def name: String = if pruned then "frames, as the page offers" else "frames"
  private def hole(i: Int): String = i match
    case 0 => "table"
    case 1 => "answer"
    case _ => "line"

  /** the options of the next line after `lines` */
  def offered(lines: Vector[String], tables: Map[String, Table], question: String): Vector[String] =
    val all = Frame.next(lines, tables, question)
    if pruned then FrameReading.pruned(all, FrameReading.Reading(lines, Vector.empty), tables, question) else all

  def next(tables: Map[String, Table], text: String, chosen: Vector[Choice]): Option[Hole] =
    val lines = chosen.map(_.option)
    Option.when(!lines.lastOption.contains(Frame.end))(Hole(hole(lines.size), offered(lines, tables, text)))
  def assemble(tables: Map[String, Table], chosen: Vector[Choice]): Option[FrameTerm] =
    Option.when(chosen.lastOption.exists(_.option == Frame.end))(FrameTerm(chosen.map(_.option)))
  def path(tables: Map[String, Table], t: FrameTerm): Vector[Choice] =
    t.lines.zipWithIndex.map((l, i) => Choice(hole(i), l))
  def say(tables: Map[String, Table], t: FrameTerm, language: String): String = FrameSay.say(t.lines, language)
  def parse(tables: Map[String, Table], words: String, language: String): Option[FrameTerm] =
    FrameSay.parse(words, language).toOption.map(FrameTerm(_))

object FrameSpace:
  /** every line `Frame.next` allows */
  val all: FrameSpace = FrameSpace(pruned = false)
  /** as the page offers them */
  val page: FrameSpace = FrameSpace(pruned = true)
  def of(pruned: Boolean): FrameSpace = if pruned then page else all
