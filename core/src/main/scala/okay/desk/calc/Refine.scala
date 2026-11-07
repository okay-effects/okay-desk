package okay.desk.calc

import okay.desk.*
import okay.desk.Space.{Kind, Schema}
import okay.desk.skill.Read
import okay.scala3.*
import Term.*

/** THE CONTEXT a hole is filled from: the tables, the question, the term so far */
/** `words`: the data's own names in another language (skill.Words, §29 of data.md) — the dictionary the exact fill reads
 * tables, columns and values by; it falls back to the generator's (`Phrase.Russian`) by itself */
final case class Context(schema: Schema, question: String, term: Term, words: okay.desk.skill.Words = okay.desk.skill.Words.none,
    languages: Vector[Language] = Vector(Language.En.language, Language.Ru.language)):
  lazy val lower: String = question.toLowerCase
  /** the question has a word of this constructor in any of its languages (`Language.lists`) */
  def has(key: String): Boolean = languages.exists(l => l.list(key).nonEmpty && word(l.list(key)*))
  /** the question begins with one of the phrases of this key */
  def startsWith(key: String): Boolean = languages.exists(_.list(key).exists(p => lower.matches(s"(?U)^${java.util.regex.Pattern.quote(p)}\\b.*")))
  /** the number words of the languages */
  def numbers: Map[String, String] = languages.flatMap(_.numbers).toMap
  /** the forms of a table's name: the data's own words, then the languages' */
  def tableForms(n: String): Vector[String] = words.table(n).toVector.flatMap((a, b, c, d) => Vector(a, b, c, d)) ++ languages.flatMap(_.tables.getOrElse(n, Vector.empty))
  def columnForms(c: String): Vector[String] = words.column(c).toVector.flatMap((a, b, d) => Vector(a, b, d)) ++ languages.flatMap(_.columns.getOrElse(c, Vector.empty))
  def valueForms(v: String): Vector[String] = words.value(v).toVector ++ languages.flatMap(_.values.get(v))
  /** the question's words, and their runs of up to four (a value of several words), lowercased — computed once per
   * question, not per hole (a reading asks for them at every hole) */
  lazy val tokens: Vector[String] = "(?U)[\\p{L}\\p{N}][\\p{L}\\p{N}.'-]*".r.findAllIn(question).map(_.replaceAll("[.'-]+$", "")).toVector
  lazy val grams: Set[String] = (1 to 4).flatMap(k => if tokens.size >= k then tokens.sliding(k).map(_.mkString(" ").toLowerCase) else Nil).toSet
  def word(ws: String*): Boolean = ws.exists(w => lower.matches(s"(?U).*\\b${w.stripSuffix("*")}${if w.endsWith("*") then "\\w*" else ""}\\b.*"))

/** a distribution over a hole's candidates: constructor → score (a log-probability where the source has one);
 * EMPTY means the source has no opinion on this hole */
type Dist = Vector[(String, Double)]

/**
 * A FILL: the one operation. A source that, for a hole of a type with its candidates (from the grammar) and the
 * context, gives a distribution. The sources: the data (exact facts: a name or a value written in the question),
 * the model's own tokens at the term rendered as a prefix (the text tier), a reader of states (the vector tier),
 * the user (an answer to "not sure: A or B"), an oracle (the gold term, for the laws). Fills compose: the first
 * with an opinion, or their sum.
 */
trait Fill[R[_]]:
  def apply(ty: Ty, candidates: Vector[String], ctx: Context): Dist ! R

