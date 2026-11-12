package okay.desk.ui

import okay.business.scala3.*
import okay.desk.calc.Language
import okay.desk.organ.{Cue, Organ, OrganFile}
import okay.desk.puzzle.*
import okay.inlay.domain.{Kit, Slot}
import okay.http.{Fetch, Response}

/** The finite-constraint tool used by the shared reasoning loop. Reading, missing facts and clarification happen inside
  * the ordinary chat; this object has no screen.
  */
object Puzzles:
  final case class State(
      text: String = "",
      parsed: Option[PuzzleText.Parsed] = None,
      lessons: Vector[Cue] = Vector.empty,
      facts: Vector[World.Fact] = Vector.empty,
      factProblem: Option[String] = None
  )

  val file: String = Clarified.prefix + "puzzle-lessons.json"
  val factsFile: String = Clarified.prefix + "puzzle-world.json"

  /** the reader: the person's lessons first, then the words; the organ of the phrases is induced on the JVM benches */
  private def reader(lessons: Vector[Cue]): Kit.Reader[PuzzleDomain.Schema] =
    val own = Organ("puzzle-lessons", lessons.flatMap(c => c.words ++ c.unless).distinct.sorted, lessons)
    val lang = (c: String) => Language.builtIn.getOrElse(c, Language.En.language)
    if lessons.isEmpty then PuzzleDomain.rules
    else PuzzleDomain.first(PuzzleDomain.ByOrgan(own, lang), PuzzleDomain.rules)

  private def language(text: String): String = Language.detect(text).headOption.getOrElse("en")

  def read[R[_]](s: App.State, text: String, online: Boolean)(using
      In[Disk, R],
      In[Fetch, R],
      In[Ui, R]
  ): App.State ! R =
    readLocal[R](s, text).flatMap { next =>
      if online then gather[R](next) else pure(next)
    }

  private def readLocal[R[_]](s: App.State, text: String)(using In[Disk, R]): App.State ! R =
    for
      kept <- Disk.get[R](file)
      world <- Disk.get[R](factsFile)
    yield
      val lessons = kept.flatMap(t => OrganFile.parse(t).toOption).map(_.cues).getOrElse(s.puzzle.lessons)
      val facts = world.flatMap(t => World.parse(t).toOption).getOrElse(s.puzzle.facts)
      val parsed = PuzzleText.parse(text, reader(lessons), facts)
      s.copy(puzzle = s.puzzle.copy(text = text, parsed = Some(parsed), lessons = lessons, facts = facts))

  private def gather[R[_]](s: App.State)(using In[Disk, R], In[Fetch, R], In[Ui, R]): App.State ! R =
    val parsed = s.puzzle.parsed.get
    val needs = parsed.clues.filter(c => c.world && c.read.isEmpty).map(c => World.read(c.text, parsed.schema))
    val plan = needs
      .foldLeft[Either[String, Vector[Wikidata.Request]]](Right(Vector.empty)) { (acc, need) =>
        for
          before <- acc
          n <- need
          requests <- Wikidata.plan(n, parsed.puzzle)
        yield before ++ requests
      }
      .map(_.distinctBy(_.url))
    plan match
      case Left(message)                       => pure(s.copy(puzzle = s.puzzle.copy(factProblem = Some(message))))
      case Right(requests) if requests.isEmpty => pure(s)
      case Right(requests)                     =>
        val fetched = requests.foldLeft(pure[Either[String, Vector[World.Fact]], R](Right(Vector.empty))) {
          (acc, request) =>
            acc.flatMap {
              case bad @ Left(_) => pure(bad)
              case Right(facts)  =>
                for
                  _ <- App.busy[R](s, s"Wikidata: finding ${request.property} of ${request.subject}…")
                  response <- perform[Fetch](Fetch.Call("GET", request.url, Map.empty, "", (r: Response) => r))
                yield
                  if response.status == 200 then Wikidata.decode(request, response.body).map(facts :+ _)
                  else
                    Left(
                      s"Wikidata unavailable: HTTP ${response.status}; missing ${request.subject}/${request.property}"
                    )
            }
        }
        fetched.flatMap {
          case Left(message) => pure(s.copy(puzzle = s.puzzle.copy(factProblem = Some(message))))
          case Right(facts)  =>
            val keys = facts.map(f => (f.subject, f.property)).toSet
            val kept = s.puzzle.facts.filterNot(f => keys((f.subject, f.property)))
            useFacts[R](s, World.json(kept ++ facts))
        }

  def useFacts[R[_]](s: App.State, text: String)(using In[Disk, R]): App.State ! R =
    World.parse(text) match
      case Left(message) => pure(s.copy(puzzle = s.puzzle.copy(factProblem = Some(message))))
      case Right(facts)  =>
        Disk.put[R](factsFile, World.json(facts)).map { _ =>
          val parsed = PuzzleText.parse(s.puzzle.text, reader(s.puzzle.lessons), facts)
          s.copy(puzzle = s.puzzle.copy(facts = facts, parsed = Some(parsed), factProblem = None))
        }

  /** a form chosen for a clue: the clue re-read as that form; the choice a lesson (the clue's stems → the form) */
  def chosen[R[_]](s: App.State, clue: String, form: String)(using In[Disk, R]): App.State ! R =
    val lang = Language.builtIn.getOrElse(language(clue), Language.En.language)
    val lesson = Cue(Organ.words(clue, lang).toSet, Set.empty, "form", form, sure = true)
    val lessons = (s.puzzle.lessons :+ lesson).distinct
    val organ = Organ("puzzle-lessons", lessons.flatMap(c => c.words ++ c.unless).distinct.sorted, lessons)
    Disk.put[R](file, OrganFile.json(organ)).map { _ =>
      val parsed = PuzzleText.parse(s.puzzle.text, reader(lessons), s.puzzle.facts)
      s.copy(puzzle = s.puzzle.copy(parsed = Some(parsed), lessons = lessons))
    }

  /** the forms a clue could take with the values found in it, as the buttons offer them */
  def options(clue: PuzzleText.Clue, schema: PuzzleDomain.Schema): Vector[(String, Constraint)] =
    val found = PuzzleDomain.copier.found(clue.text, schema).map(_._2)
    val n = PuzzleDomain.copier.copy(clue.text, schema, Slot("n", Vector.empty, copied = true))
    PuzzleDomain.forms.flatMap { f =>
      val parts = Map("form" -> f) ++ found.headOption.map("a" -> _) ++ found.lift(1).map("b" -> _) ++ n.map("n" -> _)
      PuzzleDomain.assemble(schema, parts).map(f -> _)
    }
