package okay.desk.calc

import okay.desk.*
import okay.desk.Space.{Kind, Schema}

/**
 * THE CALCULUS (specs/calculus.md): typed terms with holes. The model does ONE thing — fills a hole of a type from
 * the context (the question and the term so far) — and the denotation of a closed term is exact. Types are
 * indexed by the data (a schema gives the tables, their columns, a column's values); a term is well-typed by
 * construction, so a closed term always denotes. Everything else is a strategy of refinement (which hole, which
 * source fills it: the data, a reader, the model's own tokens, the user) — never a new mechanism.
 */
enum Ty:
  case Program
  /** which kind of program: rows, an absence, a closure */
  case KindT
  case TableT
  /** a link out of the table, or none */
  case LinkT(table: String)
  /** a link INTO the table (another table's column naming its rows): an absence's other side */
  case ReverseLinkT(table: String)
  /** a column of one of the tables in a role: a condition's, a group's, an aggregate's; `none` allowed when optional */
  case ColumnT(tables: Vector[String], role: Role, optional: Boolean, except: Vector[String] = Vector.empty)
  case OpT(kind: Kind)
  case ValueT(table: String, column: String)
  /** an aggregate function; required when a group was chosen (a group without an aggregate says nothing) */
  case FnT(required: Boolean)
  /** an order: none, or by what, in a direction */
  case OrderT(table: String, aggregate: Option[String], grouped: Boolean)
  case DirT
  /** a limit; only after an order (a limit of unordered rows is arbitrary) */
  case LimitT(ordered: Boolean)
  case NodeT(edges: String)
  case DirectionT
  // the answer grammar's types (Answers.scala): the sentence, a FACT (a cell of the result), a WORD slot
  case AnswerT
  case FactT(row: Int, column: String)
  case WordT(slot: String)

enum Role:
  case Where, Group, Agg

enum Term:
  case Hole(ty: Ty)
  /** a constructor of a type applied; `args` filled left to right, their types given by `Grammar.next` */
  case Con(ty: Ty, name: String, args: Vector[Term])
  /** an atom chosen: a name, a value, a number */
  case Leaf(ty: Ty, value: String)

  def closed: Boolean = this match
    case Hole(_) => false
    case Con(_, _, args) => args.forall(_.closed)
    case Leaf(_, _) => true
  def holes: Int = this match
    case Hole(_) => 1
    case Con(_, _, args) => args.map(_.holes).sum
    case Leaf(_, _) => 0
  def show: String = this match
    case Hole(ty) => s"?${ty.toString.takeWhile(_ != '(')}"
    case Con(_, name, args) => if args.isEmpty then name else s"$name(${args.map(_.show).mkString(", ")})"
    case Leaf(_, v) => v

/** THE GRAMMAR: the constructors of a type over a schema, and the type of a constructor's next argument given the
 * arguments so far (the types depend on earlier choices: a column's type on the table chosen) */
/** THE RULES of a term language: what a type's constructors are, which open arguments, the type of the next argument
 * — the hole-finding, the closing and the filling are the same for every such language (the terms, the answers) */
trait Rules:
  import Term.*
  def constructors(ty: Ty): Vector[String]
  def opens(ty: Ty, con: String): Boolean
  def next(ty: Ty, con: String, args: Vector[Term]): Option[Ty]
  /** an OPEN type: any atom is a candidate, the grammar lists only examples (a number compared against) */
  def open(ty: Ty): Boolean = false

  /** the leftmost hole: its type, its path (argument indices), and a way to fill it (the term with the hole replaced) */
  def nextHole(term: Term): Option[(Ty, Vector[Int], Term => Term)] = term match
    case Hole(ty) => Some((ty, Vector.empty, t => t))
    case Leaf(_, _) => None
    case Con(ty, name, args) =>
      // a hole inside an argument first; else the next argument not yet opened
      args.zipWithIndex.collectFirst(Function.unlift { (a, i) =>
        nextHole(a).map((hty, path, put) => (hty, i +: path, (t: Term) => Con(ty, name, args.updated(i, put(t))))) })
        .orElse(next(ty, name, args).map(nt => (nt, Vector(args.size), (t: Term) => Con(ty, name, args :+ t))))
  /** closed under the rules: no hole, and no argument still to open (`Term.closed` sees only the holes written) */
  def closed(term: Term): Boolean = nextHole(term).isEmpty
  /** a hole of `ty` filled with `con`: a leaf, or a node opened with no arguments yet */
  def apply(ty: Ty, con: String): Term = if opens(ty, con) then Con(ty, con, Vector.empty) else Leaf(ty, con)

