package okay.desk.skill

import okay.desk.*
import okay.scala3.*

/**
 * THE SKILL AS READERS AND AN ASSEMBLER (specs/data.md §7, §9, §10): the model untouched; probes trained on the
 * pairs read the program's slots from its states where it decides them — the table, the shape, the columns, the
 * op, and the START of a condition's value among the question's tokens (the quote mechanism: copied from there,
 * not generated); the program is assembled — exact, well-formed by construction. `program` answers a question the
 * way the page's loop would, with no generation at all. Every method is a program over `Read` (§15 step 4): the
 * JVM runs it in place (`Read.run`), the browser by futures (`Read.future`) — one skill, both platforms.
 */
final class Readers:
  import Probes.*
  import Read.each
  private var mean: Array[Float] = Array.empty; private var std: Array[Float] = Array.empty; private var scale = 1f
  private def z(x: Array[Float]): Array[Float] =
    Array.tabulate(x.length)(i => math.max(-4f, math.min(4f, (x(i) - mean(i)) / std(i))) * scale)
  /** the data's own words (§29): a dataset's names in another language, read as exact facts beside the generator's
   * dictionary; none unless the data brings them */
  var words: Words = Words.none
  private var shape: Softmax = null
  private var op: Softmax = null
  private var fnProbe: Softmax = null
  /** THE READERS BY CONSTRUCTOR (§25, step 3): one small probe per constructor of the space, read at the program's
   * start — the kind (rows, an absence, a closure either way), whether there is a condition and its comparison, a
   * group, an order and its direction, a limit, a selection. The compound shape probe (one class per combination)
   * fell to 46% on the space's pairs, and its marginal "some condition" fired on everything */
  private val parts = scala.collection.mutable.Map.empty[String, Softmax]
  private val partNames = Vector("kind", "where", "group", "order", "dir", "limit", "select")
  /** the constructor's classes of a gold program */
  private def partsOf(program: String, sl: Slots): Map[String, String] =
    val dir = Query.parse(program).toOption.collect { case Query.Relational(_, _, _, _, _, _, o +: _, _) => if o.desc then "desc" else "asc" }.getOrElse("-")
    val kind = if sl.shape == "absent" || sl.shape.startsWith("closure") then sl.shape else "rows"
    Map("kind" -> kind, "where" -> Readers.part(sl.shape, 2), "group" -> Readers.part(sl.shape, 3), "order" -> Readers.part(sl.shape, 4),
      "dir" -> dir, "limit" -> Readers.part(sl.shape, 5), "select" -> Readers.part(sl.shape, 7))
  /** the probability a constructor's reading satisfies `p`; the compound probe's marginal when the skill predates the parts */
  private def partP(name: String, q: Array[Float], shapeProbs: Array[Double], p: String => Boolean): Double =
    parts.get(name) match
      case Some(probe) =>
        val ps = logSoftmax(probe.scores(q)).map(v => math.exp(v.toDouble))
        probe.classes.zipWithIndex.collect { case (c, i) if p(c) => ps(i) }.sum
      case None =>
        val i = partNames.indexOf(name)
        val of: String => String = name match
          case "kind" => c => if c == "absent" || c.startsWith("closure") then c else "rows"
          case "dir" => _ => "-"
          case _ => c => Readers.part(c, Vector(0, 2, 3, 4, 0, 5, 7)(i))
        shape.classes.zipWithIndex.collect { case (c, j) if p(of(c)) => shapeProbs(j) }.sum
  val fns = Vector("count", "sum", "avg", "min", "max")
  private def fnPrefix(t: String) = "```json\n{\"from\": \"" + t + "\", \"aggregate\": [{\"fn\": \""
  private val pointers = scala.collection.mutable.Map.empty[String, Pointer]
  val ops = Vector("=", "!=", "<", "<=", ">", ">=", "contains")
  private def opPrefix(t: String, c: String) = "```json\n{\"from\": \"" + t + "\", \"where\": [{\"column\": \"" + c + "\", \"op\": \""
  private def valuePrefix(t: String, c: String, o: String) = opPrefix(t, c) + o + "\", \"value\": "

  /** the state at the end of the prompt followed by `prefix` — where the model decides the slot */
  private def query[R[_]: Has[Read]](pr: Prompted, prefix: String): Array[Float] ! R =
    for
      more <- Read.encode(prefix)
      ids = pr.ids ++ more
      st <- Read.states(ids, Vector(ids.length - 1))
    yield st.head
  private def keys[R[_]: Has[Read]](pr: Prompted): Vector[Array[Float]] ! R = Read.states(pr.ids, pr.names.map(_._2))
  private def questionStates[R[_]: Has[Read]](pr: Prompted): Vector[Array[Float]] ! R = Read.states(pr.ids, pr.question)

  /** ASK — the second reader (bridge-4's lesson: two readers fail on different phrasings; their sum read 100%): the
   * model's own next-token log-probabilities at the prefix's end, over the candidates' first tokens */
  private def ask[R[_]: Has[Read]](pr: Prompted, prefix: String, candidates: Vector[String]): Array[Float] ! R =
    for
      more <- Read.encode(prefix)
      l <- Read.logits(pr.ids ++ more)
      firsts <- each(candidates)(c => Read.encode(c).map(_.head))
    yield
      val m = l.max; val z = math.log(l.map(v => math.exp((v - m).toDouble)).sum)
      firsts.map(f => (l(f) - m - z).toFloat).toArray
  /** the table by both readers: the pointer at the question's last token and the model asked at the program's start */
  private def tableBoth[R[_]: Has[Read]](pr: Prompted, last: Array[Float], ks: Vector[Array[Float]], names: Vector[String]): Array[Float] ! R =
    val tks = names.map(n => ks(pr.indexOfTable(n)))
    val ps = logSoftmax(pointers("table").scores(last, tks).take(tks.size))
    ask(pr, "```json\n{\"from\": \"", names).map(as => ps.zip(as).map((a, b) => a + b))
  private def logSoftmax(xs: Array[Float]): Array[Float] =
    val m = xs.max; val z = math.log(xs.map(v => math.exp((v - m).toDouble)).sum)
    xs.map(v => (v - m - z).toFloat)
  /** both readers summed: the pointer's log-probabilities over the candidates and the model's */
  // `candidates`: (table, column) pairs — the index found within the table
  private def both[R[_]: Has[Read]](pr: Prompted, prefix: String, pointer: Pointer, q: Array[Float], ks: Vector[Array[Float]],
      candidates: Vector[(String, String)]): String ! R =
    bothScores(pr, prefix, pointer, q, ks, candidates).map(scored => candidates(scored.zipWithIndex.maxBy(_._1)._2)._2)
  /** the two readers' summed log-probabilities per candidate */
  private def bothScores[R[_]: Has[Read]](pr: Prompted, prefix: String, pointer: Pointer, q: Array[Float], ks: Vector[Array[Float]],
      candidates: Vector[(String, String)]): Array[Float] ! R =
    val names = candidates.map(_._2)
    val cks = candidates.map((t, c) => ks(pr.indexOf(t, c)))
    val ps = logSoftmax(pointer.scores(q, cks).take(cks.size))
    ask(pr, prefix, names).map { as =>
      // the two readers disagreeing is remembered: the self-play judge takes only answers they agreed on
      if ps.zipWithIndex.maxBy(_._1)._2 != as.zipWithIndex.maxBy(_._1)._2 then agreedAll = false
      ps.zip(as).map((a, b) => a + b)
    }
  private var agreedAll = true
  /** the margin under which the table reading abstains (log-probability); 0 never abstains */
  var abstainMargin: Float = 0.3f
  /** the program, and whether every slot the two readers both read was read alike */
  def programWith[R[_]: Has[Read]](tables: Map[String, Table], question: String): Option[(String, Boolean)] ! R =
    agreedAll = true
    program(tables, question).map(_.toOption.map(p => (p, agreedAll)))

  /** the gold value's first token among the question's, if the value is written verbatim in the question */
  private def valueStart(pr: Prompted, value: String): Option[Int] =
    val at = pr.qText.toLowerCase.indexOf(value.toLowerCase)
    Option.when(at >= 0)(pr.qChars.lastIndexWhere(_ <= at)).filter(_ >= 0)

  /** THE NAME READER (§25): the token of the question that names a table — its English name or a Russian form of it
   * (`Phrase.Russian.tables`) — for the pointer from the question's TOKENS to the tables' names; the last-token
   * pointer reads the table by meaning, this one by the name written, in any language */
  private def nameToken(pr: Prompted, table: String): Option[Int] =
    val forms = table +: words.table(table).toVector.flatMap((a, b, c, d) => Vector(a, b, c, d))
    forms.flatMap(valueStart(pr, _)).headOption
  /** the tables scored by the name pointer: each table's best question token (log-softmax over the tables) */
  private def nameScores(qs: Vector[Array[Float]], tks: Vector[Array[Float]]): Array[Float] =
    val p = pointers("name")
    val per = qs.map(q => p.scores(q, tks))
    logSoftmax(Array.tabulate(tks.size)(i => per.map(_(i)).max))

  /** the two table readers' log-probabilities per table, and the question token each table's name score came from */
  def tableReadings[R[_]: Has[Read]](tables: Map[String, Table], question: String): String ! R =
    for
      pr <- prompted(Pairs.Pair(tables, question, ""))
      ks <- keys(pr).map(_.map(z))
      last <- Read.states(pr.ids, Vector(pr.ids.length - 1)).map(st => z(st.head))
      qs <- questionStates(pr).map(_.map(z))
      pieces <- Read.each(pr.question)(t => Read.decode(Seq(pr.ids(t))))
    yield
      val names = tables.keys.toVector.sorted
      val tks = names.map(n => ks(pr.indexOfTable(n)))
      val byLast = logSoftmax(pointers("table").scores(last, ks))
      val byMeaning = logSoftmax(names.map(n => byLast(pr.indexOfTable(n))).toArray)
      val per = qs.map(q => pointers("name").scores(q, tks))
      val byName = nameScores(qs, tks)
      names.zipWithIndex.map { (n, i) =>
        val tok = per.indices.maxBy(j => per(j)(i))
        f"$n: meaning ${byMeaning(i)}%.2f name ${byName(i)}%.2f (at '${pieces(tok).trim}')" }.mkString("; ")

  def train[R[_]: Has[Read]](pairs: Vector[Pairs.Pair]): Unit ! R =
    for
      exs <- each(pairs.flatMap(p => slots(p.program).map(sl => (p, sl))))((p, sl) => prompted(p).map(pr => (p, sl, pr)))
      base <- each(exs)((_, sl, pr) => Read.states(pr.ids, (pr.ids.length - 1) +: pr.names.map(_._2)).map(st => (sl, pr, st)))
      keyStates =
        val all = base.flatMap(_._3)
        val d = all.head.length
        mean = Array.tabulate(d)(i => all.map(_(i)).sum / all.size)
        std = Array.tabulate(d)(i => math.max(math.sqrt(all.map(x => (x(i) - mean(i)) * (x(i) - mean(i))).sum / all.size), 1e-3).toFloat)
        scale = 8f / math.sqrt(d).toFloat
        val keyStates = base.map(_._3.tail.map(z))
        val table = Pointer(d)
        table.train(base.zip(keyStates).map { case ((sl, pr, st), ks) => (z(st.head), ks, pr.indexOfTable(sl.table)) })
        pointers("table") = table
        keyStates
      d = mean.length
      _ <- each(prefixes) { (name, prefix) =>
        each(exs)((_, sl, pr) => query(pr, prefix(sl.table)).map(z)).map { qs =>
          if name == "shape" then
            shape = Softmax(exs.map(_._2.shape).distinct, d)
            shape.train(qs.zip(exs).map((q, e) => (q, e._2.shape)), steps = 300, lr = 0.05f)
            val golds = exs.map((p, sl, _) => partsOf(p.program, sl))
            for part <- partNames do
              val probe = Softmax(golds.map(_(part)).distinct, d)
              probe.train(qs.zip(golds).map((q, g) => (q, g(part))), steps = 300, lr = 0.05f)
              parts(part) = probe
          else
            val p = Pointer(d)
            val slot: Slots => Option[String] = name match
              case "where" => _.where
              case "group" => _.group
              case "agg" => _.agg
              case _ => _.order
            // trained over the table's own columns (and none): other tables' names would only dilute the choice
            p.train(qs.zip(exs).zip(keyStates).map { case ((q, (p0, sl, pr)), ks) =>
              val cols = p0.tables(sl.table).columns
              (q, cols.map(c => ks(pr.indexOf(sl.table, c))), slot(sl).map(cols.indexOf).filter(_ >= 0).getOrElse(cols.size)) })
            pointers(name) = p
        }
      }
      // the name pointer: the question token naming the table against the tables' names
      nameEx <- each(exs.zip(keyStates)) { case ((p, sl, pr), ks) =>
        nameToken(pr, sl.table).fold(pure[Option[(Array[Float], Vector[Array[Float]], Int)], R](None)) { tok =>
          questionStates(pr).map { qs =>
            val names = p.tables.keys.toVector.sorted
            Some((z(qs(tok)), names.map(n => ks(pr.indexOfTable(n))), names.indexOf(sl.table)))
          }
        }
      }
      _ =
        val name = Pointer(d)
        name.train(nameEx.flatten, steps = 150, lr = 0.05f)
        pointers("name") = name
        println(s"  the name reader: ${nameEx.flatten.size} of ${exs.size} pairs name the table in the question")
      // the op and the value's start, on the pairs that have a condition
      withWhere = exs.flatMap { (p, sl, pr) =>
        Query.parse(p.program).toOption.collect { case Query.Relational(_, _, Cond.Cmp(c, o, v) +: _, _, _, _, _, _) => (p, sl, pr, c, o, v.text) }
      }
      // the aggregate's function read at its own point, where it is written
      fnQs <- each(exs)((_, sl, pr) => query(pr, fnPrefix(sl.table)).map(z))
      _ =
        fnProbe = Softmax(fns :+ "-", d)
        fnProbe.train(fnQs.zip(exs).map((q, e) => (q, Readers.part(e._2.shape, 1))), steps = 300, lr = 0.05f)
      opQs <- each(withWhere)((_, sl, pr, c, _, _) => query(pr, opPrefix(sl.table, c)).map(z))
      _ =
        op = Softmax(ops, d)
        op.train(opQs.zip(withWhere).map((q, e) => (q, e._5)), steps = 300, lr = 0.05f)
      valueEx <- each(withWhere) { (_, sl, pr, c, o, v) =>
        valueStart(pr, v).fold(pure[Option[(Array[Float], Vector[Array[Float]], Int)], R](None)) { start =>
          for
            q <- query(pr, valuePrefix(sl.table, c, o))
            qs <- questionStates(pr)
          yield Some((z(q), qs.map(z), start))
        }
      }
    yield
      val value = Pointer(d)
      value.train(valueEx.flatten, steps = 150, lr = 0.05f)
      pointers("value") = value
      println(s"  readers: ${exs.size} pairs, ${withWhere.size} with a condition, ${valueEx.flatten.size} with the value in the question")

  private val numberWords = Map("one" -> 1, "two" -> 2, "three" -> 3, "four" -> 4, "five" -> 5, "ten" -> 10,
    "один" -> 1, "одна" -> 1, "два" -> 2, "две" -> 2, "три" -> 3, "четыре" -> 4, "пять" -> 5, "десять" -> 10)

  /** the question answered by the readers: the program's text, or why not */
  def program[R[_]: Has[Read]](tables: Map[String, Table], question: String): Either[String, String] ! R =
    for
      pr <- prompted(Pairs.Pair(tables, question, ""))
      _ <- Read.note(s"the model reads the question and the tables' description (${pr.ids.length} tokens)…")
      names = pr.names.map(_._1)
      ks <- keys(pr).map(_.map(z))
      last <- Read.states(pr.ids, Vector(pr.ids.length - 1)).map(st => z(st.head))
      // the table by the pointer among every name (§24: 61% of the held-out tables; among the tables' names alone or
      // with the model asked at `{"from": "` it read fewer of the sample — a column pointed at falls to the exact rules)
      // the table by BOTH readers: the last-token pointer (by meaning) and the name pointer (by the name written in the
      // question, in any language), their log-probabilities over the tables summed
      tableNames = tables.keys.toVector.sorted
      qs <- if pointers.contains("name") then questionStates(pr).map(_.map(z)) else pure[Vector[Array[Float]], R](Vector.empty)
      tableScores =
        val byLast = logSoftmax(pointers("table").scores(last, ks))
        val tks = tableNames.map(n => ks(pr.indexOfTable(n)))
        val byMeaning = logSoftmax(tableNames.map(n => byLast(pr.indexOfTable(n))).toArray)
        // the name reader alone when it is sure (94.5% alone on the held-out; the meaning reader is biased by a schema:
        // customers on every question of the sample), else both
        val both = if qs.isEmpty then byMeaning else
          val byName = nameScores(qs, tks)
          val sorted = byName.sorted.reverse
          if sorted.length < 2 || sorted(0) - sorted(1) >= 0.5f then byName else byMeaning.zip(byName).map(_ + _)
        val all = byLast.clone()
        for (n, i) <- tableNames.zipWithIndex do all(pr.indexOfTable(n)) = both(i)
        all
      ti = tableScores.zipWithIndex.maxBy(_._1)._2
      // ABSTENTION (§25, step 4): the two best tables within the margin and nothing exact to decide — "not sure" is the
      // answer, named; the margin is in log-probability (0.3 ≈ the best under 1.35× the second)
      closeTables =
        val byTable = tables.keys.toVector.sorted.map(n => (n, tableScores(pr.indexOfTable(n)))).sortBy(-_._2)
        byTable.take(2) match
          case Vector((a, sa), (b, sb)) if sa - sb < abstainMargin => Some((a, b))
          case _ => None
      // EXACT FIRST, for the table too: a table named in the question is the table (the pointer's choice among several
      // named); else the one table a named column belongs to (real data: "GICS Sector" is a column of one table only);
      // else the pointer's
      chosen =
        val lowerQ = question.toLowerCase
        // a table is named by its name, or by a Russian form of it (the language module's dictionary, by the stem)
        // (a table is named in the singular too: "the largest unpaid order" names orders)
        // — but not when the singular is a COLUMN of another table ("Total amount by customer": the column customer of
        // orders, not the table customers), in Russian likewise by the dictionary's stems
        def columnElsewhere(n: String, word: String) = tables.values.exists(t => t.name != n && t.columns.exists(_.equalsIgnoreCase(word)))
        def columnStemElsewhere(n: String, stem: String) = tables.values.exists(t => t.name != n &&
          t.columns.exists(c => words.column(c).exists((nom, _, _) => nom.startsWith(stem))))
        def mentioned(n: String) = n.length >= 3 && (lowerQ.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(n.toLowerCase)}\\b.*") ||
          n.length >= 4 && n.toLowerCase.endsWith("s") && !columnElsewhere(n, n.dropRight(1)) &&
            lowerQ.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(n.toLowerCase.dropRight(1))}\\b.*") ||
          words.table(n).exists((nom, _, _, _) => !columnStemElsewhere(n, nom.dropRight(1)) &&
            lowerQ.matches(s"(?U).*\\b${nom.dropRight(1)}\\p{L}{0,5}\\b.*")))
        val byName = tables.keys.toVector.sorted.filter(mentioned)
        val links = Space.Schema(tables).links
        val byColumn = tables.values.toVector.sortBy(_.name).filter(t => t.columns.exists(mentioned)).map(_.name)
        val pointed = Option.when(ti < names.size)(names(ti)).filter(tables.contains)
        // "customers with no orders": the table after the negation word is the absent one, not the target
        // ("staff that never appear in users", "работники, не встречающиеся в бронях": the table after "in" is the
        // absent one)
        val negated = byName.filter { n =>
          val forms = (java.util.regex.Pattern.quote(n.toLowerCase) +: words.table(n).toVector
            .flatMap((nom, gen, prep, _) => Vector(nom, gen, prep))).mkString("|")
          lowerQ.matches(s"(?U).*\\b(no|without|без|нет|never appear in|не встреча\\w* в)\\s+($forms)\\b.*")
        }
        val targets = if negated.nonEmpty && byName.size > negated.size then byName.filterNot(negated.contains) else byName
        // two tables named and the data links them: the rows are the link's FROM table ("Сумма заказов по каждому
        // клиенту": orders, joined to customers) — unless the question negates one (an absence, above)
        val byLink = if negated.isEmpty && targets.size == 2 then links.find(l => targets.contains(l.from) && targets.contains(l.to)).map(_.from) else None
        val c0 = byLink.orElse(pointed.filter(targets.contains)).orElse(targets.headOption)
          // columns of two tables named and the data links them (orders.customer → customers): the rows are the link's
          // FROM table, the other is joined — "Total amount per country"
          .orElse(if byColumn.size == 1 then byColumn.headOption
            else if byColumn.size == 2 then links.find(l => byColumn.contains(l.from) && byColumn.contains(l.to)).map(_.from)
              .orElse(pointed.filter(byColumn.contains).orElse(pointed).orElse(byColumn.headOption))
            else pointed.filter(byColumn.contains).orElse(pointed).orElse(byColumn.headOption))
        // an absence read on the wrong side ("Какие клиенты без заказов?" read as orders): the target is the table the
        // read one's rows point INTO when none points into the read one
        def into(a: Table, b: Table): Boolean = a.rows.nonEmpty && { val keys = b.rows.map(_(0).text).toSet
          a.columns.indices.exists(i => a.rows.forall(r => keys(r(i).text))) }
        c0.map { c =>
          val negation = lowerQ.matches("(?U).*\\b(no|without|never|без|нет)\\b.*")
          val others = tables.values.filter(_.name != c)
          if negation && !others.exists(o => into(o, tables(c))) then others.find(o => into(tables(c), o)).map(_.name).getOrElse(c) else c
        }
      // how the table was found: named in the question, by a column named there, or read from the model's state
      how = chosen.map { t =>
        val lowerQ = question.toLowerCase
        def mentioned(n: String) = n.length >= 3 &&
          lowerQ.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(n.toLowerCase)}\\b.*") ||
          words.table(n).exists((nom, _, _, _) => lowerQ.matches(s"(?U).*\\b${nom.dropRight(1)}\\p{L}{0,5}\\b.*"))
        if mentioned(t) then "named in the question"
        else if tables(t).columns.exists(mentioned) then "it has a column the question names"
        else "read from the model's state"
      }
      _ <- Read.note(chosen.fold("no table found for the question")(t => s"the table: $t (${how.get})"))
      result <- chosen match
        case Some(t) if closeTables.exists((a, b) => how.contains("read from the model's state") && (t == a || t == b)) =>
          val (a, b) = closeTables.get
          Read.note(s"not sure which table the question means: $a or $b — ask again naming it").map(_ => Left(s"not sure: $a or $b"))
        // OUTSIDE THE FRAGMENT (§28): a share, two values of one column compared, two entities compared — a refusal,
        // named, never an answer to another question
        case Some(_) if Readers.outside(question).isDefined =>
          val why = Readers.outside(question).get
          Read.note(s"not answered: $why").map(_ => Left(why))
        case Some(t) => assemble(tables, question, pr, ks, t)
        case None => pure[Either[String, String], R](Left("no table read"))
      _ <- Read.note(result.fold(why => s"no program: $why", _ => "the program assembled — the exact engine runs it"))
    yield result

  /** the program for the table read: the shape as decisions, the columns, the condition's op and value */
  private def assemble[R[_]: Has[Read]](tables: Map[String, Table], question: String, pr: Prompted,
      ks: Vector[Array[Float]], table: String): Either[String, String] ! R =
    val t = tables(table)
    val lower = question.toLowerCase
    def q(s: String) = "\"" + s + "\""
    // EXACT FACTS FROM THE DATA constrain the reads: a value of a column written in the question (a word of 3+ letters,
    // a date) means a condition on that column — and a join, when the column is in another table reachable by one
    // a join to another table: the table's column whose every value names a row of the other (its first column) —
    // found once per table, by a set (on 3376 × 503 rows the row-by-row search took 90 s a question)
    val joinOf: Map[String, Option[String]] = tables.values.map { o =>
      val keys = o.rows.map(_(0).text).toSet
      o.name -> (if o.name == table || t.rows.isEmpty then None
        else t.columns.zipWithIndex.collectFirst { case (c, i) if t.rows.forall(r => keys(r(i).text)) => c })
    }.toMap
    def joinTo(o: Table): Option[String] = joinOf(o.name)
      // the reverse: another table's column whose every value names a row of this one (its first column) — an absence's
      // other table ("customers with no orders": orders.customer into customers.name)
      val reverse: Vector[(Table, String)] = tables.values.toVector.filter(o => o.name != table && o.rows.nonEmpty).flatMap { o =>
        val keys = t.rows.map(_(0).text).toSet
        o.columns.zipWithIndex.collectFirst { case (c, i) if o.rows.forall(r => keys(r(i).text)) => (o, c) }
      }
      // the question's words and their runs of up to four (a value of several words): a value is named when it is one
    // of them — but never a word of the question itself ("how MANY" is not the city Many, "IN" not the state), and a
    // short code (AK, IN, UA) only as written, in capitals
    val qWords = "(?U)[\\p{L}\\p{N}][\\p{L}\\p{N}.'-]*".r.findAllIn(question).map(_.replaceAll("[.'-]+$", "")).toVector
    val grams: Set[String] = (1 to 4).flatMap(k => if qWords.size >= k then qWords.sliding(k).map(_.mkString(" ")) else Nil).toSet
    val gramsLower = grams.map(_.toLowerCase)
    def russianStem(w: String) = if w.length <= 3 then w else w.dropRight(if w.length > 4 then 2 else 1)
    // the question with common Russian words masked for the values' stems: "Самоа" is not in "самый", "Того" not in "того"
    val lowerV = Readers.commonRu.foldLeft(lower)((s, w) => s.replaceAll(s"(?U)\\b$w\\b", "_"))
    def inQuestion(v: String): Boolean =
      !Readers.stop(v.toLowerCase) && (if v.length <= 3 && v.forall(_.isUpper) then grams(v) else gramsLower(v.toLowerCase)) ||
        // a value's Russian form from the dictionary, by its stem — a negated form ("не оплачено") only as negated, and
        // the plain form not inside the negated one ("оплачено" is not in "не оплачено")
        words.value(v).exists { form =>
          val stem = form.split(" ").map(russianStem).mkString(" ")
          val pattern = s"(?U).*\\b$stem\\p{L}{0,${if stem.length >= 5 then 5 else 2}}\\b.*"
          if form.startsWith("не ") then lowerV.matches(pattern) || lowerV.matches(pattern.replace("не ", "не"))
          else lowerV.matches(pattern) && !lower.matches(s"(?U).*\\bне ?$stem\\p{L}{0,5}\\b.*")
        }
    // a number after a comparison is its bound, not a cell's value ("Value > 1.99E11" is no condition Value = 1.99E11)
    def compares(v: String): Boolean = v.matches("-?[\\d.]+(?:[eE][+-]?\\d+)?") && lower.matches(
      s"(?U).*(?:\\b(?:${Readers.comparisons}|greater|до)\\s+|[<>]=?\\s*)" +
        s"${java.util.regex.Pattern.quote(v.toLowerCase)}.*")
    val found: Vector[(Table, String, String)] = tables.values.toVector.flatMap { o =>
      o.columns.zipWithIndex.flatMap { (c, i) =>
        o.rows.map(_(i).text).distinct.filter(v => (v.length >= 3 && !v.forall(_.isDigit) || v.length >= 2 && v.forall(_.isUpper) ||
          v.matches("\\d{4}-\\d\\d-\\d\\d")) && inQuestion(v) && !compares(v)).map(v => (o, c, v))
      }
    }.filter((o, _, _) => o.name == table || joinTo(o).isDefined).sortBy((o, _, v) => (if o.name == table then 0 else 1, -v.length))
    // every fact a condition; the first the "fact" (the join and the where column)
    // ONE VALUE, ONE CONDITION: a value found in several columns ("blue" in name and in category) is the condition on
    // the column the question names beside it, else on the table's own column — never on all of them, and never a
    // join to another table for a value the table itself holds
    // a column's forms in the question: its name (and plural), its Russian forms by the stem
    def forms(c: String): Vector[String] =
      (Vector(java.util.regex.Pattern.quote(c.toLowerCase) + "(?:s|es)?") ++ words.column(c).toVector
        .flatMap((nom, gen, dat) => Vector(nom, gen, dat).map(f => russianStem(f) + "\\p{L}{0,5}"))).distinct
    def at(f: String): Vector[Int] = s"(?U)\\b$f\\b".r.findAllMatchIn(lower).map(_.start).toVector
    def valueAt(v: String): Vector[Int] = at(java.util.regex.Pattern.quote(v.toLowerCase)) ++
      words.value(v).toVector
      .flatMap(form => at(form.split(" ").map(russianStem).mkString(" ") + "\\p{L}{0,5}"))
    // how far the column is written from the value ("with owner = paid by kind": owner is beside paid, kind is not); a
    // column before the value is nearer than one after ("whose vendor is delta by region")
    def gap(c: String, v: String): Int =
      (for i <- forms(c).flatMap(at); j <- valueAt(v) yield if i <= j then j - i else (i - j) * 2 + 5)
        .minOption.getOrElse(Int.MaxValue)
    val facts = found.groupBy(_._3.toLowerCase).values.toVector.map { same =>
      same.sortBy((o, c, v) => (gap(c, v), if o.name == table then 0 else 1)).head
    }.sortBy((o, _, v) => (if o.name == table then 0 else 1, -v.length)).distinctBy(_._2)
    val fact = facts.headOption
    // a join only when the data needs one: a fact in another table, or a column of another table named in the question
    val join = facts.find(_._1.name != table).flatMap((o, _, _) => joinTo(o).map(c => (o, c))).orElse(
      tables.values.filter(_.name != table).flatMap(o => joinTo(o).filter(_ => o.columns.exists(c =>
        c.length >= 3 && !t.columns.contains(c) && lower.matches(s"(?U).*\\b${c.toLowerCase}\\b.*")))
          .map(c => (o, c))).headOption)
    // a word (or a stem ending in *) at a word boundary: "count" must not match "country"
    // a star is any letters, inside a phrase too: "самы* высок*" is "самым высоким" (a regex star after "ы" had made the
    // phrase match nothing, and the readers decided the direction — right on the JVM, wrong on WebGPU)
    def wordsIn(text: String, ws: Seq[String]): Boolean =
      ws.exists(w => text.matches(s"(?U).*\\b${w.replace("*", "\\p{L}*")}\\b.*"))
    def word(ws: String*): Boolean = wordsIn(lower, ws)
    // the joinable tables' columns are candidates too (a translated column may be theirs: "по странам" → country): a
    // choice among them brings the join
    val joinable = tables.values.toVector.filter(_.name != table).flatMap(o => joinTo(o).map(c => (o, c)))
    var joinNow: Option[(Table, String)] = join
    val cols = t.columns.map(c => (table, c)) ++ joinable.flatMap((o, _) => o.columns.filterNot(c => t.columns.contains(c) || c == "name").map(c => (o.name, c)))
    // EXACT FIRST: a column named in the question (its name, a word of 3+ letters) is the column — the readers decide
    // only among what the question does not name (a translation: "по городам" for city)
    // (a column is named by its name, or its name's plural or singular: "values" names Value, "orders" an order column)
    // (or by a Russian form of the column's name — the language module's dictionary, by the stem: "по городам" names city)
    val named = cols.filter((_, c) => c.length >= 3 && lower.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(c.toLowerCase)}(s|es)?\\b.*") ||
      c.length >= 4 && c.toLowerCase.endsWith("s") && lower.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(c.toLowerCase.dropRight(1))}\\b.*") ||
      words.column(c).exists((nom, _, _) => lower.matches(s"(?U).*\\b${russianStem(nom)}\\p{L}{0,5}\\b.*")))
    // THE COLUMN ITS ROLE'S WORDS NAME: "per team", "по каждому типу", "sorted by day" — the column written right after
    // the words that give it its role is that role's column, exactly (not the first column the question names)
    def after(leads: String*): Option[String] =
      cols.map(_._2).distinct.map(c => (c, forms(c).flatMap(f => leads.flatMap(l =>
        s"(?U)\\b$l\\s+$f\\b".r.findFirstMatchIn(lower).map(_.start))).minOption))
        .collect { case (c, Some(i)) => (c, i) }
        .minByOption(_._2).map(_._1)
    lazy val groupLead: Option[String] = after("per", "for each", "(?<!sorted |ordered )by", "по каждому", "в разрезе",
      "(?<!упорядочив )по")
    lazy val orderLead: Option[String] = after("sorted by", "ordered by", "упорядочив по", "the biggest",
      "the largest", "the smallest",
      "the highest", "the lowest", "the least", "самым большим", "самым маленьким", "наибольшим", "наименьшим",
        "самый высокий",
      "самый низкий")
    // the aggregate's column: the numeric column written right after the function's words ("Наибольший рейтинг среди
    // платежей
    // у которых количество ниже 100": rating, not qty)
    lazy val aggLead: Option[String] = after("the largest", "the smallest", "the highest", "the lowest", "maximum",
      "minimum",
      "average", "mean", "total", "sum of", "наибольш\\w*", "наименьш\\w*", "максимум", "минимум", "средн\\w*",
        "средняя величина",
      "сумма", "итого", "суммарн\\w*").filter(c => t.column(c).exists(i => t.rows.exists(_(i).num.isDefined)))
    // the numeric column a comparison is written after: "with cost greater than 273", "где total > 109"
    lazy val compared: Option[String] = t.columns.find(c => forms(c).exists(f => lower.matches(
      s"(?U).*\\b$f\\s+(?:${Readers.comparisons}|[<>]=?)\\s*-?\\d.*")))
    // a comparison's direction: greater by its words or its sign
    lazy val greater: Boolean = word("over", "above", "more than", "greater", "больше", "выше", "свыше") ||
      lower.matches("(?U).*>=?\\s*-?\\d.*")
    val used = scala.collection.mutable.Set.empty[String]
    // a time column (year, date) is an aggregate's or an order's column only when the question names it: "the largest
    // GDP" orders by the measure, not by the year
    def isTime(c: String) = c.toLowerCase.matches(
      "(?U).*\\b(year|date|day|month|quarter|week|time|год|дата|день|месяц)\\b.*")
    // ("в 2010 году", "in which year" name a year, not the time column as the measure)
    val lowerNoYear = lower.replaceAll("(?U)\\b\\d{4}\\s+(год\\w*|year)\\b|" +
      "\\b(в каком|какой|какого) год\\w*|\\b(in )?(which|what) year", " ")
    def namedHere(c: String) = forms(c).exists(f => lowerNoYear.matches(s"(?U).*\\b$f\\b.*"))
    def column(slot: String): Option[String] ! R =
      val prefix = prefixes.find(_._1 == slot).get._2(table)
      // a numeric column for agg and order, a text one for group; a named column not yet used
      def numeric(c: String) = cols.find(_._2 == c).exists((tb, _) => tables(tb).column(c).exists(i => tables(tb).rows.exists(_(i).num.isDefined)))
      val lead = slot match
        case "group" => groupLead
        case "order" => orderLead.orElse(groupLead)
        case _ => None
      val exact = (lead.toVector ++ named.map(_._2).filter(c => if slot == "agg" || slot == "order" then numeric(c) &&
        (!isTime(c) || namedHere(c))
        else if slot == "group" then !numeric(c) else true)).filterNot(used).distinct
      // a key column ("id") sorts, aggregates or groups nothing unless the question names it: the readers choose among the rest
      val keyLike = Set("id", "key", "uid")
      val pool0 = if named.exists((_, c) => keyLike(c)) then cols else cols.filterNot((_, c) => keyLike(c) && slot != "where")
      // an aggregate or an order is over a numeric column, a group over a text one: the readers choose among those only
      // a joined table's columns only for a group ("по странам"): a condition, an order or an aggregate stay on the table's
      // own unless a fact of the data brought the join
      val pool1 = if slot == "group" || joinNow.isDefined then pool0 else pool0.filter((tb, _) => tb == table)
      val measure = pool1.filter((_, c) => numeric(c) && (!isTime(c) || namedHere(c) || lead.contains(c)))
      val pool = if slot == "agg" || slot == "order" then
        (if measure.nonEmpty then measure else pool1.filter((_, c) => numeric(c)))
        else if slot == "group" then pool1.filterNot((_, c) => numeric(c)) else pool1
      // the table's own columns first; a joined table's column only when the readers prefer it by a clear margin
      val chosenP: String ! R = exact.headOption.fold {
        val cands = if pool.nonEmpty then pool else cols
        for
          qv <- query(pr, prefix).map(z)
          scores <- bothScores(pr, prefix, pointers(slot), qv, ks, cands)
        yield
          val own = cands.zipWithIndex.filter((tc, _) => tc._1 == table); val other = cands.zipWithIndex.filter((tc, _) => tc._1 != table)
          val ownBest = own.map((tc, i) => (scores(i), tc._2)).maxByOption(_._1); val otherBest = other.map((tc, i) => (scores(i), tc._2)).maxByOption(_._1)
          (ownBest, otherBest) match
            case (Some((so, co)), Some((st, ct))) => if st > so + 3f then ct else co
            case (Some((_, co)), None) => co
            case (None, Some((_, ct))) => ct
            case _ => cands.head._2
      }(c => pure(c))
      val role = slot match
        case "agg" => "the column to aggregate"
        case "order" => "the column to order by"
        case "group" => "the column to group by"
        case _ => "the condition's column"
      for
        _ <- if exact.nonEmpty then pure[Unit, R](()) else Read.note(s"choosing $role among ${pool.size max 1}…")
        chosen <- chosenP
        _ <- Read.note(s"$role: $chosen (${if exact.headOption.contains(chosen) then "named in the question"
          else "read from the model's state"})")
      yield
        used += chosen
        if !t.columns.contains(chosen) then joinNow = joinNow.orElse(joinable.find(_._1.columns.contains(chosen)))
        Some(chosen)
    /** the value: from the read start among the question's tokens, the longest value of the column (or a number or
     * a date) that begins there; else by exact match anywhere in the question */
    def constant(col: String, o: String): Option[String] ! R =
      val owner = if t.columns.contains(col) then t else joinNow.map(_._1).orElse(tables.values.find(_.columns.contains(col))).getOrElse(t)
      val vs = owner.rows.map(_(owner.columns.indexOf(col)).text).distinct.filter(_.nonEmpty).sortBy(-_.length)
      val numericCol = owner.column(col).exists(i => owner.rows.exists(_(i).num.isDefined) && owner.rows.forall(r => r(i) == Value.Empty || r(i).num.isDefined))
      for
        qs <- questionStates(pr).map(_.map(z))
        vq <- query(pr, valuePrefix(table, col, o)).map(z)
        start = pointers("value").predict(vq, qs)
        from = if start < pr.qChars.size then pr.qChars(start) else 0
        rest = question.drop(from)
        copied = vs.find(v => rest.toLowerCase.startsWith(v.toLowerCase))
          .orElse("^\\s*(\\d{4}-\\d\\d-\\d\\d|\\d+(?:\\.\\d+)?)".r.findFirstMatchIn(rest).map(_.group(1)).filter(v => numericCol || v.contains("-")))
          .orElse(vs.find(inQuestion))
          // (a number in the question is a value for a NUMERIC column only: "the five smallest values in 2023" had put 2023 into Country Name)
          .orElse("\\d{4}-\\d\\d-\\d\\d|\\d+(?:\\.\\d+)?".r.findAllIn(question).toVector.lastOption.filter(v => numericCol || v.contains("-")))
        // nothing copied (a translated value, "не оплачено"): the model asked over the column's values
        v <- copied.fold(
          if vs.nonEmpty && vs.size <= 30 then ask(pr, valuePrefix(table, col, o) + "\"", vs).map(a => Some(vs(a.zipWithIndex.maxBy(_._1)._2)))
          else pure[Option[String], R](None))(c => pure(Some(c)))
      yield v
    // A TIME WRITTEN IN THE QUESTION ("in 2020", "за 2023"): a number that is a value of the table's time column is a
    // condition on it — and not the limit's number
    val timeColumn = t.columns.find(c => c.toLowerCase.matches("(?U).*\\b(year|date|day|month|quarter|week|time|год|дата|день|месяц)\\b.*"))
    val timeValues: Vector[String] = timeColumn.toVector.flatMap { c =>
      val vs = t.rows.map(_(t.columns.indexOf(c)).text).toSet
      "\\d{4}(?:-\\d\\d(?:-\\d\\d)?)?".r.findAllIn(question).toVector.distinct.filter(vs)
    }
    // the limit's number: never a date's digits ("in 2026-11-06" is no limit of 2026), never a condition's number
    // ("with cost greater than 273" is no limit of 273), never a time
    def number(cond: Option[String]): Option[Int] =
      "(?<![\\d,eE+]|\\d\\.|[eE]-)\\d+(?![\\d,eE]|\\.\\d)".r
        .findAllIn(question.replaceAll("\\d{4}-\\d\\d(?:-\\d\\d)?", " ")
        .replace(cond.getOrElse("\u0000"), " "))
      .toVector.filterNot(timeValues.contains).filterNot(cond.contains).flatMap(_.toIntOption).headOption
      .orElse(numberWords.collectFirst { case (w, n) if lower.matches(s"(?U).*\\b$w\\b.*") => n })
    for
      _ <- Read.note("deciding the form of the answer: a count, a sum, a list, groups, an order…")
      // the shape by two readers: the probe, and the model asked which section comes first at `{"from": T, "`
      shapeQ <- query(pr, prefixes.head._2(table)).map(z)
      probeScores = logSoftmax(shape.scores(shapeQ))
      fnAsk <- ask(pr, fnPrefix(table), fns)
      fnQ <- query(pr, fnPrefix(table)).map(z)
      decided =
        // THE SHAPE AS SEPARATE DECISIONS, each exact where the question says it, read where it does not: the probe's
        // marginals over its compound classes are the fallback
        val probs = probeScores.map(v => math.exp(v.toDouble))
        def partIs(name: String)(p: String => Boolean): Double = partP(name, shapeQ, probs, p)
        val closure = partIs("kind")(_.startsWith("closure")) > 0.5 || word("reach*", "depend*", "lead*", "достиж*",
          "достига*", "завис*", "вед*", "доход*")
        // the closure's direction: FROM the node where the question asks what it depends on, reaches, where it leads;
        // TO it where it asks what reaches or leads to it; the reader where no word says
        val closureFromWords = word("depend*", "reachable", "where does", "завис*", "достижим*", "куда")
        val closureTo = !closureFromWords && (word("reach*", "lead*", "ведут", "ведёт", "доход*", "достига*", "кто") ||
          partIs("kind")(_ == "closure-to") > partIs("kind")(_ == "closure-from"))
          // ABSENCE (§24): "customers with no orders", "без заказов" — the rows of this table that no row of another names;
          // needs another table with a column into this one's first (the reverse of a join); the probe where no word says it
          val absent = reverse.nonEmpty && (word("without", "never", "lacking", "без", "нет", "не встреча*") ||
            lower.matches("(?U).*\\bno\\b.*") || partIs("kind")(_ == "absent") > 0.5)
          val fnProbeScores = logSoftmax(fnProbe.scores(fnQ))
        val fnByReaders = (fns :+ "-")(fnProbeScores.zipWithIndex.map((s, i) => (if i < fns.size then s + fnAsk(i) else s, i)).maxBy(_._1)._2)
        val topN = word("top", "топ", "the first", "first \\d", "only the first", "первые", "только перв*", "three",
          "five",
          "ten", "три", "пять", "десять")
        val perEach = word("per", "each", "по кажд*", "кажд*", "в разрезе", "по стат*", "по город*", "по клиент*", "по стран*")
        // GROWTH: a change in time within an entity — needs a time column (year, date, day, month) in the table
        val timeCol = t.columns.find(c => c.toLowerCase.matches("(?U).*\\b(year|date|day|month|quarter|week|time|год|дата|день|месяц)\\b.*"))
        val growth = timeCol.isDefined && word("grow*", "grew", "growth", "increase*", "rose", "rise", "fastest",
          "slowest", "decline*", "fell", "shrank", "shrink*", "рост*", "раст*", "растёт", "растут", "вырос*", "рос",
          "росли", "росла", "быстрее", "упал*", "снижа*")
        // the function's words, but not a COLUMN's name ("the smallest count among items": count is the column;
        // "Maximum total of events": total is) — a column's name only where it opens the question ("Count the events")
        val colWords = (t.columns ++ joinable.flatMap(_._1.columns)).map(_.toLowerCase).filter(_.length >= 3).distinct
        // (in Russian too: "Три самых больших заказа: id и сумма" — сумма is the column amount, not a sum)
        val lowerFn = colWords.flatMap(forms).foldLeft(lower)((s, f) => s.replaceAll(s"(?U)(?<=.)\\b$f\\b", "_"))
        def wordFn(ws: String*): Boolean = wordsIn(lowerFn, ws)
        // "the smallest X among T", "минимум X", "наибольший X среди T": an aggregate, not a row
        def extreme(words: String): Boolean = lowerFn.matches(s"(?U).*\\b(?:$words)\\s+\\S+\\s+(?:among|среди)\\b.*")
        val fn = if growth then "growth"
          else if wordFn("how many", "number of", "count", "сколько", "число", "посчитай") then "count"
          else if wordFn("average", "mean", "средн*") then "avg"
          else if wordFn("minimum", "минимум*") || extreme("the smallest|the lowest|наименьш\\w*") then "min"
          else if wordFn("maximum", "максимум*") || extreme("the largest|the highest|наибольш\\w*") then "max"
          else if wordFn("total", "sum of", "summed", "сумм*", "итого") then "sum"
          else if topN && !perEach then "-"
          // ("the largest X" is an order with a limit OR a maximum: the readers decide, trained on both phrasings)
          else fnByReaders
        val hasGroup = fn == "growth" || perEach || word("by") && fn != "-" || (fn != "-" && groupLead.isDefined) ||
          (fn != "-" && partIs("group")(_ == "group") > 0.5)
        // (an aggregate without groups is one number: nothing to order — "The largest since among people" is a maximum)
        // (with a minimum or a maximum the extreme's word is the function's, not an order: "Наименьший Value среди gdp
        // в разрезе Country Code" is a minimum per group, unordered)
        val extremeWords = Vector("largest", "smallest", "highest", "lowest", "самы*", "наибольш*", "наимен*",
          "максимальн*", "минимальн*")
        val orderWords = Vector("descending", "ascending", "first", "top", "sorted", "ordered", "по убыван*",
          "по возраст*", "топ", "от большего", "от меньшего", "упорядоч*") ++
          (if fn == "min" || fn == "max" then Vector.empty else extremeWords)
        val hasOrder = (fn == "growth" || word(orderWords*) ||
          orderLead.isDefined && fn != "min" && fn != "max" || partIs("order")(_ == "order") > 0.5)
          && (fn == "-" || fn == "growth" || hasGroup)
        // ("the largest X" with no aggregate is one row: exact; a maximum has fn from the readers, so this does not fire)
        // ("largest first", "сначала самые большие" are an order's direction, not one row)
        val lowerNoDir = lower.replaceAll("(?U)\\b(largest|smallest) first\\b|" +
          "\\bсначала самые (большие|маленькие)\\b", " ")
        def wordNoDir(ws: String*): Boolean = wordsIn(lowerNoDir, ws)
        val hasLimit = fn == "growth" || topN || (wordNoDir("largest", "smallest", "highest", "lowest", 
          "biggest", "least",
          "самы*", "наибольш*", "наимен*") && fn == "-" && !hasGroup) || partIs("limit")(_ == "limit") > 0.5
        val hasSelect = partIs("select")(_ == "select") > 0.5
        // a number after a comparison word or sign is a condition on a numeric column: that number, not the first one
        val numberCond = (s"(?iU)\\b(?:${Readers.comparisons}|greater|до)\\s+(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)|" +
          "[<>]=?\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)")
          .r.findFirstMatchIn(question).map(m => Option(m.group(1)).getOrElse(m.group(2)))
        val hasWhere = fact.isDefined || numberCond.isDefined || partIs("where")(_ != "-") > 0.5
        val descByReader = parts.contains("dir") && partIs("dir")(_ == "desc") >= partIs("dir")(_ == "asc")
        val sh = if absent then Vector("absent") else if closure then Vector(if closureTo then "closure-to" else "closure-from")
          else Vector("rel", fn, if hasWhere then "=" else "-", if hasGroup then "group" else "-", if hasOrder then "order" else "-",
            if hasLimit then "limit" else "-", "-", if hasSelect then "select" else "-")
        (sh, fn, hasGroup, hasOrder, numberCond, timeCol, descByReader)
      (sh, fn, hasGroup, hasOrder, numberCond, timeCol, descByReader) = decided
      _ <- Read.note("the form of the answer: " + Readers.form(sh))
      result <-
        if sh.head == "absent" then
          // the other table: the one named after the negation word, else the one the data joins back from
          val o = reverse.find((o, _) => lower.matches(s"(?U).*\\b(no|without|без|нет|в)\\s+${java.util.regex.Pattern.quote(o.name.toLowerCase)}\\b.*"))
            .getOrElse(reverse.head)
          val conds = facts.filter(_._1.name == table).map((_, c, v) => Conjunctive.Cond(table, c, "=", v)) ++
            numberCond.flatMap(n => compared.map(c => Conjunctive.Cond(table, c, if greater then ">" else "<", n)))
          pure[Either[String, String], R](Conjunctive.program(tables, table, conds = conds, absent = Some(Conjunctive.Join(o._1.name, t.columns.head, o._2)),
            select = Vector(t.columns.head)).map(Query.print))
        else if sh.head == "closure-from" || sh.head == "closure-to" then
          val e = tables.values.find(_.columns.size == 2).getOrElse(t)
          val nodes = e.rows.flatten.map(_.text).distinct.sortBy(-_.length)
          pure[Either[String, String], R](nodes.find(n => lower.contains(n.toLowerCase)).toRight(s"no node named in the question (table $table, shape ${sh.mkString(" ")})").map { c =>
            val goal = if sh.head == "closure-from" then s"reach(${Logic.constant(c)}, X)" else s"reach(X, ${Logic.constant(c)})"
            s"""{"datalog": "reach(X, Y) :- ${e.name}(X, Y). reach(X, Z) :- ${e.name}(X, Y), reach(Y, Z).", "goal": ${q(goal.replace("\"", "\\\""))}}"""
          })
        else
          val Vector(_, _, whereOp0, group, order, limit, _, select) = sh: @unchecked
          // the fact forces the condition the shape may have missed
          val whereOp = if whereOp0 == "-" && fact.isDefined then "=" else whereOp0
          // the columns in the order that disambiguates them: the aggregate's (numeric) and the order's first, then the group's
          // (text), then the condition's (the fact's column, or the numeric one of a number condition)
          for
            aggCol <-
              if fn == "growth" then
                // the measure: a numeric column named in the question, else the first that is not the time
                val nums = t.columns.filter(c => !timeCol.contains(c) && t.column(c).exists(i => t.rows.exists(_(i).num.isDefined) && t.rows.forall(r => r(i) == Value.Empty || r(i).num.isDefined)))
                pure[Option[String], R](nums.find(c => named.exists(_._2 == c)).orElse(nums.find(_.toLowerCase != "id")))
              else if fn != "-" && fn != "count" then aggLead.fold(column("agg"))(c => { used += c; pure(Some(c)) })
              else pure[Option[String], R](None)
            orderCol <- if hasOrder && !(fn != "-" && hasGroup) then column("order") else pure[Option[String], R](None)
            groupCol <-
              if fn == "growth" then
                // the entity: a text column named in the question, else the first that is not a code or a key
                val texts = t.columns.filter(c => !timeCol.contains(c) && !t.column(c).exists(i => t.rows.forall(r => r(i) == Value.Empty || r(i).num.isDefined)))
                pure[Option[String], R](texts.find(c => named.exists(_._2 == c)).orElse(texts.find(c => !c.toLowerCase.matches(".*(code|id|key)$"))).orElse(texts.headOption))
              else if hasGroup then column("group") else pure[Option[String], R](None)
            whereCol <- if whereOp == "-" then pure[Option[String], R](None) else
              fact.map(_._2).map { c => used += c; c }
                // a number condition: the numeric column whose values' range holds the number (not the key "id" by accident)
                .orElse(numberCond.flatMap(_ => compared)).orElse(numberCond.flatMap { n => val x = n.toDouble
                  val numericCols = t.columns.filterNot(used).filter(c => t.column(c).exists(i => t.rows.exists(_(i).num.isDefined) && t.rows.forall(r => r(i) == Value.Empty || r(i).num.isDefined)))
                  numericCols.find(c => named.exists(_._2 == c)).orElse(numericCols.find { c => val vs = t.rows.flatMap(_(t.column(c).get).num); vs.nonEmpty && vs.min <= x && x <= vs.max })
                    .orElse(numericCols.filterNot(_ == "id").headOption).orElse(numericCols.headOption) })
                .orElse(if timeValues.nonEmpty then timeColumn else None)
                // a condition the readers alone found needs its column named in the question: with no value, no number
                // and no time there, a condition was invented ("Наименьший часы среди invoices" had got hours = 120)
                .fold { val taken = used.toSet
                  column("where").map(_.filter(c => named.exists(_._2 == c) && !taken(c))) }(c => pure(Some(c)))
            // the first condition's op and value
            // THE ORDER'S DIRECTION (the phone asked "slowest grow GDP?" and got the fastest: "-" was written always): the
            // words decide where they are exact — ascending (smallest, slowest, lowest, least, cheapest, earliest, ascending,
            // наимен*, медленн*, самы* мал*/низк*/дешёв*, по возраст*), descending (largest, fastest…) — else the model is
            // asked at the order's prefix: "-" or the column's name next
            ascending <-
              val asc = word("smallest", "slowest", "lowest", "least", "fewest", "cheapest", "earliest", "ascending", "bottom", "наимен*",
                "медленн*", "меньше всего", "от меньшего к большему", "сначала самые маленькие", "самы* мал*",
                  "самы* низк*", "самы* дешев*", "самы* медленн*", "по возраст*", "худш*", "worst")
              val desc = word("largest", "fastest", "highest", "most", "biggest", "greatest", "top", "descending", "latest", "best", "наибольш*",
                "быстрее", "больше всего", "от большего к меньшему", "сначала самые большие", "самы* больш*",
                  "самы* высок*", "самы* быстр*", "по убыван*", "лучш*", "топ")
              // an explicit direction phrase decides first: "по возрастанию, топ-10" is ascending, though "топ" alone
              // reads as the largest
              val explicitAsc = word("ascending", "in ascending order", "smallest first", "по возрастанию",
                "от меньшего к большему", "сначала самые маленькие")
              val explicitDesc = word("descending", "in descending order", "largest first", "по убыванию",
                "от большего к меньшему", "сначала самые большие")
              if explicitAsc && !explicitDesc then pure[Boolean, R](true)
              else if explicitDesc && !explicitAsc then pure[Boolean, R](false)
              else if asc && !desc then pure[Boolean, R](true)
              else if desc then pure[Boolean, R](false)
              else if !hasOrder then pure[Boolean, R](false)
              else if parts.contains("dir") then pure[Boolean, R](!descByReader)
              else ask(pr, "```json\n{\"from\": \"" + table + "\", \"order\": [\"", Vector("-", orderCol.orElse(aggCol).getOrElse("x")))
                .map(scores => scores(1) > scores(0))
            first <- whereCol.fold(pure[Option[String], R](None)) { c =>
              val opP: String ! R =
                if whereOp == "empty" then pure("empty")
                else if fact.exists(_._2 == c) && !lower.matches("(?U).*\\b(not|не|кроме|except)\\b.*") then pure("=")
                else if numberCond.isDefined then pure(if greater then ">" else "<")
                else
                  for
                    oq <- query(pr, opPrefix(table, c)).map(z)
                    as <- ask(pr, opPrefix(table, c), ops)
                  yield
                    val ps = logSoftmax(op.scores(oq))
                    ops(ps.zip(as).map((a, b) => a + b).zipWithIndex.maxBy(_._1)._2)
              for
                _ <- Read.note(s"reading the condition on $c: the comparison and the value…")
                o <- opP
                v <- fact.filter(_._2 == c).map(_._3).orElse(numberCond).fold(constant(c, o))(v => pure(Some(v)))
                _ <- Read.note(v.filter(_.nonEmpty).fold(s"no value for the condition on $c: no condition")(value =>
                  s"the condition: $c $o $value"))
              yield v.filter(_.nonEmpty).map(value =>
                // (a condition with no value is no condition: an empty one answered nothing and said nothing)
                s"""{"column": ${q(c)}, "op": ${q(o)}, "value": ${if value.matches("-?\\d+(\\.\\d+)?") && o != "=" then value else q(value)}}""")
            }
          yield
            val parts = Vector.newBuilder[String]
            parts += s""""from": ${q(table)}"""
            // (the join after the columns are chosen: a column of a joinable table brings it)
            val joinSlot = parts.result().size
            val conds = Vector.newBuilder[String]
            first.foreach(conds += _)
            for (_, c, v) <- facts.drop(1) if !whereCol.contains(c) do conds += s"""{"column": ${q(c)}, "op": "=", "value": ${q(v)}}"""
            // a number condition beside a fact ("где тип это paid и у которых год выше 2020"): both conditions
            for n <- numberCond; c <- compared if !whereCol.contains(c) do
              conds += s"""{"column": ${q(c)}, "op": ${q(if greater then ">" else "<")}, "value": $n}"""
            for c <- timeColumn if !whereCol.contains(c); v <- timeValues do
              conds += s"""{"column": ${q(c)}, "op": "=", "value": ${if v.forall(_.isDigit) then v else q(v)}}"""
            val cs = conds.result()
            if cs.nonEmpty then parts += s""""where": [${cs.mkString(", ")}]"""
            if group == "group" then groupCol.foreach(c => parts += s""""group": [${q(c)}]""")
            if fn != "-" then
              val c = if fn == "count" then Some("*") else aggCol
              val by = if fn == "growth" then timeCol.map(b => s""", "by": ${q(b)}""").getOrElse("") else ""
              c.foreach(col => parts += s""""aggregate": [{"fn": ${q(fn)}, "column": ${q(col)}, "as": ${q(if col == "*" then "n" else fn)}$by}]""")
            if order == "order" then
              val c = if fn != "-" && group == "group" then Some(if fn == "count" then "n" else fn) else orderCol
              c.foreach(col => parts += s""""order": [${q((if ascending then "" else "-") + col)}]""")
            if limit == "limit" then parts += s""""limit": ${number(numberCond)
              .getOrElse(if fn == "growth" then 10 else 1)}"""
            if select == "select" then orderCol.foreach(c => parts += s""""select": ["id", ${q(c)}]""")
            val built = parts.result()
            val withJoin = joinNow.fold(built)((o, c) => built.take(joinSlot) ++ Vector(s""""join": {"table": ${q(o.name)}, "on": [[${q(c)}, ${q(o.columns.head)}]]}""") ++ built.drop(joinSlot))
            Right(withJoin.mkString("{", ", ", "}"))
    yield result

  /** THE READERS MEASURED BY CONSTRUCTOR (§25, step 3): the whole reading run on held-out pairs, the program read
   * compared with the gold constructor by constructor — the kind (rows, an absence, a closure), the table, the
   * conditions, the group, the aggregate, the order, the limit — and whole; the abstentions counted apart, and the
   * WRONG ABOVE THE THRESHOLD (answered, and wrong) is the number the exactness promise rests on */
  def measureByConstructor[R[_]: Has[Read]](pairs: Vector[Pairs.Pair]): Unit ! R =
    val right = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val total = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var abstained = 0; var whole = 0; var failed = 0
    val shown = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    def parts(q: Query): Map[String, String] = q match
      case Query.Relational(from, join, where, group, aggs, _, order, limit) =>
        val aliases = aggs.map(a => a.as -> a.fn).toMap
        Map("kind" -> "rows", "table" -> from, "join" -> join.map(_.table).getOrElse("-"),
          "where" -> where.map { case Cond.Cmp(c, op, v) => s"$c $op ${v.text}"; case w => w.toString }.sorted.mkString(";"),
          "group" -> group.mkString(","), "agg" -> aggs.map(a => s"${a.fn} ${a.column}").mkString(","),
          "order" -> order.map(o => s"${if o.desc then "-" else ""}${aliases.getOrElse(o.column, o.column)}").mkString(","), "limit" -> limit.fold("-")(_.toString))
      case Query.Logical(rules, goal) =>
        if rules.contains(" not ") then
          Map("kind" -> "absent", "table" -> "\\w+\\(.*?\\)\\s*:-\\s*(\\w+)\\(".r.findFirstMatchIn(rules).map(_.group(1)).getOrElse("-"),
            "where" -> "(V\\w+ [<>=!]+ [^,.]+)".r.findAllMatchIn(rules).map(_.group(1)).toVector.sorted.mkString(";"))
        else Map("kind" -> "closure", "table" -> "goal", "goal" -> goal.replaceAll("\\s", ""))
    val keys = Vector("kind", "table", "join", "where", "group", "agg", "order", "limit", "goal")
    for
      _ <- each(pairs) { p =>
        program(p.tables, p.question).map {
          case Left(_) => abstained += 1
          case Right(text) =>
            (Query.parse(text).toOption.map(parts), Query.parse(p.program).toOption.map(parts)) match
              case (Some(got), Some(gold)) =>
                for k <- keys if gold.contains(k) || got.contains(k) do
                  total(k) += 1
                  if got.getOrElse(k, "-") == gold.getOrElse(k, "-") then right(k) += 1
                  else if shown(k) < sys.env.get("DESK_SHOW").fold(3)(_.toInt) then
                    shown(k) += 1
                    println(s"    $k: ${p.question} → got ${got.getOrElse(k, "-")}, gold ${gold.getOrElse(k, "-")}")
                if keys.forall(k => got.getOrElse(k, "-") == gold.getOrElse(k, "-")) then whole += 1
              case _ => failed += 1
        }
      }
    yield
      val n = pairs.size
      println(f"  by constructor on $n held-out pairs: whole ${whole * 100.0 / n}%.1f%%, abstained $abstained, unparsable $failed, " +
        f"wrong above the threshold ${(n - abstained - whole) * 100.0 / n}%.1f%%; " +
        keys.filter(total.contains).map(k => f"$k ${right(k) * 100.0 / total(k)}%.0f%% (${total(k)})").mkString(", "))

  /** THE READERS AS A SOURCE OF THE CALCULUS (specs/calculus.md §15) — the vector tier of `Weigh`: a `Fill` whose
   * opinion on a hole comes from the probe or pointer that reads that constructor, at the prefix of the term so far.
   * One prompt per question (cached: a reading asks about a dozen holes). Log-probabilities over the candidates; no
   * opinion on a value's text, a link, a node (the data or the model's tokens fill those). The "none" of an optional
   * slot is the constructor probe's "-"; the columns share the rest by the pointer with ask. */
  def weigh[R[_]: Has[Read]]: calc.Fill[R] = new calc.Fill[R]:
    import calc.{Context, Dist, Term, Ty}
    private var cache: Option[(String, Prompted, Vector[Array[Float]], Array[Float])] = None
    private def ready(ctx: Context): (Prompted, Vector[Array[Float]], Array[Float]) ! R = cache match
      case Some((q, pr, ks, last)) if q == ctx.question => pure((pr, ks, last))
      case _ =>
        for
          pr <- prompted(Pairs.Pair(ctx.schema.tables, ctx.question, ""))
          ks <- keys(pr).map(_.map(z))
          last <- Read.states(pr.ids, Vector(pr.ids.length - 1)).map(st => z(st.head))
        yield { cache = Some((ctx.question, pr, ks, last)); (pr, ks, last) }
    /** the table of the term so far, else the pointer's best */
    private def tableOf(ctx: Context, pr: Prompted, ks: Vector[Array[Float]], last: Array[Float]): String =
      ctx.term match
        case Term.Con(_, "Rows" | "Absent", args) if args.headOption.exists(_.isInstanceOf[Term.Leaf]) => args.head.asInstanceOf[Term.Leaf].value
        case _ =>
          val names = ctx.schema.tables.keys.toVector.sorted
          names(pointers("table").predict(last, names.map(n => ks(pr.indexOfTable(n)))) min (names.size - 1))
    /** a constructor probe's log-probabilities over the candidates, `map` naming each candidate's class */
    private def byPart(name: String, shapeQ: Array[Float], cs: Vector[String], map: String => String): Dist =
      val probs = logSoftmax(shape.scores(shapeQ)).map(v => math.exp(v.toDouble))
      val classes = cs.map(map).distinct
      val mass = classes.map(c => c -> math.log(math.max(1e-9, partP(name, shapeQ, probs, _ == c)))).toMap
      cs.map(c => (c, mass(map(c)) - math.log(cs.count(x => map(x) == map(c)).toDouble)))
    def apply(ty: Ty, cs: Vector[String], ctx: Context): Dist ! R =
      ready(ctx).flatMap { (pr, ks, last) =>
        val table = tableOf(ctx, pr, ks, last)
        def shapeQ = query(pr, prefixes.head._2(table)).map(z)
        ty match
          case Ty.TableT =>
            tableBoth(pr, last, ks, cs).map(sc => cs.zip(sc.map(_.toDouble)))
          case Ty.Program => shapeQ.map(sq => byPart("kind", sq, cs, c => if c == "Rows" then "rows" else if c == "Absent" then "absent" else "closure"))
          case Ty.KindT => shapeQ.map(sq => byPart("kind", sq, cs, identity))
          case Ty.DirectionT => shapeQ.map(sq => byPart("kind", sq, cs, c => if c == "from" then "closure-from" else "closure-to"))
          case Ty.ColumnT(ts, role, _, _) =>
            val slot = role match { case calc.Role.Where => "where"; case calc.Role.Group => "group"; case calc.Role.Agg => "agg" }
            val part = role match { case calc.Role.Where => "where"; case calc.Role.Group => "group"; case _ => "" }
            val prefix = prefixes.find(_._1 == slot).get._2(table)
            val columns = cs.filter(_ != "none")
            val owner = (c: String) => ts.find(t => ctx.schema.tables.get(t).exists(_.columns.contains(c))).getOrElse(table)
            val cands = columns.map(c => (owner(c), c)).filter((t, c) => pr.indexOf(t, c) >= 0)
            for
              sq <- shapeQ
              pNone = if cs.contains("none") && part.nonEmpty then partP(part, sq, logSoftmax(shape.scores(sq)).map(v => math.exp(v.toDouble)), _ == "-") else 0.0
              qv <- query(pr, prefix).map(z)
              scores <- if cands.isEmpty then pure[Array[Float], R](Array.empty) else bothScores(pr, prefix, pointers(slot), qv, ks, cands)
            yield
              val ls = if scores.isEmpty then Array.empty[Float] else logSoftmax(scores)
              val some = math.log(math.max(1e-9, 1 - pNone))
              cands.zip(ls).map((tc, l) => (tc._2, some + l.toDouble)) ++ (if cs.contains("none") then Vector(("none", math.log(math.max(1e-9, pNone)))) else Vector.empty)
          case Ty.FnT(_) =>
            query(pr, fnPrefix(table)).map(z).map { fq =>
              val ps = logSoftmax(fnProbe.scores(fq)); val classes = fns :+ "-"
              cs.flatMap(c => classes.indexOf(if c == "none" then "-" else c) match { case -1 => None; case i => Some((c, ps(i).toDouble)) }) }
          case Ty.OpT(_) =>
            val column = ctx.term match
              case Term.Con(_, _, args) => args.reverse.collectFirst { case Term.Con(Ty.ColumnT(_, calc.Role.Where, _, _), c, inner) if inner.isEmpty => c }.getOrElse("")
              case _ => ""
            query(pr, opPrefix(table, column)).map(z).map { oq =>
              val ps = logSoftmax(op.scores(oq))
              cs.flatMap(c => ops.indexOf(c) match { case -1 => None; case i => Some((c, ps(i).toDouble)) }) }
          case Ty.DirT => shapeQ.map(sq => byPart("dir", sq, cs, identity))
          case Ty.OrderT(_, _, _) => shapeQ.map(sq => byPart("order", sq, cs, c => if c == "none" then "-" else "order"))
          case Ty.LimitT(_) => shapeQ.map(sq => byPart("limit", sq, cs, c => if c == "none" then "-" else "limit"))
          case _ => pure(Vector.empty)
      }

  /** the readers measured on held-out pairs, slot by slot: the pointer alone against the pointer with ask — so a
   * change is judged on 200 pairs, not on the bench's 12 questions */
  def measure[R[_]: Has[Read]](pairs: Vector[Pairs.Pair]): Unit ! R =
    var shapeRight = 0; var tableRight = 0; var tableRu = 0; var tableRuRight = 0; var tableBothRight = 0; var tableAmong = 0; var tableByName = 0; var tableByBoth = 0
    val right = scala.collection.mutable.Map.empty[String, (Int, Int, Int, Int)].withDefaultValue((0, 0, 0, 0))
    for
      exs <- each(pairs.flatMap(p => slots(p.program).map(sl => (p, sl))))((p, sl) => prompted(p).map(pr => (p, sl, pr)))
      _ <- each(exs) { (p, sl, pr) =>
        val t = p.tables(sl.table)
        for
          ks <- keys(pr).map(_.map(z))
          last <- Read.states(pr.ids, Vector(pr.ids.length - 1)).map(st => z(st.head))
          _ =
            // the table pointer, and in Russian apart (a translated table name is read, never copied)
            val ru = p.question.exists(c => c >= 'а' && c <= 'я')
            val ok = pointers("table").predict(last, ks) == pr.indexOfTable(sl.table)
            if ok then tableRight += 1
            if ru then { tableRu += 1; if ok then tableRuRight += 1 }
          names = p.tables.keys.toVector.sorted
          _ = if names.lift(pointers("table").predict(last, names.map(n => ks(pr.indexOfTable(n))))).contains(sl.table) then tableAmong += 1
          qs <- if pointers.contains("name") then questionStates(pr).map(_.map(z)) else pure[Vector[Array[Float]], R](Vector.empty)
          _ =
            if qs.nonEmpty then
              val tks = names.map(n => ks(pr.indexOfTable(n)))
              val byName = nameScores(qs, tks)
              if names(byName.zipWithIndex.maxBy(_._1)._2) == sl.table then tableByName += 1
              val byMeaning = logSoftmax(names.map(n => logSoftmax(pointers("table").scores(last, ks))(pr.indexOfTable(n))).toArray)
              if names(byMeaning.zip(byName).map(_ + _).zipWithIndex.maxBy(_._1)._2) == sl.table then tableByBoth += 1
          tb <- tableBoth(pr, last, ks, names)
          _ = if names(tb.zipWithIndex.maxBy(_._1)._2) == sl.table then tableBothRight += 1
          sq <- query(pr, prefixes.head._2(sl.table)).map(z)
          _ = if shape.predict(sq) == sl.shape then shapeRight += 1
          _ <- each(prefixes.tail) { (name, prefix) =>
            val gold = (name match { case "where" => sl.where; case "group" => sl.group; case "agg" => sl.agg; case _ => sl.order })
            gold.fold(unit[R]) { g =>
              val cands = t.columns.map(c => (sl.table, c))
              val cks = cands.map((tb, c) => ks(pr.indexOf(tb, c)))
              for
                qv <- query(pr, prefix(sl.table)).map(z)
                sc = pointers(name).scores(qv, cks).take(cks.size)
                alone = t.columns(sc.zipWithIndex.maxBy(_._1)._2)
                asked <- ask(pr, prefix(sl.table), t.columns)
                byAsk = t.columns(asked.zipWithIndex.maxBy(_._1)._2)
                withAsk <- both(pr, prefix(sl.table), pointers(name), qv, ks, cands)
              yield
                val (n, a, k, b) = right(name)
                right(name) = (n + 1, a + (if alone == g then 1 else 0), k + (if byAsk == g then 1 else 0), b + (if withAsk == g then 1 else 0))
            }
          }
        yield ()
      }
    yield println(f"  measured on ${exs.size} held-out pairs: table pointer ${tableRight * 100.0 / exs.size}%.1f%% (ru ${tableRuRight * 100.0 / math.max(tableRu, 1)}%.1f%% of $tableRu) among the tables ${tableAmong * 100.0 / exs.size}%.1f%% with ask ${tableBothRight * 100.0 / exs.size}%.1f%% by the name ${tableByName * 100.0 / exs.size}%.1f%% meaning+name ${tableByBoth * 100.0 / exs.size}%.1f%%; shape ${shapeRight * 100.0 / exs.size}%.1f%%; " +
      right.toVector.sortBy(_._1).map((k, v) => f"$k pointer ${v._2 * 100.0 / v._1}%.0f%% ask ${v._3 * 100.0 / v._1}%.0f%% both ${v._4 * 100.0 / v._1}%.0f%% (${v._1})").mkString(", "))

  // ---- the readers as bytes (a skill is a file, read on the JVM and in the browser alike): a header, then named
  // tensors — a text line "name length" and float32 LE; the classes of a probe as a text line
  def bytes: Array[Byte] =
    val out = new java.io.ByteArrayOutputStream
    def line(s: String): Unit = out.write((s + "\n").getBytes("UTF-8"))
    def tensor(name: String, xs: Array[Float]): Unit =
      line(s"$name ${xs.length}")
      val b = java.nio.ByteBuffer.allocate(xs.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      xs.foreach(b.putFloat); out.write(b.array())
    def probe(name: String, p: Softmax): Unit =
      line(s"$name.classes ${p.classes.mkString("\t")}"); tensor(s"$name.w", p.w.flatten); tensor(s"$name.b", p.b)
    line(s"readers ${mean.length} ${pointers.size}")
    tensor("mean", mean); tensor("std", std); tensor("scale", Array(scale))
    probe("shape", shape); probe("op", op); probe("fn", fnProbe)
    line(s"parts ${parts.size}")
    for (k, p) <- parts.toVector.sortBy(_._1) do { line(s"part $k"); probe(k, p) }
    for (k, p) <- pointers.toVector.sortBy(_._1) do
      line(s"pointer $k ${p.wq.length}"); tensor(s"$k.wq", p.wq.flatten); tensor(s"$k.wk", p.wk.flatten); tensor(s"$k.none", p.none)
    out.toByteArray

  def load(bytes: Array[Byte]): Unit =
    var at = 0
    def line(): String =
      val start = at
      while bytes(at) != '\n'.toByte do at += 1
      at += 1
      new String(bytes, start, at - 1 - start, "UTF-8")
    def tensor(): Array[Float] =
      val len = line().split(" ").last.toInt
      val b = java.nio.ByteBuffer.wrap(bytes, at, len * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      at += len * 4
      Array.fill(len)(b.getFloat)
    def probe(): Softmax =
      val classes = line().split(" ", 2).last.split("\t").toVector
      val w = tensor(); val b = tensor()
      val p = Softmax(classes, w.length / classes.size)
      w.grouped(w.length / classes.size).zipWithIndex.foreach((row, i) => row.copyToArray(p.w(i))); b.copyToArray(p.b)
      p
    val head = line().split(" ")
    val d = head(1).toInt; val n = head(2).toInt
    mean = tensor(); std = tensor(); scale = tensor()(0)
    shape = probe(); op = probe(); fnProbe = probe()
    // the constructors' probes, when the skill has them (a line "parts k"); else the next line is a pointer's
    parts.clear()
    var pending = line()
    if pending.startsWith("parts ") then
      for _ <- 1 to pending.split(" ")(1).toInt do
        val name = line().split(" ")(1)
        parts(name) = probe()
      pending = line()
    for i <- 1 to n do
      val h = (if i == 1 then pending else line()).split(" "); val k = h(1); val rows = h(2).toInt
      val wq = tensor(); val wk = tensor(); val none = tensor()
      val p = Pointer(d, rows)
      wq.grouped(d).zipWithIndex.foreach((row, i) => row.copyToArray(p.wq(i))); wk.grouped(d).zipWithIndex.foreach((row, i) => row.copyToArray(p.wk(i)))
      none.copyToArray(p.none); pointers(k) = p

object Readers:
  /** a question outside the fragment the readers assemble: why, or None — a share; two values of one time column
   * compared ("in 2010 and 2020"); two entities compared ("faster than") */
  def outside(question: String): Option[String] =
    val q = question.toLowerCase
    def has(re: String) = q.matches(s"(?U).*$re.*")
    if has("\\b(share|percent\\w*|proportion|доля|долю|доли|процент\\w*)\\b")
      then Some("a share is not in the fragment yet")
    else if "\\b(1[89]|20)\\d\\d\\b".r.findAllIn(q).toVector.distinct.size >= 2
      && !has("\\b(between|from|с|от)\\b") then
      Some("two years compared: not in the fragment yet (ask for each year)")
    else if has("\\b(compare|сравни\\w*)\\b") || has("\\b(than|чем)\\b") && !has("\\d") then
      Some("a comparison of two things is not in the fragment yet (ask for each)")
    else None
  /** common Russian words a short name's stem could match (Самоа in самый, Того in того) */
  val commonRu: Vector[String] = Vector("самый", "самая", "самое", "самой", "самые", "самых", "самым", "самого",
    "самому", "самыми", "того", "этого", "всего", "чего", "кого", "мало", "много")
  /** the words of a comparison before its number, the longer first ("greater than" before "greater") */
  val comparisons = "over|above|more than|greater than|below|under|less than|больше|выше|свыше|меньше|ниже"
  /** the words of a question that are never a value of the data */
  val stop: Set[String] = Set("how", "many", "much", "which", "what", "where", "who", "when", "in", "of", "per", "by", "the",
    "a", "an", "is", "are", "was", "were", "with", "for", "from", "to", "on", "at", "and", "or", "not", "all", "each", "every",
    "there", "that", "this", "than", "сколько", "какие", "какой", "какая", "какое", "что", "кто", "где", "когда", "в", "по",
    "из", "на", "и", "или", "не", "все", "всех", "каждый", "каждом", "каждого", "за", "с", "у", "от", "до", "для", "как", "это",
    "есть", "чем")
  /** a part of a compound shape class; "-" where the class has none (a closure) */
  def part(shape: String, i: Int): String = shape.split(" ").lift(i).getOrElse("-")

  /** a shape in words, for the page's steps: "a count, with a condition, per group" */
  def form(sh: Vector[String]): String = sh.head match
    case "closure-from" => "what can be reached from a node (a closure, by Datalog)"
    case "closure-to" => "what reaches a node (a closure, by Datalog)"
    case "absent" => "the rows no row of another table names (a negation, by Datalog)"
    case _ =>
      val Vector(_, fn, where, group, order, limit, _, _) = sh: @unchecked
      val what = fn match
        case "count" => "a count"
        case "sum" => "a sum"
        case "avg" => "an average"
        case "min" => "a minimum"
        case "max" => "a maximum"
        case "growth" => "the growth over time"
        case _ => "a list of rows"
      (Vector(what) ++ Option.when(where != "-")("with a condition") ++ Option.when(group == "group")("per group") ++
        Option.when(order == "order")("ordered") ++ Option.when(limit == "limit")("the top ones")).mkString(", ")

  /** the readers run in place on a `Reading` — the JVM's face, the bench's */
  final class On(val readers: Readers, reading: Reading):
    def train(pairs: Vector[Pairs.Pair]): Unit = Read.run(readers.train[Read](pairs), reading)
    def program(tables: Map[String, Table], question: String): Either[String, String] =
      Read.run(readers.program[Read](tables, question), reading)
    def programWith(tables: Map[String, Table], question: String): Option[(String, Boolean)] =
      Read.run(readers.programWith[Read](tables, question), reading)
    def measure(pairs: Vector[Pairs.Pair]): Unit = Read.run(readers.measure[Read](pairs), reading)
    def measureByConstructor(pairs: Vector[Pairs.Pair]): Unit = Read.run(readers.measureByConstructor[Read](pairs), reading)
    def tableReadings(tables: Map[String, Table], question: String): String = Read.run(readers.tableReadings[Read](tables, question), reading)
    def bytes: Array[Byte] = readers.bytes
    def load(bytes: Array[Byte]): Unit = readers.load(bytes)
