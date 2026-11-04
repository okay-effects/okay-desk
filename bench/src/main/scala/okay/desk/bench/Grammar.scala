package okay.desk.bench

import mlx.llm.{Port, Tokenizer}
import okay.desk.Table

/** a segment of the program: a pick among options (each with what follows it), or free text until a quote */
sealed trait Seg
final case class Pick(options: Vector[(String, () => List[Seg])]) extends Seg
final case class Free(max: Int, name: String) extends Seg
/** a number, bare: digits (a sign, a point) until what follows */
case object Num extends Seg
def Lit(s: String): Seg = Pick(Vector(s -> (() => Nil)))

/**
 * THE PROGRAM'S GRAMMAR AS A DECODE PORT (the mask regime of okay-inlay specs/bridge.md): the model cannot write
 * anything but a well-formed query over the tables at hand. The JSON is a sequence of SEGMENTS — a PICK among
 * options (the Session scores whole continuations and takes the best: a table, a column, an op, the next section or
 * the end) and FREE text (a value, an alias, Datalog rules) until a closing quote. Between picks the model is free
 * only where a judgement is needed; names of tables and columns, ops and aggregates come from the lists, never from
 * the model — an unknown column is impossible by construction. A token may close one segment and open the next
 * (`"}` after a value): the text is fed through the segments as far as it matches.
 */
