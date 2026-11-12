package okay.desk.bench

import okay.desk.puzzle.*
import okay.desk.know.Knowledge

object TestFarming extends App:
  val question = "Крестьянин купил на базаре козу, кочан капусты и волка. По дороге домой надо было переправиться через реку. У крестьянина была маленькая лодка, в которую кроме него могла поместится только одна из его покупок. Как ему переправить все товары через реку, если нельзя оставлять козу наедине с капустой и волка наедине с козой?"

  println("=== 1. GridText ===")
  val counts = GridText.counts(question)
  println(s"counts: $counts (expected: empty - no 'N houses' mentioned)")

  val looksLike = GridText.looksLike(question)
  println(s"looksLike: $looksLike (expected: false - no houses)")

  val infer = GridText.infer(question)
  println(s"infer: $infer")

  println("\n=== 2. PuzzleText.parse (with rules reader) ===")
  val parsed = PuzzleText.parse(question, PuzzleDomain.rules)
  println(s"ready: ${parsed.ready}")
  println(s"problems: ${parsed.problems}")
  println(s"inferred: ${parsed.inferred}")
  parsed.clues.zipWithIndex.foreach { case (clue, i) =>
    println(s"  [$i] form=${clue.read.map(_.form).getOrElse("NONE")}, text='${clue.text.take(60)}', problem=${clue.problem}")
  }

  println("\n=== 3. Knowledge.isConstraints ===")
  val isC = Knowledge.isConstraints(question)
  println(s"isConstraints: $isC")

  println("\n=== 4. Try explicit format ===")
  val explicit = """Categories: коза, капуста, волк
    |Коза не остаётся с капустой наедине.
    |Волк не остаётся с козой наедине."""
  val parsedExplicit = PuzzleText.parse(explicit, PuzzleDomain.rules)
  println(s"explicit ready: ${parsedExplicit.ready}")
  println(s"explicit problems: ${parsedExplicit.problems}")
  parsedExplicit.clues.foreach { c =>
    println(s"  form=${c.read.map(_.form).getOrElse("NONE")}, text='${c.text}'")
  }

  println("\n=== 5. What we need to build ===")
  println("- A reader for 'не остаётся с X наедине' -> Constraint.Differ")
  println("- A state-space solver (BFS over bank states)")
  println("- Integration: parse -> constraints -> solve -> path output")
