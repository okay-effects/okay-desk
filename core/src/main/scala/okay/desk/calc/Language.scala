package okay.desk.calc

import okay.effects.json.Json

/**
 * A LANGUAGE AS DATA, IN BOTH DIRECTIONS (docs/calculus.md §10; GF's idea: one concrete syntax serves parsing and
 * linearization). One record per language: the WORDS of each constructor as the exact fill reads them (counts,
 * averages, sizes, directions, limits, conditions, negations, closures, what lies outside the grammar), the FORMS
 * of the generator's names (tables in four cases, columns in three, values in one) as the dictionary reads them,
 * the stem rule, and the PIECES the phrases are said with (templates with slots). English and Russian are built
 * in; another language is a JSON file of the same shape (`lang/uk.json`, `lang/pl.json`), loaded when a question
 * needs it (`Language.detect`) — on the JVM from the resources, on the page by a fetch.
 */
final case class Language(code: String, lists: Map[String, Vector[String]], numbers: Map[String, String],
    tables: Map[String, Vector[String]], columns: Map[String, Vector[String]], values: Map[String, String],
    stemDrop: Int, pieces: Map[String, Vector[String]]):
  def list(key: String): Vector[String] = lists.getOrElse(key, Vector.empty)
  /** the stem of a form: the ending taken off, a short word kept whole */
  def stem(w: String): String = if w.length <= 3 then w else w.dropRight(if w.length > 6 then stemDrop else 1)

