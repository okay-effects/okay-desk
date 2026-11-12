package okay.desk.tasks

import okay.effects.json.Json

import Gold.{n, op, v}

/**
 * PLANS: a state, the actions allowed and what must never happen — the question is the least number of actions, or
 * whether the goal can be reached at all. Each is a transition model for the exact planner; families (the tower, the
 * lamps, the jugs) are written once and stated for several sizes.
 */
object Plans:
  private type Vars = Vector[(String, Vector[Json])]
  private def binary(names: Seq[String]): Vars = names.toVector.map(_ -> Vector(n(0), n(1)))
  private def model(vars: Vars, start: Seq[Int], goal: Seq[Int], moves: Seq[Json], safe: Json*): Gold =
    Gold.Plan(Json.print(Json.Obj(Vector("vars" -> Json.Obj(vars.map((k, d) => k -> Json.Arr(d))),
      "start" -> Json.Arr(start.toVector.map(n)), "goal" -> Json.Arr(goal.toVector.map(n)),
      "moves" -> Json.Arr(moves.toVector), "safe" -> Json.Arr(safe.toVector)))))
  private def all(conditions: Seq[Json]): Json =
    if conditions.size == 1 then conditions.head else op("and", conditions*)
  private def set(to: (String, Json)*): Json = Json.Obj(Vector("set" -> Json.Obj(to.toVector)))
  private def when(guard: Json, to: (String, Json)*): Json =
    Json.Obj(Vector("set" -> Json.Obj(to.toVector), "if" -> guard))
  private def flip(names: Seq[String]): Json = Json.Obj(Vector("flip" -> Json.Arr(names.toVector.map(v))))

  /** the ferryman: he crosses with `seats` of his charges; a pair that must not be left alone is `eater -> eaten` */
  private def river(charges: Vector[String], seats: (Int, Int), eats: (String, String)*): Gold =
    model(binary("farmer" +: charges), Vector.fill(charges.size + 1)(0), Vector.fill(charges.size + 1)(1),
      Vector(Json.Obj(Vector("flip" -> Json.Arr(Vector(v("farmer"))), "choose" -> Json.Obj(Vector(
        "vars" -> Json.Arr(charges.map(v)), "min" -> n(seats._1), "max" -> n(seats._2), "equal" -> v("farmer")))))),
      eats.map((a, b) => op("or", op("=", v("farmer"), v(a)), op("!=", v(a), v(b))))*)

  /** two jugs and a tap: fill one, empty one, or pour one into the other until it is empty or the other is full */
  private def jugs(small: Int, large: Int, goal: (Int, Int)): Gold =
    val both = op("+", v("small"), v("large"))
    def pour(from: String, to: String, room: Int): Json =
      val fits = op("<=", both, n(room))
      set(to -> op("if", fits, both, n(room)), from -> op("if", fits, n(0), op("-", both, n(room))))
    model(Vector("small" -> Gold.ints(0, small), "large" -> Gold.ints(0, large)), Vector(0, 0),
      Vector(goal._1, goal._2), Vector(set("small" -> n(small)), set("large" -> n(large)), set("small" -> n(0)),
        set("large" -> n(0)), pour("small", "large", large), pour("large", "small", small)))

  /** the tower: disk 1 is the smallest; a disk moves when no smaller one lies on it or on the peg it goes to */
  private def tower(disks: Int, neighbours: Boolean): Gold =
    def disk(i: Int): String = s"disk$i"
    val moves = for i <- 1 to disks; peg <- 1 to 3 yield
      val near = if neighbours then Vector(op("=", op("abs", op("-", v(disk(i)), n(peg))), n(1))) else Vector.empty
      val free = (1 until i).flatMap(j => Vector(op("!=", v(disk(j)), v(disk(i))), op("!=", v(disk(j)), n(peg))))
      when(all((op("!=", v(disk(i)), n(peg)) +: near) ++ free), disk(i) -> n(peg))
    model((1 to disks).toVector.map(i => disk(i) -> Gold.ints(1, 3)), Vector.fill(disks)(1), Vector.fill(disks)(3),
      moves)

  /** lamps with a button each: a button turns over its lamp and the lamps beside it */
  private def lamps(start: Vector[Int], ring: Boolean): Gold =
    val size = start.size
    def lamp(i: Int): String = s"lamp${i + 1}"
    def beside(i: Int): Vector[Int] =
      if ring then Vector((i + size - 1) % size, i, (i + 1) % size)
      else Vector(i - 1, i, i + 1).filter(j => j >= 0 && j < size)
    model(binary(start.indices.map(lamp)), start, Vector.fill(size)(1),
      start.indices.map(i => flip(beside(i).map(lamp))))

  /** glasses, all upside down: one move turns over exactly `together` of them */
  private def glasses(count: Int, together: Int): Gold =
    val names = (1 to count).map(i => s"glass$i")
    model(binary(names), Vector.fill(count)(0), Vector.fill(count)(1), names.combinations(together).map(flip).toVector)

  private def lift(floors: Int, up: Int, down: Int, from: Int, to: Int): Gold =
    model(Vector("floor" -> Gold.ints(1, floors)), Vector(from), Vector(to),
      Vector(set("floor" -> op("+", v("floor"), n(up))), set("floor" -> op("-", v("floor"), n(down)))))

  /** frogs on a row of stones with one free in the middle: 1 goes right, 2 goes left; a step, or a jump over one */
  private def frogs(each: Int): Gold =
    val size = 2 * each + 1
    def stone(i: Int): String = s"stone$i"
    def go(kind: Int, from: Int, over: Option[Int], to: Int): Json =
      when(all(Vector(op("=", v(stone(from)), n(kind)), op("=", v(stone(to)), n(0))) ++
        over.map(o => op("!=", v(stone(o)), n(0)))), stone(from) -> n(0), stone(to) -> n(kind))
    val moves = (1 to size).flatMap(i => Vector(
      Option.when(i + 1 <= size)(go(1, i, None, i + 1)), Option.when(i + 2 <= size)(go(1, i, Some(i + 1), i + 2)),
      Option.when(i - 1 >= 1)(go(2, i, None, i - 1)), Option.when(i - 2 >= 1)(go(2, i, Some(i - 1), i - 2))).flatten)
    model((1 to size).toVector.map(i => stone(i) -> Gold.ints(0, 2)),
      Vector.fill(each)(1) ++ Vector(0) ++ Vector.fill(each)(2),
      Vector.fill(each)(2) ++ Vector(0) ++ Vector.fill(each)(1), moves)

  /** the two groups on the left bank and the boat's side; nowhere may the second group outnumber the first */
  private def crossing(each: Int): Gold =
    val loads = Vector((1, 0), (2, 0), (0, 1), (0, 2), (1, 1))
    def sail(side: Int, sign: String)(load: (Int, Int)): Json = when(op("=", v("boat"), n(side)),
      "guards" -> op(sign, v("guards"), n(load._1)), "robbers" -> op(sign, v("robbers"), n(load._2)),
      "boat" -> n(1 - side))
    model(Vector("guards" -> Gold.ints(0, each), "robbers" -> Gold.ints(0, each), "boat" -> Gold.ints(0, 1)),
      Vector(each, each, 0), Vector(0, 0, 1), loads.map(sail(0, "-")) ++ loads.map(sail(1, "+")),
      op("or", op("=", v("guards"), n(0)), op(">=", v("guards"), v("robbers"))),
      op("or", op("=", v("guards"), n(each)), op("<=", v("guards"), v("robbers"))))

  /** cards in a row: one move swaps two neighbours */
  private def cards(start: Vector[Int]): Gold =
    def place(i: Int): String = s"place${i + 1}"
    model(start.indices.toVector.map(i => place(i) -> Gold.ints(1, start.size)), start, start.sorted,
      (0 until start.size - 1).map(i => set(place(i) -> v(place(i + 1)), place(i + 1) -> v(place(i)))))

  private def knight(size: Int, from: (Int, Int), to: (Int, Int)): Gold =
    val jumps = for dx <- Vector(-2, -1, 1, 2); dy <- Vector(-2, -1, 1, 2) if dx.abs != dy.abs yield (dx, dy)
    model(Vector("column" -> Gold.ints(1, size), "row" -> Gold.ints(1, size)), Vector(from._1, from._2),
      Vector(to._1, to._2),
      jumps.map((dx, dy) => set("column" -> op("+", v("column"), n(dx)), "row" -> op("+", v("row"), n(dy)))))

  private val never = ("It cannot be done", "Это невозможно")
  private def plan(title: (String, String), text: (String, String), gold: Gold, moves: Option[Int]): Task =
    Task.of(Kind.Plans, title, text, moves.fold(never)(m => (m.toString, m.toString)), gold,
      moves.fold("unreachable")(_.toString))

  private def ferry(title: (String, String), who: (String, String), boat: (String, String), gold: Gold,
      moves: Option[Int]): Task =
    plan(title, (s"${who._1} ${boat._1} What is the least number of crossings?",
      s"${who._2} ${boat._2} За какое наименьшее число переправ это можно сделать?"), gold, moves)
  private val wolf = (
    "A farmer has to take a wolf, a goat and a cabbage across a river. Left without the farmer, the wolf eats " +
      "the goat and the goat eats the cabbage.",
    "Крестьянину нужно перевезти через реку волка, козу и капусту. Без крестьянина волк съест козу, " +
      "а коза съест капусту.")
  private val wolfCharges = Vector("wolf", "goat", "cabbage")

  private def hanoi(disks: Int, moves: Int): Task =
    plan((s"The tower of Hanoi: $disks disks", s"Ханойская башня: $disks ${if disks < 5 then "диска" else "дисков"}"),
      (s"The tower of Hanoi: $disks disks of different sizes are stacked on the first of three pegs, the largest " +
        "at the bottom. One move takes the top disk of a peg onto another peg, and a larger disk is never put on " +
        "a smaller one. What is the least number of moves that brings the whole tower to the third peg?",
        s"Ханойская башня: $disks ${if disks < 5 then "диска" else "дисков"} разного размера надеты на первый из " +
          "трёх стержней, самый большой внизу. За один ход верхний диск одного стержня переносят на другой " +
          "стержень, и больший диск нельзя класть на меньший. За какое наименьшее число ходов всю башню можно " +
          "перенести на третий стержень?"), tower(disks, false), Some(moves))

  private val button = (
    "Each lamp has its own button; pressing it switches that lamp and the lamps next to it: one that was on " +
      "goes off, one that was off goes on.",
    "У каждой лампы своя кнопка; нажатие переключает эту лампу и соседние с ней: горевшая гаснет, " +
      "не горевшая загорается.")
  private val jug = (
    "both empty, and a tap. One action fills a jug from the tap, empties a jug, or pours one jug into the other " +
      "until the first is empty or the second is full.",
    "оба пустые, и кран. Одно действие — наполнить кувшин из крана, вылить кувшин или переливать из одного " +
      "кувшина в другой, пока первый не опустеет или второй не наполнится.")

  val tasks: Vector[Task] = Vector(
    ferry(("The wolf, the goat and the cabbage", "Волк, коза и капуста"), wolf,
      ("The boat holds the farmer and at most one of the three.",
        "В лодку помещается крестьянин и не больше одного из троих."),
      river(wolfCharges, (0, 1), "wolf" -> "goat", "goat" -> "cabbage"), Some(7)),
    ferry(("The fox, the goose and the grain", "Лиса, гусь и зерно"),
      ("A woman has to take a fox, a goose and a sack of grain across a river. Left without her, the fox eats " +
        "the goose and the goose eats the grain.",
        "Женщине нужно перевезти через реку лису, гуся и мешок зерна. Без неё лиса съест гуся, а гусь съест зерно."),
      ("The boat holds her and at most one of the three.", "В лодку помещается она и не больше одного из троих."),
      river(Vector("fox", "goose", "grain"), (0, 1), "fox" -> "goose", "goose" -> "grain"), Some(7)),
    ferry(("The wolf, the goat and the cabbage: a bigger boat", "Волк, коза и капуста: лодка побольше"), wolf,
      ("The boat holds the farmer and at most two of the three.",
        "В лодку помещается крестьянин и не больше двух из троих."),
      river(wolfCharges, (0, 2), "wolf" -> "goat", "goat" -> "cabbage"), Some(3)),
    ferry(("The wolf, the goat and the cabbage: never an empty boat", "Волк, коза и капуста: лодка не ходит пустой"),
      wolf,
      ("The boat holds the farmer and exactly one of the three: he never crosses alone.",
        "В лодку помещается крестьянин и ровно один из троих: в одиночку он не переправляется."),
      river(wolfCharges, (1, 1), "wolf" -> "goat", "goat" -> "cabbage"), None),
    plan(("Jugs of 3 and 5 litres", "Кувшины на 3 и 5 литров"),
      (s"There is a 3-litre jug and a 5-litre jug, ${jug._1} What is the least number of actions after which " +
        "the 5-litre jug holds exactly 4 litres and the 3-litre jug is empty?",
        s"Есть кувшин на 3 литра и кувшин на 5 литров, ${jug._2} За какое наименьшее число действий можно " +
          "получить ровно 4 литра в пятилитровом кувшине при пустом трёхлитровом?"), jugs(3, 5, (0, 4)), Some(7)),
    plan(("Jugs of 4 and 7 litres", "Кувшины на 4 и 7 литров"),
      (s"There is a 4-litre jug and a 7-litre jug, ${jug._1} What is the least number of actions after which " +
        "the 7-litre jug holds exactly 5 litres and the 4-litre jug is empty?",
        s"Есть кувшин на 4 литра и кувшин на 7 литров, ${jug._2} За какое наименьшее число действий можно " +
          "получить ровно 5 литров в семилитровом кувшине при пустом четырёхлитровом?"), jugs(4, 7, (0, 5)), Some(8)),
    plan(("Jugs of 4 and 6 litres", "Кувшины на 4 и 6 литров"),
      (s"There is a 4-litre jug and a 6-litre jug, ${jug._1} What is the least number of actions after which " +
        "the 6-litre jug holds exactly 3 litres and the 4-litre jug is empty?",
        s"Есть кувшин на 4 литра и кувшин на 6 литров, ${jug._2} За какое наименьшее число действий можно " +
          "получить ровно 3 литра в шестилитровом кувшине при пустом четырёхлитровом?"), jugs(4, 6, (0, 3)), None),
    hanoi(2, 3), hanoi(3, 7), hanoi(4, 15), hanoi(5, 31),
    plan(("The tower of Hanoi: only to the next peg", "Ханойская башня: только на соседний стержень"),
      ("Three disks of different sizes are stacked on the first of three pegs standing in a row, the largest at " +
        "the bottom. One move takes the top disk of a peg onto the peg next to it, and a larger disk is never put " +
        "on a smaller one. What is the least number of moves that brings the whole tower to the third peg?",
        "Три диска разного размера надеты на первый из трёх стержней, стоящих в ряд, самый большой внизу. " +
          "За один ход верхний диск одного стержня переносят на соседний стержень, и больший диск нельзя класть " +
          "на меньший. За какое наименьшее число ходов всю башню можно перенести на третий стержень?"),
      tower(3, true), Some(26)),
    plan(("Four lamps in a row", "Четыре лампы в ряд"),
      (s"Four lamps stand in a row, all off. ${button._1} What is the least number of presses that lights all four?",
        s"Четыре лампы стоят в ряд, все выключены. ${button._2} За какое наименьшее число нажатий можно зажечь " +
          "все четыре?"), lamps(Vector(0, 0, 0, 0), false), Some(2)),
    plan(("Five lamps, the middle one on", "Пять ламп, средняя горит"),
      (s"Five lamps stand in a row; only the middle one is on. ${button._1} What is the least number of presses " +
        "that lights all five?",
        s"Пять ламп стоят в ряд; горит только средняя. ${button._2} За какое наименьшее число нажатий можно " +
          "зажечь все пять?"), lamps(Vector(0, 0, 1, 0, 0), false), Some(2)),
    plan(("Five lamps in a ring", "Пять ламп по кругу"),
      (s"Five lamps stand in a ring, all off. ${button._1} What is the least number of presses that lights all five?",
        s"Пять ламп стоят по кругу, все выключены. ${button._2} За какое наименьшее число нажатий можно зажечь " +
          "все пять?"), lamps(Vector(0, 0, 0, 0, 0), true), Some(5)),
    plan(("Three glasses, two at a time", "Три стакана, по два за раз"),
      ("Three glasses stand upside down. One move turns over exactly two of them. What is the least number of " +
        "moves after which all three stand upright?",
        "Три стакана стоят вверх дном. За один ход переворачивают ровно два из них. За какое наименьшее число " +
          "ходов можно поставить все три правильно?"), glasses(3, 2), None),
    plan(("Four glasses, three at a time", "Четыре стакана, по три за раз"),
      ("Four glasses stand upside down. One move turns over exactly three of them. What is the least number of " +
        "moves after which all four stand upright?",
        "Четыре стакана стоят вверх дном. За один ход переворачивают ровно три из них. За какое наименьшее число " +
          "ходов можно поставить все четыре правильно?"), glasses(4, 3), Some(4)),
    plan(("A lift with two buttons", "Лифт с двумя кнопками"),
      ("In a house of 10 floors the lift has two buttons: one takes it 3 floors up, the other 2 floors down; a " +
        "button that would take it out of the house does nothing. What is the least number of presses that takes " +
        "the lift from floor 1 to floor 5?",
        "В доме 10 этажей, у лифта две кнопки: одна поднимает его на 3 этажа, другая опускает на 2; кнопка, " +
          "которая вывела бы лифт за пределы дома, не срабатывает. За какое наименьшее число нажатий можно " +
          "попасть с 1 этажа на 5?"), lift(10, 3, 2, 1, 5), Some(3)),
    plan(("A lift: the long way round", "Лифт: длинный путь"),
      ("In a house of 8 floors the lift has two buttons: one takes it 5 floors up, the other 3 floors down; a " +
        "button that would take it out of the house does nothing. What is the least number of presses that takes " +
        "the lift from floor 1 to floor 2?",
        "В доме 8 этажей, у лифта две кнопки: одна поднимает его на 5 этажей, другая опускает на 3; кнопка, " +
          "которая вывела бы лифт за пределы дома, не срабатывает. За какое наименьшее число нажатий можно " +
          "попасть с 1 этажа на 2?"), lift(8, 5, 3, 1, 2), Some(5)),
    plan(("A lift that cannot get there", "Лифт, который не доедет"),
      ("In a house of 9 floors the lift has two buttons: one takes it 4 floors up, the other 6 floors down; a " +
        "button that would take it out of the house does nothing. What is the least number of presses that takes " +
        "the lift from floor 1 to floor 2?",
        "В доме 9 этажей, у лифта две кнопки: одна поднимает его на 4 этажа, другая опускает на 6; кнопка, " +
          "которая вывела бы лифт за пределы дома, не срабатывает. За какое наименьшее число нажатий можно " +
          "попасть с 1 этажа на 2?"), lift(9, 4, 6, 1, 2), None),
    plan(("Two frogs", "Две лягушки"),
      ("Three stones lie in a row. A green frog sits on the left stone and a brown frog on the right one; the " +
        "middle stone is free. The green frog moves only to the right, the brown one only to the left. One move " +
        "is a step onto the free stone next to a frog, or a jump over one frog onto the free stone behind it. " +
        "What is the least number of moves after which the frogs have changed places?",
        "Три камня лежат в ряд. Зелёная лягушка сидит на левом камне, коричневая — на правом; средний камень " +
          "свободен. Зелёная движется только вправо, коричневая — только влево. Один ход — шаг на соседний " +
          "свободный камень или прыжок через одну лягушку на свободный камень за ней. За какое наименьшее число " +
          "ходов лягушки поменяются местами?"), frogs(1), Some(3)),
    plan(("Four frogs", "Четыре лягушки"),
      ("Five stones lie in a row. Two green frogs sit on the two left stones and two brown frogs on the two right " +
        "ones; the middle stone is free. Green frogs move only to the right, brown ones only to the left. One " +
        "move is a step onto the free stone next to a frog, or a jump over one frog onto the free stone behind " +
        "it. What is the least number of moves after which the green and the brown frogs have changed places?",
        "Пять камней лежат в ряд. Две зелёные лягушки сидят на двух левых камнях, две коричневые — на двух " +
          "правых; средний камень свободен. Зелёные движутся только вправо, коричневые — только влево. Один " +
          "ход — шаг на соседний свободный камень или прыжок через одну лягушку на свободный камень за ней. " +
          "За какое наименьшее число ходов зелёные и коричневые лягушки поменяются местами?"), frogs(2), Some(8)),
    plan(("Three guards and three robbers", "Три стражника и три разбойника"),
      ("Three guards and three robbers have to cross a river in a boat that holds one or two people and cannot " +
        "cross empty. On neither bank may the robbers outnumber the guards who are there. What is the least " +
        "number of crossings?",
        "Трём стражникам и трём разбойникам нужно переправиться через реку в лодке, которая вмещает одного или " +
          "двух человек и пустой не ходит. Ни на одном берегу разбойников не должно быть больше, чем стражников, " +
          "если стражники там есть. За какое наименьшее число переправ это можно сделать?"), crossing(3), Some(11)),
    plan(("Two guards and two robbers", "Два стражника и два разбойника"),
      ("Two guards and two robbers have to cross a river in a boat that holds one or two people and cannot cross " +
        "empty. On neither bank may the robbers outnumber the guards who are there. What is the least number of " +
        "crossings?",
        "Двум стражникам и двум разбойникам нужно переправиться через реку в лодке, которая вмещает одного или " +
          "двух человек и пустой не ходит. Ни на одном берегу разбойников не должно быть больше, чем стражников, " +
          "если стражники там есть. За какое наименьшее число переправ это можно сделать?"), crossing(2), Some(5)),
    plan(("Three cards", "Три карточки"),
      ("Three cards lie in a row in the order 3, 2, 1. One move swaps two cards lying next to each other. What " +
        "is the least number of moves that puts them in the order 1, 2, 3?",
        "Три карточки лежат в ряд в порядке 3, 2, 1. За один ход меняют местами две соседние карточки. За какое " +
          "наименьшее число ходов их можно разложить в порядке 1, 2, 3?"), cards(Vector(3, 2, 1)), Some(3)),
    plan(("Four cards", "Четыре карточки"),
      ("Four cards lie in a row in the order 4, 3, 2, 1. One move swaps two cards lying next to each other. What " +
        "is the least number of moves that puts them in the order 1, 2, 3, 4?",
        "Четыре карточки лежат в ряд в порядке 4, 3, 2, 1. За один ход меняют местами две соседние карточки. " +
          "За какое наименьшее число ходов их можно разложить в порядке 1, 2, 3, 4?"), cards(Vector(4, 3, 2, 1)),
      Some(6)),
    plan(("Double or add one", "Удвоить или прибавить один"),
      ("A counter shows 1. One button doubles the number, the other adds 1; the counter cannot show more than " +
        "15. What is the least number of presses after which it shows 13?",
        "Счётчик показывает 1. Одна кнопка удваивает число, другая прибавляет 1; больше 15 счётчик показать " +
          "не может. За какое наименьшее число нажатий можно получить 13?"),
      model(Vector("counter" -> Gold.ints(0, 15)), Vector(1), Vector(13), Vector(
        set("counter" -> op("*", v("counter"), n(2))), set("counter" -> op("+", v("counter"), n(1))))), Some(5)),
    plan(("A knight on a small board", "Конь на маленькой доске"),
      ("A chess knight stands in a corner of a 3 by 3 board. What is the least number of moves that takes it to " +
        "the opposite corner?",
        "Шахматный конь стоит в углу доски 3 на 3. За какое наименьшее число ходов он попадёт в противоположный " +
          "угол?"), knight(3, (1, 1), (3, 3)), Some(4)),
    plan(("A knight and the centre", "Конь и центр доски"),
      ("A chess knight stands in a corner of a 3 by 3 board. What is the least number of moves that takes it to " +
        "the centre square?",
        "Шахматный конь стоит в углу доски 3 на 3. За какое наименьшее число ходов он попадёт на центральную " +
          "клетку?"), knight(3, (1, 1), (2, 2)), None))
