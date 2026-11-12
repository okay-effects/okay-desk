package okay.desk.logic

import okay.datalog.{Literal, Parse, Program}
import okay.desk.{Logic, Query, Table}

/** Existing neural data readers already author query programs. Check their execution through the same gate. */
object DataProof:
  private def relevant(tables: Map[String, Table], program: Program, goal: String): Map[String, Table] =
    val used = program.rules.flatMap(r => r.head.pred +: r.body.collect {
      case Literal.Pos(a) => a.pred
      case Literal.Neg(a) => a.pred
    }).toSet + goal
    tables.filter((name, _) => used(name))

  /** Keep expanded certificates bounded on a phone. Larger queries retain the existing exact data handler. */
  def fits(tables: Map[String, Table], query: Query.Logical, rows: Table): Boolean =
    val parsed = for
      program <- Parse.program(Logic.quoted(query.rules))
      goal <- Parse.atom(Logic.quoted(query.goal))
    yield (program, relevant(tables, program, goal.pred))
    parsed.exists { (program, data) =>
      program.rules.size <= 32 && rows.size <= 64 && data.valuesIterator.map(_.size).sum <= 256 &&
        data.valuesIterator.flatMap(_.rows.iterator.flatMap(_.iterator.map(_.text))).toSet.size <= 64
    }

  def check(tables: Map[String, Table], query: Query.Logical, rows: Table): Either[String, Protocol.Result] =
    for
      program <- Parse.program(Logic.quoted(query.rules))
      goal <- Parse.atom(Logic.quoted(query.goal))
      checked <- Execute.definition(relevant(tables, program, goal.pred), Compile.Compiled(program, goal))
      actual = rows.rows.map(row => rows.columns.zip(row.map(_.text)).toMap).toSet
      expected = checked.answers.map(_.bindings).toSet
      _ <- Either.cond(actual == expected, (), "the query result disagrees with the checked Datalog definition")
    yield Protocol.complete(checked.definition, checked.answers)
