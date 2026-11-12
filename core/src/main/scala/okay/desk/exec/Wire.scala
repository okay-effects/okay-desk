package okay.desk.exec

import okay.effects.json.Json
import scala.compiletime.{constValue, erasedValue, summonFrom}
import scala.deriving.Mirror

/** A closed, typed worker protocol. No executable source, runtime class loading or object deserialization. */
trait Wire[A]:
  def write(value: A): Json
  def read(value: Json): A

object Wire:
  private def bad(): Nothing = throw IllegalArgumentException("invalid computation message")
  private def codec[A](put: A => Json, get: Json => A): Wire[A] = new Wire[A]:
    def write(value: A): Json = put(value)
    def read(value: Json): A = get(value)
  given Wire[String] = codec(Json.Str(_), { case Json.Str(s) => s; case _ => bad() })
  given Wire[Boolean] = codec(Json.Bool(_), { case Json.Bool(b) => b; case _ => bad() })
  given Wire[Int] = codec(
    n => Json.Num(n),
    {
      case Json.Num(n) if n.isWhole && n >= Int.MinValue && n <= Int.MaxValue => n.toInt
      case _                                                                  => bad()
    }
  )
  given Wire[Double] = codec(
    n => if n.isFinite then Json.Num(n) else bad(),
    {
      case Json.Num(n) if n.toDouble.isFinite => n.toDouble
      case _                                  => bad()
    }
  )
  given Wire[Float] = codec(n => if n.isFinite then Json.Num(n.toDouble) else bad(), {
    case Json.Num(n) if n.toFloat.isFinite => n.toFloat
    case _ => bad()
  })
  given [A](using w: Wire[A]): Wire[Vector[A]] = codec(
    xs => Json.Arr(xs.map(w.write)),
    {
      case Json.Arr(xs) => xs.toVector.map(w.read)
      case _            => bad()
    }
  )
  given [A](using w: Wire[A]): Wire[Option[A]] = codec(
    _.fold[Json](Json.Null)(w.write),
    {
      case Json.Null => None
      case other     => Some(w.read(other))
    }
  )
  given [A](using w: Wire[A]): Wire[Map[String, A]] = codec(
    xs => Json.Obj(xs.toVector.map((k, v) => k -> w.write(v))),
    {
      case Json.Obj(xs) if xs.map(_._1).distinct.size == xs.size => xs.map((k, v) => k -> w.read(v)).toMap
      case _                                                     => bad()
    }
  )
  // Solver grids have typed keys: encode as pairs, not as stringified Ref values.
  given [A, B](using a: Wire[A], b: Wire[B]): Wire[Map[A, B]] = codec(
    xs => Json.Arr(xs.toVector.map((k, v) => Json.Arr(Vector(a.write(k), b.write(v))))),
    {
      case Json.Arr(xs) =>
        val pairs = xs.map {
          case Json.Arr(Vector(k, v)) => a.read(k) -> b.read(v)
          case _                      => bad()
        }
        if pairs.map(_._1).distinct.size != pairs.size then bad() else pairs.toMap
      case _ => bad()
    }
  )

  inline given auto[A](using m: Mirror.Of[A]): Wire[A] = derived[A]
  inline def get[A]: Wire[A] = summonFrom {
    case w: Wire[A]      => w
    case m: Mirror.Of[A] => derived[A](using m)
  }
  private inline def codecs[T <: Tuple]: Vector[Wire[Any]] = inline erasedValue[T] match
    case _: EmptyTuple => Vector.empty
    case _: (h *: t)   => get[h].asInstanceOf[Wire[Any]] +: codecs[t]
  private inline def labels[T <: Tuple]: Vector[String] = inline erasedValue[T] match
    case _: EmptyTuple => Vector.empty
    case _: (h *: t)   => constValue[h].toString +: labels[t]

  inline def derived[A](using m: Mirror.Of[A]): Wire[A] =
    val ws = codecs[m.MirroredElemTypes]
    val names = labels[m.MirroredElemLabels]
    inline m match
      case p: Mirror.ProductOf[A] => product(p, names, ws)
      case s: Mirror.SumOf[A]     => sum(s, names, ws)

  def product[A](m: Mirror.ProductOf[A], names: Vector[String], ws: Vector[Wire[Any]]): Wire[A] = codec(
    a => Json.Obj(names.zip(ws.zip(a.asInstanceOf[Product].productIterator.toVector).map((w, v) => w.write(v)))),
    {
      case Json.Obj(fs) if fs.map(_._1).distinct.size == fs.size && fs.map(_._1).toSet == names.toSet =>
        val fields = fs.toMap
        m.fromProduct(Tuple.fromArray(names.zip(ws).map((n, w) => w.read(fields(n))).toArray))
      case _ => bad()
    }
  )
  def sum[A](m: Mirror.SumOf[A], names: Vector[String], ws: Vector[Wire[Any]]): Wire[A] = codec(
    a =>
      val i = m.ordinal(a)
      Json.obj("case" -> Json.Str(names(i)), "value" -> ws(i).write(a))
    ,
    {
      case Json.Obj(fs) if fs.map(_._1).toSet == Set("case", "value") && fs.size == 2 =>
        val f = fs.toMap
        val i = names.indexOf(f("case").string)
        if i < 0 then bad() else ws(i).read(f("value")).asInstanceOf[A]
      case _ => bad()
    }
  )

  def encode[A: Wire](value: A): String = Json.print(summon[Wire[A]].write(value))
  def decode[A: Wire](text: String): Either[String, A] =
    try Right(summon[Wire[A]].read(Json.fromText(text)))
    catch case e: Exception => Left(Option(e.getMessage).getOrElse("invalid computation message"))
