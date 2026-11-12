# Finite constraints inside the shared reasoning process

*The operator, 2026-11-08: «решатель любых логических задач и головоломок прямо в браузере? Возможно?» — and «а что
мешает добавить знания о мире через поиск в интернете?». This spec is the design; its laws are `PuzzleSuite`; the
page's screen is §6.*

## 1. The claim, with its one honest limit

The SOLVER is universal for the class: finite domains, constraints, search with propagation. The zebra puzzle,
seating, schedules, knights and knaves, kinship, sudoku-like grids, ages — one engine, exact, with the propagation
trace as the proof. The READING is not universal: a 0.6B model does not read arbitrary prose into logic. "Any
puzzle" is reached the way the tables are: a calculus of constraints with a bounded grammar (some thirty forms, four
languages), each clue read into a term and SAID BACK in words so the person sees exactly what will be solved; where
the sources are unsure the clue is a question with buttons; where the grammar lacks a form the person picks one and
the choice is a lesson, lessons become organs, the page reads new wordings next week. A larger model in the browser
(WebLLM) may propose a term for a free sentence; the term is type-checked, said back and confirmed — the model
proposes, it never decides. What stays outside: world knowledge (§7, a source with provenance), infinite domains,
puzzles of invention.

## 2. The model of a puzzle (`okay.desk.puzzle.Puzzle`)

- **Sorts**: named lists of values (`house: 1..5`, `colour: red, green, ivory, yellow, blue`, `nation: …`). One sort is
  the POSITIONS, ordered (houses in a row, seats, days); every other sort's values are placed on positions, one value
  per position (a logic grid: each value of each sort belongs to exactly one position).
- **Constraints**, each with its words in four languages (§4):
  `Same(a, b)` — a and b are on the same position (the Englishman lives in the red house);
  `Differ(a, b)`; `At(a, n)` — a is at position n (the Norwegian in the first house), `Middle(a)`;
  `Left(a, b)` — a somewhere left of b; `RightNext(a, b)` — a immediately right of b (the green house is immediately
  to the right of the ivory one); `Adjacent(a, b)` — next to each other; `Apart(a, b, k)` — k positions apart;
  `Count(sort, predicate, n)` later; knights and knaves and kinship as further families (§8).
- **A puzzle** is sorts, constraints, and the question (which value of which sort: "who owns the fish?" = the
  position of `fish` and the nation there).

## 3. The engine (`Solver`)

Variables: one per (sort, value) — its position. Domains as bit sets over positions. Propagation: all-different per
sort (a value fixed removes its position from the others; a position left for one value fixes it), and each
constraint's arc rule (Same: the two domains intersect; At: a singleton; RightNext: shifts; Adjacent: the union of
shifts; Left: bounds). Search: the value with the smallest domain, branch, propagate, backtrack. Every removal is
recorded with the constraint (or the all-different) that caused it: THE TRACE, which is the proof shown. Outcomes:
one solution (the grid and the trace), several (how many, up to a bound, and the values that differ — the puzzle is
under-determined, the person is told which clue would settle it), none (the smallest set of clues found inconsistent
by removing one at a time — the person is told which clues clash). Puzzle sizes are tiny: the whole classic puzzle
propagates in milliseconds in Scala.js.

## 4. The reading (`PuzzleDomain`, an `okay.inlay.domain.Domain`)