object Language:
  /** the script a question is written in decides which languages to ask for */
  def detect(question: String): Vector[String] =
    val q = question.toLowerCase
    val uk = q.exists(c => "іїєґ".contains(c))
    val pl = q.exists(c => "ąęłńśźżć".contains(c))
    val cyr = q.exists(c => c >= 'а' && c <= 'я')
    (if uk then Vector("uk") else Vector.empty) ++ (if pl then Vector("pl") else Vector.empty) ++
      (if cyr && !uk then Vector("ru") else Vector.empty) ++ Vector("en")

  private def strings(j: Option[Json]): Vector[String] = j match
    case Some(Json.Arr(xs)) => xs.collect { case Json.Str(s) => s }.toVector
    case _ => Vector.empty
  private def lists(j: Option[Json]): Map[String, Vector[String]] = j match
    case Some(Json.Obj(fs)) => fs.map((k, v) => k -> strings(Some(v))).toMap
    case _ => Map.empty
  private def strs(j: Option[Json]): Map[String, String] = j match
    case Some(Json.Obj(fs)) => fs.collect { case (k, Json.Str(v)) => k -> v }.toMap
    case _ => Map.empty

  def parse(text: String): Either[String, Language] =
    try Json.fromText(text) match
      case Json.Obj(fs) =>
        val m = fs.toMap
        Right(Language(m.get("code").collect { case Json.Str(s) => s }.getOrElse("?"), lists(m.get("lists")), strs(m.get("numbers")),
          lists(m.get("tables")), lists(m.get("columns")), strs(m.get("values")),
          m.get("stemDrop").collect { case Json.Num(n) => n.toInt }.getOrElse(2), lists(m.get("pieces"))))
      case _ => Left("an object expected")
    catch case e: Exception => Left(e.getMessage)

  /** the built-in languages by code; others from `load` */
  def builtIn: Map[String, Language] = Map("en" -> En.language, "ru" -> Ru.language)

  /** a language's file on the JVM: the resources `lang/<code>.json` */
  def load(code: String): Option[Language] =
    builtIn.get(code).orElse {
      val in = Option(getClass.getResourceAsStream(s"/lang/$code.json"))
      in.flatMap(s => parse(new String(s.readAllBytes(), "UTF-8")).toOption)
    }

  /** the languages a question needs, those found */
  def forQuestion(question: String, loaded: String => Option[Language] = load): Vector[Language] =
    detect(question).flatMap(loaded(_))

  object En:
    val language: Language = Language("en",
      lists = Map(
        "count" -> Vector("how many", "number of"), "countWord" -> Vector("count"), "avg" -> Vector("average", "mean"),
        "sum" -> Vector("total", "sum", "summed"), "min" -> Vector("minimum", "smallest", "least"), "max" -> Vector("maximum"),
        "size" -> Vector("largest", "smallest", "highest", "lowest", "biggest", "maximum", "minimum"), "sizeAny" -> Vector("largest", "smallest", "biggest", "highest", "lowest"),
        "big" -> Vector("largest", "fastest", "highest", "most", "biggest", "top"),
        "small" -> Vector("smallest", "slowest", "lowest", "least"),
        "desc" -> Vector("descending"), "asc" -> Vector("ascending"),
        "ordered" -> Vector("sorted", "ordered", "first", "top"), "top" -> Vector("top", "first"), "topOnly" -> Vector("top"),
        "one" -> Vector("the row", "which of", "the one"), "onlyFirst" -> Vector("only the first", "the first one"),
        "rows" -> Vector("which", "show", "list"), "whatWas" -> Vector("what was the", "what is the", "what were the", "what are the"),
        "group" -> Vector("per", "each", "by"), "among" -> Vector("among", "of"),
        "condition" -> Vector("with", "where", "whose", "over", "above", "below", "under", "than"),
        "more" -> Vector("above", "more than", "greater", "over"), "less" -> Vector("below", "under", "less than"),
        "absent" -> Vector("without", "never", "lacking", "no"), "negation" -> Vector("no", "without"), "into" -> Vector("in"),
        "closure" -> Vector("reach*", "depend*", "lead*"), "closureFrom" -> Vector("depend*", "reachable", "where does"),
        "closureTo" -> Vector("reach*", "lead*"),
        "growth" -> Vector("grow*", "grew", "growth"), "pace" -> Vector("fastest", "slowest", "faster", "slower"),
        "outside" -> Vector("share", "compare*", "ratio", "percent*"), "compare" -> Vector("than that of", "faster than", "slower than", "than the"),
        // the acts (Dialogue.Act): what a message does before it is read as a question
        "yes" -> Vector("yes", "yeah", "yep", "right", "ok", "okay", "sure"), "no" -> Vector("no", "nope"),
        "thanks" -> Vector("thanks", "thank you", "cheers", "great"), "forget" -> Vector("forget that", "forget it", "don't remember", "do not remember"), "correction" -> Vector("no,", "not that", "i mean", "i meant", "should have been", "wrong,"),
        // THE DISPUTED WORDS (BACKLOG A 2): a word that means either of two constructors — the data asks instead of deciding
        "ask:fn:count|sum" -> Vector("how much money", "amount of money"), "ask:fn:avg|sum" -> Vector("typical"),
        "ask:dir:desc|asc" -> Vector("sorted by", "ordered by", "in order of")),
      numbers = Map("one" -> "1", "three" -> "3", "five" -> "5", "ten" -> "10"),
      tables = Map.empty, columns = Map.empty, values = Map.empty, stemDrop = 1, pieces = Map.empty)

  object Ru:
    private def t(a: String, b: String, c: String, d: String) = Vector(a, b, c, d)
    private def c(a: String, b: String, d: String) = Vector(a, b, d)
    val language: Language = Language("ru",
      lists = Map(
        "count" -> Vector("сколько", "число", "посчитай"), "avg" -> Vector("средн*"), "sum" -> Vector("итого", "сумм*"),
        "min" -> Vector("минимум", "наимен*"), "max" -> Vector("максимум"),
        "size" -> Vector("наибольш*", "наимен*", "максим*", "миним*", "самы*"), "sizeAny" -> Vector("больш*", "мал*", "высок*", "низк*"),
        "big" -> Vector("наибольш*", "больш*", "высок*", "быстр*", "топ"), "small" -> Vector("наимен*", "мал*", "низк*", "медленн*"),
        "desc" -> Vector("по убыван*", "от большего", "сначала самые большие"), "asc" -> Vector("по возраст*", "от меньшего", "сначала самые маленькие"),
        "ordered" -> Vector("упорядоч*", "сначала", "топ", "первые"), "top" -> Vector("топ", "первые"), "topOnly" -> Vector("топ", "первые"),
        "one" -> Vector("строка", "у какого"), "onlyFirst" -> Vector("только перв*"),
        "rows" -> Vector("какие", "покажи", "выведи", "список"), "whatWas" -> Vector("какой был", "какая была", "какое было", "каков"),
        "group" -> Vector("по кажд*", "в кажд*", "кажд*", "в разрезе", "по"), "among" -> Vector("среди", "по", "в разрезе"),
        "condition" -> Vector("с", "где", "у которых", "больше", "меньше"),
        "more" -> Vector("больше", "выше", "свыше"), "less" -> Vector("меньше", "ниже"),
        "absent" -> Vector("без", "нет", "не встреча*"), "negation" -> Vector("без", "нет"), "into" -> Vector("в"),
        "closure" -> Vector("достиж*", "достиг*", "завис*", "вед*", "доход*"), "closureFrom" -> Vector("завис*", "достижим*", "куда"),
        "closureTo" -> Vector("ведут", "ведёт", "доход*", "достига*", "кто"),
        "growth" -> Vector("рос", "росл*", "рост*", "раст*", "выраст*", "вырос*"), "pace" -> Vector("быстрее", "медленнее", "быстр*", "медленн*"),
        "outside" -> Vector("дол*", "сравни*", "процент*"), "compare" -> Vector("чем у", "чем в", "быстрее, чем", "быстрее чем", "медленнее, чем", "медленнее чем", "по сравнению"),
        "yes" -> Vector("да", "ага", "верно", "точно", "ок", "давай"), "no" -> Vector("нет", "неа"),
        "thanks" -> Vector("спасибо", "благодарю", "отлично", "супер"), "forget" -> Vector("не запоминай", "забудь"), "correction" -> Vector("не так", "нет,", "я имею в виду", "я имел в виду", "я имела в виду", "надо было", "неверно", "не то"),
        "ask:fn:count|sum" -> Vector("сколько денег", "на какую сумму", "денег"), "ask:fn:avg|sum" -> Vector("обычн*", "типичн*"),
        "ask:dir:desc|asc" -> Vector("отсортир*", "упорядочен*", "в порядке"),
        "sumPhrases" -> Vector("сумма заказов", "сумма продаж", "итого")),
      numbers = Map("один" -> "1", "одна" -> "1", "три" -> "3", "пять" -> "5", "десять" -> "10"),
      tables = okay.desk.Phrase.Russian.tables.map((k, v) => k -> t(v._1, v._2, v._3, v._4)),
      columns = okay.desk.Phrase.Russian.columns.map((k, v) => k -> c(v._1, v._2, v._3)),
      values = okay.desk.Phrase.Russian.values, stemDrop = 2, pieces = Map.empty)
