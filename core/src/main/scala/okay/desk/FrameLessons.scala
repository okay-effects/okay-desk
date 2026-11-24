package okay.desk

import okay.effects.json.Json

/**
 * THE PERSON'S CHOICES AS LESSONS (SPRINT K5): where the reading asked and the person chose a line, the choice is
 * kept — by the tables' names and the question's words, normalized — and weighs that line next time the same
 * question is read over the same tables (`FrameReading.remembered`), as a link does. Kept on the device only; a value
 * of its own, so a page keeps it in its storage and a measure can start from none.
 */
final case class FrameLessons(kept: Map[String, Set[String]] = Map.empty):
  def add(tables: Map[String, Table], question: String, line: String): FrameLessons =
    val k = FrameLessons.key(tables, question)
    FrameLessons(kept.updated(k, kept.getOrElse(k, Set.empty) + line))
  def lines(tables: Map[String, Table], question: String): Set[String] =
    kept.getOrElse(FrameLessons.key(tables, question), Set.empty)
  def forget(tables: Map[String, Table], question: String): FrameLessons =
    FrameLessons(kept - FrameLessons.key(tables, question))
  def json: String = Json.Obj(kept.toVector.sortBy(_._1).map((k, ls) =>
    k -> Json.Arr(ls.toVector.sorted.map(Json.Str.apply)))).render

object FrameLessons:
  /** the tables' names and the question's words: lower case, the punctuation and the spaces between them dropped */
  def key(tables: Map[String, Table], question: String): String =
    tables.keys.toVector.sorted.mkString(",") + "|" +
      "(?U)\\w+".r.findAllIn(question.toLowerCase).mkString(" ")
  def parse(text: String): FrameLessons =
    try FrameLessons(Json.fromText(text).record.map((k, v) => k -> v.list.map(_.string).toSet).toMap)
    catch case _: Exception => FrameLessons()
