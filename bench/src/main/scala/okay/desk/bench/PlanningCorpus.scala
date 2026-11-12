package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.effects.json.Json
import mlx.llm.Chat

/** Offline supervision for different finite-state problems. No vocabulary or task templates run in the chat. */
object PlanningCorpus:
  private def strings(xs: Vector[String]): Json = Json.Arr(xs.map(Json.Str(_)))
  private def bits(xs: Vector[Int]): Json = Json.Arr(xs.map(n => Json.Num(n)))
  private def eq(a: String, b: String): Json = Json.Arr(Vector(Json.Str("="), Json.Str(a), Json.Str(b)))
  def transport(labels: Vector[String], unsafe: Vector[(Int, Int)], capacity: Int,
      initial: Vector[Int], russian: Boolean, phrasing: Int = 0,
      target: Option[Vector[Int]] = None, allowAlone: Boolean = true): SemanticCorpus.Pair =
    val ids = "f" +: labels.tail.indices.map(i => ('a' + i).toChar.toString).toVector
    val goal = target.getOrElse(Vector.fill(ids.size)(1))
    val moves = Vector(Json.obj("flip" -> strings(Vector("f")), "choose" -> Json.obj(
      "vars" -> strings(ids.tail), "min" -> Json.Num(if allowAlone then 0 else 1),
      "max" -> Json.Num(capacity), "equal" -> Json.Str("f"))))
    val safe = unsafe.map { (a, b) =>
      Json.Arr(Vector(Json.Str("or"), Json.Arr(Vector(Json.Str("!="), Json.Str(ids(a + 1)),
        Json.Str(ids(b + 1)))), eq("f", ids(a + 1))))
    }
    val program = Json.print(Json.obj("plan" -> Json.obj("vars" -> strings(ids),
      "labels" -> Json.Obj(ids.zip(labels.map(Json.Str(_)))), "start" -> bits(initial),
      "goal" -> bits(goal), "moves" -> Json.Arr(moves), "safe" -> Json.Arr(safe))))
    val f = labels.head
    val limit = if phrasing < 2 then capacity.toString else if russian then
      (if capacity == 1 then "одного" else "двух") else (if capacity == 1 then "one" else "two")
    val left = labels.zip(initial).collect { case (label, 0) => label }.mkString(", ")
    val right = labels.zip(initial).collect { case (label, 1) => label }.mkString(", ")
    val placement = if initial.forall(_ == 0) then
      (if russian then "Сначала все находятся на левом берегу." else "Initially everyone is on the left bank.")
    else if initial.forall(_ == 1) then
      (if russian then "Сначала все находятся на правом берегу." else "Initially everyone is on the right bank.")
    else if russian then s"Вначале слева: $left. Справа: $right."
    else s"Initially on the left: $left. On the right: $right."
    val restrictions = unsafe.map { (a, b) =>
      if russian then s"Если $f не рядом, нельзя оставлять вместе ${labels(a + 1)} и ${labels(b + 1)}."
      else s"Without $f, ${labels(a + 1)} and ${labels(b + 1)} must not share either bank."
    }.mkString(" ")
    val finish = if goal.forall(_ == 1) then
      (if russian then "Цель: все на правом берегу." else "Everyone must finish on the right bank.")
    else if goal.forall(_ == 0) then
      (if russian then "Цель: все на левом берегу." else "Everyone must finish on the left bank.")
    else
      val a = labels.zip(goal).collect { case (name, 0) => name }.mkString(", ")
      val b = labels.zip(goal).collect { case (name, 1) => name }.mkString(", ")
      if russian then s"В конце слева должны находиться: $a; справа: $b."
      else s"At the end the left bank must contain $a; the right bank must contain $b."
    val minimum = if allowAlone then "" else if russian then "Поездки без груза запрещены. "
      else "Every crossing must carry at least one item besides the operator. "
    val ordinary = if russian then
      (if phrasing % 2 == 0 then s"$f перевозит через реку: ${labels.tail.mkString(", ")}. "
        else s"Нужно переправить через реку ${labels.tail.mkString(", ")}; лодкой управляет $f. ") +
        s"$placement В каждой поездке в лодке обязательно находится $f и не более $limit из остальных. " +
        s"$minimum $restrictions $finish Найдите кратчайшую безопасную последовательность поездок."
    else s"$f must ferry ${labels.tail.mkString(", ")} across a river. $placement " +
      s"The boat must always contain $f and at most $limit other items. $minimum $restrictions " +
      s"$finish Find the shortest safe sequence of crossings."
    val inventory = labels.tail.mkString(", ")
    val journey = if russian then phrasing % 5 match
      case 0 => s"После покупок у воды остановился $f. С ним $inventory. Нужно доставить их через реку. "
      case 1 => s"$f возвращается домой. Его груз: $inventory. На пути река, есть только маленькая лодка. "
      case 2 => s"Помогите организовать переправу. Участники: $f, $inventory. Лодку может вести только $f. "
      case 3 => s"$f купил груз: $inventory. Возвращаясь домой, он подошёл к реке с небольшой лодкой. "
      case _ => s"На берегу ждут $f и $inventory. Моста нет. Как выполнить переправу на лодке? "
    else phrasing % 5 match
      case 0 => s"On the way home, $f reaches a river with $inventory. There is a small boat but no bridge. "
      case 1 => s"After visiting the market, $f needs to take $inventory across a river using a boat. "
      case 2 => s"Arrange a crossing for $f and $inventory. Only $f can operate the boat. "
      case 3 => s"$f bought these items at a market: $inventory. On the return journey there is a river. "
      case _ => s"Help $f deliver $inventory to the opposite bank. The only way across is a small boat. "
    val space = if russian then
      s"Помимо того, кто гребёт ($f), лодка вмещает не больше $limit предметов груза. "
    else s"Besides its operator ($f), the boat holds at most $limit cargo items. "
    val narrative = if phrasing < 4 then ordinary else
      journey + placement + " " + space + minimum + restrictions + " " + finish +
        (if russian then " Какой кратчайший безопасный план?" else " What is a shortest safe plan?")
    val implicitStart = if initial.forall(_ == 0) then "" else placement + " "
    val implicitGoal = if goal.forall(_ == 1) then
      (if russian then "Как доставить весь груз на другой берег?" else "How can all the cargo reach the other bank?")
    else finish + (if russian then " Как это сделать?" else " How can this be done?")
    val prohibitions = unsafe.map { (a, b) =>
      if russian then s"Нельзя оставлять наедине ${labels(a + 1)} и ${labels(b + 1)} без $f."
      else s"Do not leave ${labels(a + 1)} alone with ${labels(b + 1)} unless $f is there too."
    }.mkString(" ")
    val implicitStory = if russian then
      s"$f оказался у реки с грузом: $inventory. Ему надо переправиться с этими вещами. " +
        s"Есть небольшая лодка: кроме него в ней помещается не более $limit из остальных. "
    else s"$f reaches a river carrying $inventory and needs to get across with them. " +
      s"A small boat can hold its operator and at most $limit of the other items. "
    val question = if phrasing < 12 then narrative else
      implicitStory + implicitStart + minimum + prohibitions + " " + implicitGoal
    // Inflection belongs to offline language supervision, never to the runtime interpreter.
    val accusative = Map("лиса" -> "лису", "птица" -> "птицу", "кот" -> "кота", "кролик" -> "кролика",
      "плата" -> "плату", "лампа" -> "лампу", "батарея" -> "батарею")
    val instrumental = Map("лиса" -> "лисой", "зерно" -> "зерном", "птица" -> "птицей", "сыр" -> "сыром",
      "кот" -> "котом", "кролик" -> "кроликом", "датчик" -> "датчиком", "магнит" -> "магнитом",
      "плата" -> "платой", "лампа" -> "лампой", "батарея" -> "батареей", "пакет" -> "пакетом")
    val lonePairs = unsafe.map { (a, b) =>
      if russian then s"${accusative.getOrElse(labels(a + 1), labels(a + 1))} наедине с " +
        instrumental.getOrElse(labels(b + 1), labels(b + 1))
      else s"${labels(a + 1)} alone with ${labels(b + 1)}"
    }.mkString(if russian then " и " else " or ")
    val prohibition = if unsafe.isEmpty then
      (if russian then "Оставлять груз без присмотра разрешено." else "Unattended cargo is allowed.")
    else if russian then "Нельзя оставлять " + lonePairs + "." else "Do not leave " + lonePairs + " unattended."
    val shopping = if russian then
      s"$f приобрёл на рынке ${labels.tail.map(x => accusative.getOrElse(x, x)).mkString(", ")}. " +
        "Дорога домой проходит через реку. Есть лодка, которой он управляет сам, " +
        s"и в неё помимо него может поместиться не больше $limit из покупок. "
    else s"$f bought ${labels.tail.mkString(", ")} at the market and needs to cross a river on the way home. " +
      s"The boat has room for its operator and at most $limit of the purchases. "
    val destination = if goal.forall(_ == 1) then
      (if russian then "Как ему переправить все покупки через реку?" else "How can all purchases get across?")
    else implicitGoal
    SemanticCorpus.Pair(if phrasing < 20 then question else
      shopping + implicitStart + minimum + prohibition + " " + destination, program)

  def pairs(seed: Int, n: Int): Vector[SemanticCorpus.Pair] =
    val r = new scala.util.Random(seed)
    Vector.fill(n) {
      val russian = r.nextBoolean()
      r.nextInt(4) match
        case 0 | 1 =>
          val count = 2 + r.nextInt(3)
          val people = if russian then Vector("курьер", "проводник", "рыбак", "садовник")
            else Vector("courier", "guide", "sailor", "gardener")
          val cargo = if russian then Vector("лиса", "зерно", "птица", "сыр", "кот", "кролик",
            "датчик", "магнит", "плата", "лампа", "батарея", "пакет")
            else Vector("fox", "grain", "hen", "cheese", "cat", "rabbit", "sensor", "magnet", "board", "lamp")
          val labels = people(r.nextInt(people.size)) +: r.shuffle(cargo).take(count)
          val candidates = labels.tail.indices.toVector.combinations(2).map(xs => xs(0) -> xs(1)).toVector
          val unsafe = r.shuffle(candidates).take(r.nextInt(math.min(3, candidates.size) + 1))
          val initial = if r.nextInt(4) != 0 then Vector.fill(count + 1)(0) else
            val states = (0 until (1 << (count + 1))).map(code =>
              Vector.tabulate(count + 1)(i => (code >> i) & 1)).filter { s =>
              unsafe.forall((a, b) => s(a + 1) != s(b + 1) || s(0) == s(a + 1))
            }
            states(r.nextInt(states.size))
          val target = if r.nextInt(3) != 0 then None else
            Some(if r.nextBoolean() then Vector.fill(count + 1)(0) else Vector.fill(count + 1)(r.nextInt(2)))
          transport(labels, unsafe, 1 + r.nextInt(2), initial, russian, r.nextInt(32), target, r.nextInt(5) != 0)
        case 2 =>
          val max = 3 + r.nextInt(4)
          val target = r.nextInt(max + 1)
          val up = 1 + r.nextInt(2)
          val down = 1 + r.nextInt(2)
          val question = if russian then
            s"Счётчик x принимает целые значения от 0 до $max. Начальное значение 0, цель $target. " +
              s"За один шаг можно прибавить $up или вычесть $down, оставаясь в диапазоне. Найдите кратчайший план."
          else s"Counter x is an integer from 0 to $max. It starts at 0; the goal is $target. " +
            s"Each step adds $up or subtracts $down without leaving the range. Find a shortest plan."
          val moves = Vector("+" -> up, "-" -> down).map { (op, amount) =>
            Json.obj("set" -> Json.obj("x" -> Json.Arr(Vector(Json.Str(op), Json.Str("x"), Json.Num(amount)))))
          }
          SemanticCorpus.Pair(question, Json.print(Json.obj("plan" -> Json.obj(
            "vars" -> Json.obj("x" -> bits((0 to max).toVector)), "start" -> bits(Vector(0)),
            "goal" -> bits(Vector(target)), "moves" -> Json.Arr(moves), "safe" -> Json.Arr(Vector.empty)))))
        case _ =>
          val ids = Vector("x", "y", "z").take(2 + r.nextInt(2))
          val initial = ids.map(_ => r.nextInt(2))
          val target = ids.map(_ => r.nextInt(2))
          val actions = (Vector(ids.take(2)) ++ r.shuffle(ids).take(r.nextInt(ids.size + 1)).map(Vector(_))).distinct
          val description = actions.map(_.mkString("+")).mkString("; ")
          val question = if russian then
            s"Переключатели ${ids.mkString(", ")} имеют значения 0 или 1. Начальное состояние " +
              s"${initial.mkString(",")}, целевое ${target.mkString(",")} в том же порядке. " +
              s"За шаг можно инвертировать один из наборов: $description. Других ограничений нет. Найдите план."
          else s"Switches ${ids.mkString(", ")} take values 0 or 1. Initial values ${initial.mkString(",")}; " +
            s"target ${target.mkString(",")} in that order. Each step flips one of these sets: $description. " +
            "There are no other restrictions. Find a shortest plan."
          SemanticCorpus.Pair(question, Json.print(Json.obj("plan" -> Json.obj("vars" -> strings(ids),
            "start" -> bits(initial), "goal" -> bits(target),
            "moves" -> Json.Arr(actions.map(xs => Json.obj("flip" -> strings(xs)))),
            "safe" -> Json.Arr(Vector.empty)))))
    }

  def main(args: Array[String]): Unit =
    val out = Path.of(args(0))
    val tokenizer = Chat.load(Path.of(sys.props("user.home") + "/.cache/okay-desk/semantic-full28")).tokenizer
    val tasks = pairs(779, 1800).filter(p => tokenizer.encode(p.program).length <= 380 &&
      tokenizer.encode(okay.desk.skill.NativeCompiler.promptText(p.question) + p.program).length < 1500)
    Files.writeString(out, tasks.map(p => Json.print(Json.obj("question" -> Json.Str(p.question),
      "program" -> Json.Str(p.program)))).mkString("\n") + "\n")
    println(s"planning lessons: ${tasks.size}; maximum output tokens: " + tasks.map(p =>
      tokenizer.encode(p.program).length).max)
