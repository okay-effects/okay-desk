# Пилот in the calculus — Okay!Chat's deterministic dialogue model as handlers of `Weigh`

*The operator, 2026-11-07: «есть проект okay-chat, где есть детерминированные диалоговые модели — подумай, как они могут
быть
встроены и использованы в нашем новом исчислении». Written after reading Okay!Chat's README and its specs (`pilot.md`,
`routing.md`, `turn-decision.md`, `meaning.md`, `slot-matching.md`, `memory.md`, `learning-loop.md`, `intent-dsl.md`,
`conversation-continuations.md`) and the platform's `okay.frame.Frame`, `okay.intent.{Centroid, Induced, Dsl}`. Nothing
below is built; every claim about that code names the file it was read in.*

## 1. The one-sentence claim

Пилот and the calculus are the same shape seen from two sides: Пилот is a chain of deterministic readers that answers,
acts or ASKS and never lets a model supply a fact; the calculus is a chain of `Weigh` handlers over typed holes that
closes a term or leaves it OPEN and never lets a model supply a figure. Each has what the other lacks. Пилот has the
dialogue — the act layer, the question per slot, the suspension across a restart, the person's lesson, the learning
loop with a person in it. The calculus has the typed term — a reading is a term, a hole has a type, the data fills what
is written, ambiguity is an open hole, and a closed term runs exactly. The embedding is: Пилот's layers become handlers
of `Weigh`; the calculus' open hole becomes Пилот's question; a need in the marketplace becomes a term of a grammar.

## 2. The correspondence, item by item

| Okay!Chat (where read) | the calculus (where it is) | the same thing, or what is missing |
|---|---|---|
| the layer order rules → typos → vectors → a question (README "How a turn is routed") | `Fill.first(exact, readers, model)` → an open term (`Refine.scala`, `Strategy.scala`) | the same chain; theirs has a layer the calculus lacks — the person's own lesson AHEAD of the rules (`Support.Remembered`) |
| `Support.Exact(rule) / Typo(d) / Semantic(p, runnerUp)` (turn-decision §Layer 1) | `Dist` — candidates with weights, `Step` notes (`Weigh.scala`) | a `Dist` says how much, not WHO; the Writer notes say the hole and the choice, not the source. Add the source to the step |
| two gates: threshold + margin over the runner-up (README "Two gates") | `margin` in `Refine.greedy`; "two moves too close" at level 0 | the same rule; the calculus' margin is per strategy, theirs per intent (`"semantic": false` opts out) |
| `Frame`: `Slot(name, ask per lang, parse, extract, show, options)` (`okay/okay-frame/Frame.scala`) | `Ty` + `Rules.constructors` (options), `Fill.exact` (extract), `Say` (show), `Language.pieces` | missing in the calculus: `ask` — the QUESTION of a hole, per language; `parse` of a person's answer to it |
| `okay.agent.Conversation`: an intake suspended in a journal, resumed after a restart (`Delim.pause`, `Dialogue`) | `Strategy.program` over `Choose.among` — pure, finishes in one go | missing: the person as a `Weigh` handler that PAUSES; the rest of a reading as one value (the open term + the program) |
| `Turn.decide(state, evidence): Action` — `Answer(field) / Correct(field) / Fire / Ask / Distinguish(a, b) / Acknowledge / Menu` | level 0: a state, the moves enabled, a step, a chooser (`Level0.scala`) | the dialogue is one more level: the state is the term so far and the hole pending; the moves are Пилот's actions |
| the act layer: answer / correction / question-about-us / pleasantry; polarity yes/no read directly | nothing — the page reads every message as a question | missing: «да», «не так», «спасибо» must not become terms |
| `ModelLane.stray`: a reply naming a date, price, place, link no tool returned is refused | `Claims.readBack` / `Answer.readBack`: every number in the sentence is a cell or an exact derivation | the same law, stated twice; the generating lane on the page ("kept only when faithful") should use the stronger of the two |
| `intent.Dsl`: `Lit / Stem / Opt / Words`, gaps with no `.*`, `(?iU)` and `\b` emitted; `Catalog` built, `Compile`d, `TestIntentDsl` keeps the shipped file equal to the builder | `Language.lists` — strings with a trailing `*`, a hand-rolled stem (`stemDrop`), a regex built in `Context.word` | the calculus' word lists are the strings Okay!Chat left in 2026-09; the Dsl is the builder they should be |
| `okay.intent.Induced` — RIPPER-shaped cue rules grown from the corpus, pruned, a person can read them | the exact rules are written by hand; 30 of 242 teacher pairs misread by wording ("сколько денег" is a sum) | grow the lists from the teacher pairs per constructor, review, compile into `lang/<code>.json` |
| «надо было: …» → `taught` in the journal → `Remembered` for that person → harvest → corpus (memory.md) | nothing | the learning loop, closed, with a person in it — ready-made for the page's refusals and corrections |
| `Need(core, place, window, budget, text)` and `Slots.Verdict` per slot (slot-matching.md) | a term of a grammar; `Den` runs it | a NEED IS A TERM: `Rules` whose types are the slots; the intake is `Strategy.greedy` with the person last; matching by slot is its denotation against the listings |
| "the rest of a conversation is a scattering of 28 maps; make it one value" (conversation-continuations §3) | an open term is a value; a step is a value; a strategy is a program | the value they are looking for: the term with its pending hole, in the journal |