object Fill:
  /** a source of a row in a wider row (the same program, more effects beside it) */
  def in[R[_], S[_]](f: Fill[R])(using Sub[R, S]): Fill[S] = new Fill[S]:
    def apply(ty: Ty, cs: Vector[String], ctx: Context): Dist ! S = f(ty, cs, ctx).into[S]

  /** a pure source in any row */
  def lift[R[_]](f: Fill[Pure]): Fill[R] = new Fill[R]:
    def apply(ty: Ty, cs: Vector[String], ctx: Context): Dist ! R = pure(run(f(ty, cs, ctx)))

  /** the first source with an opinion */
  def first[R[_]](fills: Fill[R]*): Fill[R] = new Fill[R]:
    def apply(ty: Ty, cs: Vector[String], ctx: Context): Dist ! R =
      fills.foldLeft(pure[Dist, R](Vector.empty))((acc, f) => acc.flatMap(d => if d.nonEmpty then pure(d) else f(ty, cs, ctx)))
  /** a source's explicit REFUSAL: this candidate means "not this grammar" — the reading stops open, no source after is asked */
  val refuse: String = "?"

  /** the oracle: the gold term's constructor at the hole's path — the law `read ∘ phrase = id` with a perfect source */
  def oracle[R[_]](grammar: Grammar, gold: Term): Fill[R] = new Fill[R]:
    def apply(ty: Ty, cs: Vector[String], ctx: Context): Dist ! R =
      val at = grammar.nextHole(ctx.term).map(_._2).getOrElse(Vector.empty)
      def walk(t: Term, path: Vector[Int]): Option[Term] =
        if path.isEmpty then Some(t) else t match { case Con(_, _, args) => args.lift(path.head).flatMap(walk(_, path.tail)); case _ => None }
      pure(walk(gold, at).flatMap { case Leaf(_, v) => Some(v); case Con(_, n, _) => Some(n); case _ => None }
        .filter(v => cs.contains(v) || grammar.open(ty)).map(v => Vector((v, 0.0))).getOrElse(Vector.empty))

  /** THE DATA AS A SOURCE: what the question writes exactly — a table's name (the language module's dictionary for a
   * Russian form), a column's name, a value of the column, a number, the words of a comparison, of a function, of a
   * direction, of a limit. One candidate when the question names exactly one; no opinion otherwise. */
  def exact[R[_]]: Fill[R] = new Fill[R]:
    def apply(ty: Ty, cs: Vector[String], ctx: Context): Dist ! R =
      val q = ctx.lower
      def named(n: String): Boolean = n.length >= 3 && (q.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(n.toLowerCase)}(s|es)?\\b.*") ||
        n.length >= 4 && n.endsWith("s") && q.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(n.toLowerCase.dropRight(1))}\\b.*"))
      // a column of several words named by one of them ("country" for Country Name) when no other column shares the word,
      // or the others are codes and keys ("Country Code")
      def namedByWord(c: String): Boolean =
        val ws = c.split("[\\s_]+").filter(_.length >= 4).map(_.toLowerCase)
        ws.exists { w => q.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(w)}(s|es)?\\b.*") &&
          !ctx.schema.cols.exists(o => o.name != c && !o.name.toLowerCase.matches(".*\\b(code|id|key)\\b.*") && o.name.split("[\\s_]+").map(_.toLowerCase).contains(w)) }
      def ruStem(w: String) = if w.length <= 3 then w else w.dropRight(if w.length > 6 then 2 else 1)
      // (a form whole, or the nominative's stem — "замовлення" and "замовлень" share "замовлен", not "замовленн")
      def ruTable(n: String): Boolean = ctx.tableForms(n).exists(f => q.matches(s"(?U).*\\b$f\\b.*") || q.matches(s"(?U).*\\b${ruStem(f)}\\p{L}{0,5}\\b.*"))
      // (a whole form of the dictionary — дню — or the stem)
      def ruColumn(n: String): Boolean = ctx.columnForms(n).exists(f => q.matches(s"(?U).*\\b$f\\b.*")) ||
        ctx.columnForms(n).exists(nom => q.matches(s"(?U).*\\b${if nom.length <= 3 then nom else nom.dropRight(if nom.length > 4 then 2 else 1)}\\p{L}{0,5}\\b.*"))
      // the values the term's own conditions already use, and the own table's values (a linked table's are others')
      val ownValues: Set[String] = ctx.term match
        case Con(_, "Rows", args) => args.headOption.collect { case Leaf(_, t) => ctx.schema.tables.get(t).toVector.flatMap(_.rows.flatten.map(_.text)).toSet }.getOrElse(Set.empty)
        case _ => Set.empty
      def one(xs: Vector[String]): Dist = if xs.size == 1 then Vector((xs.head, 0.0)) else Vector.empty
      // a value's Russian form from the dictionary, by its stem: a negated form ("не оплачено", "неоплаченные") only as
      // negated, the plain form not inside the negated one
      // (a stem long enough that "Самоа" is not in "самый": two letters off a long word, one off a short)
      def ruValue(v: String): Boolean = ctx.valueForms(v).exists { form =>
        val stem = form.split(" ").map(ruStem).mkString(" ")
        val pattern = s"(?U).*\\b$stem\\p{L}{0,5}\\b.*"
        if form.startsWith("не ") || form.startsWith("nie ") then q.matches(pattern) || q.matches(pattern.replaceFirst("(не|nie) ", "$1"))
        else q.matches(pattern) && !q.matches(s"(?U).*\\b(не|nie) ?$stem\\p{L}{0,5}\\b.*") }
      val grams: Set[String] = ctx.grams
      // the limit the words say: a number with top/first words, 1 for "the row/which of/the largest X" without groups
      val topWords = ctx.has("top") || ctx.has("size")
      val limitNumber: Option[String] =
        val n = "\\b(\\d{1,2})\\b".r.findAllMatchIn(ctx.question).map(_.group(1)).toVector.filter(Set("1", "3", "5", "10"))
        val words = ctx.numbers.collect { case (w, v) if ctx.word(w) => v }.toVector
        if (n ++ words).size == 1 && topWords then Some((n ++ words).head) else None
      // the columns the term's conditions already use (a group on one says nothing), and the function chosen
      val used: Set[String] = ctx.term match
        case Con(_, "Rows", args) => args.collect { case Con(Ty.ColumnT(_, Role.Where, _, _), c, _) => c }.toSet
        case _ => Set.empty
      val fnChosen: Option[String] = ctx.term match { case Con(_, "Rows", args) => args.lift(6).collect { case Leaf(_, f) => f }; case _ => None }
      val grouped = ctx.term match { case Con(_, "Rows", args) => args.lift(5).exists { case Leaf(_, "none") => false; case Leaf(_, _) => true; case _ => false }; case _ => false }
      val limitOne = limitNumber.isEmpty && (ctx.has("onlyFirst") || !grouped && (ctx.has("one") || ctx.has("size") && !ctx.has("group") && !ctx.has("among")))
      val d: Dist = ty match
        case Ty.TableT =>
          // named in the question — not by a word that is a column of another table ("by customer", "по клиенту");
          // two named and linked: the link's FROM table has the rows, unless one is negated (an absence: the other)
          // the English singular in the question (and not the plural) while another table has that column; or a Russian
          // stem shared with another table's column while no whole form of the table's name is in the question
          def singularOnly(n: String) = q.matches(s"(?U).*\\b${n.toLowerCase.dropRight(1)}\\b.*") && !q.matches(s"(?U).*\\b${n.toLowerCase}\\b.*")
          def ruWhole(n: String) = ctx.tableForms(n).exists(f => q.matches(s"(?U).*\\b$f\\b.*"))
          def columnElsewhere(n: String) = ctx.schema.tables.values.exists(t => t.name != n && t.columns.exists(c =>
            c.equalsIgnoreCase(n.dropRight(1)) && singularOnly(n) ||
              !named(n) && !ruWhole(n) && ctx.columnForms(c).headOption.exists(nom => ctx.tableForms(n).headOption.exists(_.dropRight(1) == nom))))
          // whole names first ("customers", "клиентов"); a singular ("order") only when no table is named whole — "in descending
          // order" must not name orders beside customers
          def wholeNamed(n: String) = q.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(n.toLowerCase)}\\b.*") || ruWhole(n)
          val whole = cs.filter(n => wholeNamed(n) && !columnElsewhere(n))
          val names = if whole.nonEmpty then whole else cs.filter(n => (named(n) || ruTable(n)) && !columnElsewhere(n))
          // no table named: the tables whose columns are named — one, or two linked (the rows are the link's from table)
          val byColumn = if names.nonEmpty then Vector.empty else cs.filter(n => ctx.schema.tables(n).columns.exists(c => named(c) || ruColumn(c)))
          val byColumnOne = if byColumn.size == 1 then byColumn
            else if byColumn.size == 2 then ctx.schema.links.filter(l => byColumn.contains(l.from) && byColumn.contains(l.to)).map(_.from).distinct else Vector.empty
          // (in an absence, the other table is also the one after "in"/"в": "customers that never appear in orders")
          val negationWords = (ctx.languages.flatMap(_.list("negation")) ++ (ctx.term match { case Con(_, "Absent", _) => ctx.languages.flatMap(_.list("into")); case _ => Vector.empty })).map(java.util.regex.Pattern.quote).mkString("|")
          val negated = names.filter(n => q.matches(s"(?U).*\\b($negationWords)\\s+(${java.util.regex.Pattern.quote(n.toLowerCase)}|${(ctx.tableForms(n).map(java.util.regex.Pattern.quote) ++ ctx.tableForms(n).map(f => ruStem(f) + "\\p{L}{0,5}")).mkString("|")})\\b.*"))
          val targets = if negated.nonEmpty && names.size > negated.size then names.filterNot(negated.contains) else names
          if targets.size == 2 then one(ctx.schema.links.filter(l => targets.contains(l.from) && targets.contains(l.to)).map(_.from).distinct)
          else if targets.nonEmpty then one(targets) else one(byColumnOne)
        case Ty.ColumnT(_, Role.Group, _, _) =>
          if ctx.has("group") then one(cs.filter(c => c != "none" && !used(c) && (named(c) || ruColumn(c) || namedByWord(c))))
          else Vector(("none", 0.0))
        case Ty.ColumnT(ts, Role.Where, optional, _) =>
          // a value of a column written in the question names the column; "none" when no value and no column is written
          // (on a linked table, a value the own table also has is the own table's: "customer dee" is orders.customer)
          val own = ts.headOption.exists(t => ctx.term match { case Con(_, "Rows", args) => args.headOption.contains(Leaf(Ty.TableT, t)); case _ => true })
          // (a year written in the question — "in 2010", "в 2010 году" — is a condition on the time column, not a number)
          // (what the term's conditions already use is not written "again": a second condition sees the rest)
          val usedVals: Set[String] = ctx.term match
            case Con(_, "Rows", args) => args.collect { case Con(Ty.ColumnT(_, Role.Where, _, _), _, Vector(_, Leaf(_, v))) => v }.toSet
            case _ => Set.empty
          val years = "\\b(1[89]\\d\\d|20\\d\\d)\\b".r.findAllIn(ctx.question).toSet.filterNot(y => usedVals.exists(_.contains(y)))
          val valued = cs.filter(c => c != "none" && ts.exists(t => ctx.schema.tables.get(t).exists(tb => tb.column(c).exists(i =>
            tb.rows.map(_(i).text).distinct.filter(v => own || !ownValues(v)).exists(v => v.length >= 3 && !v.forall(_.isDigit) && !(v.length <= 3 && v.forall(_.isUpper)) && grams(v.toLowerCase) ||
              years.contains(v) && ctx.schema.of(t).exists(col => col.name == c && col.kind == Kind.Time) ||
              v.length >= 2 && v.forall(_.isUpper) && ctx.question.matches(s"(?U).*\\b$v\\b.*") ||
              ruValue(v))))))
          // a number with a comparison word: the numeric column whose range holds it
          val number = "\\d+(?:\\.\\d+)?".r.findAllIn(ctx.question).toVector.filterNot(n => usedVals.contains(n) || years.contains(n) || usedVals.exists(u => u.toDoubleOption.contains(n.toDouble))).headOption
            .filter(_ => ctx.has("more") || ctx.has("less")).map(_.toDouble)
          val numbered = number.toVector.flatMap(x => cs.filter(c => c != "none" && ts.exists(t => ctx.schema.of(t).exists(col => col.name == c && col.kind == Kind.Num &&
            { val vs = ctx.schema.tables(t).rows.flatMap(_(ctx.schema.tables(t).columns.indexOf(c)).num); vs.nonEmpty && vs.min <= x && x <= vs.max }))))
          // the columns something is written for, in the TABLE'S order — the canonical order of two conditions: the first slot
          // takes the first, the second slot the next (its candidates exclude the columns up to the first's)
          // a value written that several columns hold ("green" in kind and in name): the column the question NAMES has it;
          // none named — ambiguous, no opinion (the same value is never two conditions)
          def valuesOf(c: String): Set[String] = ts.flatMap(t => ctx.schema.tables.get(t).toVector.flatMap(tb => tb.column(c).toVector.flatMap(i => tb.rows.map(_(i).text)))).toSet
          val writtenValues: Set[String] = valued.flatMap(c => valuesOf(c).filter(v => !usedVals(v) && (years.contains(v) || v.length >= 3 && !v.forall(_.isDigit) && !(v.length <= 3 && v.forall(_.isUpper)) && grams(v.toLowerCase) ||
            v.length >= 2 && v.forall(_.isUpper) && ctx.question.matches(s"(?U).*\\b$v\\b.*") || ruValue(v)))).toSet
          val byValue = writtenValues.toVector.map(v => v -> valued.filter(c => valuesOf(c)(v)))
          val ambiguous = byValue.exists((_, cols) => cols.size > 1 && !cols.exists(c => named(c) || ruColumn(c) || namedByWord(c)))
          val settled = byValue.flatMap((_, cols) => if cols.size == 1 then cols else cols.filter(c => named(c) || ruColumn(c) || namedByWord(c)))
          val valuedSettled = settled.distinct // (a column with no unused written value is not a condition again)
          val written = if ambiguous then Vector.empty else (valuedSettled ++ numbered).distinct.sortBy(c => ts.flatMap(t => ctx.schema.tables.get(t).toVector.flatMap(tb => Option.when(tb.columns.contains(c))(tb.columns.indexOf(c)))).headOption.getOrElse(99))
          if ambiguous then Vector.empty
          else if written.nonEmpty then Vector((written.head, 0.0))
          else if usedVals.nonEmpty && valued.nonEmpty then Vector(("none", 0.0)) // the written value is the first condition's
          // (no value, no number, no year written, and — for a first condition — no word of a condition: no condition; a
          // second condition's words were the first's)
          else if years.isEmpty && optional && (usedVals.nonEmpty || !ctx.has("condition")) then Vector(("none", 0.0))
          else Vector.empty
        case Ty.OpT(Kind.Num) =>
          // ("over" counts only before a number: "summed over orders" is not a comparison)
          val more = ctx.languages.exists(l => l.list("more").exists(w => if w == "over" then q.matches("(?U).*\\bover \\d.*") else ctx.word(w)))
          val less = ctx.has("less")
          if more && !less then Vector((">", 0.0)) else if less && !more then Vector(("<", 0.0)) else Vector.empty
        case Ty.OpT(_) => one(cs)
        case Ty.ValueT(t, c) =>
          val numeric = ctx.schema.of(t).exists(x => x.name == c && x.kind == Kind.Num)
          val timeValues = ctx.schema.cols.filter(_.kind == Kind.Time).flatMap(col => ctx.schema.tables(col.table).column(col.name).toVector.flatMap(i => ctx.schema.tables(col.table).rows.map(_(i).text))).toSet
          if numeric then one("\\d+(?:\\.\\d+)?".r.findAllIn(ctx.question).toVector.distinct.filterNot(timeValues))
          else if ctx.schema.of(t).exists(col => col.name == c && col.kind == Kind.Time) then
            // an open type: the year or the date written in the question, whether the examples list it or not
            val yearsWritten = "\\b(1[89]\\d\\d|20\\d\\d)\\b".r.findAllIn(ctx.question).toSet
            val written = ctx.schema.tables(t).column(c).toVector.flatMap(i => ctx.schema.tables(t).rows.map(_(i).text)).distinct.filter(v => ctx.grams(v.toLowerCase) || yearsWritten.contains(v))
            one(written)
          else
            val vs = ctx.schema.tables(t).column(c).toVector.flatMap(i => ctx.schema.tables(t).rows.map(_(i).text).distinct)
            one(vs.filter(v => v.length >= 2 && (grams(v.toLowerCase) || ruValue(v))).sortBy(-_.length).take(1))
        case Ty.FnT(_) =>
          // ("сумма" is also the Russian name of a column called amount: then the word says nothing about the function)
          // (a sum word that is also a column's name in a language — "сумма" for amount — says nothing about the function)
          val sumIsColumn = ctx.schema.cols.exists(c => ctx.columnForms(c.name).exists(f => ctx.languages.exists(_.list("sum").exists(w => f.startsWith(w.stripSuffix("*")))))) && !ctx.has("sumPhrases")
          // ("count" is also a column's name in the pairs: then the word alone says nothing about the function)
          val countIsColumn = ctx.schema.cols.exists(_.name == "count")
          if ctx.has("count") || ctx.has("countWord") && !countIsColumn then Vector(("count", 0.0))
          else if ctx.has("avg") then Vector(("avg", 0.0))
          else if ctx.has("sum") && !(sumIsColumn && !ctx.languages.exists(l => l.code == "en" && ctx.word(l.list("sum")*))) then Vector(("sum", 0.0))
          else if (limitNumber.isDefined || limitOne) && !grouped && !ctx.has("group") then Vector(("none", 0.0))
          else
            // a question that is a noun phrase of the table ("The orders where…", "Заказы за…"): rows, no function
            val tableWords = ctx.schema.tables.keys.toVector.flatMap(n => n +: n.dropRight(1) +: ctx.words.table(n).toVector.flatMap((a, b, c, d) => Vector(a, b, c, d)))
            val sizeWords = ctx.has("size")
            // (a question that starts with a column's word in the plural — "Countries with…" for Country Name — lists rows)
            val columnWords = ctx.schema.cols.flatMap(_.name.split("[\\s_]+")).filter(_.length >= 4).map(_.toLowerCase).toSet
            val ruColumnStems = ctx.schema.cols.flatMap(c => ctx.columnForms(c.name)).map(ruStem).toSet
            val startsWithColumn = q.split("[^\\p{L}]+").headOption.exists(w => columnWords(w) || w.endsWith("ies") && columnWords(w.dropRight(3) + "y") || w.endsWith("s") && columnWords(w.dropRight(1)) ||
              ruColumnStems.exists(st => st.length >= 4 && w.startsWith(st)))
            if ctx.has("rows") || ctx.startsWith("whatWas") && !sizeWords || startsWithColumn && !sizeWords && !(ctx.has("count") || ctx.has("countWord") || ctx.has("avg") || ctx.has("sum") || ctx.has("min") || ctx.has("max")) ||
              q.matches(s"(?U)^(the )?(\\p{L}+ )?(${tableWords.map(java.util.regex.Pattern.quote).mkString("|")})\\b.*") && !sizeWords then Vector(("none", 0.0))
            // ("smallest first, the first 5" is an order with a limit, not a minimum)
            else if ctx.has("min") && !(ctx.has("one") || ctx.has("max") || ctx.has("big") || ctx.has("top") || ctx.has("ordered")) then Vector(("min", 0.0))
            else if ctx.has("max") && !ctx.has("min") then Vector(("max", 0.0))
            else Vector.empty
        case Ty.OrderT(_, agg, _) =>
          val ordered = ctx.has("size") || ctx.has("desc") || ctx.has("asc") || ctx.has("ordered") || ctx.has("top")
          // with an aggregate and no group there is nothing to order; a size word is the function's when it is min/max
          val explicit = ctx.has("desc") || ctx.has("asc") || ctx.has("ordered") || ctx.has("top")
          val sizeIsFn = fnChosen.exists(f => f == "min" || f == "max")
          if !ordered || sizeIsFn && !explicit || agg.isDefined && !grouped && !explicit then Vector(("none", 0.0))
          else agg match
            case Some(_) => one(cs.filter(_ != "none"))
            case None =>
              // (a column a condition already uses is not the order's: "в 2023 году" is the condition, the order is the measure)
              val namedCols = cs.filter(c => c != "none" && (named(c) || ruColumn(c)))
              // "the largest X" with no column named: a size word orders by the one numeric column
              val numeric = cs.filter(c => c != "none" && !used(c) && ctx.schema.cols.exists(x => x.name == c && x.kind == Kind.Num))
              if namedCols.nonEmpty then one(namedCols)
              else if ctx.has("size") || ctx.has("sizeAny") then one(numeric)
              else Vector.empty
        case Ty.DirT =>
          // the explicit words first; a size word ("smallest") says the direction only when it is not the function (min/max)
          if ctx.has("desc") && !ctx.has("asc") then Vector(("desc", 0.0))
          else if ctx.has("asc") && !ctx.has("desc") then Vector(("asc", 0.0))
          else if fnChosen.exists(f => f == "min" || f == "max") then Vector.empty
          else if ctx.has("small") then Vector(("asc", 0.0))
          else if ctx.has("big") then Vector(("desc", 0.0)) else Vector.empty
        case Ty.LimitT(_) =>
          limitNumber.map(n => Vector((n, 0.0))).getOrElse(
            if limitOne then Vector(("1", 0.0))
            else if !topWords || grouped && !ctx.has("topOnly") then Vector(("none", 0.0))
            else Vector.empty)
        case Ty.Program | Ty.KindT =>
          val outside = ctx.has("outside")
          val k = if outside then "" else if ctx.has("closure") then "closure" else if ctx.has("absent") then "absent" else "rows"
          if k.isEmpty then Vector((Fill.refuse, 0.0)) else Vector((if ty == Ty.Program then k.capitalize else k, 0.0))
        case Ty.DirectionT => if ctx.has("closureFrom") then Vector(("from", 0.0))
          else if ctx.has("closureTo") then Vector(("to", 0.0)) else Vector.empty
        case Ty.NodeT(_) => one(cs.sortBy(-_.length).filter(n => q.contains(n.toLowerCase)).take(1))
        case Ty.LinkT(_) =>
          // a link when the question names a column of the linked table or a value of it; none when nothing of it is named
          val links = cs.filter(_ != "none")
          val needed = links.filter { l => val to = l.split("->").last
            ctx.schema.tables.get(to).exists(tb => tb.columns.exists(c => !ctx.schema.tables.get(ctx.term match { case Con(_, "Rows", args) => args.headOption.collect { case Leaf(_, t) => t }.getOrElse(""); case _ => "" }).exists(_.columns.contains(c)) && (named(c) || ruColumn(c))) ||
              tb.columns.indices.exists(i => tb.rows.map(_(i).text).distinct.filterNot(ownValues).exists(v => v.length >= 2 &&
                (v.forall(_.isUpper) && ctx.question.matches(s"(?U).*\\b$v\\b.*") || v.length >= 3 && !v.forall(_.isDigit) && grams(v.toLowerCase))))) }
          if needed.size == 1 then Vector((needed.head, 0.0)) else if needed.isEmpty then Vector(("none", 0.0)) else Vector.empty
        case Ty.ReverseLinkT(_) => one(cs)
        case _ => Vector.empty
      pure(d)

  /** THE MODEL'S OWN TOKENS (the text tier): the term so far rendered as the program's JSON up to the hole, the
   * candidates scored by the model's next-token log-probabilities at that prefix. Any model with logits serves. */
  def model[R[_]: Has[Read]](tables: Map[String, Table]): Fill[R] = new Fill[R]:
    def apply(ty: Ty, cs: Vector[String], ctx: Context): Dist ! R =
      Render.prefix(ctx.term, ty) match
        case None => pure(Vector.empty)
        case Some(prefix) =>
          val shown = cs.map(c => Render.candidate(ty, c, ctx.term))
          for
            ids <- Read.prompt(Prompt.system(tables), ctx.question)
            more <- Read.encode(prefix)
            l <- Read.logits(ids ++ more)
            firsts <- Read.each(shown)(c => Read.encode(c).map(_.head))
          yield
            val m = l.max; val z = math.log(l.map(v => math.exp((v - m).toDouble)).sum)
            cs.zip(firsts.map(f => (l(f) - m - z).toDouble))

