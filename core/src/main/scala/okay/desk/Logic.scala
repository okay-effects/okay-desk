package okay.desk

import okay.datalog.{Eval, Fact, Parse}

/**
 * THE LOGICAL HANDLER: the tables as relations (a row a fact, the columns its positions in order), the model's
 * rules on okay-datalog, the goal's bindings as the result table. For what a relational query cannot say: paths,
 * closures, rules with exceptions — exact, with proofs available.
 */
object Logic:
  def facts(tables: Map[String, Table]): Vector[Fact] =
    tables.values.toVector.flatMap(t => t.rows.map(r => Fact(t.name, r.map(_.text))))

  /** a bare word with a hyphen or a capital inside (orders-db, Kyiv) quoted, as the parser wants — outside quotes */
  def quoted(text: String): String =
    "(\"[^\"]*\")|([A-Za-z_][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)+)".r.replaceAllIn(text, m =>
      if m.group(1) != null then java.util.regex.Matcher.quoteReplacement(m.group(1))
      else "\"" + m.group(2) + "\"")

  def run(tables: Map[String, Table], q: Query.Logical): Either[String, Table] =
    for
      program <- Parse.program(quoted(q.rules)).left.map(e => s"rules: $e")
      goal <- Parse.atom(quoted(q.goal)).left.map(e => s"goal: $e")
      result <- Eval.run(program, facts(tables)).left.map(e => s"evaluation: $e")
    yield bindings(result, goal)

  /** a program built as data (the logic grammar's denotation): no text, no parsing, the same exact handler */
  def run(tables: Map[String, Table], program: okay.datalog.Program, goal: okay.datalog.Atom): Either[String, Table] =
    Eval.run(program, facts(tables)).left.map(e => s"evaluation: $e").map(bindings(_, goal))

  private def bindings(result: okay.datalog.Result, goal: okay.datalog.Atom): Table =
    val rows = result.query(goal)
    val vars = rows.headOption.map(_.keys.toVector.sorted).getOrElse(Vector.empty)
    Table("result", vars, rows.map(b => vars.map(v => Value.of(b(v)))))

  /** a constant as Datalog writes it: bare when a lowercase word, quoted otherwise */
  def constant(s: String): String =
    if s.nonEmpty && s.head.isLower && s.forall(c => c.isLetterOrDigit || c == '_') then s
    else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** the relations as the model must write them: each table's name and its columns' positions */
  def describe(tables: Map[String, Table]): String =
    tables.values.toVector.sortBy(_.name).map(t => s"  ${t.name}(${t.columns.mkString(", ")})").mkString("\n")

/**
 * A CONJUNCTIVE QUERY COMPILED TO DATALOG (specs/data.md §24): the rows of a table that join others on keys, meet
 * conditions, and — what the relational query cannot say — have NO matching row in another table (stratified
 * negation). The one builder every such program comes from, so a program is well-formed by construction: a rule
 * `q(selected…) :- target(all its columns as variables), joined(…shared variables…), conditions, not has(key)`,
 * and `has(key) :- absent(…)` for the negation; the goal `q(…)`.
 */
object Conjunctive:
  final case class Join(table: String, on: String, other: String)
  final case class Cond(table: String, column: String, op: String, value: String)
  /** `absent`: rows of `target` with no row of that table whose `other` column equals the target's `on` column */
  def program(tables: Map[String, Table], target: String, joins: Vector[Join] = Vector.empty, conds: Vector[Cond] = Vector.empty,
      absent: Option[Join] = None, select: Vector[String] = Vector.empty): Either[String, Query.Logical] =
    def vars(t: Table): Vector[String] = t.columns.map(c => "V" + t.name.capitalize.filter(_.isLetterOrDigit) + c.capitalize.filter(_.isLetterOrDigit))
    def atom(t: Table, vs: Vector[String]) = s"${t.name}(${vs.mkString(", ")})"
    for
      t <- tables.get(target).toRight(s"no table $target")
      js <- joins.foldLeft(Right(Vector.empty[(Table, Vector[String])]): Either[String, Vector[(Table, Vector[String])]]) { (acc, j) =>
        acc.flatMap(got => tables.get(j.table).toRight(s"no table ${j.table}").flatMap { o =>
          if !t.columns.contains(j.on) then Left(s"no column ${j.on} in $target") else if !o.columns.contains(j.other) then Left(s"no column ${j.other} in ${o.name}")
          else Right(got :+ (o, vars(o).updated(o.columns.indexOf(j.other), vars(t)(t.columns.indexOf(j.on))))) }) }
      ab <- absent.fold(Right(None): Either[String, Option[(Table, Vector[String], String)]])(a => tables.get(a.table).toRight(s"no table ${a.table}").flatMap { o =>
        if !t.columns.contains(a.on) then Left(s"no column ${a.on} in $target") else if !o.columns.contains(a.other) then Left(s"no column ${a.other} in ${o.name}")
        else Right(Some((o, vars(o), vars(t)(t.columns.indexOf(a.on))))) })
    yield
      val all = (t, vars(t)) +: js
      def varOf(table: String, column: String): Option[String] = all.find(_._1.name == table).flatMap((tb, vs) => tb.columns.indexOf(column) match { case -1 => None; case i => Some(vs(i)) })
      val cs = conds.flatMap(c => varOf(c.table, c.column).map(v => s"$v ${if c.op == "contains" then "=" else c.op} ${Value.of(c.value).num.map(_ => c.value).getOrElse(Logic.constant(c.value))}"))
      val selected = (if select.isEmpty then t.columns else select).flatMap(c => varOf(target, c).orElse(all.drop(1).flatMap((tb, _) => varOf(tb.name, c)).headOption))
      val head = s"q(${selected.mkString(", ")})"
      val body = all.map((tb, vs) => atom(tb, vs)) ++ cs ++ ab.toVector.map((_, _, key) => s"not has($key)")
      val hasRule = ab.map((o, vs, _) => s" has(${vs(o.columns.indexOf(absent.get.other))}) :- ${atom(o, vs)}.").getOrElse("")
      Query.Logical(s"$head :- ${body.mkString(", ")}.$hasRule", head)