## 3. Four embeddings, in the order to build them

### 3.1 Пилот's layers as handlers of `Weigh` (the service side)

- `Fill.rules(catalog)`: a `Weigh` handler over Okay!Chat's `Dsl` rules — one candidate where a rule fires, none where
  none does, `?` where a rule of a constructor the grammar lacks fires. This is `Fill.exact` with the words built,
  not written: `(?iU)`, `\b`, stems and gaps emitted, a vocabulary written once. The calculus' `Language.lists` become
  a `Catalog` per language; `TestIntentDsl`'s discipline (the shipped file equals the builder) replaces the GF law's
  job of catching a typo in a list.
- `Fill.fuzzy`: the typo layer — a bounded edit distance on the SAME vocabulary (`Fuzzy.literalTriggers`), so a
  misspelt «сколко» still reaches the count constructor, and an exact-format slot (a year, a number) is never corrected.
- `Fill.centroid(encoder)`: the vector tier on the JVM without a transformer in the loop — the authored phrasings of
  each constructor embedded by the 23 MB encoder, a centroid per candidate, cosine with the margin gate. The page keeps
  the readers (states of the model already there); the service, which has no GPU and no Qwen, gets the same tier from
  the encoder it already runs. Two handlers, one operation, one law (`WeighSuite`).
- `Fill.remembered(person)`: the person's own lessons FIRST — «надо было: по странам» pairs the sentence with the hole's
  candidate; the next time the same words (or a typo away) read to it before any rule. The journal is the truth; a
  restart folds it back (memory.md).

Order: `Fill.first(remembered, rules, fuzzy, centroid | readers, model)`. Each source only where the one before was
silent — Okay!Chat's chain of responsibility, as the calculus already writes it.

### 3.2 The person as a source, and the dialogue as a level (both sides)

The open item in `docs/calculus.md §10`: "the person as a source and dialogue as refining the last term". Okay!Chat
has built exactly this piece, and the calculus has the type it was missing:

- A hole gets a QUESTION: `Language.pieces("ask:<type>")` — "which column: amount or day?", «по какому столбцу — amount
  или day?» — the `Slot.ask` map, data per language, journalable. `Distinguish(a, b)` is the question of a hole whose
  two best candidates are within the margin; `Ask(slot)` is the question of a hole no source had an opinion on.
- The person is a `Weigh` handler that PAUSES: `Fill.person` answers `Weigh.Of(ty, cs, ctx)` by emitting the question
  and suspending the program (`Delim.pause` on Okay!Chat's platform; on okay-scala, the program returned with the hole
  as its state — `Strategy.greedyIn` already returns `(steps, term)`, and an open term with its steps IS the paused
  program, since the strategy is deterministic from the term). The answer, when it comes, is read by the exact fill
  over the hole's candidates (`Slot.parse`: a re-ask rather than a stored string), and the strategy continues from the
  term. This is "conversations as continuations" with the continuation made explicit: the term.
- The act layer before reading: `yes / no / correction / pleasantry / question` — Okay!Chat's `Acts` head and polarity.
  The page today reads «спасибо» as a question about the data. With the act decided first, «да» answers the pending
  hole, «не так: по странам» corrects the last term's group hole (a lesson), «спасибо» acknowledges.
- Level 4, the dialogue: a calculus described by level 0 — the state is `(term, pending hole, language, lesson book)`,
  the moves are `Turn.Action` (answer the hole, correct a hole, fire the closed term, ask, distinguish, acknowledge),
  `next(state)` enables them from the evidence, the chooser is the decision table. `Turn.decide` is pure and listable
  (turn-decision §Layer 2) — the same property the levels have. Laws: replayable from its journal; a correction
  reaches the term it names; a closed term fires once.

