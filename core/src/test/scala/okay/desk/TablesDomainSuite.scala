package okay.desk

import okay.desk.skill.Pairs
import okay.inlay.domain.Kit

/** TABLES AS A DOMAIN (okay-inlay specs/domains.md): the framework's laws on the desk's space — parts and assemble
 * inverse on drawn points over many schemas, the drawn points answered by the engines, the kit's pairs made */
class TablesDomainSuite extends munit.FunSuite:
  test("parts and assemble: a drawn point comes back as itself, on 40 schemas"):
    val rnd = scala.util.Random(3)
    (1 to 40).foreach { i =>
      val tables = Pairs.fromSpace(scala.util.Random(i), ru = false).tables
      val s = Space.Schema(tables)
      (1 to 25).foreach { _ =>
        val p = TablesDomain.random(rnd, s)
        assertEquals(TablesDomain.assemble(s, TablesDomain.parts(p)), Some(p), p.toString)
      }
    }

  test("the kit's pairs: the space × the phrases, each answered by the engines with rows"):
    val s = Space.Schema(Sample.tables)
    val pairs = Kit.pairs(TablesDomain)(s, 60, 5, Vector("en", "ru"))
    assertEquals(pairs.size, 60)
    pairs.foreach(p => assert(TablesDomain.run(p.point, s).exists(_.rows.nonEmpty), p.toString))
