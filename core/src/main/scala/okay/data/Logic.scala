package okay.data

import okay.datalog.{Eval, Fact, Parse}

/**
 * THE LOGICAL HANDLER: the tables as relations (a row a fact, the columns its positions in order), the model's
 * rules on okay-datalog, the goal's bindings as the result table. For what a relational query cannot say: paths,
 * closures, rules with exceptions — exact, with proofs available.
 */
object Logic:
  def facts(tables: Map[String, Table]): Vector[Fact] =
    tables.values.toVector.flatMap(t => t.rows.map(r => Fact(t.name, r.map(_.text))))

  def run(tables: Map[String, Table], q: Query.Logical): Either[String, Table] =
    for
      program <- Parse.program(q.rules).left.map(e => s"rules: $e")
      goal <- Parse.atom(q.goal).left.map(e => s"goal: $e")
      result <- Eval.run(program, facts(tables)).left.map(e => s"evaluation: $e")
    yield
      val rows = result.query(goal)
      val vars = rows.headOption.map(_.keys.toVector.sorted).getOrElse(Vector.empty)
      Table("result", vars, rows.map(b => vars.map(v => Value.of(b(v)))))

  /** the relations as the model must write them: each table's name and its columns' positions */
  def describe(tables: Map[String, Table]): String =
    tables.values.toVector.sortBy(_.name).map(t => s"  ${t.name}(${t.columns.mkString(", ")})").mkString("\n")
