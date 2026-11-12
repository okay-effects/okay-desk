package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.{Sample, Table}

class SiteSamplesSuite extends munit.FunSuite:
  test("the site serves the same sample facts the exact laws and examples describe"):
    val root = Path.of(sys.props("user.dir")).getParent
    for (name, expected) <- Sample.tables do
      val filename = name + ".csv"
      val text = Files.readString(root.resolve("web/page/sample").resolve(filename))
      assertEquals(Table.parse(filename, text), Right(expected))
