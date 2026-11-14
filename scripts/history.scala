//> using scala 3.3.8

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/** THE HISTORY OF RUNS (docs/toolkit.md §2, step 6): a `StatementRead` log turned into rows of
 * `~/work/my/okay-desk-private/history.tsv` — date, the run's name, its seed, a set and language, exact with no gate
 * (%), silent with no gate (%), and through the page's gate: shown, wrong among shown. One file for every run, so
 * a change is judged against everything before it.
 * `scala-cli run scripts/history.scala -- <name> <seed> <run log> [note]` */
@main def history(name: String, seed: String, log: String, note: String*): Unit =
  val out = Path.of(sys.props("user.home"), "work", "my", "okay-desk-private", "history.tsv")
  if !Files.exists(out) then
    Files.writeString(out, "date\tname\tseed\tset\tlanguage\texact%\tsilent%\tshown\twrong_shown\tnote\n")
  val lines = Files.readAllLines(Path.of(log)).asScala.toVector.map(_.replaceFirst("^\\[info\\] ", ""))
  val head = """^(Test|Unseen|Sealed|Collection|Teacher test) (en|ru|uk|pl): (\d+) texts$""".r
  val noGate = """.*no gate:\s+exact ([\d.]+)%.*silent ([\d.]+)%.*""".r
  val gate = (""".*the gate:\s+asked [\d.—%]+, shown (\d+): exact (\d+), harmless (\d+), signalled (\d+), """ +
    """refused (\d+), silent (\d+).*""").r
  // the day the run ended: its log's time
  val date = java.time.LocalDate.ofInstant(Files.getLastModifiedTime(Path.of(log)).toInstant,
    java.time.ZoneId.systemDefault).toString
  val rows = lines.indices.flatMap { i =>
    lines(i) match
      case head(set, lang, _) =>
        val exact = lines.slice(i + 1, i + 4).collectFirst { case noGate(e, s) => (e, s) }
        val shown = lines.slice(i + 1, i + 5).collectFirst { case gate(n, _, _, sig, _, sil) =>
          (n, (sig.toInt + sil.toInt).toString) }
        exact.map((e, s) => Vector(date, name, seed, set, lang, e, s, shown.fold("")(_._1), shown.fold("")(_._2),
          note.filterNot(_.startsWith("--")).mkString(" ")).mkString("\t"))
      case _ => None
  }
  Files.writeString(out, rows.map(_ + "\n").mkString, StandardOpenOption.APPEND)
  println(s"${rows.size} rows of $name seed $seed to $out")
