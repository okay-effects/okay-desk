package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.puzzle.Solver
import okay.desk.skill.Read
import okay.desk.statement.{Assign, Reader}
import okay.desk.tasks.Gold
import okay.effects.json.JsonText
import scala.jdk.CollectionConverters.*

/**
 * THE READER ON ZEBRALOGIC (specs/formalization.md §17): public puzzles of other authors, the gold of each made by
 * `scripts/zebralogic.scala` (an exact reading of the benchmark's templates, kept where the solution is unique).
 * A puzzle's text is put in the page's form (the lists without their backticks, the clues as they are); the
 * reader reads it, the statement is solved for the WHOLE grid, and the grid is compared with the gold cell by cell.
 * Counted: the grid right; and the gate as the page has it — no word never met, every margin over its bar.
 * ZEBRA_GOLD=gold.jsonl, ZEBRA_READER=reader file, ZEBRA_COUNT (all).
 */
object ZebraRead:
  /** the benchmark's text as a person would paste it: no backticks, no markdown heading, a list a sentence */
  def text(puzzle: String): String =
    puzzle.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("##")).map { l =>
      if l.startsWith("- ") then l.drop(2).replace("`", "").stripSuffix(".") + "." else l }.mkString("\n")

  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val gold = Files.readAllLines(Path.of(sys.env("ZEBRA_GOLD"))).asScala.toVector
      .flatMap(l => JsonText.parse(l).toOption)
      .take(sys.env.get("ZEBRA_COUNT").map(_.toInt).getOrElse(Int.MaxValue))
    val trained = Reader.parse(Files.readAllBytes(Path.of(sys.env("ZEBRA_READER")))).fold(sys.error(_), identity)
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val reading = ComputeReading(dir, 12, ComputeReading.device.getOrElse(okay.llm.compute.OnMlx()))
    var (n, stated, right, shown, shownRight) = (0, 0, 0, 0, 0)
    val bySize = scala.collection.mutable.Map.empty[String, (Int, Int)].withDefaultValue((0, 0))
    val why = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    for g <- gold do
      val t = text(g("puzzle").string)
      val solution = g("solution").record.map((v, h) => v.toLowerCase -> h.int).toMap
      val seen = Read.run(Reader.see[Read](t, trained.norm, trained.echo), reading)
      val r = Reader.read(trained, seen)
      val grid = r.statement.toRight("no statement").flatMap(s => Assign.compile(s, t, whole = true)).flatMap {
        case Gold.Grid(p) => Solver.solve(p, 2) match
          case Solver.Outcome.One(grid, _) => Right(grid.map((ref, h) => ref.value.toLowerCase -> h))
          case Solver.Outcome.Several(_, _, _) => Left("several")
          case Solver.Outcome.None(_) => Left("none")
        case _ => Left("not a grid") }
      n += 1
      if r.statement.nonEmpty then stated += 1
      val ok = grid.exists(gr => solution.forall((v, h) => gr.get(v).contains(h)))
      if ok then right += 1
      grid.left.foreach(w => why(w.take(40)) += 1)
      if grid.isRight && !ok then why("a wrong grid") += 1
      // the gate: no word never met and every margin over its bar (the "no question" doubt is this bench's)
      val language = if Reader.russian(t) then "ru" else "en"
      val calm = r.news == (0, 0) && Reader.kinds.forall(k =>
        r.least.get(k).forall(_ >= trained.bars.getOrElse(s"$language $k", 0f)))
      if grid.isRight && calm then { shown += 1; if ok then shownRight += 1 }
      val size = g("size").string
      bySize(size) = (bySize(size)._1 + 1, bySize(size)._2 + (if ok then 1 else 0))
      if n % 50 == 0 then println(s"$n puzzles: right $right")
    println(f"ZebraLogic: $n puzzles; a statement $stated; the grid right $right (${100.0 * right / n}%.1f%%); " +
      s"shown by the gate $shown, of them right $shownRight")
    println("by size: " + bySize.toVector.sortBy(_._1).map((s, c) => s"$s ${c._2}/${c._1}").mkString(", "))
    println("why not: " + why.toVector.sortBy(-_._2).map((w, k) => s"$k × $w").mkString("; "))
