# Learning on purpose: how to use the organs, the books and the code reader

The mechanisms of `specs/organs.md` and BACKLOG D, as a person uses them. The principle behind every one: the
model's weights never change; what grows is a set of exact parts — organs, dictionaries, paradigm blocks — each
behind an effect with a law. A thing a law has not passed does not serve. Everything here runs on the JVM in
seconds with no model loaded; the same code runs in the browser.

## 1. Organs

An organ is a reading rule over the words of a question, written as a program of okay-inlay's DSL and compiled
into a transformer block. It says, for a hole of the reading (a path in the term), a constructor — or nothing.

**Induce one from pairs.** A pair is a question and the term it means (`Organs.templateOf(term)` keys the
constructors by their path, the copied values left out):

```scala
import okay.desk.organ.*
val pairs: Vector[(Vector[String], Template)] =
  questions.map(q => (Organ.words(q.text, language), Organs.templateOf(q.goldTerm)))
val organ = Organ.induce("my-organ", pairs, minCover = 2)   // per path, FOIL gain, pruned on a held-out third
```

`minCover` is how many pairs a cue must cover to be kept; `prune = false` keeps every cue that is pure on the
training pairs (for a law on a small sample), the default prunes on a held-out third and keeps a cue only if it is
right on nine of ten pairs it fires on there.

**Check the law before anything else.** The block must equal the interpreter on the inputs you care about:

```scala
val ws = Organ.words(question, language)
assert(organ.forward(ws) == organ.interpret(ws))         // the compiled block == the reference
organ.forward(ws)                                        // Some(Template(path -> constructor)) or None
```

**Use it as a source of the reading.** The organ is a handler of `Organs.Call`; `Organs.fill` is the `Weigh` source:

```scala
type S[X] = Strategy.Rest[Organs][X]
val fill = Fill.first[S](Fill.exact[S], Organs.fill[S](Vector("my-organ")))        // the data first, then the organ
val (steps, term) = run(handle(Strategy.greedyIn[Organs](g, ctx, fill), Organs.byWeights[Pure, _](Vector(organ))))
```

Which order? Measured on 963 held-out teacher pairs (specs/organs.md §8): the organ BEFORE the data fixes more
and breaks some; AFTER the data it never breaks a right reading and fixes less. Put it after the data unless you
have measured otherwise on your pairs (`bench/OrganBench` is the measurement).

**Keep it as a file.** `OrganFile.json(organ)` / `OrganFile.parse(text)`: the cues as data. The file is what symdex
reads (§4) and what a page keeps beside the lessons. A file read back answers the same on every input
(`LearnSuite`).

**What an organ cannot do.** It reads words, not meaning: a wording it never saw is silence (good) or a cue that
over-generalizes (the measured risk, 1–5% wrong depending on the domain). Values — a number, a name, a date — are
never the organ's; the data copies them.

## 2. The book of a dataset

A data dictionary beside the data, a few lines a person writes (or a model authors offline), `web/page/books/<name>.md`:

```
# the World Bank's GDP series
table gdp: GDP, gross domestic product, economy; ru: ВВП, валовой внутренний продукт, экономика
column Value: GDP, value, output; ru: ВВП, значение, объём
column Year: year; ru: год, году
value Ukraine: ru: Украина, Украины
example: Which economies grew fastest?
example: Объём экономики Украины в 2020 году
```

`table`, `column`, `value` lines give the names' forms in any language (`en forms; ru: forms`); `example` lines
are questions the book says the data answers.

```scala
import okay.desk.learn.Book
val book = Book.parse(text, "gdp")
val lesson = Book.read(schema, book, words = words)      // the data alone, with the dictionary as a language
lesson.pairs       // the examples the data closed: (question, term) — gold by construction
lesson.questions   // the examples left open: (question, the hole, its options) — what to ask a person or a model
lesson.organ       // induced from the pairs; law-checked by its own forward == interpret
book.language      // the dictionary as a Language record: pass it in Context(…, languages = book.language +: …)
```

