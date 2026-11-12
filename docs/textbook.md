# Small neurosymbolic language models

A textbook written while building one. It records what this project (okay-desk, with okay-inlay under it) has
learnt about a technology: SMALL, EXTENSIBLE, MODULAR, SPECIALIZED NEUROSYMBOLIC LANGUAGE MODELS — a small language
model that reads what a person wrote into a formal statement, and exact engines that compute the answer.

How to read it. Each chapter is one component of the technology: what it is for, how it works inside, the ways it
has been implemented here, what was measured, what failed, what is not known. Every number has its source in
brackets — `D/` is okay-desk, `I/` is okay-inlay, then the file and section. A result that did not work is kept
with the reason the project gives for it: a negative result is knowledge. The diary of each line of work is its
spec; this book is the map over them. The specification of the technology — its aim, its interfaces, its open
questions — is `specs/technology.md`.

The book is not finished and is not meant to be: a chapter changes when a measurement changes what we know.

---

## 1. The problem and the idea

**The problem.** A language model computes badly and does not know when. A model of 35 billion parameters, asked
how many of 40 orders are unpaid, says 15 (the answer is 14); allowed to think, it gets 14 after 1,342 tokens and
three minutes. One compiled attention head with a small MLP — 29,492 parameters — gives 14 [I/README.md]. A small
model is worse at computing and no better at knowing it. Making the model larger buys reading and computing
together, at a price a phone cannot pay.

**The idea.** Split the work by what each side is good at.

- The MODEL READS. It turns a text into a formal object — a query, a statement of a puzzle, a set of facts — and
  thereby chooses which engine will answer. It solves nothing and computes nothing.
- EXACT ENGINES COMPUTE. A relational engine, Datalog, a finite-domain solver, exact arithmetic. What an engine
  did not produce is never shown.

This moves the whole risk into one place, the reading, and makes it inspectable: a reading is a small formal
object that can be shown to the person, checked by laws, compared with a gold one, and refused.

**The promise.** Not "always right". The promise is: *an answer is exact for the question as read; the reading is
shown; the rate of wrong readings is a measured number* [I/specs/bus.md §1]. When the model is unsure of a reading
it does not answer; it asks about the place it is unsure of.

**Why small is possible.** Reading into a fixed formal language is a far smaller task than open conversation: the
model chooses among the few things the grammar allows at each place. It needs to *understand* the sentence; it
does not need the layers that prepare the next token. Chapter 4 shows how far that goes.

## 2. The map

```
 text ──► base model ──► states (the bus) ──► readers ──► a formal object ──► an exact engine ──► a result
 (L0)        (L1)            (L3)              (L4)       (term / statement)      (solver, …)         │
                                                              ▲   │ shown                             ▼
                                              the person ─────┘   └────────────► said in words ◄── the answer
                                              (a choice at a hole is a lesson)
```

| component | what it does | chapter |
|---|---|---|
| effects | the glue: every part is a description; a handler gives it meaning | 3 |
| the base model | gives states for a text; small enough for a phone | 4 |
| the bus | the contract between a model and what reads it | 5 |
| readers | read one decision each off the states; nothing is generated | 6 |
| the formal language | what a reading is: a typed term, a statement | 7 |
| engines | compute the answer exactly | 8 |
| the corpus | what the readers are taught on, and who writes it | 9 |
| the gate | when no answer is shown | 10 |
| saying | the answer in words, every figure the engine's | 11 |
| dialogue and lessons | asking; being corrected; repair | 12 |
| modules | a language, an ability — a file beside an unchanged base | 13 |
| measures | how we know | 14 |

## 3. Effects: the glue

**What.** In the okay libraries a program is a *description* — a value that says "weigh these candidates", "give me
the states at these positions", "show this screen" — and its meaning is given by a *handler*. The same program
runs with different handlers: a model in a browser, a model on a Mac, no model at all in a test.

**Why it matters here.** Every decision of this technology that we may want to change is a handler:

