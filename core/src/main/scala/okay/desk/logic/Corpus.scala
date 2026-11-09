package okay.desk.logic

import okay.desk.{Sample, Table}
import scala.util.Random

/**
 * THE LOGIC CORPUS (specs/logic.md §5.3): task texts generated from formal models, with the model each text says —
 * the training and evaluation data of the reading heads. A generator is a teacher, not a reader: it writes the text
 * and keeps the gold program, and the law is that `Extract.gold` recovers the program from the text. The four
 * families are the grammar's: planning (a reachable set), numbers (comparisons over ages), kinship (a family), and
 * data (questions over the sample's tables). Each split uses its own sentence patterns and its own names, so a
 * held-out text is not a training text with another constant.
 */
object Corpus:
  enum Split:
    case Train, Validate, Test

  final case class Item(family: String, split: Split, text: String, ontology: Ontology,
      tables: Map[String, Table], rules: String, goal: String)

  // ---- the ontologies (one per family) ----

  val family: Ontology = Ontology(
    predicates = Map(
      "parent" -> Predicate("parent", Vector("person", "person")),
      "male" -> Predicate("male", Vector("person")),
      "sibling" -> Predicate("sibling", Vector("person", "person")),
      "grandparent" -> Predicate("grandparent", Vector("person", "person")),
      "uncle" -> Predicate("uncle", Vector("person", "person")),
      "ancestor" -> Predicate("ancestor", Vector("person", "person")),
      "person" -> Predicate("person", Vector("person")),
      "hasChild" -> Predicate("hasChild", Vector("person")),
      "noChild" -> Predicate("noChild", Vector("person"))),
    constants = Map("person" -> Vector("alice", "bob", "carol", "dave", "eve")),
    inputs = Set("parent", "male"))

  val numbers: Ontology = Ontology(
    predicates = Map(
      "age" -> Predicate("age", Vector("person", "number")),
      "adult" -> Predicate("adult", Vector("person")),
      "minor" -> Predicate("minor", Vector("person")),
      "between" -> Predicate("between", Vector("person")),
      "older" -> Predicate("older", Vector("person", "person")),
      "sameAge" -> Predicate("sameAge", Vector("person", "person"))),
    constants = Map(
      "person" -> Vector("ann", "bob", "cid", "dee", "eve"),
      "number" -> Vector("17", "18", "21", "30", "45")),
    inputs = Set("age"))

  val plan: Ontology = Ontology(
    predicates = Map(
      "initial" -> Predicate("initial", Vector("state")),
      "edge" -> Predicate("edge", Vector("state", "state")),
      "reachable" -> Predicate("reachable", Vector("state"))),
    constants = Map("state" -> Vector("s0", "s1", "s2", "s3")),
    inputs = Set("initial", "edge"))

  val data: Ontology =
    val base = Ontology.ofTables(Sample.tables)
    base.copy(predicates = base.predicates ++ Map(
      "reach" -> Predicate("reach", Vector("edges.from", "edges.to")),
      "unpaidUa" -> Predicate("unpaidUa", Vector("orders.id")),
      "has" -> Predicate("has", Vector("customers.name")),
      "noOrders" -> Predicate("noOrders", Vector("customers.name"))))

  // ---- the programs a family's questions ask ----

  private val reachable = "reachable(X) :- initial(X). reachable(Y) :- reachable(X), edge(X, Y)."
  private val reach = "reach(X, Y) :- edges(X, Y). reach(X, Z) :- edges(X, Y), reach(Y, Z)."
  private val unpaidUa =
    "unpaidUa(I) :- orders(I, C, A, unpaid, D, City), customers(C, \"UA\", Since)."
  private val noOrders =
    "has(C) :- orders(I, C, A, S, D, City). noOrders(C) :- customers(C, Country, Since), not has(C)."
  private val sibling = "sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y."
  private val ancestor = "ancestor(X, Y) :- parent(X, Y). ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z)."
  private val grandparent = "grandparent(X, Z) :- parent(X, Y), parent(Y, Z)."
  private val uncle = "uncle(X, Y) :- sibling(X, P), parent(P, Y), male(X)."
  private val childless =
    """person(X) :- parent(X, Y). person(X) :- parent(Y, X).
      |hasChild(X) :- parent(X, Y).
      |noChild(X) :- person(X), not hasChild(X).""".stripMargin

  // ---- the sentence patterns, one set per split ----

  private val planTexts: Map[Split, Vector[String]] = Map(
    Split.Train -> Vector("You start at {start}. {moves} Which states can be reached?",
      "The start is {start}. {moves} What is reachable?"),
    Split.Validate -> Vector("Begin at {start}; {moves} Which places are reachable?"),
    Split.Test -> Vector("Start: {start}. {moves} Name every state reachable from {start}."))

  private val moveTexts: Map[Split, Vector[String]] = Map(
    Split.Train -> Vector("From {a} you can go to {b}."),
    Split.Validate -> Vector("{a} leads to {b}."),
    Split.Test -> Vector("{a} -> {b}."))

  private val ageTexts: Map[Split, Vector[String]] = Map(
    Split.Train -> Vector("{facts}"),
    Split.Validate -> Vector("The ages are: {facts}"),
    Split.Test -> Vector("Ages — {facts}"))

  private val ageFactTexts: Map[Split, Vector[String]] = Map(
    Split.Train -> Vector("{name} is {age}."),
    Split.Validate -> Vector("{name} {age};"),
    Split.Test -> Vector("{name} ({age})."))

  private val ageQuestions: Map[String, Map[Split, Vector[String]]] = Map(
    "adult" -> Map(
      Split.Train -> Vector("Who is an adult?", "Which people are adults?"),
      Split.Validate -> Vector("Who counts as an adult?"),
      Split.Test -> Vector("Name every adult.")),
    "minor" -> Map(
      Split.Train -> Vector("Who is a minor?", "Which people are minors?"),
      Split.Validate -> Vector("Who is under age?"),
      Split.Test -> Vector("Name every minor.")),
    "between" -> Map(
      Split.Train -> Vector("Who is between {low} and {high}?"),
      Split.Validate -> Vector("Whose age is from {low} to {high}?"),
      Split.Test -> Vector("Name everyone aged {low} to {high}.")),
    "older" -> Map(
      Split.Train -> Vector("Who is older than {person}?"),
      Split.Validate -> Vector("Who is senior to {person}?"),
      Split.Test -> Vector("Name everyone older than {person}.")),
    "same" -> Map(
      Split.Train -> Vector("Who is the same age as {person}?"),
      Split.Validate -> Vector("Who shares {person}'s age?"),
      Split.Test -> Vector("Name everyone with the age of {person}.")))

  private val kinFactTexts: Map[Split, Vector[String]] = Map(
    Split.Train -> Vector("{a} is a parent of {b}."),
    Split.Validate -> Vector("{b}'s parent is {a}."),
    Split.Test -> Vector("{a} has a child, {b}."))

  private val maleTexts: Map[Split, Vector[String]] = Map(
    Split.Train -> Vector("{name} is male."),
    Split.Validate -> Vector("{name} is a man."),
    Split.Test -> Vector("Male: {name}."))

  private val kinQuestions: Map[String, Map[Split, Vector[String]]] = Map(
    "grandparent" -> Map(
      Split.Train -> Vector("Who are {person}'s grandchildren?"),
      Split.Validate -> Vector("Name the grandchildren of {person}."),
      Split.Test -> Vector("Who counts as a grandchild of {person}?")),
    "ancestor" -> Map(
      Split.Train -> Vector("Who are {person}'s descendants?"),
      Split.Validate -> Vector("List everyone descended from {person}."),
      Split.Test -> Vector("Name all descendants of {person}.")),
    "uncle" -> Map(
      Split.Train -> Vector("Who is {person}'s uncle?"),
      Split.Validate -> Vector("Name the uncle of {person}."),
      Split.Test -> Vector("Who is an uncle of {person}?")),
    "sibling" -> Map(
      Split.Train -> Vector("Who are {person}'s siblings?"),
      Split.Validate -> Vector("Name the siblings of {person}."),
      Split.Test -> Vector("Who is a sibling of {person}?")),
    "noChild" -> Map(
      Split.Train -> Vector("Who has no children?"),
      Split.Validate -> Vector("Who is childless?"),
      Split.Test -> Vector("Name everyone with no children.")))

  private val dataTexts: Map[String, Map[Split, Vector[String]]] = Map(
    "depend" -> Map(
      Split.Train -> Vector("Which services does web depend on?", "What does web depend on?"),
      Split.Validate -> Vector("What can web reach?"),
      Split.Test -> Vector("Name everything web depends on.")),
    "reaches" -> Map(
      Split.Train -> Vector("What can reach orders-db?", "Which services reach orders-db?"),
      Split.Validate -> Vector("What leads to orders-db?"),
      Split.Test -> Vector("Name every service that reaches orders-db.")),
    "unpaidUa" -> Map(
      Split.Train -> Vector("Which unpaid orders belong to customers in UA?",
        "Which orders are unpaid and from UA customers?"),
      Split.Validate -> Vector("List the unpaid orders of UA customers."),
      Split.Test -> Vector("Name the unpaid orders placed by customers from UA.")),
    "noOrders" -> Map(
      Split.Train -> Vector("Which customers have no orders?", "Who has never ordered?"),
      Split.Validate -> Vector("Which customers never placed an order?"),
      Split.Test -> Vector("Name every customer without an order.")))

  /** every sentence pattern of every split, for the split law: a held-out text is not a training text */
  def templates: Map[String, Map[Split, Vector[String]]] = Map(
    "plan" -> merge(planTexts, moveTexts),
    "ages" -> merge(ageTexts, ageFactTexts, questions(ageQuestions)),
    "kin" -> merge(kinFactTexts, maleTexts, questions(kinQuestions)),
    "data" -> questions(dataTexts))

  private def merge(maps: Map[Split, Vector[String]]*): Map[Split, Vector[String]] =
    Split.values.toVector.map(s => s -> maps.toVector.flatMap(_.getOrElse(s, Vector.empty))).toMap

  private def questions(m: Map[String, Map[Split, Vector[String]]]): Map[Split, Vector[String]] =
    merge(m.values.toVector*)

  // ---- the generators ----

  def items(split: Split, count: Int, seed: Int): Vector[Item] =
    val rnd = Random(seed)
    planning(split, count, rnd) ++ ages(split, count, rnd) ++ kin(split, count, rnd) ++ data(split, count, rnd)

  private def render(template: String, slots: Map[String, String]): String =
    slots.foldLeft(template)((t, kv) => t.replace("{" + kv._1 + "}", kv._2))

  private def pick(rnd: Random, xs: Vector[String]): String = xs(rnd.nextInt(xs.size))
  private def cap(name: String): String = name.head.toUpper.toString + name.tail

  private def planning(split: Split, count: Int, rnd: Random): Vector[Item] =
    Vector.fill(count) {
      val edges =
        if rnd.nextInt(3) == 0 then Vector((0, 1), (2, 3))
        else Vector((0, 1), (1, 2), (2, 3)) ++ (if rnd.nextBoolean() then Vector((0, 2)) else Vector.empty)
      val shuffled = rnd.shuffle(edges)
      val facts = (s"initial(s0)." +: shuffled.map((a, b) => s"edge(s$a, s$b).")).mkString(" ")
      val moves = shuffled.map((a, b) => render(pick(rnd, moveTexts(split)),
        Map("a" -> s"s$a", "b" -> s"s$b"))).mkString(" ")
      val text = render(pick(rnd, planTexts(split)), Map("start" -> "s0", "moves" -> moves))
      Item("planning", split, text, plan, Map.empty, s"$facts $reachable", "reachable(X)")
    }

  private val persons = Vector("ann", "bob", "cid", "dee", "eve")
  private val ageValues = Vector(17, 18, 21, 30, 45)
  private val ageKinds = Vector("adult", "minor", "between", "older", "same")

  private def ages(split: Split, count: Int, rnd: Random): Vector[Item] =
    Vector.fill(count) {
      val kind = pick(rnd, ageKinds)
      val chosen = rnd.shuffle(persons).take(3).zip(rnd.shuffle(ageValues).take(3))
      val people = kind match
        case "minor" => chosen.updated(0, (chosen(0)._1, 17))
        case "between" => chosen.updated(0, (chosen(0)._1, 21))
        case "same" => chosen.updated(1, (chosen(1)._1, chosen(0)._2))
        case _ => chosen
      val facts = people.map((p, a) => render(pick(rnd, ageFactTexts(split)),
        Map("name" -> cap(p), "age" -> a.toString))).mkString(" ")
      val (rules, goal, slots) = kind match
        case "adult" => ("adult(X) :- age(X, N), N >= 18.", "adult(X)", Map.empty[String, String])
        case "minor" => ("minor(X) :- age(X, N), N < 18.", "minor(X)", Map.empty[String, String])
        case "between" => ("between(X) :- age(X, N), N >= 18, N <= 30.", "between(X)", Map.empty[String, String])
        case "older" =>
          val youngest = people.minBy(_._2)._1
          ("older(X, Y) :- age(X, N), age(Y, M), N > M.", s"older(X, $youngest)", Map("person" -> cap(youngest)))
        case _ =>
          val twin = people(0)._1
          ("sameAge(X, Y) :- age(X, N), age(Y, N), X != Y.", s"sameAge(X, $twin)",
            Map("person" -> cap(twin)))
      val question = render(pick(rnd, ageQuestions(kind)(split)), slots ++ Map("low" -> "18", "high" -> "30"))
      Item("numbers", split, s"$facts $question", numbers, Map.empty,
        s"${people.map((p, a) => s"age($p, $a).").mkString(" ")} $rules", goal)
    }

  private val kinKinds = Vector("grandparent", "ancestor", "uncle", "sibling", "noChild")

  private def kin(split: Split, count: Int, rnd: Random): Vector[Item] =
    Vector.fill(count) {
      val kind = pick(rnd, kinKinds)
      val p = rnd.shuffle(family.constants("person")).take(4)
      val (a, b, c, d) = (p(0), p(1), p(2), p(3))
      val parents = Vector((a, b), (a, c), (b, d))
      val facts = parents.map((x, y) => render(pick(rnd, kinFactTexts(split)),
        Map("a" -> cap(x), "b" -> cap(y)))).mkString(" ")
      val (rules, goal, ask, male) = kind match
        case "grandparent" => (grandparent, s"grandparent($a, X)", Map("person" -> cap(a)), false)
        case "ancestor" => (ancestor, s"ancestor($a, X)", Map("person" -> cap(a)), false)
        case "uncle" => (s"$sibling $uncle", s"uncle(X, $d)", Map("person" -> cap(d)), true)
        case "sibling" => (sibling, s"sibling($b, X)", Map("person" -> cap(b)), false)
        case _ => (childless, "noChild(X)", Map.empty[String, String], false)
      val said = if male then " " + render(pick(rnd, maleTexts(split)), Map("name" -> cap(c))) else ""
      val program = s"${parents.map((x, y) => s"parent($x, $y).").mkString(" ")}" +
        (if male then s" male($c)." else "") + s" $rules"
      val question = render(pick(rnd, kinQuestions(kind)(split)), ask)
      Item("kinship", split, s"$facts$said $question", family, Map.empty, program, goal)
    }

  private val dataKinds = Vector("depend", "reaches", "unpaidUa", "noOrders")

  private def data(split: Split, count: Int, rnd: Random): Vector[Item] =
    Vector.fill(count) {
      val kind = pick(rnd, dataKinds)
      val (rules, goal) = kind match
        case "depend" => (reach, "reach(web, X)")
        case "reaches" => (reach, "reach(X, \"orders-db\")")
        case "unpaidUa" => (unpaidUa, "unpaidUa(I)")
        case _ => (noOrders, "noOrders(C)")
      Item("data", split, pick(rnd, dataTexts(kind)(split)), data, Sample.tables, rules, goal)
    }
