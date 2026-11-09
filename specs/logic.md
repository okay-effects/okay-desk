# The logic fragment: the model weighs a Datalog program

The operator's requirement (2026-11-09): a small model on the page, WebGPU or WebGL2 on a phone, reads an arbitrary
logic task and builds its formal definition; exact handlers compute the answer. The model never counts and never
invents a constant; an open hole is a question, not a guess. This spec holds the design, the laws and the numbers.
`BACKLOG.md` lists what is not built.

The diagnosis that motivates it is in `understanding.md` §5 and `data.md` §18: one generative pass does segmentation,
ontology, logic and syntax at once. The intact 28-layer control passes 12/12 short mutation tasks but 0/8 longer
held-out questions, while data + readers + exact tokens reach 91% whole with zero wrong. Capacity is not the
constraint; the interface is. So the model does what a small model does best — copy spans and weigh candidates in a
typed grammar — and an exact engine composes and checks.

## 1. The rule

- The model reads the task. There is no symbolic text parser and no topic dictionary standing in for the model.
- The model chooses points of a typed grammar of Datalog programs; it does not emit free JSON or prose.
- The grammar is the formal target language, not a reader: semantic decisions stay with the model.
- Exact handlers (`okay-datalog`) evaluate the program. A number is a copied constant or an engine binding, never a
  generated one.
- A closed term is not re-asked; an open hole is a question to the person, with options from the grammar.
- The existing product path (the 12-layer student, the data readers) does not regress; this is an added path.

## 2. Ontology (built)

`core/src/main/scala/okay/desk/logic/Ontology.scala`:

```scala
final case class Predicate(name: String, args: Vector[String])          // args: the sort of each position
final case class Ontology(predicates: Map[String, Predicate],
    constants: Map[String, Vector[String]], inputs: Set[String])
```

- A predicate's argument sorts type the constants; variables stay untyped, as Datalog has them, and the grammar uses
  a sort only to offer a constant where it fits.
- `inputs` are relations given by the data or the text (level 0). Every predicate not in `inputs` is derived: a rule
  may define it.
- `ofTables` turns an opened table into an input predicate: the sorts are `table.column`, and a sort's constants are
  that column's distinct non-empty values. This is the bridge to `Space.Schema`; the tables themselves are data.
- `LogicGrammar.pool` is `X Y Z U V W S T`; a constant named like a variable is refused, since Datalog would read it
  as a variable.

## 3. The grammar (built)

`core/src/main/scala/okay/desk/logic/LogicGrammar.scala` extends the existing `calc.Rules`. Its closed terms are
Datalog programs: a goal, facts, and rules with a head and a body of positive atoms, negated atoms and comparisons.
The new types live in `calc.Calculus` beside the data calculus, so `Encode`, `Weigh` and `Fill` are reused rather
than duplicated:

```scala
case LogicT                                   // the whole program
case GoalT                                    // the question's atom
case FactsT                                   // the facts
case RulesT(levels: Map[String, Int])         // the rules so far and the stratum of each defined predicate
case RuleT(levels: Map[String, Int])
case BodyT(head: String, vars: Vector[String], bound: Vector[String], levels: Map[String, Int])
case LitT(head: String, vars: Vector[String], bound: Vector[String], levels: Map[String, Int])
case AtomT(bound: Vector[String], fresh: Boolean, except: Vector[String])
case ArgT(pred: String, index: Int, bound: Vector[String], fresh: Boolean)
case TermT(bound: Vector[String])             // a comparison's operand
case CmpOpT
```

`Encode.tree(g, word, root)` is generalized to take the root type, so the same word codec covers a whole program.

### 3.1 Goal first

`Program` opens as goal, then facts, then rules. The first holes are the question's predicate and arguments, then the
facts, then the rules. The goal directs the reading; it is not a post-hoc check on a generated program. In the
corpus law, the word's first letters are `Program`, the goal predicate and its arguments.

### 3.2 Safety by construction

A body closes (`nil` offered) only when every variable of its head is bound by a positive atom; otherwise only
`cons`. A negated atom or a comparison offers only variables already bound. A fact offers constants only, so a
variable in a fact is not a point of the grammar. These are the range-restriction and safety conditions, enforced
where the term is written rather than checked afterwards.

### 3.3 Stratification by construction: levels, not a set

