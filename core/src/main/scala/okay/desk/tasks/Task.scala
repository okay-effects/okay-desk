package okay.desk.tasks

import okay.datalog.{Eval, Parse}
import okay.desk.{Query, Sample}
import okay.desk.know.{Arithmetic, Formal, Planning}
import okay.desk.puzzle.{Puzzle, Solver}
import okay.desk.statement.{Problem, Writing}
import okay.effects.json.Json

/** the same words in English and in Russian */
final case class Words(en: String, ru: String):
  def in(russian: Boolean): String = if russian then ru else en

/** the classes of the collection: what a task asks for, not its topic */
enum Kind(val title: Words):
  case Einstein extends Kind(Words("Einstein's puzzle", "Задача Эйнштейна"))
  case Places extends Kind(Words("Who is where", "Кто где"))
  case Matching extends Kind(Words("Who is who", "Кто есть кто"))
  case Order extends Kind(Words("Comparisons", "Сравнения"))
  case Deduction extends Kind(Words("Deduction", "Вывод"))
  case Kinship extends Kind(Words("Family", "Родство"))
  case Routes extends Kind(Words("Routes and dependencies", "Маршруты и зависимости"))
  case Truth extends Kind(Words("Knights and liars", "Рыцари и лжецы"))
  case Plans extends Kind(Words("Plans", "Планы"))
  case Quantities extends Kind(Words("Quantities", "Количества"))
  case Numbers extends Kind(Words("Number puzzles", "Числовые головоломки"))
  case Data extends Kind(Words("Data", "Данные"))

/**
 * THE STATEMENT A TASK IS TO BE READ AS (specs/formalization.md §3): the gold formalization, written for the exact
 * solver that decides it. A model's work on a task is this statement and the choice of the solver — never the answer.
 */
enum Gold:
  /** an ordered grid with its question(s), for `puzzle.Solver` */
  case Grid(puzzle: Puzzle)
  /** a finite constraint program, for `know.Formal` */
  case Finite(program: String)
  /** a transition model, for `know.Planning` */
  case Plan(program: String)
  /** Datalog facts and rules with the goal atom, for okay-datalog */
  case Rules(program: String, goal: String)
  /** an expression over the numbers written in the task, for `know.Arithmetic` */
  case Sum(expression: String)
  /** a query over the sample tables, for `Exact` or Datalog */
  case Table(program: String)

  def solver: String = this match
    case Grid(_) => "ordered grid"
    case Finite(_) => "finite constraints"
    case Plan(_) => "planning"
    case Rules(_, _) => "Datalog"
    case Sum(_) => "arithmetic"
    case Table(_) => "tables"

  /** the statement as text, for a person to read */
  def show: String = this match
    case Grid(p) => (p.sorts.map(s => s"${s.name}: ${s.values.mkString(", ")}") ++
      p.constraints.map(_.toString) ++ p.questions.map(_.toString)).mkString("\n")
    case Finite(p) => p
    case Plan(p) => p
    case Rules(p, goal) => s"$p\n?- $goal"
    case Sum(e) => e
    case Table(p) => p

  /**
   * THE EXACT RESULT, in the form a task's `expect` is written in: a grid's answers in the questions' order ("several"
   * or "none" when the clues do not decide); a finite program's goals in the order of their names, a goal with several
   * possible values as `a|b`; a plan's number of moves, or "unreachable"; a ground goal's "yes" or "no", an open
   * goal's bindings in order; a number; a table's rows (those of a Datalog goal in order).
   */
  def run(text: String): Either[String, Vector[String]] = this match
    // the solutions alone: the clash of an inconsistent grid is not asked for here, and seeking it can take long
    case Grid(p) => Right(Solver.solutions(p, 2) match
      case Vector(g) => Solver.answers(p, g).map(_._2)
      case Vector() => Vector("none")
      case _ => Vector("several"))
    case Finite(p) => Formal.run(p).flatMap { r =>
      if r.status == "budget exceeded" then Left(r.status)
      else if r.status == "inconsistent" then Right(Vector("none"))
      else Right(r.answers.toVector.sortBy(_._1).map(_._2.mkString("|"))) }
    case Plan(p) => Planning.run(p).map(r => Vector(if r.status == "proved" then r.moves.size.toString else r.status))
    case Rules(p, goal) =>
      for
        program <- Parse.program(p)
        atom <- Parse.atom(goal)
        result <- Eval.run(program, Vector.empty)
      yield
        val rows = result.query(atom)
        if rows.forall(_.isEmpty) then Vector(if rows.nonEmpty then "yes" else "no")
        else rows.map(b => b.toVector.sortBy(_._1).map(_._2).mkString(",")).sorted
    case Sum(e) => Arithmetic.run(e, text).map(Vector(_))
    case Table(p) => Query.parse(p).flatMap(q => Sample.run(q).map { table =>
      val rows = table.rows.map(_.map(_.text).mkString(","))
      q match
        case _: Query.Logical => rows.sorted
        case _ => rows })