/** THE TERM AS THE PROGRAM'S JSON, up to the hole: the forced prefix at which the model is asked — what the
 * readers' prefixes were, now derived from the term instead of listed */
object Render:
  private def leaf(t: Term): Option[String] = t match { case Leaf(_, v) => Some(v); case Con(_, n, _) => Some(n); case _ => None }
  def prefix(term: Term, hole: Ty): Option[String] = term match
    case Hole(Ty.Program) | Con(Ty.Program, _, Vector()) => Some("```json\n{\"")
    case Con(Ty.Program, "Rows", args) =>
      val t = leaf(args(0)).getOrElse("")
      val start = s"```json\n{\"from\": \"$t\""
      Some(hole match
        case Ty.TableT => "```json\n{\"from\": \""
        case Ty.LinkT(_) => start + ", \"join\": {\"table\": \""
        case Ty.ColumnT(_, Role.Where, _, _) => start + ", \"where\": [{\"column\": \""
        case Ty.OpT(_) => start + s""", "where": [{"column": "${args.lastOption.flatMap(leaf).getOrElse("")}", "op": """"
        case Ty.ValueT(_, c) => start + s""", "where": [{"column": "$c", "op": "${args.lastOption.collect { case Con(_, _, Vector(Leaf(_, op))) => op }.getOrElse("=")}", "value": """"
        case Ty.ColumnT(_, Role.Group, _, _) => start + ", \"group\": [\""
        case Ty.FnT(_) => start + ", \"aggregate\": [{\"fn\": \""
        case Ty.ColumnT(_, Role.Agg, _, _) => start + s""", "aggregate": [{"fn": "${args.lift(6).flatMap(leaf).getOrElse("sum")}", "column": """"
        case Ty.OrderT(_, _, _) => start + ", \"order\": [\""
        case Ty.DirT => start + ", \"order\": [\""
        case Ty.LimitT(_) => start + ", \"limit\": "
        case _ => start)
    case Con(Ty.Program, "Closure", _) => Some("```json\n{\"datalog\": \"reach(X, Y) :- edges(X, Y). reach(X, Z) :- edges(X, Y), reach(Y, Z).\", \"goal\": \"reach(")
    case _ => None
  /** a candidate as the model would write it at the prefix */
  def candidate(ty: Ty, c: String, term: Term): String = ty match
    case Ty.KindT => if c == "rows" then "from" else "datalog"
    case Ty.LinkT(_) => if c == "none" then "}" else c.split("->").last
    case Ty.ColumnT(_, _, _, _) if c == "none" => "]"
    case Ty.FnT(_) if c == "none" => "]"
    case Ty.OrderT(_, _, _) if c == "none" => "]"
    case Ty.LimitT(_) if c == "none" => "}"
    case Ty.DirT => if c == "desc" then "-" else term match { case Con(_, _, args) => args.lift(8).flatMap(leaf).getOrElse("x"); case _ => "x" }
    case Ty.DirectionT => if c == "from" then "X" else "\""
    case _ => c

