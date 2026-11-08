package okay.desk.learn

import okay.desk.calc.Language
import okay.inlay.{Cat, Compile, Forward, Guard, Interpret, Layer, Program, Rule, Val, Write}

/**
 * A GRAMMAR AS A BLOCK (BACKLOG D: the book of the model's languages): the forms of a language's names — a table in
 * four cases, a column in three — compiled into a transformer block: the name and the case in, the form out; one
 * layer of rules, one position; the law: the block equals the table on every (name, case). What a small model
 * gets wrong in its weights ("заказах" or "заказов") is exact here, and `Say` can take its forms from the block as
 * well as from the table — the paradigm is a part of the model, readable, with a proof.
 */
final case class Paradigm(language: Language):
  private val tableCases = Vector("nom", "gen", "prep", "dat")
  private val columnCases = Vector("nom", "gen", "dat")
  val entries: Vector[(String, String, String)] =
    language.tables.toVector.sortBy(_._1).flatMap((n, fs) => fs.zip(tableCases).map((f, c) => (n, c, f))) ++
      language.columns.toVector.sortBy(_._1).flatMap((n, fs) => fs.zip(columnCases).map((f, c) => (n, c, f)))
  private val names = entries.map(_._1).distinct
  private val cases = entries.map(_._2).distinct
  private val forms = entries.map(_._3).distinct
  private val name = Cat("name", names)
  private val kase = Cat("case", cases)
  private val form = Cat("form", forms)
  val program: Program = Program(Seq(name, kase), Seq(Layer(rules = entries.map((n, c, f) =>
    Rule(Seq(Guard.Is(name, n), Guard.Is(kase, c)), Seq(Write.To(form, f)))))), maxLen = 2)
  lazy val weights = Compile(program)
  private def in(n: String, c: String): Vector[Interpret.Record] = Vector(Map("name" -> Val.C(Some(n)), "case" -> Val.C(Some(c))))
  private def out(rs: Vector[Interpret.Record]): Option[String] = rs.lastOption.flatMap(_.get("form")).collect { case Val.C(Some(f)) => f }
  /** the form by the table */
  def byTable(n: String, c: String): Option[String] = entries.collectFirst { case (`n`, `c`, f) => f }
  /** the form by the interpreter */
  def interpret(n: String, c: String): Option[String] = if names.contains(n) && cases.contains(c) then out(Interpret.run(program, in(n, c))) else None
  /** the form by the block */
  def forward(n: String, c: String): Option[String] = if names.contains(n) && cases.contains(c) then out(Forward.run(program, weights, in(n, c))) else None