What it does and does not do, measured (`bench/BookBench`, the GDP book): 12 of 13 examples read by the data
alone with the dictionary, the 13th ("Compare Ukraine and Poland") a refusal — correct; on the 19 real questions
the dictionary changed nothing (30 right, 8 open, 0 wrong with the data's words, with or without the book),
because those questions do not use the book's words. A book helps exactly where people use ITS words. The open
examples are the curiosity loop: ask, get a pair, re-read.

## 3. The paradigm: a grammar as a block

The forms of a language's names (a table in four cases, a column in three) compiled into a block:

```scala
import okay.desk.learn.Paradigm
val p = Paradigm(Language.Ru.language)       // or a language from lang/uk.json, lang/pl.json
p.forward("orders", "gen")                   // Some("заказов") — by the weights
p.byTable("orders", "gen")                   // the same, by the table; the law: equal on every entry
```

The law holds for Russian, Ukrainian and Polish (180–189 forms each, `LearnSuite`). The use: a small model that
inflects badly gets the paradigm as an exact part; `Say` can take forms from the block as from the table.

## 4. The code reader: questions about a codebase

The Code domain (`okay.desk.code.Code`): a question means a RELATION of symdex's prelude and a SYMBOL.

**Index the project** with symdex (SemanticDB on, `symdex status --root <project>`), then dump the facts once:

```sh
for r in 'defined(S)' 'kind(S, K)' 'named(S, N)' 'defined_in(S, F)' 'in_module(S, M)' 'calls(A, B)' 'extends(A, B)' 'overrides(A, B)' 'test(S)'; do
  n=${r%%(*}; symdex query goal="$r" format=json limit=200000 --root <project> > ~/.cache/okay-desk/code/$n.json; done
```

**Induce the organ and measure** (`bench/CodeBench`; `CODE_FACTS=<dir>`): pairs from the space's phrases in
English and Russian (Ukrainian and Polish wordings are in `Code.more`), the organ from the even half, the measure
on the odd half. On okay-desk's own index: 97% whole, 1% wrong with the organ then the rules; the hand-written
rules alone 84% and 13.7% wrong. The bench writes the organ to `web/page/organs/code.json`.

**Ask symdex in words.** Copy the organ file to `<project>/.symdex/organs/code.json` (or pass `organ=<path>`):

```sh
symdex ask question="Who calls Dialogue.read?" --root <project>
symdex ask question="Какие тесты покрывают Dialogue.decided?" --root <project>
symdex ask question="Who implements Rules?" --root <project>      # 'Rules' names 2 symbols — which? …
```

The relation is read by the organ (its cues evaluated by symdex itself — the law is that this evaluator, the
interpreter and the block agree), the symbol by the name written and the index's resolver; two symbols of one name
are asked back, never guessed; the answer is the `query` tool's, exact, with `why=true` for proofs. Without an
organ file the tool reads the relation by built-in words, which is right less often (the bench's 84%): a silent
organ falls back to them, so "What does X extend?" may be read as "what does X call" by the words — write the
relation plainly, or pass an organ induced from your own wording.

**A new relation** is a line in `Code.Rel` with its goal, a row of phrases per language in `Code.phrases`, a word
list in `Code.rules`, and the prelude rule if it is derived. The laws (`CodeSuite`) check the engine by hand, the
round trip and that the organ is never wrong where it speaks.

## 5. The book about itself

`bench/SelfRead <docs…>`: every sentence of the documentation naming two or more code symbols is read as a
question about the code and checked against the index. Four claims of `docs/calculus.md` and the specs verify
("`CalculusSuite` … `LevelsSuite`, `WeighSuite`, `AnswersSuite`" are tests); most sentences are not claims of the
nine relations, and prose read as a question is read wrong more often than a question is — the number to improve
is the reader's silence on prose, not the engine. Use it as a drift detector on sentences written as claims.

## 6. The dialogue around all of it

A hole no source decides — the data, an organ, the readers, the model — is a question to the person with its
options (`Dialogue.read`, `Dialogue.question`); the answer is the first source when the reading runs again
(`Dialogue.decided`); «да», «нет», «спасибо», «не так: …» are acts, not questions (`Dialogue.Act`, `Turn.decide`).
The page does this (specs/pilot.md §7). The pairs a dialogue produces are the next organ's.

## 7. Where the numbers live

| what | where measured | the number |
|---|---|---|
| organs on the teacher pairs | `bench/OrganBench`, specs/organs.md §8 | fixed 67 / broken 29 before the data; 34 / 0 after |
| the code reader | `bench/CodeBench`, specs/organs.md §9 | 97% whole, 1.0% wrong (600 held-out) |
| the GDP book | `bench/BookBench` | 12 of 13 examples read; the 19 real questions unchanged |
| the paradigm | `LearnSuite` | 558 forms in three languages, block == table |
| the dialogue on the page | specs/pilot.md §7 | GDP 38: 31 right, 6 asked, 0 wrong |
