package okay.desk.exec

import scala.concurrent.{ExecutionContext, Future}

/** A bounded dependency graph of typed tool calls. Dependencies are ordering constraints; inputs remain explicit. Ready
  * calls are submitted immediately, in parallel. Failure skips dependants, not unrelated jobs.
  */
object ComputePlan:
  final case class Node(id: String, job: Computation.Job, after: Vector[String] = Vector.empty)
  final case class Result(id: String, result: Either[String, Computation.Output])

  def validate(nodes: Vector[Node]): Either[String, Unit] =
    val ids = nodes.map(_.id)
    if nodes.isEmpty || nodes.size > 8 || ids.distinct.size != ids.size ||
      !ids.forall(_.matches("[a-zA-Z][a-zA-Z0-9_-]{0,31}"))
    then Left("a compute plan needs one to eight uniquely named jobs")
    else if nodes.exists(n => n.after.distinct != n.after || n.after.exists(d => !ids.contains(d))) then
      Left("compute dependencies must name existing jobs once")
    else
      var done = Set.empty[String]
      var more = true
      while more do
        val next = done ++ nodes.filter(_.after.forall(done)).map(_.id)
        more = next != done
        done = next
      Either.cond(done.size == nodes.size, (), "compute dependencies contain a cycle")

  def run(nodes: Vector[Node])(submit: Computation.Job => Future[Either[String, Computation.Output]])(using
      ExecutionContext
  ): Future[Either[String, Vector[Result]]] = validate(nodes) match
    case Left(error) => Future.successful(Left(error))
    case Right(_)    =>
      val jobs = scala.collection.mutable.Map.empty[String, Future[Result]]
      def runNode(node: Node): Future[Result] = jobs.getOrElseUpdate(
        node.id, {
          val dependencies = node.after.map(id => runNode(nodes.find(_.id == id).get))
          Future.sequence(dependencies).flatMap { results =>
            if results.exists(_.result.isLeft) then
              Future.successful(Result(node.id, Left("a computation dependency failed")))
            else
              val result = scala.util.Try(submit(node.job)).fold(Future.failed, identity)
              result.map(r => Result(node.id, r)).recover { case e =>
                Result(node.id, Left(Option(e.getMessage).getOrElse("computation failed")))
              }
          }
        }
      )
      Future.sequence(nodes.map(runNode)).map(rs => Right(rs))

  /** Deterministic reference handler for the JVM UI script. */
  def sync(nodes: Vector[Node]): Either[String, Vector[Result]] = validate(nodes).map { _ =>
    var results = Map.empty[String, Result]
    def one(n: Node): Result = results.getOrElse(
      n.id, {
        val before = n.after.map(id => one(nodes.find(_.id == id).get))
        val result =
          if before.exists(_.result.isLeft) then Left("a computation dependency failed")
          else Computation.run(n.job)
        val r = Result(n.id, result)
        results += n.id -> r
        r
      }
    )
    nodes.map(one)
  }
