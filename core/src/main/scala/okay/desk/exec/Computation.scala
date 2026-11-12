package okay.desk.exec

import okay.desk.{Exact, Query, Table, Weights}
import okay.desk.know.{Arithmetic, Deduction, FactReader, Formal, Knowledge, Planning}
import okay.desk.puzzle.{Puzzle, Solver}

/** The CPU API available to the reasoning program. Inputs are data; the same handlers run on JVM and in a Worker. */
object Computation:
  enum Job derives Wire:
    case Calculate(question: String, expression: String)
    case Deduce(question: String, sources: Vector[Knowledge.Source], request: Deduction.Request,
        learned: Vector[FactReader.Reading] = Vector.empty)
    case Solve(puzzle: Puzzle, bound: Int)
    case QueryData(tables: Map[String, Table], query: Query)
    case Cite(sources: Vector[Knowledge.Source], quotes: Vector[Knowledge.Quote])
    case FormalModel(program: String)
    case PlanModel(program: String)
    case LogicModel(question: String, proposal: okay.desk.logic.Protocol.Proposal, tables: Map[String, Table])
    case Evaluate(expression: String, receipts: Map[String, String])

  enum Output derives Wire:
    case Number(value: String)
    case Deduced(value: Deduction.Result)
    case Solved(value: Solver.Outcome)
    case Rows(query: Query, table: Table, handler: String, english: String, russian: String)
    case ProvenRows(query: Query, table: Table, handler: String, english: String, russian: String,
        proof: okay.desk.logic.Protocol.Result)
    case Cited(values: Vector[Knowledge.Citation])
    case FormalResult(value: Formal.Result)
    case PlanResult(value: Planning.Result)
    case LogicResult(value: okay.desk.logic.Protocol.Result)

  final case class Request(id: Int, job: Job) derives Wire
  final case class Reply(id: Int, result: Either[String, Output], elapsedMs: Double, startedAt: Double = 0) derives Wire

  def run(job: Job): Either[String, Output] =
    try
      job match
        case Job.Calculate(q, expression)               => Arithmetic.run(expression, q).map(Output.Number(_))
        case Job.Deduce(q, sources, request, learned) =>
          Deduction.run(q, sources, request, learned = learned).map(Output.Deduced(_))
        case Job.Solve(puzzle, bound)                   => Right(Output.Solved(Solver.solve(puzzle, bound)))
        case Job.QueryData(tables, q: Query.Relational) => Exact.run(tables, q).map(t => rows(q, t, "exact", tables))
        case Job.QueryData(tables, q: Query.Logical)    =>
          Weights.either(tables, q).flatMap { (t, by) =>
            if !okay.desk.logic.DataProof.fits(tables, q, t) then Right(rows(q, t, by, tables))
            else okay.desk.logic.DataProof.check(tables, q, t).map { proof =>
              val claims = okay.desk.calc.Claims.of(q, t, tables)
              Output.ProvenRows(q, t, by, okay.desk.calc.Claims.text(claims, false),
                okay.desk.calc.Claims.text(claims, true), proof)
            }
          }
        case Job.Cite(sources, quotes) => Knowledge.ground(sources, quotes).map(Output.Cited(_))
        case Job.FormalModel(program) => Formal.run(program).map(Output.FormalResult(_))
        case Job.PlanModel(program) => Planning.run(program).map(Output.PlanResult(_))
        case Job.LogicModel(question, proposal, tables) =>
          okay.desk.logic.Protocol.run(question, proposal, tables).map(Output.LogicResult(_))
        case Job.Evaluate(expression, receipts) => Formal.evaluate(expression, receipts).map(Output.Number(_))
    catch case e: Exception => Left(Option(e.getMessage).getOrElse("computation failed"))

  private def rows(query: Query, table: Table, by: String, tables: Map[String, Table]): Output =
    val claims = okay.desk.calc.Claims.of(query, table, tables)
    Output.Rows(query, table, by, okay.desk.calc.Claims.text(claims, false), okay.desk.calc.Claims.text(claims, true))
