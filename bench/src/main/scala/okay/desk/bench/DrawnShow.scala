package okay.desk.bench

import okay.desk.tasks.Drawn

/**
 * THE CORPUS, TO READ: the first tasks of each family as a person sees them, in both languages, with the answer.
 * `sbt "bench/runMain okay.desk.bench.DrawnShow [family] [how many]"`
 */
object DrawnShow:
  def main(args: Array[String]): Unit =
    val (only, count) = (args.headOption, args.lift(1).fold(1)(_.toInt))
    for family <- Drawn.families if only.forall(_ == family.name) do
      for seed <- (1 to count).map(_ + 7_000_000L) do
        val task = family.draw(seed)
        println(s"=== ${family.name} $seed${if family.held then " (held out)" else ""}")
        println(task.text.en + s"\n→ ${task.answer.en}\n")
        println(task.text.ru + s"\n→ ${task.answer.ru}\n")
