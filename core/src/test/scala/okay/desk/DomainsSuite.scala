package okay.desk

/** THE DOMAINS' LAWS: the measure's domains are not among them; every name has its Russian forms; a pair over a
 * domain's table is a program that runs */
class DomainsSuite extends munit.FunSuite:
  test("the measure's six domains are held out") {
    assert(Domains.all.forall(d => !Domains.heldOut(d.table)), Domains.all.map(_.table).filter(Domains.heldOut))
    assertEquals(Domains.heldOut, okay.desk.tasks.Everyday.tables.keySet)
  }
  test("every table and column has its Russian forms") {
    for d <- Domains.all do
      assert(d.ru._1.nonEmpty, d.table)
      for c <- d.columns do assert(c.ru._1.nonEmpty, s"${d.table}.${c.name}")
  }
  test("pairs over the domains: 300 drawn, each program runs") {
    val rnd = scala.util.Random(11)
    for _ <- 1 to 300 do
      val p = skill.Pairs.fromDomain(rnd, rnd.nextBoolean())
      val ran = Query.parse(p.program).flatMap {
        case r: Query.Relational => Exact.run(p.tables, r)
        case l: Query.Logical => Logic.run(p.tables, l)
      }
      assert(ran.isRight, s"${p.question} → ${p.program}: $ran")
  }
