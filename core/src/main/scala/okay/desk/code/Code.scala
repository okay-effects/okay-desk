package okay.desk.code

import okay.datalog.{Eval, Fact, Parse}
import okay.inlay.domain.{Domain, Kit, Pair, Slot}
import okay.desk.calc.Language
import okay.desk.organ.{Organ, Template}

/**
 * THE CODE DOMAIN (BACKLOG C: «научи модель читать код через symdex»): questions about a codebase as a space of
 * meanings — a RELATION of symdex's Datalog prelude (who calls X, what X calls, what implements X, what X
 * overrides, what a change to X affects, which tests cover X, where X is defined, what X is, which module holds
 * it) and a SYMBOL copied from the question. The exact engine is the same prelude symdex runs, on the facts symdex
 * gives (`defined, kind, named, defined_in, in_module, calls, extends, overrides, test`), evaluated by okay-datalog in
 * process — the model never names a caller or a test; it reads which relation and which symbol the question means.
 * An `okay.inlay.domain.Domain`, so the kit gives pairs, reading with abstention and the measure; the organs
 * (`Organ.induce`) learn the relation's words from the pairs.
 */
object Code extends Domain:
  /** a symbol of the index: SemanticDB's name, its short name, its two-segment name (`Dialogue.read`), its kind */
  final case class Sym(symbol: String, short: String, qualified: String, kind: String)
  final case class Schema(symbols: Vector[Sym], facts: Vector[Fact]):
    lazy val bySymbol: Map[String, Sym] = symbols.map(s => s.symbol -> s).toMap
    /** the names a question may write, longest first; a short name only when it names one symbol */
    lazy val names: Vector[(String, Vector[Sym])] =
      // (a type and its companion object are one name: the type stands for both)
      // (a lowercase short name — `max`, `which`, `is` — is a word before it is a symbol: written with its owner only)
      val byQualified = asked.groupBy(_.qualified).toVector
      val byShort = asked.filter(_.short.headOption.exists(_.isUpper)).groupBy(_.short).filter((_, ss) => ss.size == 1).toVector
      (byQualified ++ byShort).sortBy((n, _) => -n.length)
    /** symbols a question is likely about: classes, objects, traits and methods, not parameters; a type stands for
     * its companion object too */
    lazy val asked: Vector[Sym] =
      val ss = symbols.filter(s => Set("class", "object", "trait", "method").contains(s.kind) && !s.short.startsWith("<"))
      val types = ss.filter(_.symbol.endsWith("#")).map(_.symbol.dropRight(1)).toSet
      ss.filterNot(x => x.symbol.endsWith(".") && types.contains(x.symbol.dropRight(1)))
  type Answer = Vector[String]

  enum Rel(val goal: String, val variable: String):
    case Callers extends Rel("callers", "Y")
    case Calls extends Rel("calls", "B")
    case Implementers extends Rel("implementers", "Y")
    case Up extends Rel("up", "B")
    case Affects extends Rel("affects", "Y")
    case Tests extends Rel("tests_for", "T")
    case DefinedIn extends Rel("defined_in", "F")
    case Kind extends Rel("kind", "K")
    case Module extends Rel("in_module", "M")
  val relations: Vector[Rel] = Rel.values.toVector

  final case class Point(rel: Rel, symbol: Sym)

  val name = "code"

  /** symdex's prelude (CodeFacts.prelude, v0.8), goal-directed by `seed` */
  val prelude = Parse.program("""
    callers(X, Y) :- seed(X), calls(Y, X).
    callers(X, Z) :- callers(X, Y), calls(Z, Y).
    implementers(X, Y) :- seed(X), overrides(Y, X).
    implementers(X, Y) :- seed(X), extends(Y, X).
    implementers(X, Z) :- implementers(X, Y), overrides(Z, Y).
    implementers(X, Z) :- implementers(X, Y), extends(Z, Y).
    up(X, B) :- seed(X), overrides(X, B).
    up(X, C) :- up(X, B), overrides(B, C).
    affects(X, Y) :- callers(X, Y).
    affects(X, Y) :- implementers(X, Y).
    affects(X, Y) :- up(X, B), calls(Y, B).
    affects(X, Z) :- affects(X, Y), calls(Z, Y).
    tests_for(X, T) :- affects(X, T), test(T).
  """).fold(e => throw IllegalStateException(e), identity)

  def enumerate(s: Schema): Iterator[Point] = for r <- relations.iterator; sym <- s.asked.iterator yield Point(r, sym)
  def random(rnd: scala.util.Random, s: Schema): Point = Point(relations(rnd.nextInt(relations.size)), s.asked(rnd.nextInt(s.asked.size)))

  /** THE EXACT ENGINE: the goal `rel(X, V)` with X the symbol as a seed, V's bindings as the answer, sorted */
  def run(p: Point, s: Schema): Either[String, Answer] =
    val q = "\"" + p.symbol.symbol + "\""
    for
      goal <- Parse.atom(s"${p.rel.goal}($q, ${p.rel.variable})").left.map(e => s"goal: $e")
      result <- Eval.run(prelude, s.facts :+ Fact("seed", Vector(p.symbol.symbol))).left.map(e => s"evaluation: $e")
    yield result.query(goal).map(_.values.head.toString).distinct.sorted
  override def useful(a: Answer): Boolean = a.nonEmpty

  /** the answer as people see it: short names for symbols, files and kinds as they are */
  def show(a: Answer, s: Schema): Vector[String] = a.map(x => s.bySymbol.get(x).map(_.qualified).getOrElse(x))

  // ---- the phrases, English and Russian, {X} the symbol

  val phrases: Map[Rel, Map[String, Vector[String]]] = Map(
    Rel.Callers -> Map("en" -> Vector("who calls {X}?", "what calls {X}", "callers of {X}", "where is {X} called from"),
      "ru" -> Vector("кто вызывает {X}?", "откуда вызывается {X}", "вызовы {X}", "кто использует {X}")),
    Rel.Calls -> Map("en" -> Vector("what does {X} call?", "what is called from {X}", "callees of {X}", "what does {X} use"),
      "ru" -> Vector("что вызывает {X}?", "какие методы вызывает {X}", "что вызывается из {X}", "чем пользуется {X}")),
    Rel.Implementers -> Map("en" -> Vector("what implements {X}?", "implementations of {X}", "subtypes of {X}", "who extends {X}"),
      "ru" -> Vector("что реализует {X}?", "реализации {X}", "наследники {X}", "кто расширяет {X}")),
    Rel.Up -> Map("en" -> Vector("what does {X} override?", "what does {X} extend", "parents of {X}", "what is {X} an override of"),
      "ru" -> Vector("что переопределяет {X}?", "что расширяет {X}", "родители {X}", "чей метод переопределяет {X}")),
    Rel.Affects -> Map("en" -> Vector("what breaks if I change {X}?", "what does a change to {X} affect", "impact of changing {X}", "what depends on {X}"),
      "ru" -> Vector("что сломается, если изменить {X}?", "на что влияет изменение {X}", "кого затронет правка {X}", "что зависит от {X}")),
    Rel.Tests -> Map("en" -> Vector("which tests cover {X}?", "what tests to run after changing {X}", "tests for {X}", "which suites exercise {X}"),
      "ru" -> Vector("какие тесты покрывают {X}?", "какие тесты гонять после правки {X}", "тесты для {X}", "какие сьюты проверяют {X}")),
    Rel.DefinedIn -> Map("en" -> Vector("where is {X} defined?", "which file defines {X}", "location of {X}", "find the definition of {X}"),
      "ru" -> Vector("где определён {X}?", "в каком файле {X}", "где лежит {X}", "найди определение {X}")),
    Rel.Kind -> Map("en" -> Vector("what is {X}: a class, an object, a method?", "what kind of thing is {X}", "is {X} a class or an object", "the kind of {X}"),
      "ru" -> Vector("что такое {X}: класс, объект, метод?", "какого рода {X}", "{X} это класс или объект", "вид {X}")),
    Rel.Module -> Map("en" -> Vector("which module is {X} in?", "what module holds {X}", "the module of {X}", "in which module does {X} live"),
      "ru" -> Vector("в каком модуле {X}?", "какой модуль содержит {X}", "модуль {X}", "где по модулям {X}")))

  def say(rnd: scala.util.Random, p: Point, s: Schema, language: String): String =
    val ws = phrases(p.rel).getOrElse(language, phrases(p.rel)("en"))
    // the two-segment name, or the short one where it names one symbol
    val name = if s.names.exists((n, ss) => n == p.symbol.short && ss.size == 1) && rnd.nextBoolean() then p.symbol.short else p.symbol.qualified
    ws(rnd.nextInt(ws.size)).replace("{X}", name)

  def slots(s: Schema): Vector[Slot] = Vector(Slot("relation", relations.map(_.toString)), Slot("symbol", Vector.empty, copied = true))
  def parts(p: Point): Map[String, String] = Map("relation" -> p.rel.toString, "symbol" -> p.symbol.symbol)
  def assemble(s: Schema, parts: Map[String, String]): Option[Point] =
    for r <- parts.get("relation").flatMap(n => relations.find(_.toString == n)); sym <- parts.get("symbol").flatMap(s.bySymbol.get) yield Point(r, sym)

  /** THE COPIER: the symbol named in the question — the longest known name written there, one symbol */
  object copier extends Kit.Copier[Schema]:
    def copy(question: String, s: Schema, slot: Slot): Option[String] =
      if slot.name != "symbol" then None
      else s.names.collectFirst { case (n, Vector(one)) if question.matches(s"(?s).*(^|[^\\w.])${java.util.regex.Pattern.quote(n)}([^\\w.]|$$).*") => one.symbol }

  /** THE DATA'S READER: a relation by its words (one per language), the first that fires in a fixed order */
  object rules extends Kit.Reader[Schema]:
    val words: Vector[(Rel, Vector[String])] = Vector(
      Rel.Tests -> Vector("test", "suite", "тест", "сьют"),
      Rel.Affects -> Vector("break", "affect", "impact", "depend", "слома", "влия", "затрон", "завис"),
      Rel.Implementers -> Vector("implement", "subtype", "extends", "реализ", "наследник", "расширяет"),
      Rel.Up -> Vector("override", "extend?", "parent", "переопредел", "расшир", "родител", "чей"),
      Rel.DefinedIn -> Vector("defined", "defines", "file", "location", "definition", "определ", "файл", "лежит"),
      Rel.Kind -> Vector("kind", "class", "object", "класс", "объект", "рода", "вид "),
      Rel.Module -> Vector("module", "модул"),
      Rel.Calls -> Vector("does", "called from", "callee", "use", "вызывает", "вызывается из", "пользуется"),
      Rel.Callers -> Vector("who calls", "what calls", "callers", "called", "кто", "откуда", "вызовы", "использует"))
    def read(question: String, s: Schema, slot: Slot): Map[String, Double] =
      if slot.name != "relation" then Map.empty
      else
        val q = question.toLowerCase
        words.collectFirst { case (r, ws) if ws.exists(w => q.contains(w.stripSuffix("?"))) => Map(r.toString -> 1.0) }.getOrElse(Map.empty)

  /** THE ORGAN'S READER: an organ induced from pairs (the question's stems → the relation) read by its weights */
  final class ByOrgan(organ: Organ, lang: String => Language) extends Kit.Reader[Schema]:
    def read(question: String, s: Schema, slot: Slot): Map[String, Double] =
      if slot.name != "relation" then Map.empty
      else organ.forward(Organ.words(question, lang(Language.detect(question).head))).flatMap(_.parts.get("relation")).map(r => Map(r -> 1.0)).getOrElse(Map.empty)

  /** the organ induced from pairs: the relation as the one part of the template */
  def induce(pairs: Vector[Pair[Point]], lang: String => Language, name: String = "code"): Organ =
    Organ.induce(name, pairs.map(p => (Organ.words(p.question, lang(p.language)), Template(Map("relation" -> p.point.rel.toString)))), minCover = 2)

  /** one reader after another: the first with an opinion */
  def first(rs: Kit.Reader[Schema]*): Kit.Reader[Schema] = new Kit.Reader[Schema]:
    def read(question: String, s: Schema, slot: Slot): Map[String, Double] =
      rs.iterator.map(_.read(question, s, slot)).find(_.nonEmpty).getOrElse(Map.empty)


  // ---- the schema from symdex's facts (`query goal=… format=json`, one relation per file)

  /** SemanticDB's symbol → (short, two-segment) names: `okay/desk/calc/Dialogue.read().` → (read, Dialogue.read) */
  def namesOf(symbol: String): (String, String) =
    val parts = symbol.split("[/.#]").filter(_.nonEmpty).map(_.replaceAll("\\(.*\\)$", "")).toVector
    val short = parts.lastOption.getOrElse(symbol)
    (short, parts.takeRight(2).mkString("."))
  def schema(relations: Map[String, Vector[Vector[String]]]): Schema =
    val facts = relations.toVector.flatMap((rel, rows) => rows.map(r => Fact(rel, r)))
    val kinds = relations.getOrElse("kind", Vector.empty).map(r => r(0) -> r(1)).toMap
    val symbols = relations.getOrElse("defined", Vector.empty).map(_.head).distinct.map { sym =>
      val (short, qualified) = namesOf(sym); Sym(sym, short, qualified, kinds.getOrElse(sym, "?")) }
    Schema(symbols, facts)
