package okay.desk.puzzle

/**
 * A LOGIC PUZZLE AS DATA (specs/puzzles.md §2): sorts of values placed on ordered positions, one value of each sort
 * per position; constraints between values; a question. The zebra puzzle is the first instance; seating, schedules
 * and grids are the same model with other names.
 */
final case class Sort(name: String, values: Vector[String]):
  require(values.nonEmpty && values.distinct == values, s"$name: distinct values")

/** a value named by its sort and its name */
final case class Ref(sort: String, value: String):
  def show: String = value

enum Constraint:
  /** a and b are on the same position */
  case Same(a: Ref, b: Ref)
  /** a and b are on different positions */
  case Differ(a: Ref, b: Ref)
  /** a is at position n (1-based) */
  case At(a: Ref, n: Int)
  /** a is in the middle position */
  case Middle(a: Ref)
  /** a is somewhere left of b */
  case Left(a: Ref, b: Ref)
  /** a is immediately right of b (a = b + 1) */
  case RightNext(a: Ref, b: Ref)
  /** a and b are next to each other */
  case Adjacent(a: Ref, b: Ref)
  /** a is not at position n */
  case NotAt(a: Ref, n: Int)
  /** a and b are `d` positions apart, in either order ("one house between them": 2) */
  case Apart(a: Ref, b: Ref, d: Int)

  def refs: Vector[Ref] = this match
    case Same(a, b) => Vector(a, b)
    case Differ(a, b) => Vector(a, b)
    case At(a, _) => Vector(a)
    case Middle(a) => Vector(a)
    case Left(a, b) => Vector(a, b)
    case RightNext(a, b) => Vector(a, b)
    case Adjacent(a, b) => Vector(a, b)
    case NotAt(a, _) => Vector(a)
    case Apart(a, b, _) => Vector(a, b)

  /** the constraint's form, as the reading's slot names it */
  def form: String = this match
    case Same(_, _) => "same"
    case Differ(_, _) => "differ"
    case At(_, _) => "at"
    case Middle(_) => "middle"
    case Left(_, _) => "left"
    case RightNext(_, _) => "rightNext"
    case Adjacent(_, _) => "adjacent"
    case NotAt(_, _) => "notAt"
    case Apart(_, _, _) => "apart"

/** the question: which value of `asked` is on the same position as `of` — "who (nation) owns the fish (pet)" */
final case class Question(asked: String, of: Ref)

final case class Puzzle(positions: Int, sorts: Vector[Sort], constraints: Vector[Constraint],
    question: Option[Question], additionalQuestions: Vector[Question] = Vector.empty):
  require(sorts.forall(_.values.size == positions), "every sort has one value per position")
  def questions: Vector[Question] = question.toVector ++ additionalQuestions
  def sort(name: String): Option[Sort] = sorts.find(_.name == name)
  def sortOf(value: String): Option[Sort] = sorts.find(_.values.contains(value))
  /** every (sort, value) as a variable */
  def refs: Vector[Ref] = sorts.flatMap(s => s.values.map(v => Ref(s.name, v)))

object Puzzle:
  /** THE CLASSIC PUZZLE (Life International, 1962): five houses, fifteen clues, who owns the fish */
  val classic: Puzzle =
    def r(s: String, v: String) = Ref(s, v)
    Puzzle(5, Vector(
      Sort("colour", Vector("red", "green", "ivory", "yellow", "blue")),
      Sort("nation", Vector("Englishman", "Spaniard", "Ukrainian", "Norwegian", "Japanese")),
      Sort("drink", Vector("coffee", "tea", "milk", "orange juice", "water")),
      Sort("smoke", Vector("Old Gold", "Kools", "Chesterfields", "Lucky Strike", "Parliaments")),
      Sort("pet", Vector("dog", "snails", "fox", "horse", "zebra"))),
      Vector(
        Constraint.Same(r("nation", "Englishman"), r("colour", "red")),
        Constraint.Same(r("nation", "Spaniard"), r("pet", "dog")),
        Constraint.Same(r("drink", "coffee"), r("colour", "green")),
        Constraint.Same(r("nation", "Ukrainian"), r("drink", "tea")),
        Constraint.RightNext(r("colour", "green"), r("colour", "ivory")),
        Constraint.Same(r("smoke", "Old Gold"), r("pet", "snails")),
        Constraint.Same(r("smoke", "Kools"), r("colour", "yellow")),
        Constraint.Middle(r("drink", "milk")),
        Constraint.At(r("nation", "Norwegian"), 1),
        Constraint.Adjacent(r("smoke", "Chesterfields"), r("pet", "fox")),
        Constraint.Adjacent(r("smoke", "Kools"), r("pet", "horse")),
        Constraint.Same(r("smoke", "Lucky Strike"), r("drink", "orange juice")),
        Constraint.Same(r("nation", "Japanese"), r("smoke", "Parliaments")),
        Constraint.Adjacent(r("nation", "Norwegian"), r("colour", "blue"))),
      Some(Question("nation", r("pet", "zebra"))))
