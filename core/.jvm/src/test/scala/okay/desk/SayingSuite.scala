package okay.desk

import okay.desk.calc.*
import okay.scala3.*

/** A LANGUAGE IN BOTH DIRECTIONS (Say ↔ the exact fill): what a language file says of a point, the data alone reads
 * with that language loaded and never contradicts — Ukrainian and Polish, from `web/page/lang`, no code of theirs */
class SayingSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = new Grammar(s)
  val suite = new CalculusSuite
  def load(code: String): Language =
    val file = Vector(s"web/page/lang/$code.json", s"../web/page/lang/$code.json", s"../../web/page/lang/$code.json").map(java.nio.file.Path.of(_)).find(java.nio.file.Files.exists(_)).getOrElse(fail(s"no lang/$code.json"))
    Language.parse(java.nio.file.Files.readString(file)).fold(e => fail(e), identity)

  for code <- Vector("uk", "pl") do
    test(s"$code: 313 points said by the language file, read by the data alone with it — no contradictions, a share closed"):
      val lang = load(code)
      val rnd = new scala.util.Random(11)
      var closed = 0; var contradicted = Vector.empty[String]
      for p <- suite.gold ++ suite.randoms do
        val w = Encode.word(Den.term(s, p))
        val text = Say.say(rnd, p, s, lang)
        val ctx = (s, text)
        // (the languages of the reading: the file's, with English)
        val nextIn = new Word.Next[Encode.Ctx]:
          def apply(c: Encode.Ctx, word: Word.Letters): Option[Vector[String]] =
            val t = Encode.tree(g, word)
            g.nextHole(t).map { (ty, _, _) =>
              val cs = g.constructors(ty)
              run(Fill.exact[Pure](ty, cs, Context(c._1, c._2, t, languages = Vector(lang, Language.En.language)))) match
                case Vector((only, _)) => Vector(only)
                case _ => cs }
          override def open(c: Encode.Ctx, word: Word.Letters): Boolean = g.nextHole(Encode.tree(g, word)).exists((ty, _, _) => g.open(ty))
        Word.contradiction(nextIn, ctx, w).foreach((i, got, want) => contradicted = contradicted :+ s"$text → at $i: $got, gold $want ($w)")
        val r = run(Word.read(nextIn, Word.chooser[Encode.Ctx, Pure]((_, _, _) => pure(Vector.empty)), ctx))
        if r.terminal && r.moves == w then closed += 1
      println(s"  $code: the data alone closes $closed of ${suite.gold.size + suite.randoms.size}; contradictions ${contradicted.size}")
      contradicted.take(12).foreach(c => println(s"    $c"))
      assertEquals(contradicted.size, 0)
      assert(closed > 100, s"$closed closed")
