package okay.desk.tasks

/**
 * THE JUDGE OF ANSWERS IN WORDS: every task of the collection accepts its own answer as it is written in both
 * languages and as the solver gives it, and refuses the answers of the other tasks of its class; the classics by hand.
 */
class JudgeSuite extends munit.FunSuite:
  val all = Tasks.entries.map(_.task)
  def decided(t: Task): Boolean = !t.expect.exists(e => e.contains("|") || Set("several", "none", "unreachable")(e))
  def task(title: String): Task = all.find(_.title.en == title).getOrElse(fail(s"no task: $title"))
  def one(kind: Kind, expect: String*): Task =
    all.find(t => t.kind == kind && t.expect == expect.toVector).getOrElse(fail(s"no $kind task with $expect"))

  test("every task accepts its own answer, alone and inside a sentence, in both languages"):
    val refused = all.flatMap(t => Vector(t.answer.en, t.answer.ru).filterNot(t.accepts(_, alone = true)).map(a =>
      s"${t.title.en}: $a") ++ Vector(s"Answer: ${t.answer.en}.", s"Ответ: ${t.answer.ru}.").filterNot(t.accepts(_))
      .map(a => s"${t.title.en}: $a"))
    assertEquals(refused, Vector.empty)

  test("a task with a decided answer accepts the solver's values as they are"):
    val refused = all.filter(decided).filterNot(t => t.accepts(t.expect.mkString("; "), alone = true))
    assertEquals(refused.map(_.title.en), Vector.empty)

  test("a task refuses the answers of the other tasks of its class"):
    val taken = for
      t <- all if decided(t)
      o <- all if o.kind == t.kind && o.expect != t.expect
      a <- Vector(o.answer.en, o.answer.ru) if t.accepts(a, alone = true)
    yield s"${t.title.en} [${t.answer.en}] accepts: $a"
    assertEquals(taken.distinct, Vector.empty)

  test("nothing and a refusal to answer are not accepted"):
    val taken = all.filter(t => t.accepts("") || t.accepts("I do not know") || t.accepts("Не знаю"))
    assertEquals(taken.map(_.title.en), Vector.empty)

  test("a name: alone, with its article, in a sentence, in Russian — and not among other names"):
    val zebra = one(Kind.Einstein, "Japanese")
    assert(zebra.accepts("Japanese", alone = true) && zebra.accepts("the japanese", alone = true))
    assert(zebra.accepts("японец", alone = true) && zebra.accepts("The Japanese owns the zebra.", alone = true))
    assert(!zebra.accepts("Norwegian", alone = true) && !zebra.accepts("the Norwegian or the Japanese", alone = true))
    assert(zebra.accepts("Answer: the Norwegian drinks water, the Japanese owns the zebra"))

  test("a number: whole, not a part of another, nothing beside it; a long number rounded"):
    val wolf = task("The wolf, the goat and the cabbage")
    assert(wolf.accepts("7", alone = true) && wolf.accepts("7 crossings", alone = true))
    assert(wolf.accepts("7 переправ", alone = true) && wolf.accepts("The shortest plan has 7 moves: 1. take the goat"))
    assert(!wolf.accepts("17", alone = true) && !wolf.accepts("7 or 9", alone = true))
    assert(!wolf.accepts("7 9", alone = true) && !wolf.accepts("seven", alone = true))
    val mean = all.find(_.expect.exists(_.contains("463.3333333333333"))).get
    assert(mean.accepts("Kyiv, 102.5; Lviv, 463.33; Odesa, 45", alone = true))
    assert(!mean.accepts("Kyiv, 102.5; Lviv, 463.4; Odesa, 45", alone = true))
    assert(!mean.accepts("Kyiv, 463.33; Lviv, 102.5; Odesa, 45", alone = true))

  test("a yes or a no, in the words of a person and of the desk"):
    val yes = one(Kind.Deduction, "true")
    val no = one(Kind.Deduction, "false")
    for said <- Vector("Yes", "yes, it follows", "Да", "Proved: every rose is a plant.", "true") do
      assert(yes.accepts(said, alone = true) && !no.accepts(said, alone = true), said)
    for said <- Vector("No", "Нет", "Refuted: every rose is a plant.", "false", "No, it is not true", "not true") do
      assert(no.accepts(said, alone = true) && !yes.accepts(said, alone = true), said)
    assert(!yes.accepts("It does not follow from what is given") && !no.accepts("Из условий это не следует"))

  test("who is a knight and who is a liar: in any order of the two, not the other way round"):
    val t = one(Kind.Truth, "false", "true")
    assert(t.accepts("A is a liar, B is a knight", alone = true) && t.accepts("A — лжец, B — рыцарь", alone = true))
    assert(t.accepts("B is a knight and A is a liar", alone = true) && t.accepts("A liar, b knight", alone = true))
    assert(!t.accepts("A is a knight, B is a liar", alone = true) && !t.accepts("A — рыцарь, B — лжец", alone = true))
    assert(!t.accepts("A is a liar, B is a knight, C is a liar", alone = true))

  test("an order of numbers is one answer: its permutation is another"):
    val t = one(Kind.Numbers, "2413")
    assert(t.accepts("2, 4, 1, 3", alone = true) && t.accepts("2 4 1 3", alone = true))
    assert(t.accepts("2413", alone = true) && !t.accepts("1, 2, 3, 4", alone = true))
    assert(!t.accepts("3, 1, 4, 2", alone = true))
    val pair = one(Kind.Quantities, "6", "4")
    assert(pair.accepts("6 and 4", alone = true) && pair.accepts("4 кролика и 6 кур", alone = true))
    assert(!pair.accepts("4 and 6", alone = true) && !pair.accepts("4 chickens and 6 rabbits", alone = true))

  test("a set of names in any order, whole"):
    val t = one(Kind.Kinship, "dina", "egor", "nina")
    assert(t.accepts("Nina, Dina and Egor", alone = true) && t.accepts("Егор, Нина, Дина", alone = true))
    assert(!t.accepts("Dina, Egor", alone = true) && !t.accepts("Dina, Egor, Nina, Roman", alone = true))

  test("what is not decided and what cannot be: the saying is the answer"):
    val open = one(Kind.Order, "Oleg|Roman")
    assert(open.accepts("It cannot be decided") && open.accepts("Oleg or Roman", alone = true))
    assert(open.accepts("Определить нельзя") && !open.accepts("Oleg", alone = true))
    assert(!open.accepts("Roman", alone = true))
    val none = one(Kind.Plans, "unreachable")
    assert(none.accepts("It cannot be done", alone = true) && none.accepts("невозможно", alone = true))
    assert(none.accepts("Нельзя") && !none.accepts("3", alone = true) && !none.accepts("It cannot be decided"))
    val several = one(Kind.Einstein, "several")
    assert(several.accepts("more than one arrangement fits") && several.accepts("неоднозначно"))
    assert(!several.accepts("the Norwegian", alone = true) && !several.accepts("the clues contradict each other"))

  test("a table the desk drew is judged row by row: more columns pass, other rows or more of them do not"):
    val unpaid = task("Unpaid orders: how many")
    assert(Judge.tabled(unpaid, "n\n-\n4"))
    assert(!Judge.tabled(unpaid, "n\n-\n5"))
    assert(!Judge.tabled(unpaid, "id | amount\n---+-------\n4  | 200\n5  | 1000"), "the number among other rows")
    val largest = task("The three largest orders")
    assert(Judge.tabled(largest, "id | amount\n---+-------\n5  | 1000\n8  | 310\n4  | 200"))
    assert(Judge.tabled(largest, "id | customer | amount\n---+----------+-------\n4  | cid | 200\n8  | eve | 310\n" +
      "5  | bob | 1000"), "whole rows, another order")
    assert(!Judge.tabled(largest, "id | amount\n---+-------\n5  | 1000\n8  | 310"))
    assert(!Judge.tabled(largest, "id | amount\n---+-------\n5  | 1000\n8  | 310\n7  | 60"))
    assert(Judge.tabled(task("Average by city"), "city | avg\n-----+----\nKyiv | 102.5\nLviv | 463.33\nOdesa | 45"))