The earlier design tracked the set of defined predicates. It is unsound: a predicate may be defined by several rules,
and a later rule for an earlier predicate can close a cycle through negation. So every defined predicate carries a
LEVEL (an input and a fact predicate are 0).

- A rule's head needs a level at or above every positive dependency and strictly above every negative one.
- A head seen for the first time takes the level it needs; a head already defined keeps its own, so a rule that would
  need a higher one is refused (no constructor is offered, or `ofProgram` returns `Left`).
- `positiveAllowed` offers the inputs, the defined predicates at or below the head, and the head itself (recursion).
- `negativeAllowed` offers the inputs and the defined predicates strictly below the head; never the head.
- A rule head is always a derived predicate, never an input.
- Fact predicates are seeded at level 0, so a rule may use a fact's predicate positively without it being an input.

Levels only ever go up as the program is written, so the final program is stratified, and the level map is the
certificate. `LogicGrammarSuite` checks that every term the grammar enumerates is accepted by `okay-datalog`'s
`Check.strata`.

### 3.4 Refinement and enumeration

- `next(ty, con, args)` gives the type of the next argument, so a partial term is a precise set of holes.
- `accepts(term)` replays a term's word against the grammar: every constructor was allowed where it was chosen and
  the term is complete.
- `enumerate(maxSteps)` is a lazy walk of the space in the grammar's order; it holds no space.

## 4. Denotation and normal form (built)

`core/src/main/scala/okay/desk/logic/Compile.scala`:

- `program(term)` builds `okay.datalog.Program` and the goal directly as data — no text, so no quoting bug.
- `run(tables, term)` runs the program's own facts plus the tables through `Logic.run(tables, program, goal)`, the
  new overload in `Logic.scala`, and returns the query and the answer table.
- `ofProgram(program, goal, ontology)` is the inverse: a parsed program and its goal become a term. This is how the
  gold corpus enters the grammar — containment is a law, not an assumption. It renames the goal and each rule
  independently into the pool, since Datalog variables are rule-local, and it refuses an unknown predicate, a wrong
  arity, an unknown constant, a variable in a fact, an unsafe head, an unsafe negation and an unsafe comparison.
- `normal` renames variables in order of appearance per rule and sorts literals, rules and facts; `equivalent`
  compares two programs up to the names and order Datalog does not care about.

## 5. Laws (built)

`core/src/test/scala/okay/desk/LogicGrammarSuite.scala`, 8 tests over a six-task corpus (facts, transitive closure,
negation, comparison, join and difference, stratified negation):

1. **Gold containment:** every gold program is a point of the grammar (`accepts`), compiles back to an equivalent
   program, and its word starts with the goal.
2. **Round trip:** a term, its constructors, the same term (`Encode.word` then `Encode.tree`).
3. **Denotation:** each goal equals the hand-computed answer on the program's own facts.
4. **Refusal:** a variable in a fact, an unbound head, an unbound negation are refused; the grammar never offers a
   `nil` for an unsafe body, a variable in a fact, or a negation where none may appear.
5. **Stratification:** a negation that would close a cycle is refused (`p` negates `q` before `q` is defined; a
   later rule would need a higher level than its head already has), while the stratifiable form is accepted and
   passes `Check.strata`.
6. **Enumerated terms are well-formed:** 150 terms up to 28 constructors all compile and pass `Check.strata`.
7. **Engine agreement:** on 60 enumerated programs up to 26 constructors, `Eval.run` (semi-naive) and `Eval.naive`
   give the same relations.
8. **Determinism:** the same input gives the same enumeration order and the same constructors.

`LogicGrammar` and `Compile` use `Either` for a refusal; the suite's corpus and laws run on the JVM. The whole core
suite is 144 tests, up from the M0 baseline of 132.

## 5.1 M2: twenty hand tasks (built)

`core/src/test/scala/okay/desk/LogicTasksSuite.scala` puts a hand ontology over twenty tasks, five in each family,
each a gold Datalog program with a hand-computed answer:

- **data** over `Sample.tables` through `Ontology.ofTables`: transitive dependency closure from and to a constant,
  a direct edge query with no rules, a join with two copied constants (`unpaid`, `UA`), and a stratified negation
  (`customers with no orders`).
