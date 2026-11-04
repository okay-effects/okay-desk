package okay.desk

/** the law: the closure in weights == the interpreter == okay-datalog, on random graphs, from and to */
class WeightsSuite extends munit.FunSuite:
  val nodes = Vector("web", "api", "auth", "orders", "billing", "search", "cache", "db", "queue", "mail", "cdn", "dns")
  val rules = "reach(X, Y) :- edges(X, Y). reach(X, Z) :- edges(X, Y), reach(Y, Z)."

  test("weights == interpreter == datalog on 30 random graphs"):
    val rnd = new scala.util.Random(5)
    for trial <- 1 to 30 do
      val n = 4 + rnd.nextInt(8)
      val ns = rnd.shuffle(nodes).take(n)
      val edges = Vector.fill(3 + rnd.nextInt(10))((ns(rnd.nextInt(n)), ns(rnd.nextInt(n)))).filter((a, b) => a != b).distinct
      val t = Table("edges", Vector("from", "to"), edges.map((a, b) => Vector(Value.Str(a), Value.Str(b))))
      val tables = Map("edges" -> t)
      val c = ns(rnd.nextInt(n))
      for q <- Seq[Query.Logical](Query.Logical(rules, s"reach($c, X)"), Query.Logical(rules, s"reach(X, $c)")) do
        val w = Weights.run(tables, q).toOption.get.rows.map(_.head.text).toSet
        val i = Weights.reference(tables, q).toOption.get.rows.map(_.head.text).toSet
        val d = Logic.run(tables, q).toOption.get.rows.map(_.head.text).toSet
        assertEquals(w, i, s"trial $trial ${q.goal}: weights vs interpreter")
        assertEquals(w, d, s"trial $trial ${q.goal}: weights vs datalog over ${edges.mkString(" ")}")
    assert(Weights.fits(Map("edges" -> Table("edges", Vector("a", "b"), Vector.empty)), Query.Logical(rules, "reach(web, X)")))
    assert(!Weights.shape(Map.empty, Query.Logical("p(X) :- q(X).", "p(X)")).isDefined)
