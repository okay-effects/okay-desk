package okay.desk

/**
 * THE MODEL'S PART: it understands the question and writes the program; it never computes a number. The prompt
 * gives the tables' schema, the program's form in words, and a few examples OVER THESE TABLES (a small model
 * copies an example's names if they are not its own); the answer is parsed for the program (a JSON block), or
 * taken as words when there is none. A summary the model writes after the result is kept only if every number in
 * it is in the result — else the table speaks alone.
 */
object Prompt:
  /** examples written for the tables at hand: the first table's first text and first numeric columns */
  def examples(tables: Map[String, Table]): String =
    val ts = tables.values.toVector.sortBy(_.name)
    val t = ts.head
    def numeric(i: Int) = t.rows.nonEmpty && t.rows.forall(r => r(i) == Value.Empty || r(i).num.isDefined) && t.rows.exists(_(i).num.isDefined)
    val textCol = t.columns.indices.find(i => !numeric(i)).map(t.columns).getOrElse(t.columns.head)
    val numCol = t.columns.indices.find(numeric).map(t.columns)
    val sample = t.rows.headOption.map(_(t.columns.indexOf(textCol)).text).getOrElse("x")
    val rel = ts.find(_.columns.size == 2).getOrElse(t)
    val a = rel.rows.headOption.map(_(0).text).getOrElse("a")
    (Vector(
      s"""Q: How many rows are in ${t.name}?
         |A: {"from": "${t.name}", "aggregate": [{"fn": "count", "column": "*", "as": "n"}]}""".stripMargin,
      s"""Q: Which rows of ${t.name} have $textCol = $sample?
         |A: {"from": "${t.name}", "where": [{"column": "$textCol", "op": "=", "value": "$sample"}]}""".stripMargin) ++
      numCol.toVector.map(n =>
        s"""Q: Total $n by $textCol, largest first, top 5.
           |A: {"from": "${t.name}", "group": ["$textCol"], "aggregate": [{"fn": "sum", "column": "$n", "as": "total"}], "order": ["-total"], "limit": 5}""".stripMargin) ++
      Vector(
        s"""Q: What is reachable from $a through ${rel.name}, directly or through others?
           |A: {"datalog": "reach(X, Y) :- ${rel.name}(X, Y). reach(X, Z) :- ${rel.name}(X, Y), reach(Y, Z).", "goal": "reach(${Logic.constant(a)}, X)"}""".stripMargin))
      .mkString("\n\n")

  def system(tables: Map[String, Table]): String =
    val schema = tables.values.toVector.sortBy(_.name).map(_.describe).mkString("\n")
    s"""You answer questions about the user's data by writing ONE query as JSON in a ```json block; a program runs it and shows the result.
       |Never compute numbers yourself. Use only the tables and the exact column names below. Omit keys you do not need.
       |
       |$schema
       |
       |A relational query has: "from" (a table), optional "join" {"table", "on": [["this column", "other column"]]},
       |"where" (a list of {"column", "op", "value"}; op: = != < <= > >= contains empty "not empty"),
       |"group" (columns), "aggregate" (a list of {"fn", "column", "as"}; fn: count sum avg min max; column "*" for count),
       |"select" (columns to show), "order" (columns; "-c" descending), "limit" (a number).
       |A datalog query has "datalog" (rules) and "goal" (an atom); the relations are the tables with their columns in order:
       |${Logic.describe(tables)}
       |Variables start with a capital letter; a constant that is not a lowercase word goes in double quotes.
       |
       |Examples for these tables:
       |
       |${examples(tables)}
       |
       |If the question is not about the data, answer in one sentence instead.""".stripMargin

  /** the JSON block of an answer, or the words */
  def program(answer: String): Either[String, String] =
    val a = answer.replaceAll("(?s)<think>.*?</think>", "").trim
    val block = "(?s)```(?:json)?\\s*(\\{.*?\\})\\s*```".r.findFirstMatchIn(a).map(_.group(1))
    block.orElse("(?s)(\\{.*\\})".r.findFirstMatchIn(a).map(_.group(1))).toRight(a)

  /** the model asked to say the result in words: one or two sentences, with the numbers as they are */
  def summarize(question: String, q: Query, result: Table): String =
    s"""The question: $question
       |The program that ran: ${Query.print(q)}
       |Its result (${result.size} rows):
       |${result.render(20)}
       |
       |Answer the question in one or two sentences from this result only. Use the numbers exactly as shown. No JSON.""".stripMargin

  /** every number the summary states is in the result; else the summary is dropped */
  def faithful(summary: String, result: Table): Boolean =
    val said = "-?\\d+(?:[.,]\\d+)?".r.findAllIn(summary).toVector.map(_.replace(",", "."))
    val shown = (result.rows.flatten.map(_.text) ++ Vector(result.size.toString)).toSet
    said.forall(n => shown.contains(n) || shown.exists(s => s.toDoubleOption.exists(d => n.toDoubleOption.exists(x =>
      math.abs(d - x) < 1e-9 || (x != 0 && math.abs(d - x) / math.abs(x) < 1e-6)))))