- **planning** as the reachable set of a finite transition graph: chain, diamond, cycle, disconnected graph, and a
  chain with a back edge. The shortest path stays with the exact handler; this checks the Datalog definition.
- **puzzles** as family relations: grandparent, sibling with a comparison, uncle, transitive ancestor, and `noChild`
  with stratified negation.
- **arithmetic** as comparisons over age facts: adult, minor, between, older, and same age. Sums and arbitrary
  arithmetic remain with the exact `Calculate` handler; Datalog carries the comparisons.

The four laws of the suite:

1. twenty tasks, four families of five, every gold program a point of the grammar, compiled back equivalently and
   accepted by `Check.strata`;
2. no invented constants: every argument or operand leaf is a variable or a constant of the ontology;
3. each answer equals the hand's answer exactly (`Compile.run` on the program's facts and the tables);
4. every expected answer has a derivation from `Eval.run(..., proofs = true)`.

All 20 answers are exact. Two text-syntax lessons are recorded because they are easy to get wrong in a gold program:
Datalog reads an uppercase word (`UA`) as a variable, so a copied country code is quoted (`"UA"`), and a hyphenated
name (`orders-db`) is quoted (`"orders-db"`). `Compile` builds terms as data and never needs the quoting.

## 5.2 M3 first step: the extraction contract (built)

`core/src/main/scala/okay/desk/skill/Extract.scala` fixes what a reading head must produce, before any head exists:

- a `Reading` is the task's text and a word of `Choice`s: one of the grammar's constructors at each hole, and, for a
  copied constant, an `Evidence` — a `Text(span)` of the task, a `Data(table.column)`, or `Known` (the ontology's own
  constant, a threshold like 18 that the text does not say);
- `replay` refuses a constructor the grammar does not offer, a constant with no evidence, a span outside the task,
  and a span whose text is not the constant (up to case: the text's surface form against the table's value); `next`
  is what a head is asked — the hole's type, its options, and the stage it decides;
- a reading that stops before the last hole is an abstention: `g.closed` is false and the open hole is a question for
  the person (M4), never a guessed answer;
- `gold` is the corpus' way in, not a reader: a gold term as a reading, its constants found in the text in order;
- `verdict` names the first stage where a reading leaves the gold word — goal, relation, value (a variable is a
  binding) — and `summarize` gives exact, abstained, closed wrong, wrong per stage, and span F1 over a corpus.

Ten laws in `LogicExtractSuite` over six tasks (grandparent, uncle, reachable, adult, dependency closure, no orders):
every gold reading replays to its term and every span says its constant; the heads see the hole; `18` is `Known`;
six refusals; a data evidence names a real cell; an abstention stays open and is not counted wrong; a wrong goal, a
wrong relation and a wrong value are each named by their stage; the same word known rather than read is exact with
span F1 0; a copied span covers a whole word (`Eve` in `Every` is not a copy); the corpus is exact with span F1 1.

No model is measured here: this is the interface and its laws, not a fidelity number. The heads, the corpus of
paraphrases and the backend agreement remain M3's open part.

## 5.3 M3 corpus: task texts with their models (built)

`core/src/main/scala/okay/desk/logic/Corpus.scala` is the teacher side of the reading: it generates task texts from
formal models and keeps the gold program of each, so a held-out text is not a training text with another constant.

