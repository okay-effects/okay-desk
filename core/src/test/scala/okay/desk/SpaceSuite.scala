package okay.desk

/** THE SPACE OVER THE SAMPLE (specs/data.md §25): counted, every program of it runs, every gold program is in it */
class SpaceSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  test("the schema read from the data: kinds, the link orders.customer → customers, edges edge-like"):
    assertEquals(s.cols.find(_.name == "amount").map(_.kind), Some(Space.Kind.Num))
    assertEquals(s.cols.find(_.name == "day").map(_.kind), Some(Space.Kind.Time))
    assertEquals(s.cols.find(_.name == "id").map(_.kind), Some(Space.Kind.Key))
    assertEquals(s.links, Vector(Space.Link("orders", "customer", "customers")))
    assertEquals(s.edgeLike, Vector("edges"))
  test("the space counted; every 97th program runs on the engines"):
    val all = Space.enumerate(s).toVector
    println(s"  the space over the sample: ${all.size} programs (${all.count(_.isInstanceOf[Space.Program.Rel])} relational, " +
      s"${all.count(_.isInstanceOf[Space.Program.Absent])} absences, ${all.count(_.isInstanceOf[Space.Program.Closure])} closures)")
    assert(all.size > 1000)
    for p <- all.zipWithIndex.collect { case (p, i) if i % 97 == 0 => p } do
      val q = Space.compile(p, Sample.tables).toOption.get
      assert(Sample.run(q).isRight, s"$p → $q: ${Sample.run(q)}")
  test("every gold program of the sample is a point of the space"):
    val missing = Sample.questions.filter(q => !Space.contains(s, Query.parse(q.gold).toOption.get)).map(_.en)
    assertEquals(missing, Vector.empty)
