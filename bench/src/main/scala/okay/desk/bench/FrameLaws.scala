package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.*
import okay.effects.json.Json
import scala.jdk.CollectionConverters.*

/**
 * THE FRAME LANGUAGE'S LAWS, MEASURED (okay-frames `Laws`; specs/theory.md §3) on the questions with gold frames —
 * `Everyday` and the teacher's 1004 never trained (`FRAME_SET`):
 *  - REACH: the gold frame offered line by line by `Frame.next`, and by it pruned as the page prunes
 *    (`FrameReading.pruned`) — a line not offered is an error no training can fix;
 *  - THE ROUND TRIP: the gold frame compiled (`Frame.query`) answers as the gold program;
 *  - THE WRONG READ AGAIN (`records`, `FrameBench`'s FRAME_RECORDS): a frame judged wrong whose answer holds the
 *    gold's values — two frames for one meaning (a max against the top row), not a misreading.
 * `FrameLaws [records.jsonl]`, TEACH_DOMAINS=1 for the teacher's tables
 */
object FrameLaws:
  def main(args: Array[String]): Unit =
    FrameReading.pruneText = !sys.env.contains("FRAME_KEEP_TEXT")
    val sets =Vector("everyday" -> FrameBench.everyday.filter(_.lang == "en")) ++
      sys.env.get("FRAME_SET").map(f => "never trained" -> FrameBench.taught(Path.of(f))).toVector
    for (name, set) <- sets do
      var plain, pruned, trip, n = 0
      val refused = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
      val shown = scala.collection.mutable.ArrayBuffer.empty[String]
      for a <- set; gold <- a.frame do
        n += 1
        val steps = gold.indices.map { i =>
          val so = FrameReading.Reading(gold.take(i), Vector.empty)
          val offered = Frame.next(gold.take(i), a.tables, a.text)
          (offered.contains(gold(i)),
            FrameReading.pruned(offered, so, a.tables, a.text).contains(gold(i)), gold(i))
        }
        if steps.forall(_._1) then plain += 1
        steps.find(!_._2) match
          case None => pruned += 1
          case Some((_, _, line)) =>
            val section = if steps.find(!_._1).nonEmpty then "not offered" else "pruned"
            refused(s"$section: ${line.takeWhile(_ != ':')}") += 1
            if shown.size < 12 then shown += s"    [$section] ${a.text} → ${gold.mkString(" | ")} (line: $line)"
        val back = Frame.query(gold, a.tables).flatMap(Exact.run(a.tables, _)).toOption
        if a.gold.exists(g => back.exists(Sample.same(_, g))) then trip += 1
      println(s"$name: $n gold frames; reached as offered $plain, as pruned $pruned; round trip $trip")
      println(s"  refused: ${refused.toVector.sortBy(-_._2).mkString(", ")}")
      shown.foreach(println)
    // THE WRONG ONES READ AGAIN: does the reader's answer hold the gold's values?
    for f <- args.headOption; set <- sets.find(_._1 == "never trained").map(_._2) do
      val byKey = set.map(a => a.key -> a).toMap
      val wrong = Files.readAllLines(Path.of(f)).asScala.filter(_.nonEmpty).map(Json.fromText).flatMap { j =>
        def s(k: String) = j.field(k).collect { case Json.Str(x) => x }.getOrElse("")
        Option.when(s("outcome") == "wrong")((s("key"), s("frame").split(" \\| ").toVector))
      }
      var holds = 0
      val kinds = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
      val examples = scala.collection.mutable.ArrayBuffer.empty[String]
      for (key, lines) <- wrong; a <- byKey.get(key); gold <- a.gold do
        val got = Frame.query(lines, a.tables).flatMap(Exact.run(a.tables, _)).toOption
        val values = (t: Table) => t.rows.flatten.map(_.text).toSet
        // the gold's answer (a few cells) all found in the reader's, or the reader's in the gold's
        val held = got.exists(g => gold.size <= 3 && values(gold).nonEmpty && values(gold).subsetOf(values(g)) ||
          g.size <= 3 && values(g).nonEmpty && values(g).subsetOf(values(gold)))
        val pair = s"${lines.lift(1).getOrElse("")} ⇄ ${a.frame.flatMap(_.lift(1)).getOrElse("")}"
        if held then
          holds += 1
          kinds(pair) += 1
          if examples.size < 12 then
            examples += s"    ${a.text}\n      read: ${lines.mkString(" | ")}\n      gold: ${a.frame.getOrElse(Vector.empty).mkString(" | ")}"
      println(s"wrong ${wrong.size}: the answer holds the gold's values in $holds")
      println(s"  by answer lines: ${kinds.toVector.sortBy(-_._2).take(10).mkString(", ")}")
      examples.foreach(println)