/**
 * REFINEMENT: the strategy that fills holes — which hole (the leftmost), from which source (the fill given), when
 * to stop (a hole no source has an opinion on, or two candidates within the margin: the term stays open, "not
 * sure"). Greedy here; a beam over the same fills is another strategy, not another mechanism.
 */
object Refine:
  final case class Done(term: Term, steps: Vector[(Ty, String)], unsure: Option[(Ty, Vector[String])])
  def greedy[R[_]](grammar: Grammar, fill: Fill[R], ctx0: Context, margin: Double = 0.0, limit: Int = 40): Done ! R =
    def loop(ctx: Context, steps: Vector[(Ty, String)], n: Int): Done ! R =
      grammar.nextHole(ctx.term) match
        case None => pure(Done(ctx.term, steps, None))
        case Some(_) if n >= limit => pure(Done(ctx.term, steps, None))
        case Some((ty, _, put)) =>
          val cs = grammar.constructors(ty)
          if cs.isEmpty then pure(Done(ctx.term, steps, Some((ty, cs))))
          else if cs.size == 1 then loop(ctx.copy(term = put(grammar(ty, cs.head))), steps :+ (ty, cs.head), n + 1)
          else fill(ty, cs, ctx).flatMap { d =>
            if d.isEmpty then pure(Done(ctx.term, steps, Some((ty, cs))))
            else
              val sorted = d.sortBy(-_._2)
              if sorted.size >= 2 && sorted(0)._2 - sorted(1)._2 < margin then pure(Done(ctx.term, steps, Some((ty, sorted.take(2).map(_._1)))))
              else loop(ctx.copy(term = put(grammar(ty, sorted.head._1))), steps :+ (ty, sorted.head._1), n + 1)
          }
    loop(ctx0, Vector.empty, 0)