### 3.3 A need as a term (Okay!Chat's side)

`Need(core, place, area, country, remote, window, budget, text)` is a term of a small grammar: `TradeT`, `PlaceT`,
`WhenT`, `MoneyT`, each a type with its constructors from the data (the trades table, the places table, the calendar,
the currencies) — the way `ColumnT` takes its constructors from the schema. Then:

- the intake is `Strategy.greedy` with `Fill.first(remembered, rules, extract, centroid, person)`: what the sentence
  writes fills itself (`Slot.extract` is `Fill.exact`), what it does not is asked (the person), what is ambiguous is
  distinguished. The "frame parser measured before it is built" (meaning.md) becomes a `Weigh` handler over spans —
  the centroid of a slot's prototypes in context — and is measured the way every handler is: against the oracle on the
  journal's 317 turns.
- matching by slot is the DENOTATION: `Den.run` of the need-term against the listings yields the verdicts per slot
  (`Same / Near / Within / Over / Unknown`), and the order over them; the reply says the verdicts (`Say`), so the
  reasons are in the term, not in a sort key.
- the 28 per-person maps become one value: the open need-term with its pending hole, in the journal.

### 3.4 The learning loop closed over the calculus (both sides)

Okay!Chat's cycle — journal → measure → propose → a person decides → compile → verify → ship — fits the calculus
with no new box:

- the journal: every reading on the page with its steps (which source decided each hole) and what followed (a
  correction, a re-ask, «спасибо»);
- measure: `Calibration` per constructor — how often the exact fill decided, the readers, the model, the person; which
  holes stay open most;
- propose: `Induced` over the pairs (question, gold term) per constructor — cue rules a person can read, grown from
  the teacher's 242 pairs and the page's lessons; today 30 of those pairs are misread by wording and the fix is said
  in `docs/calculus.md §7` to be "the rules' silence there, not their cleverness" — induction with a precision floor
  is exactly the tool for finding WHERE to be silent and where a new word belongs;
- a person decides: `review.sh` on the proposed lists;
- compile: into `Language` (`lang/<code>.json`), with the GF law and the "data alone never wrong" law as the gate;
- ship: the page; the number to drive down is the share of holes the model or the person had to decide.

## 4. What the two gain, concretely

Okay!Chat gains: a typed reading where it has strings and 27 branches; the intake as one program with the person as
the last handler; a denotation for matching that leaves its reasons on the hit; the calculus' laws (data alone never
wrong; the space is the reading; a point and its term round-trip) over its own grammar; and a vector tier that is the
model's own states when a model is there, the encoder's centroids when not — the same handler interface.

okay-desk gains: the dialogue it does not have (acts, questions per hole, the person as a source, suspension,
lessons); word lists that are built, not written; a typo layer; a learning loop with a person in it; the `stray` law
for the generating lane; and a second real grammar (needs and listings) to prove the calculus is not about tables.

## 5. The seams

- Two effect libraries. Okay!Chat is on `okay` (`Delim`, `Row`, `Dialogue`), okay-desk on okay-scala (`!`, `Choose`,
  `Writer`). The calculus core is data (`Ty`, `Term`, `Rules`, `Dist`) plus ONE operation; porting `Weigh` and the
  strategy to `okay`'s row is a day's work, and the data types carry over as they are (both Scala 3; okay-desk 3.3.8,
  Okay!Chat 3.9.0 — the core compiles on both). Publish `okay-desk-core` as a jar and depend on it, or port the ten
  files; do not merge the repositories.
- The invariant. Okay!Chat's "no model supplies data" is stronger than the calculus' "no model supplies a figure": a
  model there may not choose a tool's ARGUMENT that it invented. In the calculus a model may weigh a VALUE hole among
  the data's candidates — that is the same rule (the candidates come from the data; the model orders them), and it
  should be said so in `AGENTS.md` there before any handler ships.
- Pace. Okay!Chat gates every routing change by a replay of the live journal with zero differences. The same gate for
  handlers here: the 242 pairs and the 19 GDP questions replayed through the old chain and the new.

## 6. The first step

`Fill.person` with a question per hole type in `Language.pieces`, on the page: a hole with no opinion or two close
candidates becomes a question with buttons (the page already shows "not sure: A or B" buttons, `App.scala` :156 — the
buttons exist; what is missing is that the answer refines the SAME term instead of re-reading the question). Then the
act layer (yes / no / correction / thanks) before reading. Measured on the teacher pairs: how many of the 30 misread
become one question instead of a wrong answer. That is the piece both projects need, and it needs no encoder, no
port and no new effect.
