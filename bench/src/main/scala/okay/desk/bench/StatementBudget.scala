package okay.desk.bench

import okay.desk.tasks.{Coverage, Tasks}

/**
 * THE STATEMENT LANGUAGE AGAINST THE COLLECTION (specs/formalization.md §7 step 2), model-free: which tasks have a
 * statement, and how many decisions a reader makes to write one — per class and language, by what the decisions are,
 * with the per-decision accuracy that 70% of exact statements demand. `sbt "bench/runMain
 * okay.desk.bench.StatementBudget"`; it reads no weights and no backbone.
 */
object StatementBudget:
  def main(args: Array[String]): Unit = println(Coverage.table(Tasks.entries.map(_.task)))