final class Grammar(val schema: Schema) extends Rules:
  import Term.*
  private def kindOf(table: String, column: String): Kind = schema.of(table).find(_.name == column).map(_.kind).getOrElse(Kind.Text)
  private def columns(tables: Vector[String], role: Role): Vector[String] = tables.flatMap(t => schema.of(t)).filter { c => role match
    case Role.Where => c.kind != Kind.Key && schema.values(c).nonEmpty
    case Role.Group => c.kind == Kind.Text
    case Role.Agg => c.kind == Kind.Num }.map(_.name)

  /** the constructors a hole of the type can take: a name and whether it opens arguments; leaves are the names */
  def constructors(ty: Ty): Vector[String] = ty match
    case Ty.Program => Vector("Rows", "Absent", "Closure")
    case Ty.KindT => Vector("rows", "absent", "closure")
    case Ty.TableT => schema.tables.keys.toVector.sorted.filterNot(schema.edgeLike.contains)
    case Ty.LinkT(t) => "none" +: schema.links.filter(_.from == t).map(l => s"${l.column}->${l.to}")
    case Ty.ReverseLinkT(t) => schema.links.filter(_.to == t).map(l => s"${l.from}.${l.column}")
    // (an aggregate's column is optional exactly when the function needs none — count, or no aggregate: then it IS none)
    case Ty.ColumnT(_, Role.Agg, true, _) => Vector("none")
    case Ty.ColumnT(ts, role, optional, except) => (if optional then Vector("none") else Vector.empty) ++ columns(ts, role).distinct.filterNot(except.contains)
    case Ty.OpT(Kind.Num) => Vector("<", ">")
    case Ty.OpT(_) => Vector("=")
    case Ty.ValueT(t, c) => schema.of(t).find(_.name == c).toVector.flatMap(schema.values).map(_.text)
    case Ty.FnT(required) => (if required then Vector.empty else Vector("none")) ++ Vector("count", "sum", "avg", "min", "max")
    case Ty.OrderT(_, Some(_), false) => Vector("none")
    case Ty.OrderT(t, agg, _) => "none" +: (agg match
      case Some("count") => Vector("n")
      case Some(fn) => Vector(fn)
      case None => schema.of(t).filter(c => c.kind == Kind.Num || c.kind == Kind.Time).map(_.name))
    case Ty.DirT => Vector("desc", "asc")
    case Ty.LimitT(false) => Vector("none")
    case Ty.LimitT(true) => Vector("none", "1", "3", "5", "10")
    case Ty.NodeT(e) => schema.tables(e).rows.flatten.map(_.text).distinct
    case Ty.DirectionT => Vector("from", "to")
    case Ty.AnswerT | Ty.FactT(_, _) | Ty.WordT(_) => Vector.empty // the answer grammar's, not the terms'

  override def open(ty: Ty): Boolean = ty match
    case Ty.ValueT(t, c) => kindOf(t, c) == Kind.Num
    case _ => false

  /** whether a constructor opens arguments (a node) or is an atom (a leaf) */
  def opens(ty: Ty, con: String): Boolean = ty match
    case Ty.Program => true
    case Ty.ColumnT(_, Role.Where, _, _) => con != "none" // a condition: the column, then its op and value
    case Ty.OrderT(_, _, _) => con != "none"              // an order: by what, then its direction
    case Ty.LinkT(_) => false
    case _ => false

  /** the type of the next argument of `con` (of type `ty`) given the arguments so far; None when complete */
  def next(ty: Ty, con: String, args: Vector[Term]): Option[Ty] =
    def leaf(i: Int): Option[String] = args.lift(i).collect { case Leaf(_, v) => v; case Con(_, n, _) => n }
    (ty, con) match
      // Rows: the table, a link, a condition on the table, one on the linked table, a group, the aggregate's function
      // and column, an order, a limit — each argument's type from the choices before it
      case (Ty.Program, "Rows") =>
        val t = leaf(0); val other = leaf(1).filter(_ != "none").map(_.split("->").last)
        args.size match
          case 0 => Some(Ty.TableT)
          case 1 => t.map(Ty.LinkT.apply)
          case 2 => t.map(x => Ty.ColumnT(Vector(x), Role.Where, optional = true))
          case 3 => Some(Ty.ColumnT(other.toVector, Role.Where, optional = true))
          case 4 =>
            val used = args.slice(2, 4).collect { case Con(_, c, _) => c }
            t.map(x => Ty.ColumnT(x +: other.toVector, Role.Group, optional = true, except = used))
          case 5 => Some(Ty.FnT(required = leaf(4).exists(_ != "none")))
          case 6 => t.flatMap(x => leaf(5).map(fn => Ty.ColumnT(Vector(x), Role.Agg, optional = fn == "none" || fn == "count")))
          case 7 => t.flatMap(x => leaf(5).map(fn => Ty.OrderT(x, Option.when(fn != "none")(fn), grouped = leaf(4).exists(_ != "none"))))
          case 8 => Some(Ty.LimitT(ordered = args.lift(7).exists { case Leaf(_, "none") => false; case _ => true }))
          case _ => None
      case (Ty.Program, "Absent") => args.size match
        case 0 => Some(Ty.TableT)
        case 1 => leaf(0).map(Ty.ReverseLinkT.apply)
        case 2 => leaf(0).map(t => Ty.ColumnT(Vector(t), Role.Where, optional = true))
        case _ => None
      case (Ty.Program, "Closure") => args.size match
        case 0 => Some(Ty.DirectionT)
        case 1 => schema.edgeLike.headOption.map(Ty.NodeT.apply)
        case _ => None
      case (Ty.ColumnT(ts, Role.Where, _, _), c) =>
        val t = ts.find(x => schema.of(x).exists(_.name == c)).getOrElse(ts.head)
        args.size match
          case 0 => Some(Ty.OpT(kindOf(t, c)))
          case 1 => Some(Ty.ValueT(t, c))
          case _ => None
      case (Ty.OrderT(_, _, _), _) => args.size match
        case 0 => Some(Ty.DirT)
        case _ => None
      case _ => None

