package okay.desk.bench

import okay.desk.calc.{Term, Ty}
import okay.desk.logic.{Compile, Corpus, LogicGrammar}
import okay.desk.skill.{Extract, LogicRead}

/**
 * THE DECISION BUDGET OF THE GATE (specs/logic.md §5.5), model-free: how many of a program's holes the head really
 * decides, and what a compact encoding would leave. A hole with one option is taken by the grammar, not by a head —
 * it cannot be read wrong — so only holes with two or more options count against the exact-program gate. This prints
 * the budget per split, per stage and per kind of decision, and the per-decision accuracy the gate demands: a program
 * with D decisions is exact with probability `a^D`, so 70% over D decisions needs `a >= 0.7^(1/D)`.
 *
 * The compact line answers the next question: the cons-chains (`cons`/`nil`) are shape, not reading — with an n-ary
 * list node one length decision replaces the whole chain, so `decisions - cons` is the budget of a compact grammar.
 * It reads no weights and no backbone.
 */
object LogicBudget:
  def term(i: Corpus.Item): Term =
    val p = okay.datalog.Parse.program(i.rules).fold(e => sys.error(e), identity)
    val a = okay.datalog.Parse.atom(i.goal).fold(e => sys.error(e), identity)
    Compile.ofProgram(p, a, i.ontology).fold(e => sys.error(e), identity)

  /** one hole of one task: its stage, the kind of choice, how many options it offered, and what the gold takes */
  final case class Hole(stage: String, kind: String, options: Int, target: String)

  private val splits = Vector("train" -> 16, "validate" -> 8, "test" -> 8)

  private def splitOf(name: String): Corpus.Split =
    Corpus.Split.values.find(_.toString.toLowerCase == name).get

  /** the kind of decision a hole asks for: the reading (goal, relation, binding, copy, operator) or the shape */
  private def kindOf(s: LogicRead.Step): String = s.ty match
    case Ty.GoalT => "goal"
    case Ty.AtomT(_, _, _) => "relation"
    case Ty.ArgT(_, _, _, _) | Ty.TermT(_) => if LogicGrammar.isVar(s.target) then "binding" else "copy"
    case Ty.LitT(_, _, _, _) => "literal"
    case Ty.FactsT | Ty.RulesT(_) | Ty.BodyT(_, _, _, _) => "list"
    case Ty.CmpOpT => "operator"
    case _ => "other"

  private def holesOf(i: Corpus.Item): Vector[Hole] =
    val g = LogicGrammar(i.ontology)
    val gold = Extract.gold(g, i.text, term(i)).fold(e => sys.error(s"${i.text}: $e"), identity)
    LogicRead.steps(g, gold).map { s =>
      Hole(Extract.stageOf(s.ty, s.target).toString, kindOf(s), s.options.size, s.target)
    }

  def main(args: Array[String]): Unit =
    val seed = 7
    val tasks = splits.map((name, n) => name -> Corpus.items(splitOf(name), n, seed)).toMap
    val holes = tasks.map((name, items) => name -> items.map(holesOf))

    def median(xs: Vector[Int]): String =
      if xs.isEmpty then "-"
      else
        val s = xs.sorted
        if s.size % 2 == 1 then s(s.size / 2).toString
        else "%.1f".format((s(s.size / 2 - 1) + s(s.size / 2)) / 2.0)

    for (name, _) <- splits do
      val perTask = holes(name)
      val hs = perTask.flatten
      val decisions = hs.filter(_.options > 1)
      val free = hs.size - decisions.size
      val perTaskDecisions = perTask.map(_.count(_.options > 1))
      val d = perTaskDecisions.sum.toDouble / perTaskDecisions.size
      println(s"$name: ${perTask.size} tasks, ${hs.size} holes: $free taken by the grammar " +
        s"(${"%.1f".format(100.0 * free / hs.size)}%), ${decisions.size} decided " +
        s"(${"%.1f".format(100.0 * decisions.size / hs.size)}%)")
      println(s"  decisions per task: mean ${"%.1f".format(d)} median ${median(perTaskDecisions)} " +
        s"max ${perTaskDecisions.max}")
      for kind <- decisions.map(_.kind).distinct.sorted do
        val ks = decisions.filter(_.kind == kind)
        val fk = hs.count(h => h.kind == kind && h.options == 1)
        println(s"  $kind: ${ks.size} decisions (${"%.1f".format(ks.size.toDouble / perTask.size)}/task), " +
          s"$fk free, ${"%.1f".format(ks.map(_.options).sum.toDouble / ks.size)} options on average")
      val cons = hs.count(h => h.kind == "list" && h.target == "cons")
      val lists = hs.count(h => h.kind == "list" && h.target == "nil")
      val compact = decisions.size - cons
      def need(dd: Double): String = "%.2f".format(100 * math.pow(0.7, 1 / dd))
      println(s"  gate arithmetic: D=$d decisions -> 70% exact needs ${need(d)}% per decision")
      println(s"  compact (n-ary lists: one length decision per list, $lists lists, $cons cons free): " +
        s"${compact} decisions, ${"%.1f".format(compact.toDouble / perTask.size)}/task -> needs " +
        s"${need(compact.toDouble / perTask.size)}%")
      println(s"  copies read by the pointer (measured 83%, not a head decision): " +
        s"${"%.1f".format(decisions.count(_.kind == "copy").toDouble / perTask.size)}/task")

    println()
    println("per family, decisions per task (as built / compact):")
    for f <- tasks("validate").map(_.family).distinct.sorted do
      val row = for (name, _) <- splits yield
        val mine = tasks(name).zip(holes(name)).filter(_._1.family == f)
        val n = mine.size
        val built = mine.map(_._2.count(_.options > 1)).sum.toDouble / math.max(1, n)
        val cons = mine.map(_._2.count(h => h.kind == "list" && h.target == "cons")).sum
        val comp = (mine.map(_._2.count(_.options > 1)).sum - cons).toDouble / math.max(1, n)
        s"$name ${"%.1f".format(built)}/${"%.1f".format(comp)}"
      println(s"  $f: ${row.mkString(", ")}")

    // the shape of the programs: a schema is the gold word with its constants abstracted and its variables canonical,
    // so two tasks share a schema when only the names differ — what a schema classifier would have to choose among
    println()
    println("program schemas (gold word, constants as #, canonical variables):")
    def schema(i: Corpus.Item): String =
      val g = LogicGrammar(i.ontology)
      val gold = Extract.gold(g, i.text, term(i)).fold(e => sys.error(s"${i.text}: $e"), identity)
      val cs = i.ontology.constants.values.flatten.toSet
      Extract.word(gold).map(t => if cs.contains(t) then "#" else t).mkString(" ")
    for f <- tasks("validate").map(_.family).distinct.sorted do
      val all = splits.flatMap((name, _) => tasks(name).filter(_.family == f))
      val shapes = all.map(schema)
      val counts = shapes.groupBy(identity).view.mapValues(_.size).toVector.sortBy(-_._2)
      println(s"  $f: ${all.size} tasks, ${counts.size} schemas; top ${counts.take(3).map(_._2).mkString("/")} " +
        s"tasks; ${counts.count(_._2 == 1)} seen once")
    val all = splits.flatMap((name, _) => tasks(name))
    val shapes = all.map(schema).distinct
    println(s"  all: ${all.size} tasks, ${shapes.size} schemas")
    // does the held-out split test a schema the train split never showed? (if not, the benchmark measures slots only)
    for f <- tasks("validate").map(_.family).distinct.sorted do
      val train = tasks("train").filter(_.family == f).map(schema).toSet
      val test = tasks("test").filter(_.family == f).map(schema)
      println(s"  $f: test schemas ${test.distinct.size}, of them in train " +
        s"${test.distinct.count(train.contains)}/${test.distinct.size}")

    // the projection a schema library offers: one decision for the schema, the slots read by the pointer
    println()
    val copies = holes("validate").flatten.count(_.kind == "copy").toDouble / tasks("validate").size
    val schemaBudget = 1 + copies
    println(s"schema-first projection: 1 schema decision + ${"%.1f".format(copies)} pointer slots = " +
      s"${"%.1f".format(schemaBudget)} decisions/task -> 70% exact needs " +
      s"${"%.2f".format(100 * math.pow(0.7, 1 / schemaBudget))}% per decision; a schema choice is " +
      s"4-way within a family")