- Four families, the grammar's: `planning` (a reachable set over `initial`/`edge`), `numbers` (comparisons over
  `age`), `kinship` (a family: parent, sibling, grandparent, ancestor, uncle, childless), `data` (questions over the
  sample's tables: dependency closure, reverse reachability, unpaid orders from `UA`, customers with no orders).
- Three splits — Train, Validate, Test — each with its own sentence patterns AND its own names, so a split law can
  check the sets are disjoint (`Corpus.templates`). The numbers family asks five question kinds (adult, minor,
  between, older, same age), the kinship family five (grandparent, ancestor, uncle, sibling, no child), the data
  family four; each kind has a distinct question pattern per split.
- `items(split, count, seed)` is deterministic: the same seed gives the same items. A generator writes the text and
  the gold program as data; the law is that `Extract.gold` recovers the program from the text.

Six laws in `LogicCorpusSuite`: every split carries the four families, eight items each; every generated text is a
point of the grammar and its gold reading replays to its term, every copied span saying its constant; every constant
is copied from the text, except the threshold `18` the ontology knows (and only in the numbers family); every answer
is the engine's and no question is empty; the splits share no sentence pattern; generation is deterministic.

The corpus is data, not a fidelity number: it says the reading heads have something to learn from and be measured on,
not that any head can read it yet.

## 5.4 M3 heads: the reader over the student's state (built, measured)

`core/src/main/scala/okay/desk/skill/LogicRead.scala` is the head that turns a state into a reading:

- the prompt is the chat template's assistant header, then `Program: ` and the constructors chosen so far; the head
  reads the state at the last token and ranks the hole's options (`g.constructors(ty)`), never a letter outside them,
  so the grammar's safety and stratification hold by construction;
- a choice is taken only when the top score leads the second by the calibrated `margin`; otherwise the reading stops
  open — an abstention, `g.closed` false, a question for the person, never a guessed answer;
- a copied constant is one of the ontology's constants, and its evidence is the first whole-word occurrence of the
  task at or after the previous copy (`Extract.find`), or `Known` when the text does not say it; the reader advances
  `from` as the gold reading does, so a repeated name cites the next occurrence, not the first;
- `Masked` is the trainer: a softmax over the hole's options only, so the head learns the grammar's decisions rather
  than a language of constructors; `steps` gives one example per gold hole, and `parameters`/`parse` carry the weights
  as `Wire` (`Parameters`: layer, mean, std, letters, weights, bias, margin).

Six laws in `LogicReadSuite`, with a synthetic head whose weights prefer the gold letter: it reproduces the gold word
exactly (with the same spans); a margin above the gap abstains and is counted abstained, not wrong; a head that prefers
another constant closes wrong and the verdict names the value (`Stage.Value`, `valueWrong` 1); a threshold the text
does not say stays `Known` in the reading too; a pointer corrects a copied constant the linear head prefers wrongly;
and a pointer margin above the gap abstains at the constant hole.

The first fidelity measurement (`bench/.../LogicBench.scala`, 2026-11-09) uses the corpus: 64 Train items
(16 per family), 32 Validate and 32 Test (8 per family), seed 7 — 2489/1291/1291 gold steps. The prompt is the chat
template's assistant header, then `Program: ` and the prefix. Two questions:

- **the goal is readable from the task.** One state per task, one masked softmax over the goal's options
  (`bench/.../LogicProbe.scala`): Train 64/64, Validate 25/32. The misses are held-out phrasings whose semantics is
  close (`Who shares Eve's age?` read as `older`; `Who is childless?` as `uncle`).
- **the whole program is not, yet.** The prefix head (100 epochs, lr 0.2, batch 32) on Validate: 1131/1291 steps
  (constructors 1049/1114, copied constants 82/177). Train: 2412/2489 (constructors 2110/2145, copies 302/344). Exact
  programs on Validate: 1/32 at margin 0, and 0 at every margin with no wrong-closed reading; the default run's only
  safe margin (6) abstains on all 32 Test items — 0 exact, 32 abstained, 0 wrong-closed. The gate (held-out exact
  ≥70% with 0 wrong-closed) is not met: a constant is copied from one state at 46% while the constructors are at 94%,
  and an exact program needs every step of a 20–40 step word to be right.

Alpha-equivalence (2026-11-09, agreed with the execution side) removes a class of Value errors that is not a
reading: a fresh argument offers every pool name, so `X` vs `Y` is a spelling, not a decision. The grammar now
offers a fresh variable only as the first unused name (`LogicGrammar.firstUnused`; reusing an already bound name
stays an option, since that is a semantic choice), `Compile.ofProgram` inverts the canonical rename in the order
`(head.vars ++ body.flatMap(_.vars)).distinct`, and `Compile.same` / `Extract.same` compare programs up to a
consistent rename (`Extract.Summary.semanticExact` reports it beside `exact`). The scope of canonical naming is
syntactic (head variables, then earlier atoms), while the safety-bound set stays only positive body variables, so a
positive atom may mention a head variable before it is bound. Six alpha laws bring `LogicGrammarSuite` to 14, and the
bench marks a rename-only divergence separately from a changed premise.

The bench is configurable: `LOGIC_DIR` (default: the 12-layer student) and `LOGIC_LAYERS` (default 12) choose the
backbone and the read-out layer, so the same typed head and pointer can be compared on the intact 28-layer
`semantic-full28` (257 MiB base + ~5 MiB head, no generative LoRA, `headOnCpu` / `Read.States`). It enforces the
gate before saving: `closedWrong == 0` and `exact >= ceil(0.70 * tasks)`, otherwise it prints "do not ship" and
writes nothing.

With the pointer for copies (the hole's state as query, the task's words in the same prompt as keys, `Known` as
the "none" key) and alpha canonicalization, the comparison (seed 7, same corpus and splits):

- **12-layer student** (`cut19-vocab-p12-m4`, layer 12): pointer 336/336 Train, 127/154 Validate; Train gold path
  `Score(41, 0, 23)` (exact, wrong-closed, abstained) at margin 0; Validate steps 1163/1291 (constructors
  1078/1114, copies 85/177). Of 140 margin pairs, 70 have no wrong-closed reading; every safe pair abstains on all
  32 tasks — no margin is both safe and exact. Nothing is saved.
- **28-layer `semantic-full28`:** pointer 334/336 Train, 124/154 Validate; Train gold path `Score(54, 0, 10)`;
  Validate steps 1192/1291 (constructors 1073/1114, copies 119/177). Of 140 margin pairs, 70 are safe; the best
  (margin 3.0, pointer 0.0–0.5) reads 3/32 Validate exactly with 29 abstained and 0 wrong-closed, and 0/32 Test
  (32 abstained, 0 wrong-closed, span F1 0.154; test pointer 147/177). The intact backbone is better on the gold
  path and closes three validation programs, but the held-out Test split still has no exact reading.

The exact-program gate therefore stays open on both backbones: a larger student improves the gold path, not the
safety/exactness trade-off. No head is promoted; the 28-layer run's artifact (saved before the gate check landed)
was removed, and `web/Engine.scala` is not wired to the reader. When a run does pass the gate, the bench writes
`~/.cache/okay-desk/logic-reader-<layers>.json`, so the 12- and 28-layer heads cannot overwrite each other; neither
is packaged unless qualified.

The next levers are stage-wise, not a bigger prefix decoder: the remaining errors concentrate in the goal and in
copies (28-layer Validate: Goal 23 right / 9 wrong, Value 279/295 right), so read the goal first, then facts, then
rules, and widen the held-out patterns before adding capacity.

## 6. M0 baseline and current numbers

M0 (2026-11-09): `sbt -batch coreJVM/test` passed 132 tests before this work. After M1: 140 tests pass, including
the 8 laws above. After M2: 144 tests pass, the 20 tasks adding 4 laws. After the extraction contract: 153 tests
pass. After the corpus: 159 tests pass. After the reader: 164 tests pass (the whole-word span law and the reader's 4
laws added). After the pointer: 166 tests pass (the reader's 2 pointer laws added). After alpha-equivalence: 187
tests pass (13 grammar laws, the reader's 6). The product numbers this path must not regress are in `data.md` and
`understanding.md`: data +
readers + exact facts 12/12 en and 9/12 ru; readers on held-out pairs 97–99% whole with 1–3% wrong; the 28-layer
generative control 12/12 short mutation but 0/8 longer held-out. The logic fragment is a core addition; no page or
model path changes yet, so those numbers are unchanged.

## 7. Built (2026-11-09)

- `core/.../logic/Ontology.scala`: predicates, sorts, constants, inputs, `ofTables`.
- `core/.../logic/LogicGrammar.scala`: the typed grammar, goal first, safety and levels by construction, `accepts`,
  `enumerate`, canonical fresh variables (`firstUnused`) and the goal/atom/body scopes.
- `core/.../logic/Compile.scala`: term to program and goal, `run`, `ofProgram` with the inverse canonical rename,
  normal form, equivalence up to a consistent rename (`same`).
- `core/.../calc/Calculus.scala`: the logic `Ty` cases; `calc/Encode.scala`: `tree` takes a root type.
- `core/.../Logic.scala`: `run(tables, Program, Atom)`.
- `core/src/test/.../LogicGrammarSuite.scala`: the 13 laws (the 8 grammar laws and 5 alpha laws).
- `core/src/test/.../LogicTasksSuite.scala`: the 20 tasks of §5.1 and their 4 laws.
- `core/.../skill/Extract.scala`: the extraction contract — reading, evidence, replay, stages, span F1, the corpus'
  gold reading.
- `core/src/test/.../LogicExtractSuite.scala`: the contract's 10 laws over six tasks.
- `core/.../logic/Corpus.scala`: the four families, three splits with disjoint patterns and names, deterministic
  generation of task texts and their gold programs.
- `core/src/test/.../LogicCorpusSuite.scala`: the corpus' 6 laws.
- `core/.../skill/LogicRead.scala`: the reader over the student's state — prompt, masked trainer, `Wire` parameters,
  the margin as abstention, copies with ordered whole-word evidence, and the pointer for a copied constant (the
  hole's state as the query, the task's words in the same prompt as keys, `Known` as the "none" key).
- `core/src/test/.../LogicReadSuite.scala`: the reader's 6 laws with a synthetic head (4 linear, 2 pointer).
- `bench/.../LogicBench.scala`: training on the corpus on a configurable backbone (`LOGIC_DIR`, `LOGIC_LAYERS`), the
  margin sweep on Validate, the Test metrics on the gold path and by the real decoder, the exact-program gate before
  the `Wire` export, and a per-layer artifact name (`logic-reader-<layers>.json`).
- `bench/.../LogicProbe.scala`: the goal predicate from the task alone — the first question of the gate.

## 8. Not built

The rest of the plan (`~/.opencode/plan/okay-desk-datalog-neuro.md`) is open and recorded in `BACKLOG.md` §E:

- **M3 (heads):** the exact-program gate (≥70% on held-out phrasings with 0 wrong-closed) is not met on either
  backbone. The 12-layer student has no margin that is both safe and exact on Validate (70 of 140 pairs are safe,
  all abstain); the intact 28-layer backbone reaches 3/32 Validate exactly at its best safe margin but 0/32 on the
  held-out Test split (32 abstained, 0 wrong-closed, §5.4). The goal is readable (25/32 on the student; 23 right / 9
  wrong on the 28-layer), so the remaining work is stage-wise reading (goal, facts, rules), wider held-out patterns,
  the stage-wise gate and backend agreement on WebGPU, WebGL2 and CPU. A larger prefix decoder alone is not the
  lever.
- **M4:** open holes as questions, lessons per person, the "second time not asked" law.
- **M5:** proof replay, grounded answers, the independent Horn check where the fragment fits inlay.
- **M6:** the phone: WebGL2, the texture budget, latency, offline reload; the capacity decision only if a gate
  demands it.

A point of the grammar is a formalization, not a faithful reading of the text; the measured reader abstains on every
held-out program, so the page is not wired to it yet.

## 9. Commands

```sh
sbt coreJVM/test                                          # the core laws, including the logic suites
sbt "coreJVM/testOnly okay.desk.LogicGrammarSuite"        # the 14 logic laws
sbt "coreJVM/testOnly okay.desk.LogicTasksSuite"          # the 20 tasks of §5.1
sbt "coreJVM/testOnly okay.desk.LogicExtractSuite"        # the extraction contract's 10 laws
sbt "coreJVM/testOnly okay.desk.LogicCorpusSuite"         # the corpus' 6 laws
sbt "coreJVM/testOnly okay.desk.LogicReadSuite"           # the reader's 6 laws
sbt "bench/runMain okay.desk.bench.LogicProbe"            # is the goal readable from the task?
sbt "bench/runMain okay.desk.bench.LogicBench"            # train, calibrate, measure, export the reader
LOGIC_LAYERS=12 sbt "bench/runMain okay.desk.bench.LogicBench"   # the student; saves logic-reader-12.json
LOGIC_DIR="$HOME/.cache/okay-desk/semantic-full28" LOGIC_LAYERS=28 \
  sbt "bench/runMain okay.desk.bench.LogicBench"          # the intact 28-layer control; logic-reader-28.json
```

The artifact path is `~/.cache/okay-desk/logic-reader-<layers>.json` (or the first argument); a run that fails the
gate writes nothing, and no artifact is packaged unless it passes. `LOGIC_TRAIN` (items per family, default 16),
`LOGIC_EPOCHS` (default 40) and `LOGIC_LR` (default 0.05) tune a run; the Test split and the gate are fixed.
