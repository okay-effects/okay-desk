package okay.desk.bench

import okay.desk.tasks.{Checked, Tasks}

/**
 * ONE DECISION WRONG, OVER THE COLLECTION (specs/formalization.md §7 step 5), model-free: what the exact machines
 * make of every statement one decision away from a right one — per class of task and per kind of fault. `sbt
 * "bench/runMain okay.desk.bench.StatementFaults"`; it reads no weights and no backbone.
 */
object StatementFaults:
  def main(args: Array[String]): Unit = println(Checked.table(Tasks.entries.map(_.task)))
