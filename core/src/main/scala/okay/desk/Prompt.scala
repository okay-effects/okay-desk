package okay.desk

/**
 * THE MODEL'S PART: it understands the question and writes the program; it never computes a number. The prompt
 * gives the tables' schema and the program's form; the answer is parsed for the program (a JSON block), or taken
 * as words when there is none. A summary the model writes after the result is kept only if every number in it is
 * in the result — else the table speaks alone.
 */
object Prompt:
  def system(tables: Map[String, Table]): String =
    val schema = tables.values.toVector.sortBy(_.name).map(_.describe).mkString("\n")
    s"""You answer questions about the user's data by writing ONE query as JSON; a program runs it and shows the result.
       |Never compute numbers yourself. Never invent columns. Use the exact column names below.
       |
       |$schema
       |
       |Relational query (use for filters, counts, sums, averages, groups, top-N, joins):
       |{"from": "table", "where": [{"column": "c", "op": "=", "value": "x"}], "group": ["c"],
       | "aggregate": [{"fn": "count", "column": "*", "as": "n"}], "select": ["c", "n"], "order": ["-n"], "limit": 10}
       |ops: = != < <= > >= contains empty "not empty"; aggregate fn: count sum avg min max; order "-c" means descending;
       |"join": {"table": "other", "on": [["this_column", "other_column"]]}; omit what you do not need.
       |
       |Datalog query (use for chains, reachability, rules): relations are the tables with their columns in order:
       |${Logic.describe(tables)}
       |{"datalog": "reach(X, Y) :- edges(X, Y). reach(X, Z) :- edges(X, Y), reach(Y, Z).", "goal": "reach(a, X)"}
       |variables start with a capital letter; a constant that is not a lowercase word goes in double quotes: reach(X, \"orders-db\")
       |
       |Reply with the JSON only, in a ```json block. If the question is not about the data, answer in one sentence.""".stripMargin

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
