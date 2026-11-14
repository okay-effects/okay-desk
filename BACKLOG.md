# BACKLOG — okay-desk

What is planned and not built, with the spec that holds the design. Done items move to the spec's "built" section
and leave this file. The operator decides the order; the numbers that motivate each item are in the specs.

## A. The dialogue and the sources (specs/pilot.md, docs/calculus.md §10)

(1, 2, 10, 12 in part, 15 at the calculus level: written 2026-11-08 — specs/organs.md §11; their numbers pending.)

1. **Lessons per person** («надо было: по странам», Okay!Chat's `Support.Remembered`): the pair phrase → the hole's
   choice kept on the disk beside `Clarified.keep`; the next question of the same person, the same words (or a typo
   away) decide the same hole ahead of the rules. A `Weigh` handler first in the chain, one file. Measure: the
   second time a wording is corrected it never asks again.
2. **The data's silence on ambiguous wording** (30 of 242 teacher pairs misread by wording; a wrong closed term is
   not a question — only an open hole is): per constructor a list of disputed words where the exact source gives
   TWO candidates instead of one, so the dialogue asks. Measure on the pairs: wrong → asked, right kept.
3. **Word lists grown by induction** (Okay!Chat's `okay.intent.Induced`, our `Organ.induce`): cue rules from pairs
   per constructor, a person reviews, compiled into `lang/<code>.json`; the GF law and "the data alone never wrong"
   as the gate. The source of item 2's lists.
4. **The margin measured with the readers** on the teacher pairs when MLX is free (ln 3 is measured on the sample
   and the GDP 19 only).
5. **Acts in Ukrainian and Polish on the page**: the engine passes its loaded languages to the act layer.
6. **The typo layer** on the same word lists (bounded edit distance, exact-format slots never corrected).
7. **Word lists built by a DSL** (Okay!Chat's `intent.Dsl`: stems, gaps without `.*`, `(?iU)` and `\b` emitted),
   the shipped file equal to the builder by a law.
8. **The encoder's centroids as the vector tier for a service without a model** (`Fill.centroid`, the 23 MB encoder).
9. **A need as a term in Okay!Chat** (specs/pilot.md §3.3): `TradeT / PlaceT / WhenT / MoneyT`, the intake as
   `Strategy.greedy` with the person last, matching by slot as the denotation. The other side's work.

## B. Organs (specs/organs.md)

10. **Silence where a cue is thin**: a cue's held-out precision as its weight; the dialogue's margin over the
    organ's votes; the "open → wrong" count (27 of 963 after the data) to zero.
11. **Organs on the page**: the user's corrections induce cues locally, compiled, the law checked in the browser,
    kept beside the lessons; the page understands the wording next time with no model.
12. **v1 — the model authors organs**: where the readers keep failing on a class, the text tier writes the cue's
    JSON; the induced organ is the oracle it is measured against; the law gates both.
13. **The nightly cycle** (Okay!Chat's learning loop): journal → induction → review → compile → law → ship; the
    number to drive down: the share of holes the model or the person had to decide.
14. **`Write`: steering as an effect** (§5): the residual stream added to, with interceptors, a journal, a policy
    (okay-guard); laws: a refused write changes nothing, the journal replays to the same logits. One more `Compute`
    op on every device.
15. **`Fork`: the KV cache as the continuation** (§6): clone and resume in okay-llm; branching, backtracking and a
    beam as handlers of `Among`; the cost of a branch the tokens after the hole.

## C. Applications of organs (the operator's question, 2026-11-08: «как применять на практике»)

16. **Organs as a portable format of knowledge between models**: a large model writes organs for a small one
    (distillation without gradients); organ files signed, versioned, checked by the law on any device; a library.
17. **Guardrails that cannot be prompted away**: a forbidden-request rule as an organ in the forward pass, exact and
    proved, raising a flag that `Write`'s handler acts on under okay-guard's policy.
18. **Circuit certification**: compile the circuit that should compute X, compare with the model's states on the
    same inputs (the bridge, readers → exact machines): "the model contains a computation equivalent to P", a number.
19. **Learning by a community without weights or data leaving**: each page induces organs locally; organs are
    reviewable and merge without conflict (two disagreeing cues give silence by construction); shared as files.
20. **Agents exchanging skills as organs** (okay-agent, rozum rooms, symdex): a skill as a compiled, typed,
    law-checked artifact instead of a paragraph of instruction.
21. **Regulation as weights**: a tax rule, a tariff, a protocol written as cues by the expert, compiled, the law its
    certificate; one artifact for the model, the spec and the regulator.
22. **Okay!Chat's intents as organs**: the pairs from the journal induced nightly, reviewed, compiled, attached as a
    handler; the invariant "no model supplies data" by construction, milliseconds on CPU.
23. **Product chat over a shop's data** (okay-mall, okay-shop): the shop's frequent wordings as organs, the model
    for the rare ones, the person's buttons for the ambiguous; a query costs nothing.

## C'. Taught (2026-11-08)

- **Code through symdex** — built: `okay.desk.code.Code` (specs/organs.md §9), the ambiguous symbol's candidates,
  symdex's `ask` tool over the organ file, Ukrainian and Polish phrases; next: the facts fetched live from
  `symdex serve` instead of a dump; the ask tool's fallback words made silent where unsure.
- **The books** (D 24, 26 in part) — built: the dataset's book (`Book`), the paradigm block, the organ file, the
  self-read of the docs (specs/organs.md §10, docs/learning.md); next: a claim grammar for prose (the self-read's
  silence), the model authoring a book's dictionary offline (`MakeWords` extended to forms and examples), the open
  examples asked on the page.

## D. Learning on purpose (specs/organs.md §9, the operator's question «может ли модель учиться, читая книги или
методом проб и ошибок»)

24. **Reading as induction**: a book (a manual, a schema's documentation, a glossary) read by the model into pairs
    (a phrasing → a term) and from the pairs into organs — the organ is what the model "learned", readable, with the
    law; the book's examples are the held-out set. Measure: pairs from the text alone that read right afterwards.
25. **Trial and error against an oracle that is not the model**: the exact engine is the judge. The model proposes a
    cue, the engine runs the term on the data, a result that matches the question's own answer (a pair's gold, a
    person's yes, a consistency check across phrasings) keeps the cue; a mismatch discards it. Search over cues, not
    over weights; the law at every step. Measure: cues found by search alone on the held-out half.
26. **Curiosity as an effect**: the model performs `Ask(question)` to a source (a person, a document, an engine) when
    its readers abstain on a class of questions; the answer becomes a pair; pairs become organs. The loop closes
    inside the calculus: abstention → question → pair → organ → fewer abstentions.
27. **Self-consistency as the teacher**: the same question phrased three ways (the space's phrases, the teacher's
    rewrites) must read to one term; where readings disagree, the disagreement is a pair for induction and a question
    for the person. No gold needed.

## E. Shared reasoning and world knowledge (specs/reasoning.md, specs/puzzles.md)

**Priority: model-authored task understanding** (specs/understanding.md). The language model must interpret the
whole arbitrary task and its question and author the formal model itself on phone WebGL2. Built experimentally:
native program generation, separate low-rank adapters, generic finite constraints, a compiled inlay Horn
certificate, typed worker execution and ?understanding in the same chat. No symbolic task-text fallback.
Built: model-selected arithmetic steps with exact receipts and cached tool continuations; paired premise/goal
mutation evaluation; model-owned dialogue and clarification continuation. Best compact result so far: 2/6 mutation
groups. An intact 28-layer control reaches 6/6 short groups but 0/8 longer held-out questions. Both remain research.
The requirement remains open: the first compact model does not generalize reliably; working examples and exact
proofs do not establish faithful interpretation of arbitrary text. Measure whole-formalization fidelity, not just
final-answer agreement, before promoting the experimental model to the default path.
The published model still does not reliably formalize the river task. Generic bounded state/action planning is
built (understanding §8), but whole-transition fidelity remains below the release gate. Runaway generation and
phone-memory escalation are bounded (§7); these stability changes are not a semantic solution to the puzzle.
Built: the typed reading execution/proof gate, portable Worker protocol, and checked proofs for the existing
logical data chat (specs/logic-execution.md). The new general typed reader remains unqualified: the 28-layer
control closed 0/32 held-out Test definitions. Do not package an all-abstain head as task understanding.

28. **More world sources and dialogue** (§7, §10–11): `Know` as an effect, ambiguity choices, more properties and
    qualifier contexts, then okay-facts and quoted Wikipedia. Built: autonomous Wikidata research for four
    properties with type/rank/time filters, sourced cache and the selector compiled by okay-inlay (§10–11).
29. **Further constraint families** (§8): knights and knaves, kinship, cell grids and age/arithmetic constraints; each with
    generated phrases, exact laws and the same read-back/dialogue flow.
    Built: source-declared house grids without category headers and multiple questions (puzzles §13).
    Arbitrary category vocabularies and unrestricted prose-to-constraint reading remain open.
30. **General source coverage** (specs/reasoning.md §6): web search and document adapters beyond Wikipedia,
    refresh and disagreement policies, and source/entity clarification inside the common chat.
    Built: explicit HTTPS HTML/plain-text/Markdown documents through the browser, provenance, caching and CORS
    failure reporting (reasoning §9). General web search and a server-side fetch adapter remain open.
31. **Learned reading into the common inference language** (specs/reasoning.md §6–7): train and measure readers
    for typed facts/rules and question goals, with source spans, abstention and clarification; test on held-out
    phrasings and real source prose. Built: the bounded Horn block in okay-inlay, sourced typed readings, exact
    multi-hop proofs, negative facts, conflicts and unknowns in one chat; the actual 12-layer student's learned
    membership/polarity/universal reader with copied spans, abstention and browser WebGL2 integration (§8).
    Built next: six named binary relations, open binding goals, explicit relation composition, live Wikipedia
    development audit and browser document reading (§9). Remaining: learned general goals and rule syntax,
    richer relations, coreference, temporal/modal terms, a larger blind real-document corpus and clarification.
32. **The minimal phone core** (specs/reasoning.md §7): reduce the student against the expanded reading contract;
    measure physical-phone WebGL2 memory and latency. Default is the existing 12-layer student; no separate writer.
33. **Measured heterogeneous execution** (specs/execution.md): move native model execution and its CPU head off
    the page, transferable messages, resource/memory budgets and per-kernel cost measurements; verified GPU
    handlers for eligible exact tools, then adaptive placement. Built: bounded CPU Worker pool, independent model
    lane, typed CPU API, dependency plans, cancellation and browser concurrency checks.
34. **The logic fragment: the model weighs a Datalog program** (specs/logic.md): the model reads the task and chooses
    points of a typed grammar — goal, facts, rules — while `okay-datalog` computes. Built: the ontology, the typed
    grammar (goal first, safety and stratification by construction through per-predicate levels, canonical fresh
    variables and the goal/atom/body scopes), the denotation and inverse, the normal form,
    `Logic.run(tables, Program, Atom)`, 14 grammar laws (8 structural and 6 alpha: a fresh variable is the first
    unused name, a consistent rename is the same definition), M2's hand ontology over 20 tasks (5 data, 5 planning,
    5 puzzle, 5 comparison) with 4 laws — every gold program a point of the grammar, no invented constants, each
    answer exact by hand, every answer with a proof — and M3's extraction contract (`skill/Extract.scala`: a reading
    as a word with evidence, replay, refusals, abstention, stage verdicts, span F1, `same`/`semanticExact`) with 10
    laws over six tasks, and M3's corpus (`logic/Corpus.scala`: four families — planning, numbers, kinship, data —
    three splits with disjoint sentence patterns and names, deterministic generation of task texts and their gold
    programs) with 6 laws, and M3's reader (`skill/LogicRead.scala`: prefix decoding over the student's state, masked
    softmax over the hole's options, margin as abstention, copies with ordered whole-word evidence, a pointer for
    copied constants, `Wire` parameters) with 6 laws. 187 core tests pass. Measured on the 12-layer student with the
    pointer and alpha canonicalization: Validate steps 1163/1291 (constructors 1078/1114, copies 85/177) and Train
    gold path `Score(41,0,23)`, but no safe margin is also exact (70 safe pairs, all abstain). The intact 28-layer
    control is better on the gold path (`Score(54,0,10)` against `Score(41,0,23)` on Train) and reaches 3/32 Validate
    exactly at its best safe margin, but 0/32 on held-out Test (32 abstained, 0 wrong-closed). The exact-program gate
    (≥70% held-out exact, 0 wrong-closed) is not met, and it cannot be met by a bigger head: `bench/.../LogicBudget`
    (model-free) counts 34.1 decisions per Validate task — 84.4% of the 1291 holes; the grammar takes only 15.6% for
    free — so 70% exact needs 98.96% per decision against a measured 90.1% (student) and 92.3% (28-layer). Compaction
    was measured, not assumed: n-ary lists take the budget to 26.6 decisions (98.67% needed), and the 128 corpus tasks
    carry only **17 program schemas** (4/5/5/3 per family), every Test schema also in Train. So the corpus's
    "understanding" is a 4-way schema choice plus slots, spelled out as 34 constructors; schema-first would be 6.5
    decisions (94.69% needed). The benchmark therefore measures spelling, not form: testing the form needs held-out
    schemas. The schema path is now built and measured (`logic/Schema.scala`, 5 laws; `bench/LogicSchema`): a schema is
    the program with its constants as holes, instantiated by filling them in order. The schema head reads 23/32 Test
    schemas (71.9%) and 22/32 Validate (68.8%) against a majority baseline of 11/32, so with the pointer at 83% per
    slot the whole-program expectation is 33% Test and 27% Validate — about a third instead of 0/32, with the schema
    choice and the pointer as the two bottlenecks. The decoder now runs end to end (`bench/LogicSchemaRead`): the
    ontology-filtered schema head, the pointer under the schema's own prefixes, `Schema.instantiate`, `Compile.run`,
    calibrated on Validate and read once on Test. At the calibrated pointer margin 2.0 (schema margin 0.0, 21 of 56
    pairs safe) it reads Test 12/32 exactly, abstains on 20, and closes **0 wrong**: data 8/8 with no abstention,
    planning 4/8, kinship and numbers all abstained. The table of where each reading fails names the levers: planning's
    pointer is perfect (20/20 slots) and its forms differ only in the fact count (2/3/4 edges), which the head cannot
    count because a text-only task writes its facts into the program; numbers' forms share every fact and differ in
    one rule (the head says "minor" for older/between/sameAge on held-out phrasings); kinship's form is right and the
    pointer picks the wrong occurrence of five repeated names (38/57 slots). More Train tasks does not help (192 Train
    tasks: the head memorizes and the safe pair collapses to 3/32 Test with 3 wrong). No head is promoted and
    `web/Engine.scala` is not wired. The bench is configurable (`LOGIC_DIR`, `LOGIC_LAYERS`, `SCHEMA_TRAIN`),
    enforces the gate before saving, and labels artifacts by layer (`logic-reader-<layers>.json`). Next: the task's
    facts out of the form (a table, or a repeating block in the form, so the fact count stops being a decision), the
    form head on held-out phrasings, the pointer over repeated names, plus a held-out split with schemas Train never
    showed; then the honest threefold gate (safety, stage thresholds, execution) with whole-program exactness reported
    rather than promised; M4 open holes as questions and
    lessons; M5 proof replay and the Horn check; M6 the phone (WebGL2, texture budget, latency, offline reload) and the
    capacity decision. The plan is `~/.opencode/plan/okay-desk-datalog-neuro.md`.

## The training pipeline (specs/core-joint.md §7)

1. **okay-forge generalized** (okay-inlay `forge/`): task sources, verifiers, objectives and measures as exchangeable
   parts; `HeadGrads`, `JointTrain`, `CoreBench`, `SemanticExport` moved from this bench into okay-inlay; one
   history of runs. After the joint adapter's three seeds; the numbers unchanged by the move.

## The loop (specs/loop.md)

1. **Corrections exported and taught**: the lessons (`learn.Lessons`) as JSON lines with consent, a task source of
   the cycle; a new adapter and heads shipped from them.
2. **The query language for everyday questions**: a share, a change between periods, two columns compared, a
   computed column — each a constructor with its denotation, words and laws.
3. **Human question sets with formal labels** (licences first): Spider/BIRD and PAUQ → `Query`, GSM8K-like →
   `Calculate`, FOLIO → `Derive`; part of each held out.
4. **The teacher as a labeller of real texts**: a program written for a real question, kept when its result is
   right or two labellings agree.
5. **Open textbooks' exercises** with their answer keys as the judge.
6. **Variety by rule**: typos, colloquial forms, follow-ups, numbers in words; "ask" for what the language cannot say.
7. **Ukrainian and Polish natively.**
