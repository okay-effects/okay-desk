package okay.desk.bench

import okay.effects.json.Json

/** Supervised programs, not runtime language rules. Test phrasings and names are deliberately separate. */
object SemanticCorpus:
  final case class Pair(question: String, program: String, history: Vector[(String, String)] = Vector.empty)
  private def j(s: String): String = Json.print(Json.Str(s))
  private def formal(vars: String, where: String, ask: String): String =
    s"""{"formal":{"vars":{$vars},"where":[$where],"ask":{$ask}}}"""
  def pairs(seed: Int, n: Int): Vector[Pair] =
    val r = new scala.util.Random(seed)
    val en = Vector("books", "boxes", "apples", "stones", "tickets", "coins", "robots", "samples")
    val ru = Vector("книг", "ящиков", "яблок", "камней", "билетов", "монет", "роботов", "образцов")
    val names = Vector("Ada", "Bo", "Cy", "Dee", "Эля", "Ира", "Лев", "Ян")
    Vector.fill(n) {
      val russian = r.nextBoolean()
      val a = 2 + r.nextInt(98)
      val b = 1 + r.nextInt(a - 1)
      val noun = if russian then ru(r.nextInt(ru.size)) else en(r.nextInt(en.size))
      r.nextInt(16) match
        case 0 | 1 =>
          val minus = r.nextBoolean()
          val q = if russian then r.shuffle(Vector(
            s"В коллекции $a $noun. ${if minus then "Забрали" else "Добавили"} $b. Сколько теперь в коллекции?",
            s"Сначала было $a $noun. ${if minus then "Отдали" else "Получили ещё"} $b. Сколько стало?",
            s"Имеется $a $noun. ${if minus then "Израсходовали" else "Прибавили"} $b. Сколько осталось?"
          )).head
          else r.shuffle(Vector(
            s"A collection has $a $noun. ${if minus then "Remove" else "Add"} $b. How many are there now?",
            s"Initially there were $a $noun. ${if minus then "We gave away" else "We received"} $b. " +
              "Find the new count.",
            s"Start with $a $noun and ${if minus then "take away" else "get another"} $b. What is the resulting amount?"
          )).head
          Pair(q, formal("", "", s"\"answer\":[\"${if minus then "-" else "+"}\",$a,$b]"))
        case 2 =>
          val q = if russian then s"В каждой из $a групп по $b $noun. Сколько всего?"
            else s"There are $a groups, each with $b $noun. What is the total?"
          Pair(q, formal("", "", s"\"answer\":[\"*\",$a,$b]"))
        case 3 =>
          val q = if russian then s"Распределите $a $noun поровну между $b участниками. Сколько на одного?"
            else s"Share $a $noun equally among $b recipients. How much does each get?"
          Pair(q, formal("", "", s"\"answer\":[\"/\",$a,$b]"))
        case 4 =>
          val x = 1 + r.nextInt(8)
          val q = if russian then s"Целое число x от 1 до 8. Если прибавить к нему $b, получится ${x + b}. Найдите x."
            else s"x is an integer from 1 to 8. Adding $b to it gives ${x + b}. Find x."
          Pair(q, formal("\"x\":[1,2,3,4,5,6,7,8]", s"[\"=\",[\"+\",\"x\",$b],${x + b}]", "\"x\":\"x\""))
        case 5 =>
          val ns = r.shuffle(names).take(3)
          val Array(x, y, z) = ns.toArray: @unchecked
          val q = if russian then
            s"$x, $y и $z занимают разные места 1, 2, 3. $x раньше $y. $z на третьем месте. Где $y?"
          else s"$x, $y and $z occupy different slots 1, 2, 3. $x is earlier than $y. $z is third. Find $y's slot."
          val vars = ns.map(name => s"${j(name)}:[1,2,3]").mkString(",")
          Pair(q, formal(vars, s"[\"distinct\",${ns.map(j).mkString(",")}],[\"<\",${j(x)},${j(y)}]," +
            s"[\"=\",${j(z)},3]", s"${j(y)}:${j(y)}"))
        case 6 =>
          val q = if russian then "p и q — логические значения. p истинно. Если p, то q. Истинно ли q?"
            else "p and q are boolean values. p is true. If p then q. Is q true?"
          Pair(q, formal("\"p\":[false,true],\"q\":[false,true]",
            "[\"=\",\"p\",true],[\"implies\",\"p\",\"q\"]", "\"q\":\"q\""))
        case 7 =>
          val q = if russian then s"Есть $a $noun, после изменения их стало $b. На сколько уменьшилось количество?"
            else s"There were $a $noun and now there are $b. By how much did the count decrease?"
          Pair(q, formal("", "", s"\"answer\":[\"-\",$a,$b]"))
        case 8 =>
          val q = if russian then s"Вычислите $b процентов от величины $a."
            else s"Find $b percent of the quantity $a."
          Pair(q, formal("", "", s"\"answer\":[\"/\",[\"*\",$a,$b],100]"))
        case 9 =>
          val c = 1 + r.nextInt(30)
          val q = if russian then s"Было $a $noun. Прибавилось $b, затем убрали $c. Каков результат?"
            else s"There were $a $noun. We added $b and then removed $c. How many remain?"
          Pair(q, formal("", "", s"\"answer\":[\"-\",[\"+\",$a,$b],$c]"))
        case 10 =>
          val c = 1 + r.nextInt(a * b)
          val q = if russian then s"Есть $a наборов по $b предметов. Из них забрали $c предметов. Сколько осталось?"
            else s"There are $a sets of $b items. We remove $c items from the total. How many are left?"
          Pair(q, formal("", "", s"\"answer\":[\"-\",[\"*\",$a,$b],$c]"))
        case 11 =>
          val q = if russian then s"Первый человек на $b лет младше второго. Первому $a лет. Сколько второму?"
            else s"The first person is $b years younger than the second. The first is $a years old. " +
              "How old is the second?"
          Pair(q, formal("", "", s"\"answer\":[\"+\",$a,$b]"))
        case 12 =>
          val symbols = r.shuffle(Vector("active", "ready", "safe", "loaded", "open", "valid")).take(3)
          val x = symbols(0)
          val y = symbols(1)
          val z = symbols(2)
          val mode = r.nextInt(4)
          val premise = if mode == 1 then z else x
          val goal = if mode == 1 then x else z
          val negative = mode == 2
          def literal(name: String): String = if negative then s"[\"not\",${j(name)}]" else j(name)
          val facts = if mode == 3 then Vector(j(x), s"[\"not\",${j(x)}]") else Vector(literal(premise))
          val rules = Vector(s"[\"implies\",${literal(x)},${literal(y)}]",
            s"[\"implies\",${literal(y)},${literal(z)}]")
          def phrase(name: String): String = if russian then s"$name ${if negative then "ложно" else "истинно"}"
            else s"$name is ${if negative then "false" else "true"}"
          val stated = if mode == 3 then
            (if russian then s"$x истинно и $x ложно." else s"$x is true and $x is false.")
          else phrase(premise) + "."
          val question = if russian then
            s"${symbols.mkString(", ")} — логические переменные. $stated " +
              s"Если ${phrase(x)}, то ${phrase(y)}. Если ${phrase(y)}, то ${phrase(z)}. Каково значение $goal?"
          else s"${symbols.mkString(", ")} are Boolean variables. $stated " +
            s"If ${phrase(x)} then ${phrase(y)}. If ${phrase(y)} then ${phrase(z)}. What is $goal?"
          Pair(question, formal(symbols.map(s => s"${j(s)}:[false,true]").mkString(","),
            (facts ++ rules).mkString(","), s"${j(goal)}:${j(goal)}"))
        case 13 =>
          val name = r.shuffle(Vector("n", "v", "t", "number", "value")).head
          val lo = 1 + r.nextInt(3)
          val hi = lo + 3 + r.nextInt(4)
          val cut = lo + 1 + r.nextInt(hi - lo - 1)
          val mode = r.nextInt(4)
          val second = if mode == 0 then cut + 2 else if mode == 1 then hi + 1 else cut
          val upperRu = if mode == 3 then "" else s" и меньше $second"
          val upperEn = if mode == 3 then "" else s" and is below $second"
          val question = if russian then
            s"$name — целое от $lo до $hi включительно. $name больше $cut$upperRu. Найдите $name."
          else s"$name is an integer from $lo through $hi inclusive. $name exceeds $cut$upperEn. " +
            s"Find $name."
          Pair(question, formal(s"${j(name)}:[${(lo to hi).mkString(",")}]",
            s"[\">\",${j(name)},$cut]" + (if mode == 3 then "" else s",[\"<\",${j(name)},$second]"),
            s"${j(name)}:${j(name)}"))
        case 14 =>
          val amountAsked = r.nextBoolean()
          val question = if russian then s"В запасе было $a $noun, затем выдали $b. " +
            (if amountAsked then "Сколько выдали?" else "Сколько осталось в запасе?")
          else s"A store held $a $noun and handed out $b. " +
            (if amountAsked then "How many did it hand out?" else "How many remain in the store?")
          Pair(question, formal("", "", if amountAsked then s"\"answer\":$b"
            else s"\"answer\":[\"-\",$a,$b]"))
        case _ =>
          val name = r.shuffle(Vector("enabled", "closed", "present", "complete")).head
          val contradicted = r.nextBoolean()
          val question = if russian then s"$name — логическое значение. $name ложно. " +
            (if contradicted then s"Также $name истинно. " else "") + s"Чему равно $name?"
          else s"$name is Boolean. $name is false. " +
            (if contradicted then s"Also $name is true. " else "") + s"What is $name?"
          val conditions = s"[\"not\",${j(name)}]" + (if contradicted then s",${j(name)}" else "")
          Pair(question, formal(s"${j(name)}:[false,true]", conditions, s"${j(name)}:${j(name)}"))
    }
