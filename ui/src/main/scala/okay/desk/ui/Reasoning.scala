package okay.desk.ui

import okay.business.scala3.*
import okay.desk.know.{Arithmetic, Deduction, FactReader, Knowledge, Repair, SourceDocument}
import Knowledge.{Source, Step}
import okay.desk.puzzle.{PuzzleDomain, Solver}
import okay.desk.exec.{Computation, ComputePlan}
import okay.http.{Fetch, Response}

/** The same chat and tool loop for every question. Network permission, evidence, exact constraints and cited answers
  * are shared; no screen or user-selected question category participates in the reasoning.
  */
object Reasoning:
  final case class State(
      online: Boolean = false,
      question: String = "",
      sources: Vector[Source] = Vector.empty,
      pending: Option[Step] = None,
      constraints: Boolean = false,
      readings: Vector[FactReader.Reading] = Vector.empty,
      attempted: Set[Knowledge.Quote] = Set.empty,
      receipts: Map[String, String] = Map.empty,
      calculations: Vector[(String, String)] = Vector.empty,
      clarification: Option[Vector[String]] = None,
      repair: Repair = Repair()
  )

  def file(question: String): String =
    Clarified.prefix + "knowledge-" + Integer.toHexString(question.hashCode) + ".json"

  def start[R[_]](s: App.State, question: String)(using
      In[Ui, R],
      In[Model, R],
      In[Disk, R],
      In[Fetch, R]
  ): App.State ! R =
    if Model.authors(s.modelName) then
      val loaded = if s.model.isDefined then pure[App.State, R](s) else App.load[R](s, s.modelName)
      return loaded.flatMap { st =>
        if st.model.isEmpty then pure(st)
        else st.reasoning.clarification match
          case Some(observations) =>
            next[R](st.copy(reasoning = st.reasoning.copy(clarification = None, repair = Repair())),
              observations :+ ("User clarification: " + question), Set.empty, 0)
          case None => general[R](st, question)
      }
    val direct = Knowledge.isConstraints(question) || Arithmetic.expression(question).nonEmpty ||
      Deduction.goal(question).nonEmpty
    val relevant = if direct || s.tables.isEmpty then pure[Boolean, R](false)
      else Model.references[R](s.tables, question)
    relevant.flatMap { data =>
      if !data then general[R](s, question)
      else
        val local = s.copy(reasoning = State(online = s.reasoning.online, question = question))
        val ready =
          if local.model.isDefined then pure[App.State, R](local)
          else App.load[R](local, local.modelName)
        ready.flatMap { st =>
          if st.model.isEmpty then pure(st)
          else App.think[R](st, question).flatMap(program => App.answer[R](st, question, program, retried = false))
        }
    }

  def general[R[_]](
      s: App.State,
      question: String
  )(using In[Ui, R], In[Model, R], In[Disk, R], In[Fetch, R]): App.State ! R =
    Disk.get[R](file(question)).flatMap { text =>
      val sources = text.flatMap(Knowledge.cached(question, _)).getOrElse(Vector.empty)
      val ready = s
        .copy(reasoning = s.reasoning.copy(question = question, sources = sources, pending = None, constraints = false,
          readings = Vector.empty, attempted = Set.empty, receipts = Map.empty, calculations = Vector.empty,
          clarification = None, repair = Repair()))
      next[R](ready, Vector.empty, Set.empty, 0)
    }

  private def finish(s: App.State, text: String): App.State =
    s.copy(reasoning = s.reasoning.copy(pending = None), log = s.log :+ App.Entry("model", text))

  private def retry[R[_]](s: App.State, program: String, error: String, observations: Vector[String],
      searched: Set[String], round: Int)(using In[Ui, R], In[Model, R], In[Disk, R], In[Fetch, R]): App.State ! R =
    if !(Model.authors(s.modelName)) then
      next[R](s, observations :+ error, searched, round + 1)
    else s.reasoning.repair.failed(program, error) match
      case Left(stopped) => pure(finish(s, stopped))
      case Right(repair) => next[R](s.copy(reasoning = s.reasoning.copy(repair = repair)),
        observations :+ error, searched, round + 1)

  private def next[R[_]](s: App.State, observations: Vector[String], searched: Set[String], round: Int)(using
      In[Ui, R],
      In[Model, R],
      In[Disk, R],
      In[Fetch, R]
  ): App.State ! R =
    val modelAuthored = Model.authors(s.modelName)
    if round >= (if modelAuthored then 16 else 5) then pure(finish(s,
      if Model.authors(s.modelName) then
        "The language model could not complete a valid formal program within the step budget. " +
          observations.lastOption.getOrElse("")
      else "The collected evidence is insufficient to settle this question."))
    else if Model.authors(s.modelName) then choose[R](s, observations, searched, round)
    else readNew[R](s).flatMap(ready => choose[R](ready, observations, searched, round))

  private def readNew[R[_]](s: App.State)(using
      In[Ui, R], In[Model, R], In[Disk, R], In[Fetch, R]
  ): App.State ! R =
    val r = s.reasoning
    val quotes = if Deduction.goal(r.question).isEmpty then Vector.empty
      else (Deduction.questionSource(r.question) +: r.sources).flatMap(FactReader.sentences)
        .filter(q => Deduction.meaning(q.text).isEmpty && !r.attempted(q)).take(24 - r.attempted.size)
    if quotes.isEmpty then pure(s)
    else
      val loaded = if s.model.isDefined then pure[App.State, R](s) else App.load[R](s, s.modelName)
      loaded.flatMap { ready =>
        if ready.model.isEmpty then pure(ready)
        else
          App.busy[R](ready, "reading new facts and rules from source text…").flatMap { _ =>
            Model.readFacts[R](quotes).map { result =>
              val readings = result.getOrElse(Vector.empty)
              val problem = result match
                case Left(error) => Vector(App.Entry("system", error))
                case Right(rs) =>
                  val accepted = rs.map(r => Knowledge.Quote(r.source, r.quote)).toSet
                  val unread = quotes.filterNot(accepted)
                  if unread.isEmpty then Vector.empty
                  else
                    val description = if unread.size <= 3 then
                      "The fact reader abstained on these source sentences:\n" + unread.map(_.text).mkString("\n")
                    else s"The fact reader abstained on ${unread.size} source sentences. Examples:\n" +
                      unread.take(2).map(_.text.take(180)).mkString("\n")
                    Vector(App.Entry("system", description))
              ready.copy(reasoning = ready.reasoning.copy(readings = r.readings ++ readings,
                attempted = r.attempted ++ quotes), log = ready.log ++ problem)
            }
          }
      }

  private def choose[R[_]](s: App.State, observations: Vector[String], searched: Set[String], round: Int)(using
      In[Ui, R], In[Model, R], In[Disk, R], In[Fetch, R]
  ): App.State ! R =
      for
        _ <- App.busy[R](s, "choosing the next reasoning step…")
        proposed <- if Model.authors(s.modelName) then
          def follow: Either[Model.AuthorFailure, Step] ! R = Model.authored[R].flatMap {
            case Right(result) => pure(result)
            case Left(notes) => perform[Ui](Ui.Working(Screen[Nothing]("okay-desk",
              App.render(s, thinking = Some(notes)).parts, Nil))).flatMap(_ => follow)
          }
          Model.author[R](s.reasoning.question, s.tables, s.reasoning.sources, observations).flatMap(_ => follow)
        else Model.reason[R](s.reasoning.question, s.reasoning.sources, observations, s.reasoning.readings)
          .map(_.left.map(error => Model.AuthorFailure.InvalidProgram("", error)))
        result <- proposed match
          case Left(Model.AuthorFailure.Generation(problem)) => pure(finish(s.copy(model = None), problem))
          case Left(Model.AuthorFailure.InvalidProgram(program, problem)) =>
            retry[R](s, program, problem, observations, searched, round)
          case Right(step)   => run[R](s, step, observations, searched, round)
      yield result

  private val asking = "This question needs external evidence. Allow online sources to collect it, or continue offline."
  /** the question of the permission answered: it leaves the chat, the answer takes its place */
  private def answered(s: App.State): App.State =
    s.copy(log = s.log.filterNot(e => e.role == "model" && e.text == asking))

  private def permission(s: App.State, step: Step): App.State =
    finish(s, asking)
      .copy(reasoning = s.reasoning.copy(pending = Some(step)))

  def allow[R[_]](s: App.State)(using In[Ui, R], In[Model, R], In[Disk, R], In[Fetch, R]): App.State ! R =
    s.reasoning.pending.fold(pure[App.State, R](s)) { step =>
      val ready = answered(s).copy(reasoning = s.reasoning.copy(online = true, pending = None))
      run[R](ready, step, Vector.empty, Set.empty, 0)
    }

  def offline(s: App.State): App.State =
    finish(
      answered(s).copy(reasoning = s.reasoning.copy(online = false)),
      if s.reasoning.pending.nonEmpty then
        "Online sources are off. The available local evidence does not settle this question."
      else "Online sources are off."
    )

  private def run[R[_]](s: App.State, step: Step, observations: Vector[String], searched: Set[String], round: Int)(using
      In[Ui, R],
      In[Model, R],
      In[Disk, R],
      In[Fetch, R]
  ): App.State ! R = step match
    case Step.Eval(expression) =>
      Model.execute[R](Computation.Job.Evaluate(expression, s.reasoning.receipts)).flatMap {
        case Right(Computation.Output.Number(value)) =>
          val id = s"r${s.reasoning.receipts.size + 1}"
          val feedback = s"Tool result $id = $value"
          val ready = s.copy(reasoning = s.reasoning.copy(
            receipts = s.reasoning.receipts.updated(id, value),
            calculations = s.reasoning.calculations :+ (id -> expression), repair = Repair()),
            log = s.log :+ App.Entry("system", feedback, program = Some(expression)))
          next[R](ready, observations :+ feedback, searched, round + 1)
        case Left(error) => retry[R](s, expression, error, observations, searched, round)
        case _ => next[R](s, observations :+ "arithmetic tool returned the wrong type", searched, round + 1)
      }
    case Step.Return(id) =>
      s.reasoning.receipts.get(id) match
        case None => retry[R](s, id, s"Unknown result ID $id; use an existing tool receipt.", observations, searched,
          round)
        case Some(value) =>
          val steps = s.reasoning.calculations.map { (name, expression) =>
            okay.effects.json.Json.obj("id" -> okay.effects.json.Json.Str(name),
              "expression" -> okay.effects.json.Json.fromText(expression))
          }
          val program = okay.effects.json.Json.print(okay.effects.json.Json.obj(
            "steps" -> okay.effects.json.Json.Arr(steps), "goal" -> okay.effects.json.Json.Str(id)))
          pure(s.copy(log = s.log :+ App.Entry("model", "Answer: " + value,
            program = Some(program), note = Some("Exact tool result; conditional on the model-authored program."))))
    case Step.Formal(program) =>
      val description = okay.desk.know.Formal.describe(program, Knowledge.language(s.reasoning.question) == "ru")
      val interpreted = s.copy(log = s.log :+ App.Entry("system", "Language model interpretation\n" + description,
        program = Some(program)))
      Model.execute[R](Computation.Job.FormalModel(program)).flatMap {
        case Left(error) => retry[R](interpreted, program, error, observations, searched, round)
        case Right(Computation.Output.FormalResult(result)) =>
          val text = if result.status == "proved" then result.answers.toVector.sortBy(_._1)
            .map((label, values) => label + ": " + values.mkString(", ")).mkString("\n")
          else result.status match
            case "inconsistent" => "The interpreted conditions are inconsistent."
            case "underdetermined" => "The interpreted conditions do not determine a unique answer."
            case _ => "The exact solver reached its work budget; no answer has been proved."
          pure(interpreted.copy(log = interpreted.log :+ App.Entry("model", text,
            note = Some(s"Exact ${result.engine}; conditional on the model interpretation. " +
              s"${result.solutions} assignments satisfy it.\n" + result.proof.mkString("\n")))))
        case _ => pure(finish(interpreted, "The formal tool returned the wrong result type."))
      }
    case Step.Plan(program) =>
      val description = okay.desk.know.Planning.describe(program, Knowledge.language(s.reasoning.question) == "ru")
      val interpreted = s.copy(log = s.log :+ App.Entry("system", "Language model transition model\n" + description,
        program = Some(program)))
      Model.execute[R](Computation.Job.PlanModel(program)).flatMap {
        case Left(error) => retry[R](interpreted, program, error, observations, searched, round)
        case Right(Computation.Output.PlanResult(result)) =>
          val path = if result.status == "proved" then
            s"Shortest plan: ${result.moves.size} steps\n" + result.states.zipWithIndex.map { (state, i) =>
              val moved = if i == 0 then "Initial state" else s"$i. " + result.moves(i - 1).mkString(", ")
              moved + "\n" + result.variables.zip(state).map((name, value) => s"$name=$value").mkString(", ")
            }.mkString("\n")
          else "No route satisfies the interpreted conditions."
          pure(interpreted.copy(log = interpreted.log :+ App.Entry("model", path,
            program = Some(result.datalog + "\n" + result.proof.mkString("\n")),
            note = Some(s"Exact ${result.engine}; conditional on the model interpretation."))))
        case _ => pure(finish(interpreted, "The planning tool returned the wrong result type."))
      }
    case Step.Clarify(question) =>
      val answered = finish(s, question)
      pure(answered.copy(reasoning = answered.reasoning.copy(clarification = Some(observations))))
    case Step.Unsupported(capability) => pure(finish(s, "The model needs an unavailable tool: " + capability))
    case Step.Query(program) =>
      okay.desk.Query.parse(program) match
        case Left(error) => retry[R](s, program, error, observations, searched, round)
        case Right(query) => Model.query[R](s.tables, query).flatMap {
          case Left(error) => retry[R](s, program, error, observations, searched, round)
          case Right((_, rows, _, _, proof)) => pure(s.copy(log = (s.log :+
            App.Entry("result", rows.render(30), program = Some(program))) ++
              proof.toVector.map(p => LogicalProof.entry(p, Knowledge.language(s.reasoning.question) == "ru"))))
        }
    case Step.Open(_) if !s.reasoning.online => pure(permission(s, step))
    case Step.Open(url) =>
      if !SourceDocument.validUrl(url) || searched(url) then
        next[R](s, observations :+ "The document address is invalid or was already tried.", searched, round + 1)
      else
        for
          _ <- App.busy[R](s, "reading the external document…")
          response <- perform[Fetch](Fetch.Call("GET", url, Map.empty, "", (r: Response) => r))
          decoded <- if response.status != 200 then
            pure[Either[String, Source], R](Left(s"Document request failed: HTTP ${response.status}; " +
              "the site may require CORS access."))
            else Model.document[R](url, response.body, SourceDocument.contentType(response.headers))
          ready <- decoded match
            case Left(error) =>
              next[R](s.copy(log = s.log :+ App.Entry("system", error)),
                observations :+ error, searched + url, round + 1)
            case Right(source) =>
              val sources = (s.reasoning.sources :+ source).distinctBy(_.id).take(12)
              Disk.put[R](file(s.reasoning.question), Knowledge.cache(s.reasoning.question, sources)).flatMap { _ =>
                next[R](s.copy(reasoning = s.reasoning.copy(sources = sources)), observations,
                  searched + url, round + 1)
              }
        yield ready
    case Step.Search(queries) if !s.reasoning.online => pure(permission(s, step))
    case Step.Search(queries)                        =>
      val fresh = queries.filterNot(searched)
      if fresh.isEmpty then next[R](s, observations :+ "Those searches were already tried.", searched, round + 1)
      else
        val language = Knowledge.language(s.reasoning.question)
        val collected = fresh.foldLeft(pure[(Vector[Source], Vector[String]), R]((Vector.empty, Vector.empty))) {
          (acc, query) =>
            acc.flatMap { (sources, errors) =>
              for
                _ <- App.busy[R](s, s"finding sources: $query…")
                response <- perform[Fetch](
                  Fetch.Call("GET", Knowledge.searchUrl(query, language), Map.empty, "", (r: Response) => r)
                )
              yield
                val decoded =
                  if response.status == 200 then Knowledge.searchResults(response.body, language)
                  else Left(s"source search failed: HTTP ${response.status}")
                decoded match
                  case Right(found)  => (sources ++ found, errors)
                  case Left(message) => (sources, errors :+ message)
            }
        }
        collected.flatMap { (found, errors) =>
          val sources = (s.reasoning.sources ++ found).distinctBy(_.id).take(12)
          val ready = s.copy(
            reasoning = s.reasoning.copy(sources = sources),
            log = s.log ++ errors.map(error => App.Entry("system", error))
          )
          Disk.put[R](file(s.reasoning.question), Knowledge.cache(s.reasoning.question, sources)).flatMap { _ =>
            next[R](
              ready,
              observations ++ errors :+ "Collected sources are available below.",
              searched ++ fresh,
              round + 1
            )
          }
        }
    case Step.Answer(quotes) =>
      Model.cite[R](s.reasoning.sources, quotes).flatMap {
        case Left(error)      => next[R](s, observations :+ error, searched, round + 1)
        case Right(citations) =>
          pure(
            s.copy(
              reasoning = s.reasoning.copy(pending = None),
              log = s.log ++ citations.map(c =>
                App.Entry(
                  "model",
                  "“" + c.text + "”",
                  note = Some(s"Quoted from ${c.source.title} — ${c.source.date}"),
                  url = Some(c.source.url)
                )
              )
            )
          )
      }
    case Step.Deduce(evidence) =>
      val result = Deduction.goal(s.reasoning.question) match
        case None       => pure[Either[String, Deduction.Result], R](Left("the deduction goal has no typed reading"))
        case Some(goal) => Model.deduce[R](s.reasoning.question, s.reasoning.sources,
          Deduction.Request(goal, evidence), s.reasoning.readings)
      result.flatMap {
        case Left(error) => next[R](s, observations :+ error, searched, round + 1)
        case Right(proved) if proved.status == Deduction.Status.Unknown && s.reasoning.sources.isEmpty =>
          run[R](s, Step.Search(okay.desk.know.Relations.research(s.reasoning.question)
            .getOrElse(Vector(s.reasoning.question.take(300)))), observations, searched, round + 1)
        case Right(proved) => pure(deduced(s, proved))
      }
    case Step.Calculate(expression) =>
      Model.calculate[R](s.reasoning.question, expression).flatMap {
        case Right(value) => pure(finish(s, s"$expression = $value"))
        case Left(error)  => next[R](s, observations :+ error, searched, round + 1)
      }
    case Step.Compute(jobs) =>
      val plan = jobs.foldLeft[Either[String, Vector[ComputePlan.Node]]](Right(Vector.empty)) { (acc, node) =>
        val job = node.step match
          case Step.Calculate(expression) => Right(Computation.Job.Calculate(s.reasoning.question, expression))
          case Step.Deduce(evidence)      =>
            Deduction
              .goal(s.reasoning.question)
              .toRight("the deduction goal has no typed reading")
              .map(g =>
                Computation.Job.Deduce(s.reasoning.question, s.reasoning.sources,
                  Deduction.Request(g, evidence), s.reasoning.readings)
              )
          case _ => Left("unsupported compute operation")
        for before <- acc; op <- job yield before :+ ComputePlan.Node(node.id, op, node.after)
      }
      plan match
        case Left(error)  => next[R](s, observations :+ error, searched, round + 1)
        case Right(nodes) =>
          Model.executePlan[R](nodes).flatMap {
            case Left(error)    => next[R](s, observations :+ error, searched, round + 1)
            case Right(results) =>
              val shown = results.foldLeft(s) { (state, result) =>
                result.result match
                  case Right(Computation.Output.Number(value)) =>
                    val expression = jobs.find(_.id == result.id).get.step.asInstanceOf[Step.Calculate].expression
                    finish(state, s"$expression = $value")
                  case Right(Computation.Output.Deduced(value)) => deduced(state, value)
                  case Left(error)                              => finish(state, s"${result.id}: $error")
                  case _                                        => finish(state, "unsupported computation result")
              }
              pure(shown)
          }
    case Step.Insufficient =>
      val evidence = s.reasoning.sources
        .take(3)
        .map(source =>
          App.Entry(
            "source",
            source.text.take(400),
            note = Some(source.title + " — " + source.date),
            url = Some(source.url)
          )
        )
      val note =
        "The available facts and supported readings are insufficient to settle this question. " +
          "These excerpts are evidence, not a proved conclusion."
      pure(finish(s.copy(log = s.log ++ evidence), note))
    case Step.Constraints(text) =>
      val tool = s.copy(reasoning = s.reasoning.copy(constraints = true))
      Puzzles.read[R](tool, text, s.reasoning.online).flatMap { read =>
        val parsed = read.puzzle.parsed.get
        if parsed.clues.exists(c => c.world && c.read.isEmpty) && !s.reasoning.online then pure(permission(read, step))
        else constraints[R](read)
      }

  /** The handler phrases only the machine's result and trace; no free model-written conclusion is published. */
  private def deduced(s: App.State, result: Deduction.Result): App.State =
    import Deduction.Status
    val answer = result.status match
      case Status.Proven if result.answers.nonEmpty => result.answers.map(_.show).mkString("Answer: ", "; ", ".")
      case Status.Proven   => s"Proved: ${result.goal.show}."
      case Status.Refuted  => s"Refuted: ${result.goal.show}."
      case Status.Unknown  => s"Unknown: the premises do not establish ${result.goal.show} or its opposite."
      case Status.Conflict => s"Conflicting premises establish both ${result.goal.show} and its opposite."
    val sources = Deduction.questionSource(s.reasoning.question) +: s.reasoning.sources
    val proof = result.proof.map { line =>
      val because = if line.premises.isEmpty then "Premise" else line.premises.map(_.show).mkString(" + ") + " ⇒"
      val source = sources.find(_.id == line.evidence.source).get
      App.Entry(
        "source",
        s"$because ${line.atom.show}",
        note = Some(s"${if s.reasoning.readings.exists(r => r.source == line.evidence.source &&
          r.quote == line.evidence.text) then "Model read" else "Read"}: " +
          s"${line.evidence.text} • ${source.title} — ${source.date}"),
        url = Option(source.url).filter(_.nonEmpty)
      )
    }
    val trace =
      if result.status != Status.Unknown then proof
      else
        result.readings.map { reading =>
          val source = sources.find(_.id == reading.evidence.source).get
          App.Entry(
            "source",
            reading.evidence.text,
            note = Some(s"Read premise/rule • ${source.title} — ${source.date}"),
            url = Option(source.url).filter(_.nonEmpty)
          )
        }
    s.copy(
      reasoning = s.reasoning.copy(pending = None),
      log = (s.log :+
        App.Entry(
          "model",
          answer,
          note = Some("Exact inlay Horn inference • conditional on the cited readings")
        )) ++ trace
    )

  /** An exact constraint tool's result appears in the conversation, just like a cited passage or a table. */
  def constraints[R[_]](s: App.State)(using In[Model, R], In[Ui, R]): App.State ! R =
    val parsed = s.puzzle.parsed.get
    if !parsed.ready then
      val problems = parsed.problems ++ parsed.clues
        .filter(_.read.isEmpty)
        .map(c => c.problem.getOrElse("Please clarify this statement: " + c.text)) ++ s.puzzle.factProblem.toVector
      pure(finish(s, problems.mkString("\n")))
    else
      val puzzle = parsed.puzzle
      val language = Knowledge.language(s.reasoning.question)
      def label(v: String): String = okay.desk.puzzle.PuzzleText.label(parsed, v, language)
      val readBack = parsed.clues.zipWithIndex
        .map { (clue, i) =>
          s"${i + 1}. ${PuzzleDomain.readBack(clue.read.get, language, label)}"
        }
        .mkString("\n")
      val evidence = parsed.clues.flatMap(_.evidence).distinct.map { f =>
        App.Entry(
          "source",
          s"${f.subject}: ${f.property} = ${f.values.mkString(", ")}",
          note = Some(f.date),
          url = Some(f.source)
        )
      }
      val schemaRead = if !parsed.inferred then Vector.empty else Vector(App.Entry("system",
        parsed.schema.puzzle.sorts.map(sort => sort.name + ": " + sort.values.map(v =>
          okay.desk.puzzle.PuzzleText.label(parsed, v, Knowledge.language(s.reasoning.question))).mkString(", "))
          .mkString("Read categories (one different value of each category per house):\n", "\n", "")))
      val ready = s.copy(
        log = (s.log ++ schemaRead) :+ App.Entry("system", readBack),
        reasoning = s.reasoning.copy(constraints = false, pending = None)
      )
      App.busy[R](ready, "solving the constraints…").flatMap(_ => Model.solve[R](puzzle)).map {
        case Left(error)                            => finish(ready, "The constraint solver failed: " + error)
        case Right(Solver.Outcome.One(grid, trace)) =>
          val rows = Solver.table(puzzle, grid).map(_.map(label).mkString(" | ")).mkString("\n")
          val answers = Solver.answers(puzzle, grid).map { (q, value) =>
            val who = label(value)
            s"${who.head.toUpper +: who.tail} — ${label(q.of.value)}."
          }
          val answer = if answers.isEmpty then "Solved." else answers.mkString("\n")
          val proof = trace.map(t => s"${t.ref.value} → ${t.position} (${t.clue}: ${t.how})").mkString("\n")
          finish(ready.copy(log = ready.log ++ evidence :+ App.Entry("result", rows, note = Some(proof))), answer)
        case Right(Solver.Outcome.Several(n, _, differing)) =>
          finish(ready, s"Several solutions (at least $n). Unsettled: ${differing.map(_.value).mkString(", ")}.")
        case Right(Solver.Outcome.None(clashing)) =>
          finish(ready, "Inconsistent statements: " + clashing.mkString(", "))
      }
