package okay.desk

import okay.inlay.{Closure, Compile, Forward, Interpret, Val}

/**
 * THE HANDLER IN WEIGHTS (okay-inlay specs/bridge.md): a Datalog query of the CLOSURE SHAPE — `r(X, Y) :- t(X, Y).
 * r(X, Z) :- t(X, Y), r(Y, Z).` with the goal `r(a, X)` or `r(X, b)` over a two-column table — is answered by the
 * transformer compiled from `Closure`, run by `Forward` (plain Scala: the JVM and the browser alike): exact by the
 * law (ClosureSuite: weights == interpreter == search), and the same answer as okay-datalog's (WeightsSuite). Any
 * other rules go to `Logic`. Capacity: a table of up to `MaxRows` rows and `MaxNames` names; beyond, `Logic` too —
 * one program, two handlers, the cheaper where it fits.
 */
object Weights:
  val MaxRows = 60
  val MaxNames = 40
  private val Rules = """^\s*(\w+)\s*\(\s*X\s*,\s*Y\s*\)\s*:-\s*(\w+)\s*\(\s*X\s*,\s*Y\s*\)\s*\.\s*\1\s*\(\s*X\s*,\s*Z\s*\)\s*:-\s*\2\s*\(\s*X\s*,\s*Y\s*\)\s*,\s*\1\s*\(\s*Y\s*,\s*Z\s*\)\s*\.\s*$""".r
  private val GoalFrom = """^\s*(\w+)\s*\(\s*("?)([^",()]+)\2\s*,\s*([A-Z]\w*)\s*\)\s*$""".r
  private val GoalTo = """^\s*(\w+)\s*\(\s*([A-Z]\w*)\s*,\s*("?)([^",()]+)\3\s*\)\s*$""".r

  /** the closure shape: (the table, the constant, from it (true) or to it, the variable's name) */
  def shape(tables: Map[String, Table], q: Query.Logical): Option[(Table, String, Boolean, String)] =
    q.rules match
      case Rules(r, t) => tables.get(t).filter(_.columns.size == 2).flatMap { table =>
        q.goal match
          case GoalFrom(`r`, _, a, x) => Some((table, a, true, x))
          case GoalTo(`r`, x, _, b) => Some((table, b, false, x))
          case _ => None
      }
      case _ => None

  def fits(tables: Map[String, Table], q: Query.Logical): Boolean = shape(tables, q).exists { (t, _, _, _) =>
    t.size <= MaxRows && t.rows.flatten.map(_.text).distinct.size <= MaxNames }

  /** the answer by the block in weights: the names reachable from the constant (or reaching it: the edges reversed) */
  def run(tables: Map[String, Table], q: Query.Logical): Either[String, Table] =
    shape(tables, q).toRight("not the closure shape").flatMap { (table, c, forward, x) =>
      val edges = table.rows.map(r => (r(0).text, r(1).text))
      val names = (edges.flatMap((a, b) => Vector(a, b)) :+ c).distinct
      if edges.size > MaxRows || names.size > MaxNames then Left(s"${table.name} is larger than the block holds")
      else
        val rounds = math.max(1, (32 - Integer.numberOfLeadingZeros(math.max(1, names.size - 1)))) // 2^rounds ≥ names
        val (program, _) = Closure.program(names, edges.size + 2, rounds)
        val sentences = edges.map((a, b) => if forward then (a, b, true) else (b, a, true))
        // the question position asks "c above ?": its gathered set `s` is everything reachable from c
        val in = Closure.inputs(sentences, (c, names.head))
        val last = Forward.run(program, Compile(program), in).last
        val reached = last("s") match
          case Val.M(s) => s
          case _ => Set.empty[String]
        Right(Table("result", Vector(x), names.filter(reached).map(n => Vector(Value.of(n)))))
    }

  /** one program, two handlers: the block where it fits, okay-datalog elsewhere */
  def either(tables: Map[String, Table], q: Query.Logical): Either[String, (Table, String)] =
    if fits(tables, q) then run(tables, q).map(_ -> "weights") else Logic.run(tables, q).map(_ -> "datalog")

  /** the interpreter's answer, for the law */
  def reference(tables: Map[String, Table], q: Query.Logical): Either[String, Table] =
    shape(tables, q).toRight("not the closure shape").map { (table, c, forward, x) =>
      val edges = table.rows.map(r => (r(0).text, r(1).text))
      val names = (edges.flatMap((a, b) => Vector(a, b)) :+ c).distinct
      val rounds = math.max(1, (32 - Integer.numberOfLeadingZeros(math.max(1, names.size - 1))))
      val (program, _) = Closure.program(names, edges.size + 2, rounds)
      val sentences = edges.map((a, b) => if forward then (a, b, true) else (b, a, true))
      val last = Interpret.run(program, Closure.inputs(sentences, (c, names.head))).last
      val reached = last("s") match
        case Val.M(s) => s
        case _ => Set.empty[String]
      Table("result", Vector(x), names.filter(reached).map(n => Vector(Value.of(n))))
    }