- the DEVICE: `Compute` is answered by `OnCpu` (the reference), `OnWebGpu`, `OnWebGl2`, `OnMlx`; the law is that
  each equals `OnCpu` [D/docs/calculus.md §6];
- the MODEL: `Read` — states, logits, encode, decode, prompt — is answered in the browser by `OnQwen`, on the JVM
  by `ComputeReading` (the page's own forward) or `MlxReading` [I/docs/effects.md §2];
- the SOURCE of a decision: `Weigh` is answered by the data, by trained readers, by the model's own tokens, by a
  person (chapter 7);
- the STRATEGY: `Among` is answered greedily, exhaustively, or by a beam;
- the PAGE: `Model`, `Ui`, `Fetch`, `Disk`, `Clipboard`.

Three consequences are worth remembering. A law can be tested with no model (an oracle handler). The page and the
bench run ONE program, so a number measured on the Mac is the page's number. And a new model, device or strategy
is added without touching the programs that use it.

**Not built.** `Write` (a module writing into the model's states) and `Fork` (the model's cache as a continuation)
exist only as design [D/specs/organs.md §5–§6]. Interceptors — control and audit of every effect — are designed
for this and not yet used here.

## 4. The base model, and how it is made small

The base is Qwen3-0.6B. Four cuts take it from 320 MB to the 122 MB the page loads.

**4.1 The cut by layers.** A layer never looks up, so the state after layer L is the same in a model that ends at
L. How readable the decisions are from each layer has a threshold between layers 17 and 18 (one decision goes
from 57.5% to 85.0%). Keeping the first 19 of 28 layers — "cut19" — gives 244 MB with the reading intact: the last
nine layers prepare the next token, which a reader does not need [D/specs/data.md §14; I/specs/core.md §2].

**4.2 The vocabulary.** The embedding is 78.7 of the 244 MB. Two ways were tried.
- BLOCKS BY SCRIPT: the table split by writing system and loaded on demand — common 5 MB, English Latin 28 MB,
  Cyrillic 2.2 MB — 37 MB for English and Russian instead of 78.7; another block is fetched when a token of it
  appears. Law: all blocks together equal the whole table exactly [D/specs/data.md §20]. This is the modular way,
  and it is what a language module rests on (chapter 13).
- PRUNING to the 38k tokens a corpus uses: 244 → 181 MB with equal states, at the price that 13% of English and
  5.5% of Russian unseen words split into pieces differently [§21].

**4.3 The student.** Twelve layers trained to give the state the 19-layer model gives. No layer is free to remove
(the influence of each is 0.056–0.126), so layers 0–10 and 16 are kept and a low-rank adapter (rank 64) is trained
on every projection toward the teacher's state: 5,000 steps, 24 minutes, error 0.42 → 0.042, then merged and
quantized to 4 bits [D/specs/data.md §21]:

| model, the teacher's readers unchanged | size | en | ru |
|---|---|---|---|
| 12 layers, untrained | 128 MB | 6/12 | 2/12 |
| + the adapter | 128 + 69 MB | 11/12 | 8/12 |
| merged at 4 bits | 122 MB | 11/12 | 7/12 |

The lesson is in the method: the student is not taught the task, it is taught to *give the same states* — so
every reader trained on the teacher keeps working on it unchanged.

**4.4 The map by component, and what it taught.** Each attention block and MLP was silenced alone and the readers
retrained, which sorted the components into FREE, NEAR, RUSSIAN'S and CORE. The same day overturned the reading
of that map: *removals compound — what is free alone is not free together*, and a component's language cannot be
read from a single ablation [I/specs/core.md §11; D/specs/data.md §22b]. A study of single ablations is a map of
single ablations and nothing more.

**4.5 Running in a browser.** One forward, `Qwen3Compute`, over the `Compute` handlers.
- A PREFIX CACHE keeps every layer's keys and values for the unchanging start of the prompt; it is exact on every
  device and took a question on WebGPU from 2.3–7.6 s to 0.5–0.9 s [D/specs/data.md §17].
- A PHONE'S LIMITS shape the engine: iOS takes the WebGL context away at a few hundred MB, so the texture pool is
  24 MB, the output head stays on the CPU (the embedding's 78 MB is never sent to the GPU), attention heads run
  two at a time, and the weights are fetched as files of a few layers each so that the page never holds them
  twice [§18].
- Half precision breaks exact mechanisms that rest on tiny differences (float32 805/805, float16 768/880) —
  quantization is not free for every component [I/specs/inlay.md].

**Not measured:** speed, memory and stability on a physical phone. Everything above is Chromium with the phone's
profile [D/specs/execution.md].

**Open.** 2-bit MLPs, pruned heads, a narrower student, the teacher being a larger model — planned, not measured
[I/specs/core.md §8–§10].

## 5. The bus: a contract between a model and what reads it

**What.** The states a model gives at agreed places, in an agreed normalisation: bus v1 is width 1024, the state
after layer 19 of cut19, per-dimension mean and spread, clipped at ±4. Readers, engines, compiled blocks and
memory are MODULES that read the bus. *Any model that gives the bus serves the same module files*
[I/specs/bus.md §2–§3].

**How a model learns the bus.** By the cheapest adapter that passes: identity → a linear (ridge) map → relative
coordinates → distillation (the student of 4.3 is the last case). Qwen3-1.7B was put on the bus by a ridge map
alone, and the readers' file, unchanged, read it as well as the reference — 43.0% against 41.8% on people's
wording [I/specs/bus.md §6].

**A lesson about tests.** On the easy test set a *constant* state read 91.3%: the test was too easy to tell a
model from no model. On the hard set the bus is worth about 12 points. A control that should fail is part of every
measure (chapter 14).

**Two tiers.** The VECTOR tier reads states (cheap, needs the bus). The TEXT tier uses the model's own
log-probabilities over the candidates, or a program the model writes — it works for any model, even a closed one,
and pays what generation pays.

## 6. Readers: reading without generating

**What.** A reader reads ONE decision off the model's states. Nothing is generated: one forward of the text, then
small linear maps.

**The kinds.**
- A PROBE: a linear map over a state → one of a few classes (the kind of a sentence, the shape of an answer).
- A POINTER: a bilinear map between a question's state and each candidate's state → which of them (a column, the
  thing a name refers to).
- ASK: the model's own next-token log-probabilities over the candidates — no training at all.
- EXACT FACTS: what the text or the data writes literally is not read, it is looked up.

**Where to read matters more than how.** The readers of data questions read "where the model decides": after a
forced prefix of the program, at the place the model would write the answer [D/specs/data.md §7–§12]. On a 0.6B
model, with the weights untouched, that took the sample from 2/12 to 12/12 in English and 11/12 in Russian —
against 6/12 for a LoRA trained on 600 pairs and 2/12 for a grammar mask alone.

**Readers disagree, and that is information.** In okay-inlay's bridge a probe alone read a relation's direction at
92–96% and ASK at 96–98%; the two *summed over their spreads* read 100.0% of 1,230 sentences. "Readers are an
ensemble by construction; a reading two readers disagree on is the one to mark unsure" [I/specs/bridge.md §8b].

**The statement reader of logic tasks** [D/specs/formalization.md §10–§12]. Four heads over the 12-layer student:
- a TAG at every token (is this a piece of a name?) — linear over the token's state and the state at the end of
  its word;
- a sentence's FRAMES (which relation it states) — a few learned "looks" that weigh the sentence's tokens, then a
  linear map: a relation is said by one or two words among twenty, and a plain mean drowns them;
- a LINK from a mention to the thing it names — a pointer;
- the ATTRIBUTE a question asks for — the question's tokens matched against the tokens that list each attribute.
On the wordings it was taught: 97–99% of whole tasks. On tasks written by another hand: 29% (en), 11% (ru). The
reader learnt its teacher's wordings, not the meaning — chapter 9.

**A causal model reads a word before the sentence is whole.** The base reads left to right: the state at a token
knows only what came before. Three ways around it: a bidirectional model (another engine; frozen, it read no
better [backbone/results.md]); removing the mask (untrained, it breaks); or SAYING THE TASK TWICE and reading the
second saying, where every token has the whole task behind it (`Reader.see(echo)`). The third costs no weights and
twice the tokens. Measured [D/specs/formalization.md §14]: inside the wordings taught the last errors go (0 wrong
of 600 English texts); Russian gains above the spread of four seeds (other hands' tasks 10.0% → 17.4%);
other hands' English does not move (34.2% → 36.0%) — so that failure is not the direction of reading.
And a lesson about method came with
it: the same reader trained from four seeds spreads by a point on taught wordings and by 6–15 points out of them
[D/specs/formalization.md §14.1]. A reader over frozen states, out of its corpus, is a draw; compare
configurations over several seeds.

**Why not let the model write the formal object?** It was tried [D/specs/understanding.md]: a 28-layer model with
an adapter writes a JSON program. It passed development tasks and failed held-out ones (12/12 on mutations, 0/8
on a held-out slice); on the river-crossing task it wrote hundreds of nested additions and ran out of tokens. And
on a phone, writing is tens of forwards where reading is one. The project's diagnosis: "capacity is not the
constraint; the interface is" [D/specs/logic.md].

**The arithmetic that every reader must respect.** A formal object of n free decisions read at accuracy a per
decision is whole with probability aⁿ. For 34 decisions and 70% whole tasks, a must be 98.96%
[D/specs/logic.md §5.5]. Two ways out, and both are used: FEWER DECISIONS (let the grammar and the data decide
all they can; choose a schema first), and NOT ANSWERING where a decision is unsure (chapter 10).

## 7. The formal side: what a reading is

Two formal languages exist in the project. They are the same idea at two grains, and bringing them under one
description is an open task.

**7.1 The calculus of terms with holes** [D/docs/calculus.md; D/specs/calculus.md]. Three primitives.

1. TYPES, INDEXED BY THE DATA. A grammar gives each type its constructors and the type of the next argument given
   the earlier ones (`TableT`, `ColumnT(tables, role, …)`, `ValueT(table, column)`, …). A term is well-typed by
   construction: the model is never offered something that cannot be.
2. ONE OPERATION: `Weigh.Of(type, candidates, context) → a distribution`. "It weighs, it does not choose." An
   empty answer is "no opinion". This is the ONLY way a model enters: as a handler of `Weigh`, by its states or by
   its tokens. It chooses words, never facts.
3. DENOTATION. A closed term is a point of the space of meanings; the point compiles to the query the engines
   run. A term with holes has no denotation: it is a reading not finished — a value of its own, an abstention,
   never an error.

Everything else is a strategy, not a mechanism.

- SOURCES are handlers of `Weigh`, chained so that each is asked only where the one before was silent: the data
  (`Fill.exact`: what the question writes literally) → the trained readers → the model's own tokens → a person.
- STRATEGIES are handlers of `Among`: greedy, every (which enumerates the whole space), beam.
- "EXACTNESS IS A PROPERTY OF THE GRAMMAR, not of a strategy": where the context decides a letter, one candidate
  is offered and nobody is asked.

Its LAWS are tests with no model: a term's denotation round-trips on 313 points; "the reading is the space" —
enumerating terms equals enumerating meanings, 26,853 = 26,853, a law that found three constraints the grammar
lacked; greedy equals its reference. Forty-two laws run in seconds [D/docs/calculus.md §7].

What was measured: the data alone closes 22 of 26 sample questions, all right, with no forward of the model at
all. And a failure worth keeping: on people's own wording the law "the data alone never errs" does NOT hold —
30 wrong of 242 («сколько денег» is a sum; the rule read a count) [D/specs/calculus.md §15]. An exact rule over
words is exact only for the wordings its author imagined.

**7.2 The statement of a logic task** [D/specs/formalization.md §3, §9]. A statement is ALIGNED TO THE TEXT:
sentence by sentence, each sentence a few FRAMES (`Same`, `Differ`, `Before`, `JustAfter`, `Beside`, `At`, `Ask`,
…) whose arguments are spans of the text — the words that name a thing, a number. It carries no knowledge of the
topic: "red" is a thing because the text lists it. The statement compiles to the solver its shape chooses.

**7.3 One idea.** In both, a reading is a typed object with holes; a model only weighs candidates the grammar
offers; a closed object has an exact meaning and an open one is a question. The statement reader is not yet a
source of `Weigh` and the `Problem` of a statement is not yet a grammar of the calculus. Open task: say both in
one language, so that a new ability is a new grammar and nothing else.

## 8. Engines: the exact side

| engine | what it answers | where |
|---|---|---|
| `Exact` | relational queries over tables | D/specs/data.md §1 |
| okay-datalog | rules, recursion, stratified negation, proofs | D/specs/data.md §24; I/specs/datalog.md |
| `Solver` | finite domains: propagation and search, the trace as a proof; one / several / none | D/specs/puzzles.md |
| `Horn.Block` | bounded propositional deduction, also compiled into weights | I/specs/horn.md |
| exact arithmetic | rationals | D/specs/reasoning.md |
| compiled blocks | a program inside transformer weights, equal to its interpreter | I/specs/inlay.md |

**Programs in weights (okay-inlay).** A small program — a lookup, a count, a rule — compiled into attention heads
and ReLU neurons, under the law `decode(Forward(Compile(p), embed(x))) == Interpret(p, x)`, property-tested. A
head is a lookup table; a neuron is a gate; counting is the width of attention. A CEK machine, effect handlers,
DPLL, a Horn engine have been compiled and are equal to their interpreters [I/specs/inlay.md §4].

**What that is for — the honest ledger** [I/specs/bridge.md §10]. A CPU is millions of times faster. "In weights"
pays only for (a) a mask over what the model may write, (b) training *through* an exact interface, (c) one
artefact with no separate program. And its limits are sharp: a dense trained model has no free dimensions to put
a block into, so a compiled block is a SIDE model reached by a hook; a fixed depth computes a window, not a
recurrence. The open question is whether (b) buys something a CPU tool does not.

**An answer is conditional.** "A correct proof is conditional on the definition and the supplied facts"
[D/specs/logic-execution.md]. The engine's exactness says nothing about the reading.

## 9. The corpus: what a reader is taught on, and who writes it

A reader is as good as the variety of what it was shown. Four ways of making a corpus were tried.

1. THE SPACE × PHRASES. Enumerate the meanings (87,445 programs over the sample), say each compositionally
   (`Phrase`, `Say`). Exact and endless — and one author's phrases. Held-out *generated* pairs read at 97.7%; the
   same readers read people's wording at 41.8% [D/specs/data.md §27, §32].
2. DRAWN TASKS OF MANY SETTINGS (logic tasks): families of settings, each with several wordings; whole families
   held out. Adding families moved unseen families from 28% to 57% (en) — variety teaches — and a set written by
   ANOTHER HAND still reads at 29% / 11% [D/specs/formalization.md §12–§12.1].
3. A TEACHER AND A JUDGE [D/specs/teacher.md]. A large local model rewrites a point's sentence k ways; the same
   model, given the schema but not the program, reads each rewrite back; the engine compares constructor by
   constructor and keeps only exact agreement. 1,945 pairs, 78% accepted, about one wrong pair in 24 by hand.
   What they did: they "did not lift the language; they made the readers CAREFUL" — wrong-when-answered fell from
   58.0% to 36.3% and abstentions rose from 9 to 120.
4. SELF-PLAY — the small model writing its own questions — failed: "consistency is not correctness"
   [D/specs/data.md §13].
5. A TEACHER OVER MARKED SENTENCES (logic tasks) [D/specs/formalization.md §15]. A task is taken apart into
   sentences whose copied pieces are marked `[key|words]`; the teacher rewrites each line in its own construction
   and in another language, keeping the marks (it sees opaque keys, or it translates them too); so the rewrite's
   gold statement is the same frames over the same keys — known with no reader. A judge reads every line back as
   relations; a line not read as the gold is written again, up to three rounds. The judge was judged first on
   the originals (97% of lines). What it buys is being measured.

**The sealed set.** A held-out set stops being held out the moment its failures are read to decide what to add.
So one set is written by another hand and its results are reported as totals only — never by family, never by
wording — to whoever maintains the corpus [D/specs/formalization.md §12.1]. It is the honest number of "a wording
nobody here wrote".

**What is known.** Variety of authors is the lever; one author's templates are learnt as templates. **Not known:**
how many authors' worth of variety a 0.6B reader needs, and whether frozen states can carry it at all or the
model itself must be trained (chapter 15, question 3).

## 10. The gate: when no answer is shown

A reader will be wrong. The gate decides which readings become answers.

- A MARGIN: the gap between the two best candidates; under a bar, the reading stops and asks. It works inside the
  wordings a reader knows, and FAILS outside them: an unfamiliar wording is read as the nearest familiar one, as
  surely as any [D/specs/formalization.md §11–§12].
- NOVELTY BY WORDS: a word, or a pair of neighbouring words, outside the names that training never had. On 1,200
  texts of families never met it shows no answer at all; on the collection it shows about 70% with none wrong.
  A distance in the space of states separated nothing [§12.1].
- THE EXACT SIDE AS A FILTER: a reading that does not compile, or whose puzzle has none or several solutions where
  one is expected, is not an answer; among the candidates of an unsure decision, the ones that survive the solver
  are kept (planned, §7 step 5).
- DISAGREEMENT of readers (chapter 6).

The numbers of the present gate, shown / of them wrong: taught wordings 286–293 of 300 / 0–2; other hands' tasks
0 shown; the collection 63–66 of 91 / 0.

The gate's honest statement is a measurement — "0 wrong of N shown" — never "wrong = 0".

## 11. Saying the answer

The requirement: an answer in natural language in which every name and number is the engine's. EACH WAY OF SAYING
IS A COMPONENT with the same input (a result, a language) and output (a sentence); they differ in what they
guarantee and what they cost.

- `Answers` — a grammar over the result: one constructor per shape of answer, FACT holes whose only candidate is
  the cell, WORD holes with a few wordings each. Guarantee: every wording is closed, its facts are cells, and the
  sentence reads back (54 of 54).
- `Claims` — what the result lets one conclude: a share, a leader and its margin; every figure derived exactly.
  Guarantee: the sentence states only the claim's figures, and the derivations recompute.
- `Say` with a `Language` file — a point of the space said from a language's pieces (cases, stems). Guarantee:
  313 points said and read back by the data alone, no contradiction.
- `Said` — a statement and its answer in the text's own words, a line a sentence. Guarantee: names are spans of
  the text; nothing is composed.
- Read-back of constraints — each clue said back before solving: the person sees what will be solved.
- Quotes — exact substrings of collected sources, checked as substrings; only a collected identifier is a source.

"Generation IS reading over other rules" [D/specs/calculus.md §10]: an answer is a term too, its holes filled by
the result. **Not built:** the model weighing among wordings (which sentence sounds natural here) — the one place
a model may enter the saying, because it chooses words, never facts. A summary written freely by a model was
tried and removed.

## 12. Dialogue, lessons, repair

- A HOLE IS A QUESTION. A reading that stops names its hole and the candidates; the page asks with the options as
  buttons; the person's choice is the first source on re-reading and refines the SAME term
  [D/specs/pilot.md]. Live: 38 questions, 31 right, 6 asked back, 0 wrong.
- ACTS are read before any reading: yes, no, thanks, a correction, "forget that".
- A LESSON: a choice at a hole is kept as a cue (these words → this constructor), asked first next time; "forget
  that" takes it back [D/docs/learning.md §6].
- AN ORGAN: a reading rule over a question's words, induced from pairs and compiled into a block, behind an
  effect. "Self-modification is safe exactly when it happens on the far side of the effects; the model's weights
  never change." Measured: a real source and not yet a safe one — 1–5% wrong where it speaks
  [D/specs/organs.md].
- THE MODEL AS AUTHOR of its own rules: the loop works, the author is weak (3 of 40 proposals accepted, fixing
  nothing).

For control this gives four handles, in the order they are cheap: read the reading (it is on the screen); answer
the question it asks; forget a lesson; retrain a reader on what was corrected.

## 13. Modules

The base never changes. A module is a file beside it.

- A LANGUAGE is: its letters (an embedding block, fetched when a token of it appears); its words for the calculus
  (`lang/<code>.json` — one record for reading and for saying; a file alone took Ukrainian from 33% to 48% on
  people's wording with no training [D/specs/calculus.md §18]); its part of each reader (the words met, the
  bars).
- A DATASET'S WORDS: its names in a language, made once by a larger model (`words/<table>.json`).
- AN ABILITY is: a kind of problem with its grammar, its compiler and engine, the readers that read it, the
  corpus that teaches it.
- A READER is a file of small float matrices (a "skill"): the same file serves every model that gives the same
  bus.

## 14. Measuring

- Split by FAMILY, not by example; hold whole families out; keep one set SEALED (chapter 9).
- Count three things, by language: shown and right, asked, shown and wrong. One number hides the trade.
- Include A CONTROL THAT SHOULD FAIL (a constant state read 91.3% of an easy test).
- Measure where it will run: the bench and the page are one program; the page has self-tests (`?selftest`) and
  scripts that drive a real browser in the phone's profile.
- Trust nothing stale: a result once came from an old server still listening on the port
  [D/specs/data.md §29]; a published page once kept the old build for an hour because the host's build failed
  silently.
- An experiment is never weakened to pass; every number in a spec comes from an experiment's output
  [I/AGENTS.md].

## 15. What failed, and what it taught

| tried | result | lesson |
|---|---|---|
| a grammar mask on a small model | 2/12 free, 2/12 masked | "the mask is the guard, not the lever" |
| LoRA on 600 pairs | 6/12 vs readers' 12/12 | with little data, reading beats retraining |
| self-play | 13.5% vs 84.0% | consistency is not correctness |
| the model writes the program | passes development, 0/8 held out | the interface, not capacity |
| single-component ablation as a map | overturned the same day | removals compound |
| teacher pairs to lift the language | readers careful, not better | one kind of data moves the gate only |
| a margin as a gate out of the corpus | silent wrong answers pass | a margin says "near something known" |
| a distance in states as novelty | separates nothing | the words say what is new |
| exact rules over words, people's wording | 30 wrong of 242 | exact only for wordings its author imagined |
| one author's corpus | 97–99% in it, 29% / 11% on another hand's | variety of authors is the lever |

## 16. Open questions

They are the research programme; `specs/technology.md` §6 keeps their state.

1. Can a causal base be read as if it saw both ways, with no new weights? (Measured: chapter 6.)
1a. How large is the run-to-run spread of a reader? Every comparison needs it.
2. How much variety of authors does a small reader need, and can a teacher model supply it with an exact judge?
3. Must the model itself be trained to read other hands' wording, or are heads over frozen states enough?
4. Does the exact side inside the reading (keep the candidates under which the puzzle has one solution) turn
   "asked" into "right" without raising "wrong"?
5. One formal language for the calculus and the statement, so that an ability is a grammar.
6. One router: which problem is this text?
7. Saying an answer naturally with a model that only orders given words.
8. How small can the base go — 2-bit MLPs, a narrower student — before the reading breaks?
9. Does "in weights" buy anything a CPU tool does not?
10. A real phone: speed, memory, stability.
