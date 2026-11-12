package okay.desk.bench

import java.nio.file.Path
import okay.desk.statement.{Assemble, Span}

/** how many of the teacher's rewrites the assembler can be taught on, and why the others not (no model: the tokens
 * are words with the space before them, as `AssembleSuite` cuts) */
object TeacherReach:
  def tokens(text: String): Vector[Span] =
    "\\s*(?:[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}])".r.findAllMatchIn(text).map(m => Span(m.start, m.end)).toVector
  def main(args: Array[String]): Unit =
    val kept = StatementTeacher.load(Path.of(args(0)))
    val why = kept.map((k, s) => k.language -> Assemble.decisions(s.statement, s.text, tokens(s.text)).left
      .map(_.takeWhile(_ != '«').take(60)))
    println(s"${kept.size} rewrites; in reach ${why.count(_._2.isRight)}")
    for (l, ws) <- why.groupBy(_._1).toVector.sortBy(_._1) do
      println(s"$l: ${ws.count(_._2.isRight)} of ${ws.size}; " + ws.collect { case (_, Left(w)) => w }
        .groupBy(identity).view.mapValues(_.size).toVector.sortBy(-_._2).take(4).map((w, n) => s"$n × $w").mkString("; "))
