package okay.desk.bench

/** pairs over the domains' tables, shown: `sbt "bench/runMain okay.desk.bench.DomainPairs [n]"` */
object DomainPairs:
  def main(args: Array[String]): Unit =
    val rnd = scala.util.Random(args.lift(1).map(_.toLong).getOrElse(5L))
    for _ <- 1 to args.headOption.map(_.toInt).getOrElse(12) do
      val p = okay.desk.skill.Pairs.fromDomain(rnd, rnd.nextBoolean())
      println(s"${p.tables.keys.mkString(",")}: ${p.question}\n    ${p.program}")
