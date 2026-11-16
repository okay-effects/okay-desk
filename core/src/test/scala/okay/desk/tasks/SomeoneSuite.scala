package okay.desk.tasks

/** someone of a solved grid: found by a name in either language, said as a sentence */
class SomeoneSuite extends munit.FunSuite:
  private val zebra = Vector("1: Norwegian, yellow, water, Kools, fox", "2: Ukrainian, blue, tea, Chesterfields, horse",
    "3: Englishman, red, milk, Old Gold, snails", "4: Spaniard, ivory, orange juice, Lucky Strike, dog",
    "5: Japanese, green, coffee, Parliaments, zebra")

  test("the Japanese, asked in English: their line as a sentence"):
    assertEquals(Someone.about(zebra, "What does the Japanese do?", ru = false),
      Vector("The Japanese lives in the green house, drinks coffee, smokes Parliaments, keeps a zebra"))

  test("«японец» names the Japanese of a task written in English; said in Russian"):
    assertEquals(Someone.about(zebra, "Что делает японец?", ru = true),
      Vector("Японец живёт в зелёном доме, пьёт кофе, курит Parliaments, держит зебру"))
    assertEquals(Someone.about(zebra, "Расскажи про испанца", ru = true),
      Vector("Испанец живёт в белом доме, пьёт апельсиновый сок, курит Lucky Strike, держит собаку"))

  test("a thing, not a person: the line of whoever has it"):
    assertEquals(Someone.about(zebra, "What about the zebra?", ru = false).size, 1)
    assertEquals(Someone.about(zebra, "Что известно о зебре?", ru = true).head.take(6), "Японец")

  test("no one named: nothing"):
    assertEquals(Someone.about(zebra, "What does the Swede do?", ru = false), Vector.empty)
