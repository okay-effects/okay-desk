package okay.desk.tasks

import okay.effects.json.Json

import Gold.{finite, ints, is, n, op, t, texts, v}
import Task.of

/**
 * QUANTITIES AND NUMBER PUZZLES: unknown numbers tied by what the text says of them — heads and legs, ages, coins —
 * and digits or cells to be filled in: letters for digits, a magic square, queens, a map to colour. A sum that is
 * simply written out in the task is an expression for the calculator; everything else is a finite constraint program.
 */
object Numbers:
  private def sum(xs: Json*): Json = xs.reduceLeft((a, b) => op("+", a, b))
  private def times(k: Int, x: Json): Json = op("*", n(k), x)
  private def distinct(names: Seq[String]): Json = op("distinct", names.map(v)*)
  /** the number written with these digits, the first the highest */
  private def number(digits: Seq[String]): Json =
    digits.map(v).reduceLeft((high, d) => op("+", op("*", high, n(10)), d))
  private def word(letters: String): Json = number(letters.map(_.toString))
  private val none = ("There is no such case: the conditions contradict each other",
    "Такого быть не может: условия противоречат друг другу")

  private def legs(title: (String, String), text: (String, String), answer: (String, String),
      a: (String, Int), b: (String, Int), heads: Int, feet: Int, expect: String*): Task =
    of(Kind.Quantities, title, text, answer, finite(Vector(a._1 -> ints(0, heads), b._1 -> ints(0, heads)),
      Vector(is(sum(v(a._1), v(b._1)), n(heads)), is(sum(times(a._2, v(a._1)), times(b._2, v(b._1))), n(feet))),
      a._1 -> v(a._1), b._1 -> v(b._1)), expect*)
  private def counted(title: (String, String), text: (String, String), expression: String, value: String): Task =
    of(Kind.Quantities, title, text, (value, value), Gold.Sum(expression), value)

  val quantities: Vector[Task] = Vector(
    legs(("Chickens and rabbits", "Куры и кролики"),
      ("There are chickens and rabbits in a yard: 10 heads and 28 legs in all. How many chickens and how many " +
        "rabbits are there?",
        "Во дворе куры и кролики: всего 10 голов и 28 ног. Сколько кур и сколько кроликов?"),
      ("6 chickens and 4 rabbits", "6 кур и 4 кролика"), "chickens" -> 2, "rabbits" -> 4, 10, 28, "6", "4"),
    legs(("Chickens and rabbits: a miscount", "Куры и кролики: ошибка в счёте"),
      ("There are chickens and rabbits in a yard: 10 heads and 27 legs in all. How many chickens and how many " +
        "rabbits are there?",
        "Во дворе куры и кролики: всего 10 голов и 27 ног. Сколько кур и сколько кроликов?"), none,
      "chickens" -> 2, "rabbits" -> 4, 10, 27, "none"),
    legs(("Spiders and beetles", "Пауки и жуки"),
      ("A boy keeps spiders and beetles in a box: 8 of them in all, with 54 legs. A spider has 8 legs and a " +
        "beetle has 6. How many beetles and how many spiders are there?",
        "Мальчик держит в коробке пауков и жуков: всего 8 штук, и у них 54 ноги. У паука 8 ног, у жука 6. " +
          "Сколько жуков и сколько пауков?"),
      ("5 beetles and 3 spiders", "5 жуков и 3 паука"), "beetles" -> 6, "spiders" -> 8, 8, 54, "5", "3"),
    legs(("Stools and chairs", "Табуреты и стулья"),
      ("A room has three-legged stools and four-legged chairs: 9 seats and 31 legs in all. How many chairs and " +
        "how many stools are there?",
        "В комнате трёхногие табуреты и четырёхногие стулья: всего 9 мест и 31 ножка. Сколько стульев и сколько " +
          "табуретов?"),
      ("4 chairs and 5 stools", "4 стула и 5 табуретов"), "chairs" -> 4, "stools" -> 3, 9, 31, "4", "5"),
    legs(("Coins of two kinds", "Монеты двух видов"),
      ("There are 7 coins on the table, some worth 2 and the others worth 5; together they are worth 26. How " +
        "many coins of 5 and how many coins of 2 are there?",
        "На столе 7 монет, одни достоинством 2, другие — 5; вместе они составляют 26. Сколько монет по 5 и " +
          "сколько по 2?"),
      ("4 coins of 5 and 3 coins of 2", "4 монеты по 5 и 3 монеты по 2"), "fives" -> 5, "twos" -> 2, 7, 26, "4", "3"),
    of(Kind.Quantities, ("Three times as old", "Втрое старше"),
      ("Anna is three times as old as Boris. In ten years she will be twice as old as he is. How old is Boris now?",
        "Анна втрое старше Бориса. Через десять лет она будет вдвое старше него. Сколько лет Борису сейчас?"),
      ("10", "10"), finite(Vector("boris" -> ints(1, 20), "anna" -> ints(1, 32)),
        Vector(is(v("anna"), times(3, v("boris"))), is(sum(v("anna"), n(10)), times(2, sum(v("boris"), n(10))))),
        "boris" -> v("boris")), "10"),
    of(Kind.Quantities, ("A father and a son", "Отец и сын"),
      ("A father is 40 and his son is 10. In how many years will the father be three times as old as the son?",
        "Отцу 40 лет, сыну 10. Через сколько лет отец будет втрое старше сына?"), ("5", "5"),
      finite(Vector("years" -> ints(0, 30)),
        Vector(is(sum(n(40), v("years")), times(3, sum(n(10), v("years"))))), "years" -> v("years")), "5"),
    of(Kind.Quantities, ("Sum and difference", "Сумма и разность"),
      ("The sum of two numbers is 20 and their difference is 4. What are the numbers?",
        "Сумма двух чисел равна 20, а разность — 4. Какие это числа?"), ("12 and 8", "12 и 8"),
      finite(Vector("larger" -> ints(0, 20), "smaller" -> ints(0, 20)),
        Vector(is(sum(v("larger"), v("smaller")), n(20)), is(op("-", v("larger"), v("smaller")), n(4))),
        "larger" -> v("larger"), "smaller" -> v("smaller")), "12", "8"),
    of(Kind.Quantities, ("Three numbers in a row", "Три числа подряд"),
      ("The sum of three consecutive whole numbers is 48. What is the smallest of them?",
        "Сумма трёх идущих подряд целых чисел равна 48. Каково наименьшее из них?"), ("15", "15"),
      finite(Vector("smallest" -> ints(1, 30)),
        Vector(is(sum(v("smallest"), sum(v("smallest"), n(1)), sum(v("smallest"), n(2))), n(48))),
        "smallest" -> v("smallest")), "15"),
    of(Kind.Quantities, ("Two shelves", "Две полки"),
      ("The top shelf holds three times as many books as the bottom one. After 8 books are moved from the top " +
        "shelf to the bottom one, both hold the same number. How many books were on each shelf at first?",
        "На верхней полке втрое больше книг, чем на нижней. Когда с верхней полки на нижнюю переставили 8 книг, " +
          "на полках стало поровну. Сколько книг было на каждой полке сначала?"),
      ("8 on the bottom shelf and 24 on the top one", "8 на нижней и 24 на верхней"),
      finite(Vector("bottom" -> ints(1, 32), "top" -> ints(1, 32)),
        Vector(is(v("top"), times(3, v("bottom"))), is(op("-", v("top"), n(8)), sum(v("bottom"), n(8)))),
        "bottom" -> v("bottom"), "top" -> v("top")), "8", "24"),
    of(Kind.Quantities, ("A remainder of two", "Остаток два"),
      ("A whole number is greater than 10 and less than 30. Divided by 3 it leaves a remainder of 2, and divided " +
        "by 5 it also leaves a remainder of 2. What is the number?",
        "Целое число больше 10 и меньше 30. При делении на 3 оно даёт остаток 2, и при делении на 5 тоже даёт " +
          "остаток 2. Что это за число?"), ("17", "17"),
      finite(Vector("number" -> ints(11, 29), "threes" -> ints(0, 9), "fives" -> ints(0, 5)),
        Vector(is(v("number"), sum(times(3, v("threes")), n(2))), is(v("number"), sum(times(5, v("fives")), n(2)))),
        "number" -> v("number")), "17"),
    of(Kind.Quantities, ("The larger of two", "Большее из двух"),
      ("Two positive whole numbers add up to 10, and the first is greater than the second. What is the first number?",
        "Два положительных целых числа в сумме дают 10, и первое больше второго. Каково первое число?"),
      ("It is not decided: 6, 7, 8 or 9", "Однозначно не определяется: 6, 7, 8 или 9"),
      finite(Vector("first" -> ints(1, 9), "second" -> ints(1, 9)),
        Vector(is(sum(v("first"), v("second")), n(10)), op(">", v("first"), v("second"))), "first" -> v("first")),
      "6|7|8|9"),
    counted(("A class and two clubs", "Класс и два кружка"),
      ("There are 25 pupils in a class. 15 of them play chess, 12 play football, and 5 play both. How many " +
        "pupils play neither?",
        "В классе 25 учеников. 15 из них играют в шахматы, 12 — в футбол, а 5 — и в то, и в другое. Сколько " +
          "учеников не играют ни во что?"), "25 - (15 + 12 - 5)", "3"),
    counted(("Notebooks and pens", "Тетради и ручки"),
      ("A notebook costs 12 and a pen costs 3. How much do 4 notebooks and 5 pens cost together?",
        "Тетрадь стоит 12, ручка — 3. Сколько стоят вместе 4 тетради и 5 ручек?"), "4 * 12 + 5 * 3", "63"),
    counted(("A journey in two parts", "Путь из двух частей"),
      ("A car drives for 3 hours at 60 km/h and then for 2 hours at 80 km/h. How many kilometres does it cover?",
        "Машина едет 3 часа со скоростью 60 км/ч, а затем 2 часа со скоростью 80 км/ч. Сколько километров она " +
          "проедет?"), "3 * 60 + 2 * 80", "340"),
    counted(("Change from a hundred", "Сдача с сотни"),
      ("Vera had 100. She bought 3 books at 18 each and 2 pens at 9 each. How much does she have left?",
        "У Веры было 100. Она купила 3 книги по 18 и 2 ручки по 9. Сколько у неё осталось?"),
      "100 - (3 * 18 + 2 * 9)", "28"),
    counted(("Boxes of apples", "Ящики с яблоками"),
      ("There are 5 boxes with 12 apples in each. In every box 2 apples have gone bad. How many good apples are " +
        "there in all?",
        "Есть 5 ящиков, в каждом по 12 яблок. В каждом ящике 2 яблока испортились. Сколько всего хороших яблок?"),
      "5 * (12 - 2)", "50"))

  private val letters = ("Each letter stands for a digit, different letters stand for different digits, and no " +
    "number starts with zero:", "Каждая буква обозначает цифру, разные буквы — разные цифры, и ни одно число не " +
    "начинается с нуля:")
  private def digits(names: String): Vector[(String, Vector[Json])] = names.toVector.map(c => c.toString -> ints(0, 9))
  private def leading(names: String): Vector[Json] = names.toVector.map(c => op("!=", v(c.toString), n(0)))
  private def cipher(sum: String, asked: String, value: Int, vars: Vector[(String, Vector[Json])],
      where: Vector[Json]): Task =
    of(Kind.Numbers, (sum, sum), (s"${letters._1} $sum. Which number is $asked?",
      s"${letters._2} $sum. Какое число обозначено как $asked?"), (value.toString, value.toString),
      finite(vars, where, asked -> word(asked)), value.toString)

  private def cell(row: Int, column: Int): String = s"r${row}c$column"
  /** a square grid of cells, each a digit of 1..size; a given cell has only its value to take */
  private def grid(fixed: Vector[String]): Vector[(String, Vector[Json])] =
    val size = fixed.size
    for row <- (1 to size).toVector; column <- 1 to size yield
      val c = fixed(row - 1)(column - 1)
      cell(row, column) -> (if c == '.' then ints(1, size) else Vector(n(c.asDigit)))
  private def lines(size: Int): Vector[Json] =
    (1 to size).toVector.flatMap(i =>
      Vector(distinct((1 to size).map(cell(i, _))), distinct((1 to size).map(cell(_, i)))))
  private def row(size: Int, i: Int): Json = number((1 to size).map(cell(i, _)))
  private def drawn(fixed: Vector[String]): String = fixed.map(_.mkString(" ")).mkString("\n")

  private def queens(size: Int, fixed: Map[Int, Int], columns: String): Task =
    def queen(i: Int): String = s"queen$i"
    val apart = for i <- 1 to size; j <- i + 1 to size yield
      op("!=", op("abs", op("-", v(queen(i)), v(queen(j)))), n(j - i))
    val known = fixed.toVector.sorted
    val en = known.map((r, c) => s"the queen of row $r stands in column $c").mkString(" and ")
    val ru = known.map((r, c) => s"ферзь строки $r стоит в столбце $c").mkString(", а ")
    of(Kind.Numbers, (s"$size queens", s"$size ${if size < 5 then "ферзя" else "ферзей"}"),
      (s"$size chess queens stand on a $size by $size board, one in every row, and no two attack each other: no " +
        s"two share a column or a diagonal. It is known that $en. In which columns do the queens of rows 1 to " +
        s"$size stand?",
        s"$size ${if size < 5 then "шахматных ферзя" else "шахматных ферзей"} стоят на доске $size на $size, по " +
          s"одному в каждой строке, и никакие два не бьют друг друга: ни в одном столбце и ни на одной диагонали " +
          s"нет двух ферзей. Известно, что $ru. В каких столбцах стоят ферзи строк с 1 по $size?"),
      (columns.mkString(", "), columns.mkString(", ")),
      finite((1 to size).toVector.map(i => queen(i) -> fixed.get(i).fold(ints(1, size))(c => Vector(n(c)))),
        distinct((1 to size).map(queen)) +: apart.toVector, "columns" -> number((1 to size).map(queen))), columns)

  private val regions = Vector("north", "east", "south", "west")
  private def colours(title: (String, String), text: (String, String), answer: (String, String),
      borders: Vector[(String, String)], known: Vector[(String, String)], asked: Vector[String],
      expect: String*): Task =
    of(Kind.Numbers, title, text, answer, finite(regions.map(_ -> texts("red", "green", "blue")),
      known.map((r, c) => is(v(r), t(c))) ++ borders.map((a, b) => op("!=", v(a), v(b))), asked.map(r => r -> v(r))*),
      expect*)

  private val magic = Vector("a", "b", "c", "d", "e", "f", "g", "h", "i")
  private val sudoku = Vector("1..4", ".41.", "2..3", ".32.")
  private val latin = Vector("1..", ".3.", "...")

  val numbers: Vector[Task] = Vector(
    cipher("TO + GO = OUT", "OUT", 102, digits("TOGU"),
      Vector(distinct("TOGU".map(_.toString)), is(sum(word("TO"), word("GO")), word("OUT"))) ++ leading("TGO")),
    cipher("AB + BA = CBC", "AB", 92, digits("ABC"),
      Vector(distinct("ABC".map(_.toString)), is(sum(word("AB"), word("BA")), word("CBC"))) ++ leading("ABC")),
    cipher("ABC × 3 = BBB", "ABC", 148, digits("ABC"),
      Vector(distinct("ABC".map(_.toString)), is(times(3, word("ABC")), word("BBB"))) ++ leading("AB")),
    cipher("SEND + MORE = MONEY", "MONEY", 10652,
      Vector("D", "E", "Y", "carry1", "N", "R", "carry2", "O", "carry3", "S", "M", "carry4")
        .map(k => k -> (if k.startsWith("carry") then ints(0, 1) else ints(0, 9))),
      Vector(distinct("SENDMORY".map(_.toString)),
        is(sum(v("D"), v("E")), sum(v("Y"), times(10, v("carry1")))),
        is(sum(v("N"), v("R"), v("carry1")), sum(v("E"), times(10, v("carry2")))),
        is(sum(v("E"), v("O"), v("carry2")), sum(v("N"), times(10, v("carry3")))),
        is(sum(v("S"), v("M"), v("carry3")), sum(v("O"), times(10, v("carry4")))),
        is(v("M"), v("carry4"))) ++ leading("SM")),
    of(Kind.Numbers, ("A magic square", "Магический квадрат"),
      ("The digits 1 to 9 are written into a 3 by 3 square, each once, so that every row, every column and both " +
        "diagonals add up to 15. The top row starts with 2 and 7. Which digit is in the centre, and which in the " +
        "bottom right corner?",
        "Цифры от 1 до 9 вписаны в квадрат 3 на 3, каждая по одному разу, так что сумма в каждой строке, каждом " +
          "столбце и на обеих диагоналях равна 15. Верхняя строка начинается с 2 и 7. Какая цифра стоит в центре " +
          "и какая — в правом нижнем углу?"),
      ("5 in the centre and 8 in the corner", "5 в центре и 8 в углу"),
      finite(magic.map(_ -> ints(1, 9)), Vector(is(v("a"), n(2)), is(v("b"), n(7)), distinct(magic)) ++
        Vector("abc", "def", "ghi", "adg", "beh", "cfi", "aei", "ceg").map(l =>
          is(sum(l.map(c => v(c.toString))*), n(15))), "centre" -> v("e"), "corner" -> v("i")), "5", "8"),
    of(Kind.Numbers, ("A small sudoku", "Маленькое судоку"),
      ("Fill the 4 by 4 grid with the digits 1 to 4 so that every row, every column and each of the four 2 by 2 " +
        s"corner blocks holds every digit once. A dot is an empty cell.\n${drawn(sudoku)}\n" +
        "What do the second row and the fourth row read?",
        "Заполните сетку 4 на 4 цифрами от 1 до 4 так, чтобы в каждой строке, каждом столбце и каждом из четырёх " +
          s"угловых блоков 2 на 2 каждая цифра встречалась один раз. Точка — пустая клетка.\n${drawn(sudoku)}\n" +
          "Что написано во второй и в четвёртой строке?"),
      ("3 4 1 2 and 4 3 2 1", "3 4 1 2 и 4 3 2 1"),
      finite(grid(sudoku), lines(4) ++ (for r <- Vector(1, 3); c <- Vector(1, 3) yield
        distinct(Vector(cell(r, c), cell(r, c + 1), cell(r + 1, c), cell(r + 1, c + 1)))),
        "row 2" -> row(4, 2), "row 4" -> row(4, 4)), "3412", "4321"),
    of(Kind.Numbers, ("A Latin square", "Латинский квадрат"),
      ("Fill the 3 by 3 grid with the digits 1 to 3 so that every row and every column holds every digit once. " +
        s"A dot is an empty cell.\n${drawn(latin)}\nWhat does the bottom row read?",
        "Заполните сетку 3 на 3 цифрами от 1 до 3 так, чтобы в каждой строке и каждом столбце каждая цифра " +
          s"встречалась один раз. Точка — пустая клетка.\n${drawn(latin)}\nЧто написано в нижней строке?"),
      ("3 1 2", "3 1 2"), finite(grid(latin), lines(3), "row 3" -> row(3, 3)), "312"),
    queens(4, Map(1 -> 2), "2413"),
    queens(5, Map(1 -> 1, 2 -> 3), "13524"),
    queens(6, Map(1 -> 2), "246135"),
    queens(8, Map(1 -> 1, 2 -> 5), "15863724"),
    colours(("Colouring a map", "Раскраска карты"),
      ("An island is divided into four regions: North, East, South and West. North borders East and South; East " +
        "borders South and West; South borders West. Each region is coloured red, green or blue, and regions with " +
        "a common border have different colours. North is red and East is green. What colours are South and West?",
        "Остров разделён на четыре области: Северную, Восточную, Южную и Западную. Северная граничит с Восточной " +
          "и Южной; Восточная — с Южной и Западной; Южная — с Западной. Каждая область закрашена красным, зелёным " +
          "или синим, и области с общей границей закрашены по-разному. Северная — красная, Восточная — зелёная. " +
          "Какого цвета Южная и Западная?"),
      ("South is blue and West is red", "Южная — синяя, Западная — красная"),
      Vector("north" -> "east", "north" -> "south", "east" -> "south", "east" -> "west", "south" -> "west"),
      Vector("north" -> "red", "east" -> "green"), Vector("south", "west"), "blue", "red"),
    colours(("Colouring a map: one colour short", "Раскраска карты: не хватает цвета"),
      ("An island is divided into four regions: North, East, South and West, and every two of them have a common " +
        "border. Each region is coloured red, green or blue, and regions with a common border have different " +
        "colours. North is red. What colour is West?",
        "Остров разделён на четыре области: Северную, Восточную, Южную и Западную, и любые две из них имеют общую " +
          "границу. Каждая область закрашена красным, зелёным или синим, и области с общей границей закрашены " +
          "по-разному. Северная — красная. Какого цвета Западная?"), none,
      regions.combinations(2).map(p => p(0) -> p(1)).toVector, Vector("north" -> "red"), Vector("west"), "none"),
    of(Kind.Numbers, ("Twice the product of its digits", "Вдвое больше произведения цифр"),
      ("A two-digit number is twice the product of its digits. What is the number?",
        "Двузначное число вдвое больше произведения своих цифр. Что это за число?"), ("36", "36"),
      finite(Vector("tens" -> ints(1, 9), "units" -> ints(0, 9)),
        Vector(is(number(Vector("tens", "units")), times(2, op("*", v("tens"), v("units"))))),
        "number" -> number(Vector("tens", "units"))), "36"),
    of(Kind.Numbers, ("Digits the other way round", "Цифры наоборот"),
      ("The digits of a two-digit number add up to 11. Written the other way round, the number is 45 greater. " +
        "What is the number?",
        "Сумма цифр двузначного числа равна 11. Если записать его цифры в обратном порядке, число станет на 45 " +
          "больше. Что это за число?"), ("38", "38"),
      finite(Vector("tens" -> ints(1, 9), "units" -> ints(1, 9)),
        Vector(is(sum(v("tens"), v("units")), n(11)),
          is(number(Vector("units", "tens")), sum(number(Vector("tens", "units")), n(45)))),
        "number" -> number(Vector("tens", "units"))), "38"),
    of(Kind.Numbers, ("Sum equal to product", "Сумма равна произведению"),
      ("Three different positive whole numbers below 10 have a sum equal to their product. What is the largest " +
        "of them?",
        "Сумма трёх разных положительных целых чисел, меньших 10, равна их произведению. Каково наибольшее из них?"),
      ("3", "3"),
      finite(Vector("least" -> ints(1, 9), "middle" -> ints(1, 9), "largest" -> ints(1, 9)),
        Vector(op("<", v("least"), v("middle")), op("<", v("middle"), v("largest")),
          is(sum(v("least"), v("middle"), v("largest")), op("*", op("*", v("least"), v("middle")), v("largest")))),
        "largest" -> v("largest")), "3"),
    of(Kind.Numbers, ("A four-digit code", "Код из четырёх цифр"),
      ("A code has four digits. The first digit is twice the second. The third is the sum of the first two. The " +
        "fourth is one less than the second. All four add up to 20. What is the code?",
        "Код состоит из четырёх цифр. Первая цифра вдвое больше второй. Третья равна сумме первых двух. " +
          "Четвёртая на единицу меньше второй. Сумма всех четырёх равна 20. Каков код?"), ("6392", "6392"),
      finite(Vector("second", "first", "third", "fourth").map(_ -> ints(0, 9)),
        Vector(is(v("first"), times(2, v("second"))), is(v("third"), sum(v("first"), v("second"))),
          is(v("fourth"), op("-", v("second"), n(1))),
          is(sum(v("first"), v("second"), v("third"), v("fourth")), n(20))),
        "code" -> number(Vector("first", "second", "third", "fourth"))), "6392"))
