package okay.desk.bench

import okay.desk.calc.Term
import okay.desk.logic.{Compile, Corpus, LogicGrammar}
import okay.desk.skill.{Extract, LogicRead}

/**
 * THE DECISION BUDGET OF THE GATE (specs/logic.md §8), model-free: how many of a program's holes the head really
 * decides. A hole with one option is taken by the grammar, not by a head — it cannot be read wrong — so only the
 * holes with two or more options count against the exact-program gate. This prints the budget per split and per
 * stage, and the per-decision accuracy the gate arithmetically demands: a program with D decisions is exact with
 * probability `a^D`, so 70% over D decisions needs `a >= 0.7^(1/D)`. It reads no weights and no backbone.
 */
object LogicBudget:
  def term(i: Corpus.Item): Term =
    val p = okay.datalog.Parse.program(i.rules).fold(e => sys.error(e), identity)
    val a = okay.datalog.Parse.atom(i.goal).fold(e => sys.error(e), identity)
    Compile.ofProgram(p, a, i.ontology).fold(e => sys.error(e), identity)

  /** one hole of one task: its stage, how many options it offered, and whether the choice is a copied constant */
  final case class Hole(split: String, family: String, stage: String, options: Int, copy: Boolean)

  private val splits = Vector("train" -> 16, "validate" -> 8, "test" -> 8)

  private def splitOf(name: String): Corpus.Split =
    Corpus.Split.values.find(_.toString.toLowerCase == name).get

  def main(args: Array[String]): Unit =
    val seed = 7
    val tasks = splits.map((name, n) => name -> Corpus.items(splitOf(name), n, seed)).toMap
    val holes = for
      (name, n) <- splits
      i <- Corpus.items(splitOf(name), n, seed)
      g = LogicGrammar(i.ontology)
      gold = Extract.gold(g, i.text, term(i)).fold(e => sys.error(s"${i.text}: $e"), identity)
      s <- LogicRead.steps(g, gold)
    yield Hole(name, i.family, Extract.stageOf(s.ty, s.target).toString, s.options.size,
      Extract.copies(s.ty, s.target))

    /** the decisions of one task: the holes the grammar leaves open */
    def decisionsOf(i: Corpus.Item): Int =
      val g = LogicGrammar(i.ontology)
      val gold = Extract.gold(g, i.text, term(i)).fold(e => sys.error(s"${i.text}: $e"), identity)
      LogicRead.steps(g, gold).count(_.options.size > 1)

    def median(xs: Vector[Int]): String =
      if xs.isEmpty then "-"
      else
        val s = xs.sorted
        if s.size % 2 == 1 then s(s.size / 2).toString
        else "%.1f".format((s(s.size / 2 - 1) + s(s.size / 2)) / 2.0)

    for (name, _) <- splits do
      val hs = holes.filter(_.split == name)
      val decisions = hs.filter(_.options > 1)
      val perTask = tasks(name).map(decisionsOf)
      val free = hs.size - decisions.size
      println(s"$name: ${tasks(name).size} tasks, ${hs.size} holes: $free taken by the grammar " +
        s"(${"%.1f".format(100.0 * free / hs.size)}%), ${decisions.size} decided " +
        s"(${"%.1f".format(100.0 * decisions.size / hs.size)}%)")
      println(s"  decisions per task: mean ${"%.1f".format(perTask.sum.toDouble / perTask.size)} " +
        s"median ${median(perTask)} max ${perTask.max}; copies among decisions ${decisions.count(_.copy)}")
      for stage <- decisions.map(_.stage).distinct.sorted do
        val ss = decisions.filter(_.stage == stage)
        val freeStage = hs.count(h => h.stage == stage && h.options == 1)
        println(s"  $stage: ${ss.size} decisions, $freeStage free, copies ${ss.count(_.copy)}")
      val d = perTask.sum.toDouble / perTask.size
      println(s"  gate arithmetic: D=$d decisions -> 70% exact needs " +
        s"${"%.2f".format(100 * math.pow(0.7, 1 / d))}% per decision; at 94% " +
        s"${"%.1f".format(100 * math.pow(0.94, d))}%, at 98% ${"%.1f".format(100 * math.pow(0.98, d))}%, " +
        s"at 99% ${"%.1f".format(100 * math.pow(0.99, d))}%")

    println()
    println("per family, decisions per task:")
    for f <- holes.map(_.family).distinct.sorted do
      val row = for (name, _) <- splits yield
        val n = tasks(name).count(_.family == f)
        val dec = holes.count(h => h.split == name && h.family == f && h.options > 1)
        s"$name ${"%.1f".format(dec.toDouble / math.max(1, n))}"
      println(s"  $f: ${row.mkString(", ")}")
