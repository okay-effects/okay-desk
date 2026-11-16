package okay.desk

import okay.desk.tasks.Everyday

/** a table's values named by a question in any of its languages; over `Everyday`, the links against the gold frames */
class LinksSuite extends munit.FunSuite:
  private val orders = Sample.tables("orders")

  test("«Львова» names Lviv; «Анны» Anna by its letters; a value said as it is"):
    assert(Links.of(orders, "Сколько заказов из Львова?").contains("city" -> "Lviv"))
    assert(Links.of(orders, "Сколько заказов у Анны?").contains("customer" -> "ann"))
    assert(Links.of(orders, "How many orders from Lviv?").contains("city" -> "Lviv"))
    assertEquals(Links.of(orders, "How many orders are there?"), Vector.empty)

  test("over Everyday (questions with an answer): the links are the gold frame's values, mostly, and find them"):
    var linked, inGold, golds, found = 0
    for q <- Everyday.questions; frame <- Everyday.frame(q).toVector if !frame.contains("answer: cannot");
        text <- Vector(q.en, q.ru) do
      val t = Everyday.tables(q.table)
      val links = Links.of(t, text).toSet
      // the gold's text values: a number is the question's own, read as it is written
      val gold = frame.flatMap(Links.named).toSet.filterNot(_._2.matches("-?[\\d.]+"))
      linked += links.size; inGold += links.count(gold); golds += gold.size; found += gold.count(links)
      val stray = links -- gold; val missed = gold -- links
      if stray.nonEmpty || missed.nonEmpty then println(s"  $text: stray $stray, missed $missed")
    println(s"links: $linked, of them in the gold $inGold; the gold's values $golds, linked $found")
    assert(inGold >= linked * 8 / 10 && found >= golds * 8 / 10, s"$inGold/$linked, $found/$golds")

  test("a column's values listed whole are a description, not a value meant"):
    assertEquals(Links.of(orders, "Orders (Kyiv, Lviv, Odesa) by amount").filter(_._1 == "city"), Vector.empty)
