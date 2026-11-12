package okay.desk.statement

import okay.desk.tasks.Gold
import Writing.{ask, list, say, Said, Stated}

/**
 * THE STATEMENT LANGUAGE BY HAND (specs/formalization.md §3), with no model anywhere: where a text's sentences are,
 * what a statement of a text may and may not say, which solver the SHAPE of a statement chooses and what its program
 * answers there, what the compilers refuse, and what a statement costs in decisions.
 */
class StatementSuite extends munit.FunSuite:
  /** a text written sentence by sentence, the copied pieces in brackets, each sentence with the frames it states */
  def written(problem: Problem, sentences: (String, Vector[Said])*): Stated =
    Writing.write(problem, sentences.toVector.zipWithIndex.map { case ((words, frames), i) =>
      Writing.Part((if i == 0 then "" else " ") + Writing.noted(words), frames) })
  def solved(s: Stated): Either[String, Vector[String]] = Compile.of(s.statement, s.text).flatMap(_.run(s.text))
  def refused(s: Stated): String = Compile.of(s.statement, s.text).swap.getOrElse("compiled")
  private val (whether, fact, edge) = (say(Con.Whether), say(Con.Fact), say(Con.Edge))

  test("the sentences of a text: stops, questions, lines, list numbers, quotes, a number with a point"):
    val text = "Five houses stand in a row. 1. The Englishman has the red house.\n2. Who has the fish? " +
      "She said \"No.\" It costs 3.5 coins!"
    assertEquals(Sentences.of(text).map(_.in(text)), Vector("Five houses stand in a row.",
      "1. The Englishman has the red house.", "2. Who has the fish?", "She said \"No.\"", "It costs 3.5 coins!"))

  val houses: Stated = written(Problem.Assign,
    "Three houses stand in a row." -> Vector.empty,
    "Their owners are [Ann], [Bob] and [Cid]." -> Vector(list(Con.Attribute, 3)),
    "Their pets are [a cat], [a dog] and [a fox]." -> Vector(list(Con.Attribute, 3)),
    s"[Ann] lives in house ${Writing.number(1)}." -> Vector(say(Con.At)),
    "[Bob] lives just after [Ann]." -> Vector(say(Con.JustAfter)),
    "[Ann] keeps [a cat|the cat]." -> Vector(say(Con.Same)),
    "[a dog|The dog] lives somewhere after [a fox|the fox]." -> Vector(say(Con.After)),
    "Who keeps [a dog|the dog]?" -> Vector(ask(0)))

  test("a text written with marks is its plain text and a statement of it"):
    assert(houses.text.startsWith("Three houses stand in a row. Their owners are Ann, Bob and Cid."), houses.text)
    assertEquals(houses.statement.check(houses.text), Right(()))
    assertEquals(houses.statement.things.map(_.in(houses.text)), Vector("Ann", "Bob", "Cid", "a cat", "a dog", "a fox"))
    assertEquals(houses.statement.sentences.map(_.frames.map(_.con)), Vector(Vector.empty, Vector(Con.Attribute),
      Vector(Con.Attribute), Vector(Con.At), Vector(Con.JustAfter), Vector(Con.Same), Vector(Con.After),
      Vector(Con.Ask)))
    // "The dog" is the thing "a dog" named: a pointer into the text, not a new name
    val dog = houses.statement.sentences(6).frames.head.args.head
    assertEquals(dog.span.map(_.in(houses.text)), Some("The dog"))
    assertEquals(dog, Arg.Mention(dog.span.get, houses.statement.things(4)))

  test("attributes, places in a row and a «who is with» question are a grid"):
    assert(Compile.of(houses.statement, houses.text).exists(_.isInstanceOf[Gold.Grid]))
    assertEquals(solved(houses), Right(Vector("Cid")))

  test("an either-or, and who has what with no row, are finite constraints; «is it so» is a goal"):
    val hats = written(Problem.Assign,
      "[Ann], [Bob] and [Cid] each wear a hat." -> Vector(list(Con.Attribute, 3)),
      "The hats are [red], [blue] and [green]." -> Vector(list(Con.Attribute, 3)),
      "[Ann] wears [red] or [blue]." -> Vector(say(Con.Either)),
      "[Ann] does not wear [red]." -> Vector(say(Con.Differ)),
      "[Bob] does not wear [red] either." -> Vector(say(Con.Differ)),
      "Which hat does [Cid] wear?" -> Vector(ask(1)),
      "Does [Bob] wear [green]?" -> Vector(whether, say(Con.Same)),
      "Does [Bob] wear [blue]?" -> Vector(whether, say(Con.Same)))
    assertEquals(hats.statement.check(hats.text), Right(()))
    assert(Compile.of(hats.statement, hats.text).exists(_.isInstanceOf[Gold.Finite]))
    assertEquals(solved(hats), Right(Vector("red", "true", "false")))

  test("a line with no attributes: the things themselves stand on the positions"):
    val race = written(Problem.Assign,
      "[Ann] finished before [Bob]." -> Vector(say(Con.Before)),
      "[Cid] finished after [Bob]." -> Vector(say(Con.After)),
      "Who was last?" -> Vector(say(Con.Last)))
    assertEquals(solved(race), Right(Vector("Cid")))

  val rain: Stated = written(Problem.Derive,
    "If [rain|it rains], [wet|the street is wet]." -> Vector(say(Con.If)),
    "[!wet|The street is dry]." -> Vector(fact),
    "Does [rain|it rain]?" -> Vector(whether, fact))

  test("claims are true-or-false unknowns: what follows, what does not, what cannot be"):
    assertEquals(rain.statement.check(rain.text), Right(()))
    assertEquals(rain.statement.mentions.map(_.denied), Vector(false, false, true, false))
    assertEquals(solved(rain), Right(Vector("false")))
    val open = written(Problem.Derive,
      "If [rain|it rains], [wet|the street is wet]." -> Vector(say(Con.If)),
      "[wet|The street is wet]." -> Vector(fact),
      "Does [rain|it rain]?" -> Vector(whether, fact))
    assertEquals(solved(open), Right(Vector("false|true")))
    val never = written(Problem.Derive,
      "[rain|It rains]." -> Vector(fact),
      "[!rain|It does not rain]." -> Vector(fact),
      "Does [rain|it rain]?" -> Vector(whether, fact))
    assertEquals(solved(never), Right(Vector("none")))

  test("exactly one of the alternatives, and which one"):
    val switches = written(Problem.Derive,
      "Exactly one of the switches [A], [B] and [C] is on." -> Vector(list(Con.OneOf, 3)),
      "[!A] is off." -> Vector(fact),
      "If [B] is on, [A] is on too." -> Vector(say(Con.If)),
      "Which switch is on?" -> Vector(say(Con.Which)))
    assertEquals(solved(switches), Right(Vector("C")))

  val family: Stated = written(Problem.Derive,
    "[Ann] and [Bob] are the parents of [Cid]." -> Vector(say(Con.Edge, 0, 2), say(Con.Edge, 1, 2)),
    "[Cid] is the father of [Dan]." -> Vector(edge),
    "Who are the ancestors of [Dan]?" -> Vector(say(Con.To)))

  test("ways are facts and the one rule of reaching, on Datalog"):
    assert(Compile.of(family.statement, family.text).exists(_.isInstanceOf[Gold.Rules]))
    assertEquals(solved(family).map(_.sorted), Right(Vector("Ann", "Bob", "Cid")))
    def roads(question: (String, Vector[Said])): Stated = written(Problem.Derive,
      "A road leads from [Kyiv] to [Lviv]." -> Vector(edge),
      "Another leads from [Lviv] to [Krakow]." -> Vector(edge),
      "[Odesa] and [Kyiv] are joined by a road both ways." -> Vector(say(Con.Link)),
      question)
    def answer(question: (String, Vector[Said])): Either[String, Vector[String]] = solved(roads(question)).map(_.sorted)
    assertEquals(answer("Where can one get from [Lviv]?" -> Vector(say(Con.From))), Right(Vector("Krakow")))
    assertEquals(answer("Is there a way from [Odesa] to [Krakow]?" -> Vector(whether, say(Con.Path))),
      Right(Vector("yes")))
    assertEquals(answer("Is there a way from [Krakow] to [Odesa]?" -> Vector(whether, say(Con.Path))),
      Right(Vector("no")))
    assertEquals(answer("Can one leave [Kyiv] and come back?" -> Vector(whether, say(Con.Round))), Right(Vector("yes")))
    assertEquals(answer("What cannot be reached from [Lviv]?" -> Vector(say(Con.Apart))),
      Right(Vector("Kyiv", "Odesa")))
    assertEquals(answer("Which towns lie on a round trip?" -> Vector(say(Con.Cycle))), Right(Vector("Kyiv", "Odesa")))
    assertEquals(answer("From which town does no road lead?" -> Vector(say(Con.Ends))), Right(Vector("Krakow")))

  test("a statement that is not of its text is refused"):
    val (s, text) = (rain.statement, rain.text)
    def changed(sentence: Int)(f: Vector[Frame] => Vector[Frame]): Either[String, Unit] =
      val at = s.sentences(sentence)
      s.copy(sentences = s.sentences.updated(sentence, at.copy(frames = f(at.frames)))).check(text)
    val (street, dry) = (s.things(1), s.sentences(1).frames.head.args.head.span.get)
    assertEquals(s.check(text + " And more."), Left("the sentences are not the text's"))
    assertEquals(s.copy(problem = Problem.Assign).check(text), Left("If is not a constructor of Assign"))
    assertEquals(changed(1)(_ => Vector(Frame(Con.If, Vector(Arg.Mention(dry, street))))),
      Left("If takes 2 pieces, not 1"))
    // a piece of another sentence
    assert(changed(2)(_ => Vector(Frame(Con.Fact, Vector(Arg.Mention(dry, street))))).swap.exists(
      _.startsWith("Fact: a piece outside its sentence")))
    // a name of something named only later, and a name of what no piece named first
    assert(changed(0)(_ => Vector(Frame(Con.Fact, Vector(Arg.Mention(street, dry))))).swap.exists(
      _.startsWith("Fact: not a Claim")))
    val numbers = houses.statement
    val at = numbers.sentences(3)
    val words = Arg.Number(at.frames.head.args.head.span.get)
    assert(numbers.copy(sentences = numbers.sentences.updated(3, at.copy(frames = Vector(Frame(Con.At,
      Vector(at.frames.head.args.head, words)))))).check(houses.text).swap.exists(_.startsWith("At: not a Number")))

  test("what a compiler cannot take to a program it refuses, and says why"):
    assertEquals(refused(written(Problem.Derive, "[rain|It rains]." -> Vector(fact))), "no question")
    assertEquals(refused(written(Problem.Derive, "[rain|It rains]." -> Vector(fact), "Is it so?" -> Vector(whether))),
      "a question of nothing")
    assertEquals(refused(written(Problem.Derive, "[rain|It rains] on the way from [Kyiv] to [Lviv]." ->
      Vector(say(Con.Fact, 0), say(Con.Edge, 1, 2)), "Does [rain|it rain]?" -> Vector(whether, fact))),
      "claims and ways in one statement")
    assertEquals(refused(written(Problem.Derive, "There is a way from [Kyiv] to [Lviv]." -> Vector(say(Con.Path)),
      "Where can one get from [Kyiv]?" -> Vector(say(Con.From)))), "a way is asked for, never stated")
    assertEquals(refused(written(Problem.Assign, "The owners are [Ann] and [Bob]." -> Vector(list(Con.Attribute, 2)),
      "The pets are [a cat], [a dog] and [a fox]." -> Vector(list(Con.Attribute, 3)),
      "Who keeps [a dog|the dog]?" -> Vector(ask(0)))),
      "the attributes have different numbers of things: Ann, Bob; a cat, a dog, a fox")
    assertEquals(refused(written(Problem.Assign, "The owners are [Ann] and [Bob]." -> Vector(list(Con.Attribute, 2)),
      "[Ann] lives before [Cid]." -> Vector(say(Con.Before)), "Who is first?" -> Vector(say(Con.First)))),
      "not among the things listed: Cid")
    assertEquals(Compile.of(rain.statement.copy(problem = Problem.Plan), rain.text).swap.toOption,
      Some("no compiler of Plan yet"))

  test("the decisions of a statement, counted by hand"):
    // the kind; 1 + 1 + 2 constructors and a stop after each sentence; 4 pieces; "the street is wet", "The street is
    // dry" and "it rain" are not written as anything named before them — three links to decide; 4 claims said or
    // denied; and the first name of all is new with no choice
    assertEquals(Budget.of(rain.statement, rain.text), Budget(kind = 1, frames = 7, pieces = 4, links = 3, signs = 4,
      picks = 0, free = 1))
    assertEquals(Budget.of(rain.statement, rain.text).decisions, 19)
    // 4 constructors and 3 stops; "Cid" is copied by two frames and named once, new like "Bob" and "Dan" (3 links);
    // "Cid" and "Dan" written again are the data's own links
    assertEquals(Budget.of(family.statement, family.text), Budget(kind = 1, frames = 7, pieces = 7, links = 3,
      signs = 0, picks = 0, free = 3))
    // six declared names are new by their role; "Ann", "Bob" written as before are the data's, and so is "the dog"
    // once "The dog" named a thing; "the cat", "The dog", "the fox" are links to decide; of two attributes the one
    // asked for is a choice
    val b = Budget.of(houses.statement, houses.text)
    assertEquals((b.links, b.picks, b.free), (3, 1, 11))

  test("one decision wrong: refused, signalled by the solver, harmless, or silently another answer"):
    import Faults.{Kind, Verdict}
    /** the verdicts of the statements with this fault in this sentence */
    def wrong(s: Stated, kind: Kind, sentence: Int): Vector[Verdict] =
      val right = Faults.result(s.statement, s.text).fold(e => fail(e), identity)
      Faults.of(s.statement).collect {
        case (`kind`, w) if w.sentences(sentence) != s.statement.sentences(sentence) =>
          Faults.verdict(w, s.text, right) }
    assertEquals(Faults.of(houses.statement).map(_._1).toSet,
      Set(Kind.Constructor, Kind.Order, Kind.Link, Kind.Pick, Kind.Dropped))
    assert(Faults.of(houses.statement).forall(_._2 != houses.statement))
    // "the dog after the fox" the wrong way round is another arrangement, as good as the right one: Bob's dog
    assertEquals(wrong(houses, Kind.Order, 6), Vector(Verdict.Silent))
    // without "Ann lives in house 1" the row may begin with Cid: the solver finds several and says so
    assertEquals(wrong(houses, Kind.Dropped, 3), Vector(Verdict.Signalled))
    // of the 12 wrong links of "Ann keeps the cat", 8 are no statement of any arrangement: a name taken for something
    // new (2), a thing with itself (2), two owners or two pets in one house (4); "Bob keeps the cat" changes nothing
    assertEquals(wrong(houses, Kind.Link, 5).size, 12)
    assertEquals(wrong(houses, Kind.Link, 5).count(_ == Verdict.Refused), 8)
    assert(wrong(houses, Kind.Link, 5).contains(Verdict.Harmless))
    // the other attribute asked for is a question of nothing the solver can answer for a pet's pet
    assertEquals(wrong(houses, Kind.Pick, 7).size, 1)
    // "the street is dry" read as "wet": rain no longer follows either way — signalled; "if" read as "exactly when"
    // gives the same answer here
    assertEquals(wrong(rain, Kind.Sign, 1), Vector(Verdict.Signalled))
    assertEquals(wrong(rain, Kind.Constructor, 0), Vector(Verdict.Harmless))

  test("a clue no arrangement can satisfy is refused before any solver sees it"):
    def of(clues: (String, Vector[Said])*): String = refused(written(Problem.Assign,
      (Vector("The owners are [Ann], [Bob] and [Cid]." -> Vector(list(Con.Attribute, 3)),
        "The pets are [a cat], [a dog] and [a fox]." -> Vector(list(Con.Attribute, 3))) ++ clues :+
        ("Who keeps [a dog|the dog]?" -> Vector(ask(0))))*))
    assertEquals(of("[Ann] lives before [Ann]." -> Vector(say(Con.Before))), "Before: Ann and itself")
    assertEquals(of("[Ann] lives with [Bob]." -> Vector(say(Con.Same))),
      "Ann and Bob are of one attribute: never on one position")
    // two clues read together: the cat is Ann's and the cat is Bob's
    assertEquals(of("[Ann] keeps [a cat|the cat]." -> Vector(say(Con.Same)),
      "[a cat|The cat] is [Bob]'s." -> Vector(say(Con.Same))),
      "Ann and Bob are of one attribute: never on one position")
