package okay.desk

/**
 * THE EXACT HANDLER of a relational query: checked against the tables first (every column named must exist —
 * the problems told in words, for the model to fix), then run: join, where, group and aggregate, select, order,
 * limit. Numbers come from here and from nowhere else.
 */
object Exact:
  def run(tables: Map[String, Table], q: Query.Relational): Either[String, Table] =
    for
      base <- tables.get(q.from).toRight(s"no table ${q.from}; the tables: ${tables.keys.toVector.sorted.mkString(", ")}")
      joined <- q.join.fold[Either[String, Table]](Right(base))(j => join(base, tables, j))
      _ <- check(joined, q)
    yield
      val filtered = joined.rows.filter(r => q.where.forall(holds(joined, r, _)))
      val grouped =
        if q.group.isEmpty && q.aggs.isEmpty then Table(q.from, joined.columns, filtered)
        else aggregate(joined, filtered, q.group, q.aggs)
      val ordered = q.order.foldRight(grouped.rows) { (o, rows) =>
        grouped.column(o.column).fold(rows) { i =>
          val sorted = rows.sortBy(_(i))(using summon[Ordering[Value]])
          if o.desc then sorted.reverse else sorted
        }
      }
      val selected = if q.select.isEmpty then grouped.columns else q.select.filter(c => grouped.column(c).isDefined)
      val idx = selected.map(c => grouped.column(c).get)
      val limited = q.limit.fold(ordered)(ordered.take)
      Table("result", selected, limited.map(r => idx.map(r)))

  private def check(t: Table, q: Query.Relational): Either[String, Unit] =
    val named = q.where.map {
      case Cond.Cmp(c, _, _) => c
      case Cond.Has(c, _) => c
      case Cond.Empty(c, _) => c
    } ++ q.group ++ q.aggs.map(_.column).filter(_ != "*") ++ q.aggs.flatMap(_.by) ++ q.order.map(_.column).filterNot(c => q.aggs.exists(_.as == c)) ++
      q.select.filterNot(c => q.aggs.exists(_.as == c))
    val missing = named.filterNot(c => t.column(c).isDefined).distinct
    val badOps = q.where.collect { case Cond.Cmp(_, op, _) if !Set("=", "!=", "<", "<=", ">", ">=").contains(op) => op }
    val badFns = q.aggs.map(_.fn).filterNot(Set("count", "sum", "avg", "min", "max", "growth", "change").contains)
    val noBy = q.aggs.filter(a => (a.fn == "growth" || a.fn == "change") && a.by.isEmpty).map(_.fn)
    if missing.nonEmpty then Left(s"no column ${missing.mkString(", ")} in ${t.name}; its columns: ${t.columns.mkString(", ")}")
    else if badOps.nonEmpty then Left(s"unknown op ${badOps.mkString(", ")}: use = != < <= > >= contains empty")
    else if badFns.nonEmpty then Left(s"unknown aggregate ${badFns.mkString(", ")}: use count sum avg min max growth change")
    else if noBy.nonEmpty then Left(s"${noBy.mkString(", ")} needs \"by\": the column that orders the values in time")
    else Right(())

  private def holds(t: Table, r: Vector[Value], c: Cond): Boolean = c match
    case Cond.Cmp(col, op, v) =>
      val x = r(t.column(col).get)
      val cmp = (x.num, v.num) match
        case (Some(a), Some(b)) => java.lang.Double.compare(a, b)
        case _ => x.text.compareToIgnoreCase(v.text)
      op match
        case "=" => cmp == 0
        case "!=" => cmp != 0
        case "<" => cmp < 0
        case "<=" => cmp <= 0
        case ">" => cmp > 0
        case _ => cmp >= 0
    case Cond.Has(col, text) => r(t.column(col).get).text.toLowerCase.contains(text.toLowerCase)
    case Cond.Empty(col, is) => (r(t.column(col).get) == Value.Empty) == is

  /** an inner join; the other table's columns prefixed `table.` where they clash */
  private def join(base: Table, tables: Map[String, Table], j: Join): Either[String, Table] =
    tables.get(j.table).toRight(s"no table ${j.table}").flatMap { other =>
      val keys = j.on.map { (a, b) =>
        val l = a.stripPrefix(base.name + "."); val r = b.stripPrefix(other.name + ".")
        (base.column(l).orElse(base.column(a)), other.column(r).orElse(other.column(b)), s"$a = $b")
      }
      val bad = keys.collect { case (None, _, s) => s } ++ keys.collect { case (_, None, s) => s }
      if j.on.isEmpty then Left("a join needs \"on\": [[\"left column\", \"right column\"]]")
      else if bad.nonEmpty then Left(s"join columns not found: ${bad.mkString(", ")}")
      else
        val li = keys.map(_._1.get); val ri = keys.map(_._2.get)
        val index = other.rows.groupBy(r => ri.map(r))
        val cols = base.columns ++ other.columns.map(c => if base.columns.contains(c) then s"${other.name}.$c" else c)
        Right(Table(base.name, cols, base.rows.flatMap(r => index.getOrElse(li.map(r), Vector.empty).map(r ++ _))))
    }

  private def aggregate(t: Table, rows: Vector[Vector[Value]], group: Vector[String], aggs: Vector[Agg]): Table =
    val gi = group.map(c => t.column(c).get)
    val groups = rows.groupBy(r => gi.map(r)).toVector.sortBy(_._1)(using Ordering.Implicits.seqOrdering)
    val cols = group.map(_.split('.').last) ++ aggs.map(_.as)
    Table("result", cols, groups.map { (key, rs) =>
      key ++ aggs.map { a =>
        val vals = if a.column == "*" then rs.map(_ => Value.Num(1)) else rs.map(_(t.column(a.column).get))
        val nums = vals.flatMap(_.num)
        a.fn match
          case "count" => Value.Num(if a.column == "*" then rs.size else vals.count(_ != Value.Empty))
          case "sum" => Value.Num(nums.sum)
          case "avg" => if nums.isEmpty then Value.Empty else Value.Num(nums.sum / nums.size)
          case "min" => vals.filter(_ != Value.Empty).minOption.getOrElse(Value.Empty)
          case "growth" | "change" =>
            // the rows in the order of `by`, the first and the last values that are numbers
            val bi = t.column(a.by.get).get; val ci = t.column(a.column).get
            val series = rs.sortBy(_(bi))(using summon[Ordering[Value]]).flatMap(_(ci).num)
            if series.size < 2 then Value.Empty
            else if a.fn == "change" then Value.Num(series.last - series.head)
            else if series.head == 0 then Value.Empty
            else Value.Num((series.last - series.head) / series.head.abs)
          case _ => vals.filter(_ != Value.Empty).maxOption.getOrElse(Value.Empty)
      }
    })
