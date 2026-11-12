package okay.desk.tasks

import okay.effects.json.Json
import Gold.*
import Task.{no, noted, of, open, says, yes, Noted}
import okay.desk.statement.{Con, Problem, Writing}
import Writing.say

/**
 * DEDUCTION AND TRUTH-TELLERS: what follows from statements, what does not follow, and what cannot be at all — each a
 * handful of true-or-false unknowns and the conditions on them, decided by the finite solver over every case. The
 * deductions are written with their statements (`okay.desk.statement`): every claim marked where it is said, every
 * sentence with the frames it states.
 */
object Deduction:
  private val k = Kind.Deduction
  private val (fact, when, iff, oneOf, whether, whichOne) =
    (say(Con.Fact), say(Con.If), say(Con.Iff), Writing.list(Con.OneOf, 2), say(Con.Whether), say(Con.Which))
  private def stated(title: (String, String), answer: (String, String), gold: Gold, expect: String*)(
      sentences: Noted*): Task = noted(k, title, Problem.Derive, sentences.toVector, answer, gold, expect*)
  private def follows(title: (String, String), answer: (String, String), names: Seq[String], where: Vector[Json],
      ask: String, expect: String)(sentences: Noted*): Task =
    stated(title, answer, finite(bools(names*), where, "answer" -> v(ask)), expect)(sentences*)

  private val rain = Vector(implies(v("rain"), v("wet")))
  private val rainSaid = says("If [rain|it rains], [wet|the street is wet].",
    "Если [rain|идёт дождь], [wet|улица мокрая].", when)
  private val chain = Vector(implies(v("alarm"), v("awake")), implies(v("awake"), v("coffee")),
    implies(v("coffee"), v("smell")), implies(v("smell"), v("cat")))
  private val chainNames = Seq("alarm", "awake", "coffee", "smell", "cat")
  private val chainSaid = Vector(
    says("If [alarm|the alarm rings], [awake|Ann wakes up].", "Если [alarm|звенит будильник], [awake|Аня просыпается].",
      when),
    says("If [awake|Ann wakes up], [coffee|she makes coffee].",
      "Если [awake|Аня просыпается], [coffee|она варит кофе].", when),
    says("If [coffee|she makes coffee], [smell|the kitchen smells of coffee].",
      "Если [coffee|она варит кофе], [smell|на кухне пахнет кофе].", when),
    says("If [smell|the kitchen smells of coffee], [cat|the cat comes to the kitchen].",
      "Если [smell|на кухне пахнет кофе], [cat|на кухню приходит кот].", when))

  val deduction: Vector[Task] = Vector(
    follows(("Cats and animals", "Кошки и животные"), yes, Seq("cat", "mammal", "animal"),
      Vector(implies(v("cat"), v("mammal")), implies(v("mammal"), v("animal")), v("cat")), "animal", "true")(
      says("All [cat|cats] are [mammal|mammals].", "Все [cat|кошки] — [mammal|млекопитающие].", when),
      says("All [mammal|mammals] are [animal|animals].", "Все [mammal|млекопитающие] — [animal|животные].", when),
      says("Tom is [cat|a cat].", "Том — [cat|кошка].", fact),
      says("Is Tom [animal|an animal]?", "Том — [animal|животное]?", whether, fact)),
    follows(("Snakes and mammals", "Змеи и млекопитающие"), no, Seq("snake", "reptile", "mammal"),
      Vector(implies(v("reptile"), not(v("mammal"))), implies(v("snake"), v("reptile")), v("snake")), "mammal",
      "false")(
      says("No [reptile] is [!mammal|a mammal].",
        "Ни одна [reptile|рептилия] не является [!mammal|млекопитающим].", when),
      says("All [snake|snakes] are [reptile|reptiles].", "Все [snake|змеи] — [reptile|рептилии].", when),
      says("Kaa is [snake|a snake].", "Каа — [snake|змея].", fact),
      says("Is Kaa [mammal|a mammal]?", "Каа — [mammal|млекопитающее]?", whether, fact)),
    follows(("Roses and flowers", "Розы и цветы"), open, Seq("rose", "flower"),
      Vector(implies(v("rose"), v("flower")), v("flower")), "rose", "false|true")(
      says("All [rose|roses] are [flower|flowers].", "Все [rose|розы] — [flower|цветы].", when),
      says("This plant is [flower|a flower].", "Это растение — [flower|цветок].", fact),
      says("Is it [rose|a rose]?", "Это [rose|роза]?", whether, fact)),
    follows(("Rain: it rains", "Дождь: идёт дождь"), yes, Seq("rain", "wet"), rain :+ v("rain"), "wet", "true")(
      rainSaid, says("[rain|It is raining].", "[rain|Идёт дождь].", fact),
      says("Is [wet|the street wet]?", "[wet|Улица мокрая]?", whether, fact)),
    follows(("Rain: the street is dry", "Дождь: улица сухая"), no, Seq("rain", "wet"), rain :+ not(v("wet")), "rain",
      "false")(
      rainSaid, says("[!wet|The street is not wet].", "[!wet|Улица не мокрая].", fact),
      says("Is [rain|it raining]?", "[rain|Идёт ли дождь]?", whether, fact)),
    follows(("Rain: the street is wet", "Дождь: улица мокрая"), open, Seq("rain", "wet"), rain :+ v("wet"), "rain",
      "false|true")(
      rainSaid, says("[wet|The street is wet].", "[wet|Улица мокрая].", fact),
      says("Is [rain|it raining]?", "[rain|Идёт ли дождь]?", whether, fact)),
    follows(("A chain of five", "Цепочка из пяти"), yes, chainNames, chain :+ v("alarm"), "cat", "true")(
      (chainSaid :+ says("[alarm|The alarm rang].", "[alarm|Будильник прозвенел].", fact) :+
        says("Does [cat|the cat come to the kitchen]?", "[cat|Придёт ли кот на кухню]?", whether, fact))*),
    follows(("A chain of five, backwards", "Цепочка из пяти, в обратную сторону"), no, chainNames,
      chain :+ not(v("cat")), "alarm", "false")(
      (chainSaid :+ says("[!cat|The cat did not come to the kitchen].", "[!cat|Кот на кухню не пришёл].", fact) :+
        says("Did [alarm|the alarm ring]?", "[alarm|Звенел ли будильник]?", whether, fact))*),
    follows(("A chain of five, from the middle", "Цепочка из пяти, с середины"), open, chainNames,
      chain :+ v("coffee"), "alarm", "false|true")(
      (chainSaid :+ says("[coffee|Ann made coffee].", "[coffee|Аня сварила кофе].", fact) :+
        says("Did [alarm|the alarm ring]?", "[alarm|Звенел ли будильник]?", whether, fact))*),
    stated(("The key", "Ключ"), ("The gardener", "Садовник"),
      finite(bools("butler", "gardener", "open"),
        Vector(op("!=", v("butler"), v("gardener")), implies(v("butler"), v("open")), not(v("open"))),
        "answer" -> which("butler" -> v("butler"), "gardener" -> v("gardener"))), "gardener")(
      says("Either [butler|the butler] or [gardener|the gardener] took the key, but not both.",
        "Ключ взял либо [butler|дворецкий], либо [gardener|садовник], но не оба.", oneOf),
      says("If [butler|the butler] took it, [open|the safe is open].",
        "Если ключ взял [butler|дворецкий], [open|сейф открыт].", when),
      says("[!open|The safe is not open].", "[!open|Сейф не открыт].", fact),
      says("Who took the key?", "Кто взял ключ?", whichOne)),
    stated(("The certificate", "Сертификат"),
      ("She has a certificate; whether she passed the exam does not follow",
        "Сертификат у неё есть; сдала ли она экзамен — из условий не следует"),
      finite(bools("passed", "certificate", "enrolled"),
        Vector(implies(v("passed"), v("certificate")), implies(not(v("certificate")), not(v("enrolled"))),
          v("enrolled")),
        "certificate" -> v("certificate"), "passed" -> v("passed")), "true", "false|true")(
      says("Every student who [passed|passes the exam] [certificate|gets a certificate].",
        "Каждый студент, [passed|сдавший экзамен], [certificate|получает сертификат].", when),
      says("Nobody [!certificate|without a certificate] [!enrolled|may enrol].",
        "[!certificate|Без сертификата] [!enrolled|записаться на курс] нельзя.", when),
      says("[enrolled|Mia has enrolled].", "[enrolled|Мия записалась на курс].", fact),
      says("Does [certificate|Mia have a certificate]?", "[certificate|Есть ли у Мии сертификат]?", whether, fact),
      says("Did [passed|she pass the exam]?", "[passed|Сдала ли она экзамен]?", whether, fact)),
    follows(("The card", "Карточка"), no, Seq("vowel", "even"), Vector(implies(v("vowel"), v("even")), not(v("even"))),
      "vowel", "false")(
      says("Every card with [vowel|a vowel] on one side has [even|an even number] on the other side.",
        "У каждой карточки, на одной стороне которой [vowel|гласная буква], на другой стороне [even|чётное число].",
        when),
      says("A card shows [!even|the number 7].", "На карточке видно [!even|число 7].", fact),
      says("Is there [vowel|a vowel] on its other side?", "Есть ли на другой её стороне [vowel|гласная]?", whether,
        fact)),
    follows(("The party", "Вечеринка"), no, Seq("ann", "bob", "cid"),
      Vector(implies(v("bob"), v("ann")), is(v("cid"), not(v("ann"))), v("bob")), "cid", "false")(
      says("[bob|Bob comes] to the party only if [ann|Ann comes].",
        "[bob|Боб придёт] на вечеринку, только если [ann|придёт Аня].", when),
      says("[cid|Cid comes] exactly when [!ann|Ann does not come].",
        "[cid|Сид придёт] тогда и только тогда, когда [!ann|Аня не придёт].", iff),
      says("[bob|Bob comes].", "[bob|Боб придёт].", fact),
      says("Does [cid|Cid come]?", "[cid|Придёт ли Сид]?", whether, fact)),
    stated(("Three switches", "Три выключателя"), ("C", "C"),
      finite(bools("a", "b", "c"),
        Vector(is(op("count", v("a"), v("b"), v("c")), n(1)), not(v("a")), implies(v("b"), v("a"))),
        "answer" -> which("A" -> v("a"), "B" -> v("b"), "C" -> v("c"))), "C")(
      says("Exactly one of the three switches [A], [B] and [C] is on.",
        "Из трёх выключателей [A], [B] и [C] включён ровно один.", Writing.list(Con.OneOf, 3)),
      says("Switch [!A] is off.", "Выключатель [!A] выключен.", fact),
      says("If switch [B] is on, then switch [A] is on.", "Если включён [B], то включён и [A].", when),
      says("Which switch is on?", "Какой выключатель включён?", whichOne)),
    stated(("Statements that cannot all hold", "Утверждения, которые не могут быть верны вместе"),
      ("This cannot be: the statements contradict each other", "Так быть не может: условия противоречат друг другу"),
      finite(bools("ann", "bob", "cid"),
        Vector(implies(v("ann"), v("bob")), implies(v("bob"), not(v("cid"))), v("ann"), v("cid")),
        "bob" -> v("bob")), "none")(
      says("If [ann|Ann comes], [bob|Bob comes].", "Если [ann|придёт Аня], [bob|придёт и Боб].", when),
      says("If [bob|Bob comes], [!cid|Cid does not come].", "Если [bob|придёт Боб], [!cid|Сид не придёт].", when),
      says("[ann|Ann] and [cid|Cid] both come.", "[ann|Аня] и [cid|Сид] оба придут.", fact, fact),
      says("Who comes?", "Кто придёт?", whichOne)))

  private val liars = Kind.Truth
  private val island = ("On an island every inhabitant is either a knight, who always tells the truth, or a liar, " +
    "who always lies. ", "На острове каждый житель — либо рыцарь, который всегда говорит правду, либо лжец, " +
    "который всегда лжёт. ")
  private def islanders(title: (String, String), text: (String, String), answer: (String, String),
      names: Seq[String], says: Vector[(String, Json)], expect: String*): Task =
    of(liars, title, (island._1 + text._1, island._2 + text._2), answer,
      finite(bools(names*), says.map((who, said) => is(v(who), said)), names.map(x => x -> v(x))*), expect*)
  /** who did it, when so many of the statements are true */
  private def culprit(title: (String, String), text: (String, String), answer: (String, String),
      suspects: Seq[String], says: Vector[Json], truths: Int, expect: String): Task =
    of(liars, title, text, answer, finite(Vector("who" -> texts(suspects*)),
      Vector(is(op("count", says*), n(truths))), "who" -> v("who")), expect)
  private def did(name: String): Json = is(v("who"), t(name))

  val truth: Vector[Task] = Vector(
    islanders(("We are both liars", "Мы оба лжецы"),
      ("A says: \"We are both liars.\" Who are A and B?", "A говорит: «Мы оба лжецы». Кто такие A и B?"),
      ("A is a liar, B is a knight", "A — лжец, B — рыцарь"), Seq("A", "B"),
      Vector("A" -> op("and", not(v("A")), not(v("B")))), "false", "true"),
    islanders(("At least one of us is a liar", "Хотя бы один из нас лжец"),
      ("A says: \"At least one of us is a liar.\" Who are A and B?",
        "A говорит: «Хотя бы один из нас лжец». Кто такие A и B?"),
      ("A is a knight, B is a liar", "A — рыцарь, B — лжец"), Seq("A", "B"),
      Vector("A" -> op("or", not(v("A")), not(v("B")))), "true", "false"),
    islanders(("Of different kinds", "Разных типов"),
      ("A says: \"B is a knight.\" B says: \"A and I are of different kinds.\" Who are A and B?",
        "A говорит: «B — рыцарь». B говорит: «Мы с A разных типов». Кто такие A и B?"),
      ("Both are liars", "Оба лжецы"), Seq("A", "B"),
      Vector("A" -> v("B"), "B" -> op("!=", v("A"), v("B"))), "false", "false"),
    islanders(("Three islanders", "Трое островитян"),
      ("A says: \"All three of us are liars.\" B says: \"Exactly one of us is a knight.\" Who are A, B and C?",
        "A говорит: «Мы все трое лжецы». B говорит: «Ровно один из нас рыцарь». Кто такие A, B и C?"),
      ("A is a liar, B is a knight, C is a liar", "A — лжец, B — рыцарь, C — лжец"), Seq("A", "B", "C"),
      Vector("A" -> op("and", not(v("A")), not(v("B")), not(v("C"))),
        "B" -> is(op("count", v("A"), v("B"), v("C")), n(1))), "false", "true", "false"),
    islanders(("The same kind", "Одного типа"),
      ("A says: \"B and I are of the same kind.\" Who is B?", "A говорит: «Мы с B одного типа». Кто такой B?"),
      ("B is a knight; what A is cannot be decided", "B — рыцарь; кто такой A, определить нельзя"), Seq("A", "B"),
      Vector("A" -> is(v("A"), v("B"))), "false|true", "true"),
    islanders(("Each vouches for the other", "Каждый ручается за другого"),
      ("A says: \"B is a knight.\" B says: \"A is a knight.\" Who are A and B?",
        "A говорит: «B — рыцарь». B говорит: «A — рыцарь». Кто такие A и B?"),
      ("It cannot be decided: both are knights or both are liars",
        "Определить нельзя: либо оба рыцари, либо оба лжецы"), Seq("A", "B"),
      Vector("A" -> v("B"), "B" -> v("A")), "false|true", "false|true"),
    islanders(("I am a liar", "Я лжец"),
      ("A says: \"I am a liar.\" Who is A?", "A говорит: «Я лжец». Кто такой A?"),
      ("This cannot be: neither a knight nor a liar can say it",
        "Так быть не может: этого не может сказать ни рыцарь, ни лжец"), Seq("A"),
      Vector("A" -> not(v("A"))), "none"),
    culprit(("The broken vase", "Разбитая ваза"),
      ("One of three children — Anna, Boris or Vera — broke a vase. Anna says: \"I did not do it.\" " +
        "Boris says: \"Vera did it.\" Vera says: \"Boris is lying.\" Exactly one of the three tells the truth. " +
        "Who broke the vase?",
        "Один из троих детей — Анна, Борис или Вера — разбил вазу. Анна говорит: «Это не я». " +
          "Борис говорит: «Это Вера». Вера говорит: «Борис лжёт». Правду говорит ровно один из троих. " +
          "Кто разбил вазу?"),
      ("Anna", "Анна"), Seq("Anna", "Boris", "Vera"),
      Vector(not(did("Anna")), did("Vera"), not(did("Vera"))), 1, "Anna"),
    culprit(("The eaten cake", "Съеденный торт"),
      ("One of three — Anna, Boris or Vera — ate the cake. Anna says: \"Boris ate it.\" " +
        "Boris says: \"I did not eat it.\" Vera says: \"I did not eat it.\" Exactly one of the three tells the " +
        "truth. Who ate the cake?",
        "Один из троих — Анна, Борис или Вера — съел торт. Анна говорит: «Его съел Борис». " +
          "Борис говорит: «Я не ел». Вера говорит: «Я не ела». Правду говорит ровно один из троих. Кто съел торт?"),
      ("Vera", "Вера"), Seq("Anna", "Boris", "Vera"),
      Vector(did("Boris"), not(did("Boris")), not(did("Vera"))), 1, "Vera"),
    culprit(("Four suspects, one truth", "Четверо подозреваемых, одна правда"),
      ("One of four suspects — Adam, Bella, Carl or Dora — took the money. Adam says: \"Bella took it.\" " +
        "Bella says: \"Dora took it.\" Carl says: \"I did not take it.\" Dora says: \"Bella is lying.\" " +
        "Exactly one of the four tells the truth. Who took the money?",
        "Один из четверых подозреваемых — Адам, Белла, Карл или Дора — взял деньги. Адам говорит: «Взяла Белла». " +
          "Белла говорит: «Взяла Дора». Карл говорит: «Я не брал». Дора говорит: «Белла лжёт». " +
          "Правду говорит ровно один из четверых. Кто взял деньги?"),
      ("Carl", "Карл"), Seq("Adam", "Bella", "Carl", "Dora"),
      Vector(did("Bella"), did("Dora"), not(did("Carl")), not(did("Dora"))), 1, "Carl"),
    culprit(("Four suspects, one lie", "Четверо подозреваемых, одна ложь"),
      ("One of four suspects — Adam, Bella, Carl or Dora — took the money. Adam says: \"Bella took it.\" " +
        "Bella says: \"Dora took it.\" Carl says: \"I did not take it.\" Dora says: \"Bella is lying.\" " +
        "Exactly one of the four lies. Who took the money?",
        "Один из четверых подозреваемых — Адам, Белла, Карл или Дора — взял деньги. Адам говорит: «Взяла Белла». " +
          "Белла говорит: «Взяла Дора». Карл говорит: «Я не брал». Дора говорит: «Белла лжёт». " +
          "Лжёт ровно один из четверых. Кто взял деньги?"),
      ("Bella", "Белла"), Seq("Adam", "Bella", "Carl", "Dora"),
      Vector(did("Bella"), did("Dora"), not(did("Carl")), not(did("Dora"))), 3, "Bella"),
    of(liars, ("Three caskets", "Три шкатулки"),
      ("A treasure is in one of three caskets. The gold casket says: \"The treasure is here.\" " +
        "The silver casket says: \"The treasure is not here.\" The lead casket says: \"The treasure is not in " +
        "the gold casket.\" Exactly one of the three inscriptions is true. Where is the treasure?",
        "Сокровище лежит в одной из трёх шкатулок. На золотой написано: «Сокровище здесь». " +
          "На серебряной: «Сокровище не здесь». На свинцовой: «Сокровище не в золотой шкатулке». " +
          "Ровно одна из трёх надписей верна. Где сокровище?"),
      ("In the silver casket", "В серебряной шкатулке"),
      finite(Vector("where" -> texts("gold", "silver", "lead")),
        Vector(is(op("count", is(v("where"), t("gold")), not(is(v("where"), t("silver"))),
          not(is(v("where"), t("gold")))), n(1))), "where" -> v("where")), "silver"),
    of(liars, ("Wrong labels", "Неверные этикетки"),
      ("Three boxes hold apples, oranges, and a mix of both. The boxes are labelled \"apples\", \"oranges\" and " +
        "\"mixed\", but every label is wrong. A fruit taken from the box labelled \"mixed\" turns out to be an " +
        "apple. What is in the box labelled \"oranges\"?",
        "В трёх коробках лежат яблоки, апельсины и смесь тех и других. На коробках написано «яблоки», " +
          "«апельсины» и «смесь», но все надписи неверны. Из коробки с надписью «смесь» достали фрукт — это " +
          "яблоко. Что лежит в коробке с надписью «апельсины»?"),
      ("The mix of both", "Смесь"),
      finite(Vector("apples" -> texts("apples", "oranges", "mixed"), "oranges" -> texts("apples", "oranges", "mixed"),
        "mixed" -> texts("apples", "oranges", "mixed")),
        Vector(op("distinct", v("apples"), v("oranges"), v("mixed")), op("!=", v("apples"), t("apples")),
          op("!=", v("oranges"), t("oranges")), is(v("mixed"), t("apples"))),
        "answer" -> v("oranges")), "mixed"))