object Gold:
  // ---- finite constraint programs as data: variables by name, numbers, texts, operators
  def v(name: String): Json = Json.Str(name)
  def n(i: Int): Json = Json.Num(i)
  def t(s: String): Json = Json.Arr(Vector(Json.Str("text"), Json.Str(s)))
  def op(o: String, args: Json*): Json = Json.Arr(Json.Str(o) +: args.toVector)
  def ints(from: Int, to: Int): Vector[Json] = (from to to).toVector.map(n)
  def texts(values: String*): Vector[Json] = values.toVector.map(Json.Str(_))
  val truth: Vector[Json] = Vector(Json.Bool(true), Json.Bool(false))
  def bools(names: String*): Vector[(String, Vector[Json])] = names.toVector.map(_ -> truth)
  def not(x: Json): Json = op("not", x)
  def implies(a: Json, b: Json): Json = op("implies", a, b)
  def is(a: Json, b: Json): Json = op("=", a, b)
  /** the value of the first variable for which the condition holds: `which(x -> c, y -> d)` is the name of the one */
  def which(cases: (String, Json)*): Json =
    cases.init.foldRight(t(cases.last._1))((c, rest) => op("if", c._2, t(c._1), rest))
  def finite(vars: Vector[(String, Vector[Json])], where: Vector[Json], ask: (String, Json)*): Gold =
    Finite(Json.print(Json.Obj(Vector("vars" -> Json.Obj(vars.map((k, d) => k -> Json.Arr(d))),
      "where" -> Json.Arr(where), "ask" -> Json.Obj(ask.toVector)))))

/**
 * A TASK OF THE COLLECTION: what a person writes (in English and in Russian), the answer a person expects, and the
 * gold statement with the exact engine's result — `check` is the law that the two agree. `opens`: the data to open
 * before asking. `stated`: the task as a reader is to state it, sentence by sentence (`okay.desk.statement`), for the
 * tasks written together with their statement.
 */
final case class Task(kind: Kind, title: Words, text: Words, answer: Words, gold: Gold, expect: Vector[String],
    opens: Vector[String] = Vector.empty, stated: Option[Task.Read] = None):
  def check: Either[String, Unit] = gold.run(text.en).flatMap(got =>
    Either.cond(got == expect, (),
      s"${title.en}: expected ${expect.mkString("; ")}, the engine gives ${got.mkString("; ")}"))

  /** a reply in words (a person's, or the desk's in the chat) says this task's answer: `Judge` */
  def accepts(reply: String, alone: Boolean = false): Boolean = Judge.accepts(this, reply, alone)
  /** a statement's own result — in the names of the text, as its solver gives it — against `expect`, through the
   * keys of the task's statement: right by what it names, whatever form the text said the name in */
  def agrees(result: Vector[String], russian: Boolean): Boolean = stated.exists { r =>
    val s = if russian then r.ru else r.en
    def key(name: String): String = s.keys.collectFirst {
      case (words, k) if okay.desk.statement.Statement.same(words, name) => k.drop(k.indexOf('/') + 1)
    }.getOrElse(name)
    result.map(_.split('|').toVector.map(key).sorted.mkString("|")).sorted == expect.sorted }

/** a place in the collection: its class is known at once, the task itself is made when it is first shown */
final class Entry(val kind: Kind, make: => Task):
  lazy val task: Task = make

object Entry:
  def of(tasks: Vector[Task]): Vector[Entry] = tasks.map(t => Entry(t.kind, t))

object Task:
  /** a task's statement in both languages: the text it is of, the frames, the collection's key of every thing */
  final case class Read(en: Writing.Stated, ru: Writing.Stated):
    def in(russian: Boolean): Writing.Stated = if russian then ru else en

  /**
   * a task written together with its statement (`Writing`): every part of the text in both languages, the things it
   * names marked, with the frames the part states — the same frames in both languages
   */
  def written(kind: Kind, title: Words, problem: Problem, parts: Vector[(Words, Vector[Writing.Said])], answer: Words,
      gold: Gold, expect: Vector[String]): Task =
    val en = Writing.write(problem, parts.map((w, said) => Writing.Part(w.en, said)))
    val ru = Writing.write(problem, parts.map((w, said) => Writing.Part(w.ru, said)))
    Task(kind, title, Words(en.text, ru.text), answer, gold, expect, stated = Some(Read(en, ru)))

  /** a sentence written by hand: its English, its Russian, the frames it states */
  type Noted = (String, String, Vector[Writing.Said])
  def says(en: String, ru: String, frames: Writing.Said*): Noted = (en, ru, frames.toVector)

  /**
   * a task written by hand with its statement, sentence by sentence: the English, the Russian (both in the marks of
   * `Writing.noted`), and the frames the sentence states
   */
  def noted(kind: Kind, title: (String, String), problem: Problem, sentences: Vector[Noted],
      answer: (String, String), gold: Gold, expect: String*): Task =
    val parts = sentences.zipWithIndex.map { case ((en, ru, said), i) =>
      val gap = if i == 0 then "" else " "
      (Words(gap + Writing.noted(en), gap + Writing.noted(ru)), said) }
    written(kind, Words(title._1, title._2), problem, parts, Words(answer._1, answer._2), gold, expect.toVector)

  /** a task written by hand: every text as (English, Russian) */
  def of(kind: Kind, title: (String, String), text: (String, String), answer: (String, String), gold: Gold,
      expect: String*): Task =
    Task(kind, Words(title._1, title._2), Words(text._1, text._2), Words(answer._1, answer._2), gold, expect.toVector)

  val yes: (String, String) = ("Yes", "Да")
  val no: (String, String) = ("No", "Нет")
  val open: (String, String) = ("It does not follow from what is given", "Из условий это не следует")