final class Grammar(tables: Map[String, Table], texts: Array[String], stop: Int) extends Port:
  private val vocab = texts.length

  // what the grammar knows while it goes: the tables in play, the aliases made
  private var from = ""
  private var joined = Vector.empty[String]
  private var aliases = Vector.empty[String]
  private def columns: Vector[String] = (from +: joined).flatMap(tables.get).flatMap(_.columns).distinct
  private def sorted: Vector[String] = columns ++ aliases ++ (columns ++ aliases).map("-" + _)
  val ops = Vector("=", "!=", "<", "<=", ">", ">=", "contains", "empty", "not empty")
  val fns = Vector("count", "sum", "avg", "min", "max")

  // ---- the grammar
  private def start: List[Seg] = List(Pick(Vector(
    "{\"from\": \"" -> (() => List(Pick(tables.keys.toVector.sorted.map(t => t -> (() => { from = t; List(Lit("\""), afterFrom(true)) }))))),
    "{\"datalog\": \"" -> (() => List(Free(300, "rules"), Lit(", \"goal\": \""), Free(60, "goal"), Lit("}"))))))
  private def afterFrom(mayJoin: Boolean): Seg = Pick(
    (if mayJoin then Vector(", \"join\": {\"table\": \"" -> (() => List(Pick(tables.keys.toVector.sorted.filter(_ != from).map(t =>
        t -> (() => { joined = joined :+ t; List(Lit("\", \"on\": [[\""), pickOf(tables(from).columns), Lit("\", \""),
          pickOf(tables(t).columns), Lit("\"]]}"), afterFrom(false)) }))))))
     else Vector.empty) ++ Vector(
      ", \"where\": [" -> (() => List(whereItem)),
      ", \"group\": [\"" -> (() => List(groupItem)),
      ", \"aggregate\": [" -> (() => List(aggItem)),
      ", \"order\": [\"" -> (() => List(orderItem)),
      ", \"limit\": " -> (() => List(limitPick)),
      "}" -> (() => Nil)))
  private def pickOf(xs: Vector[String]): Seg = Pick(xs.map(x => x -> (() => Nil)))
  private def whereItem: Seg = Pick(Vector("{\"column\": \"" -> (() => List(pickOf(columns), Lit("\", \"op\": \""),
    Pick(ops.map(op => op -> (() => if op == "empty" || op == "not empty" then List(Lit("\"}"), whereNext)
      else List(Lit("\", \"value\": "), Pick(Vector("\"" -> (() => List(Free(40, "value"), Lit("}"), whereNext)),
        "" -> (() => List(Num, Lit("}"), whereNext))))))))))))
  private def whereNext: Seg = Pick(Vector(", " -> (() => List(whereItem)), "]" -> (() => List(afterWhere))))
  private def afterWhere: Seg = Pick(Vector(
    ", \"group\": [\"" -> (() => List(groupItem)), ", \"aggregate\": [" -> (() => List(aggItem)),
    ", \"order\": [\"" -> (() => List(orderItem)), ", \"limit\": " -> (() => List(limitPick)), "}" -> (() => Nil)))
  private def groupItem: Seg = Pick(columns.map(c => c -> (() => List(Pick(Vector(
    "\", \"" -> (() => List(groupItem)), "\"]" -> (() => List(afterGroup))))))))
  private def afterGroup: Seg = Pick(Vector(", \"aggregate\": [" -> (() => List(aggItem)),
    ", \"order\": [\"" -> (() => List(orderItem)), ", \"limit\": " -> (() => List(limitPick)), "}" -> (() => Nil)))
  private def aggItem: Seg = Pick(Vector("{\"fn\": \"" -> (() => List(Pick(fns.map(f => f -> (() => List(Lit("\", \"column\": \""),
    pickOf(if f == "count" then "*" +: columns else columns), Lit("\", \"as\": \""), Free(20, "as"), Lit("}"),
    Pick(Vector(", " -> (() => List(aggItem)), "]" -> (() => List(afterAgg))))))))))))
  private def afterAgg: Seg = Pick(Vector(", \"order\": [\"" -> (() => List(orderItem)),
    ", \"limit\": " -> (() => List(limitPick)), "}" -> (() => Nil)))
  private def orderItem: Seg = Pick(sorted.map(c => c -> (() => List(Pick(Vector(
    "\", \"" -> (() => List(orderItem)), "\"]" -> (() => List(afterOrder))))))))
  private def afterOrder: Seg = Pick(Vector(", \"limit\": " -> (() => List(limitPick)), "}" -> (() => Nil)))
  private def limitPick: Seg = Pick(Vector("1", "3", "5", "10", "20", "100").map(n => n -> (() => List(Lit("}")))))

  // ---- the state: the segments pending, the text taken into the current one
  private var pending: List[Seg] = start
  private val taken = new StringBuilder
  val out = new StringBuilder
  def done: Boolean = pending.isEmpty

  private def candidates(p: Pick): Vector[(String, () => List[Seg])] =
    val so = taken.toString
    p.options.filter(_._1.startsWith(so))
  /** can `text` be read as a prefix of what the segments accept — across segment boundaries (a token `",` closes a
   * value and begins the next literal)? Options' continuations are explored; their side effects undone */
  private def accepts(text: String, segs: List[Seg], so: String, free: Boolean): Boolean =
    if text.isEmpty then true
    else segs match
      case Nil => false
      case Free(max, _) :: rest =>
        val q = text.indexOf('"')
        if q < 0 then !free || so.length + text.length <= max + 40
        else accepts(text.drop(q + 1), rest, "", false)
      case Num :: rest =>
        val digits = text.takeWhile(c => c.isDigit || c == '.' || c == '-')
        if digits.length == text.length then so.length + text.length <= 20
        else (so.nonEmpty || digits.nonEmpty) && accepts(text.drop(digits.length), rest, "", false)
      case Pick(options) :: rest =>
        options.exists { (opt, next) =>
          val whole = so + text
          if opt.startsWith(whole) then true
          else if whole.startsWith(opt) && (opt.length > so.length || opt.isEmpty) then
            val (f, j, a) = (from, joined, aliases)
            val more = next()
            val ok = accepts(whole.drop(opt.length), more ++ rest, "", false)
            from = f; joined = j; aliases = a
            ok
          else false
        }

  /** the tokens the next pick may take: those the grammar reads on from here */
  def allowed: Option[Array[Int]] = pending.headOption match
    case None => Some(Array(stop))
    case Some(Free(max, _)) if taken.length >= max =>
      Some((0 until vocab).filter(t => texts(t).startsWith("\"") && accepts(texts(t), pending, "", true)).toArray)
    case Some(_) => Some((0 until vocab).filter(t => accepts(texts(t), pending, taken.toString, true)).toArray)

  /** at a pick with ONE candidate left: its remainder, whole, forced; among several the model decides token by token
   * under `allowed` — its own next-token distribution, not a score over whole sections */
  // no whole continuation forced: a forced `"` after a name kept the model from its natural `",` token, and then
  // a lone `,` against `}` was no choice of its own — the mask alone, token by token, reads naturally
  override def choices: Option[Seq[Array[Int]]] = None

  def took(token: Int): Unit = if token != stop then { out ++= texts(token); feed(texts(token)) }

  /** the text through the segments as far as it matches; what does not match ends the program */
  private def feed(text: String): Unit =
    if text.isEmpty then ()
    else pending.headOption match
      case None => ()
      case Some(Num) =>
        val digits = text.takeWhile(c => c.isDigit || c == '.' || c == '-')
        taken ++= digits
        if digits.length < text.length then { taken.clear(); pending = pending.tail; feed(text.drop(digits.length)) }
      case Some(Free(_, name)) =>
        val q = text.indexOf('"')
        if q < 0 then taken ++= text
        else
          taken ++= text.take(q)
          val v = taken.toString
          if name == "as" then aliases = aliases :+ v
          taken.clear(); pending = pending.tail
          feed(text.drop(q + 1))
      case Some(p: Pick) =>
        val so = taken.toString + text
        val extending = candidates(p).filter(c => c._1.startsWith(so))
        extending.find(_._1 == so) match
          // exact, and no longer option begins the same way: taken; else wait for more
          case Some((_, next)) if extending.size == 1 => taken.clear(); pending = next() ++ pending.tail
          case _ if extending.nonEmpty => taken ++= text
          case _ =>
            // the text runs past an option: that option taken, the rest fed on
            candidates(p).filter(c => so.startsWith(c._1)).sortBy(-_._1.length).headOption match
              case Some((opt, next)) =>
                taken.clear(); pending = next() ++ pending.tail
                feed(so.drop(opt.length))
              case None => taken.clear(); pending = Nil

object Grammar:
  def apply(tok: Tokenizer, tables: Map[String, Table], stop: Int): Grammar =
    val texts = Array.tabulate(tok.size)(id => tok.decode(Seq(id)))
    new Grammar(tables, texts, stop)