A clue is a point: the FORM (one of the constraint kinds — a choice slot) and its VALUES (copied: the names written
in the clue, found among the sorts' values and their forms in the languages — "англичанин" ↔ Englishman, "в красном
доме" ↔ red). Phrases per form in en/ru/uk/pl are the generator (`say`), so pairs come from the space; the words
reader (`rules`) is the baseline, an organ induced from the pairs the learner, the dialogue the fallback. The law
(GF's): every phrase of every point reads back to its point with the data alone where the values are written, and the
organ never contradicts a point where it speaks. The read-back (`Say`) of a constraint is one sentence; the person
confirms a clue by seeing it.

The puzzle text: a header declaring the sorts (`Houses: 1-5`, `Colours: red, green, …`), as every logic-grid puzzle
prints, then one clue per line, then the question. Values may be written in any declared form; the languages' name
forms and the organ's lessons extend them.

## 5. Laws (`PuzzleSuite`)

- The classic puzzle's fifteen clues as constraints: one solution; the German owns the fish; the trace names, for
  the Norwegian's house, clue 10 (the Norwegian lives in the first house).
- A clue removed: several solutions, the differing values named. A clue negated: no solution, the clashing clues
  named.
- The reading: the classic text in English and in Russian read into the fourteen constraints by the data alone (the
  values written, the forms' words); every phrase of 300 random points reads back (GF).
- Propagation alone solves what propagation solves; search never changes a propagated answer (the trace is sound).

## 6. One chat

Superseded the initial separate-screen design: the same Ask input accepts the task, its read-back and exact result
appear in the conversation, and unresolved readings are clarified there. Missing world evidence uses the same
online-source permission as any other question. There is no puzzle mode and no separate Read/Solve flow.
The common architecture is [reasoning.md](reasoning.md).

## 7. The world as a source (`Know`)

A constraint may name a FACT the puzzle does not state ("the one born in a country bordering France"): a hole of a
kind "world fact". The model reads which fact is asked (the form and the names); the fact itself comes from an
effect `Know(question)` with handlers in order — Wikidata (entity search and a dozen properties, structured, with the
entity's id), okay-facts, Wikipedia with the quoted sentence for the person, the person. Every world fact in the
trace is one line with its source and date; without the network the puzzle solves without it and says which line is
missing; the page asks once for leave to use the network (the promise "nothing leaves the browser" is kept by
default and lifted explicitly). The model never reads a fact out of free text: structured answers and quotes only.

## 8. Further families, by grammar not by code

Knights and knaves: a sort of persons, a Boolean per person, a constraint `Says(p, statement)` whose truth equals
the speaker's kind; statements over the same forms. Kinship: a sort of persons and a relation table filled by clues,
Datalog rules (okay-datalog) for the derived relations, with why-provenance as the trace. Grids (sudoku-like):
positions as cells, all-different per row, column and box. Ages and small arithmetic: positions as numbers with
`Sum`, `Twice`, `OlderBy`. Each is a set of forms and phrases on the same engine and the same page.

## 9. Built (2026-11-08)

`puzzle/`: sorts, finite constraints, the classic zebra puzzle; propagation, search, uniqueness and a
deletion-minimal inconsistent subset; `PuzzleDomain` as an okay-inlay domain, phrases, copying, rules and organs;
`PuzzleText` as the header/clue reader. `PuzzleSuite` and `WorldSuite`. The chat's constraint tool lives in
`ui/Puzzles.scala`; it has no screen. A missing clue or invalid schema blocks solving.

## 10. World snapshots and compiled selection

A world hole is explicit about its candidate sort and property:

```text
Countries: France, Germany
Pets: dog, cat
France is in house 1.
The one whose countries/capital is Paris goes with dog.
Which country owns dog?
```

The Russian form is `Тот, у кого countries/capital — Paris, вместе с dog.`
`World.Fact(subject, property, values, source, date)` holds a complete property snapshot from a source.
Missing subjects are unknown, not negative facts. Disagreeing snapshots, multiple or absent matches leave the
hole open. Only a unique match among fully covered candidates becomes an ordinary `Same` constraint.

`World` compiles a categorical inverse property lookup into an okay-inlay `Lookup(all = true)` head and a
`Many` result. `Forward` runs the compiled weights in JVM and Scala.js; no subject is averaged or arbitrarily
selected. `WorldSuite` compares the block with `Interpret` and ordinary set membership on 60 seeded snapshot sets.
Limits: 64 snapshots and 64 property values. The internal JSON format preserves a source URL and snapshot date.
A supplied source is provenance, not a verification that the source is true or complete.

`World.example` is a manually checked test snapshot of current capitals, dated 2026-11-08:
[France](https://www.wikidata.org/wiki/Q142) → Paris and
[Germany](https://www.wikidata.org/wiki/Q183) → Berlin. It is not automatically supplied to user questions.
The application collects its own evidence instead (§11).

## 11. Autonomous collection

`Wikidata.plan/decode` authors SPARQL requests for every candidate subject. The person supplies neither answers
nor files. Supported properties map from English, Russian, Ukrainian and Polish names to P36 (capital), P30
(continent), P38 (currency) and P47 (borders). This is a dictionary of property IDENTIFIERS, not world facts.

Country, city and person sorts constrain entity lookup by `P31/P279*`; other sorts require an unambiguous label
or alias match. Queries escape the search terms, use `wikibase:BestRank`, exclude ended intervals, future starts
and point-in-time statements. Values have labels in the detected language. The service's `NOW()` supplies the
retrieval date; the source links to the entity and property. See
[Wikidata ranking](https://www.wikidata.org/wiki/Help:Ranking).

Limits: twelve subjects per hole, at most one hundred bindings per subject, plus §10's snapshot bounds.
Multiple entities, colliding labels, absent statements or labels, truncation, malformed responses and HTTP
failures keep the hole open. Imports are atomic, and failure keeps the existing cache. Other qualifier contexts
are not interpreted as general logic; the facts are the source's statements under this bounded time/rank policy.
Names without distinctive language characters default to English. Entity-choice dialogue remains planned.

## 12. The shared process

The operator's final direction is `specs/reasoning.md`: any question is a reasoning task, possibly requiring
knowledge. The finite solver is one exact tool beside arithmetic, source collection, checked citations and the
existing relational/Datalog tools. Network permission, evidence and answers belong to the common conversation.
`ReasoningUiSuite` exercises arithmetic, world-dependent constraints and an ordinary source-based question in
one chat on JVM and Scala.js. `scripts/puzzles.scala` drives that one input on desktop and phone; `WORLD_LIVE=1`
uses the real services. Further families in §8 and richer sources remain in `BACKLOG.md`.

## 13. Built: source-declared house grids (2026-11-09)

`GridText` reads header-free house grids with a bounded English/Russian vocabulary. The source must state the
house count and the category uniqueness rule; every mentioned category must contain that many distinct values.
Missing values are never filled from the remembered classic puzzle. Unknown clues and questions block solving.
The chat shows the inferred categories and each interpreted constraint before the result.

The reported Russian Einstein text is preserved in `core/src/test/resources/puzzles/einstein-ru.txt`.
The reader accepts its singular cigarette brands, central house, singular neighbour and observer-relative right
direction, including the `е` spelling of `ё`. Multiple questions on one line are preserved and answered from the
computed grid. Overlapping names such as `Blue Master` use the longest copied span.

`NaturalGridSuite` checks both answers, changed nationalities, contradictions, incomplete declarations, unknown
questions and the worker protocol on JVM and Scala.js. `ReasoningUiSuite` checks the common chat without model
loading or external sources. `scripts/einstein.scala` drives the exact reported text in a phone-sized Chromium
viewport with WebGPU disabled and the real 12-layer student resident on WebGL2. The finite solver runs in the
CPU Worker; it also answers a second request offline. This is not a physical-phone performance measurement.
Arbitrary vocabularies and unrestricted prose-to-constraint reading remain open work.

The second reported text, `einstein-fish-ru.txt`, declares uniqueness across three sentences. The reader checks
coverage for each inferred category, accepts plural animal forms and a colour without a repeated house noun,
and combines `слева от …, рядом с ним` into immediate left. `Вопрос:` and `Кроме того:` are section labels.
Latin-script cigarette names after Russian `курит`/`курят` are copied from the source, including unseen brands;
the stock list is no longer required for those names. `NaturalGridSuite` checks this text and renamed brands.
An independent permutation enumeration gives one solution, with the German owning the fish in house 4.

Public-site verification: build `525f2c5ef2e9`, Pages commit `5afb8ad4a7a1818d73cfc0a329c5b098c9f966d1`.
`scripts/einstein.scala` passed against `https://okay-effects.github.io/okay-desk/`, including the loaded WebGL2
student, CPU Worker, both answers and the second request offline.

The fish-variant fix was verified on Pages build `6efdfcc96f82`, commit
`646dbbffa60d0b784e4c4db684b2bc3e309066eb`. The browser driver passed both reported texts with the real student
resident on WebGL2 and the fish request offline. This exercises the symbolic clue reader, not learned general
understanding by the student; the shared semantic compiler is still planned in `reasoning.md` §10.
