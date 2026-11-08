# Organs: a model that grows exact parts of itself, by effects compiled into transformers

*The operator, 2026-11-08: «что мы можем делать революционного, принципиально нового, используя эффекты в LLM? Если
дать модели возможность самой себя модифицировать с помощью эффектов, которые компилируются в трансформеры?» This
spec is the answer as a design and a first experiment. Written after the calculus (docs/calculus.md), the dialogue
(specs/pilot.md), okay-inlay's compiler (its specs/dsl.md, bus.md, domains.md) and BusAdapter (§31) — every claim
about existing code names the file.*

## 1. The claim

A language model is a handler of one operation (`Weigh`); its forward pass is a program over `Compute` ops; its
states are read by `Read`. Everything that can be said about the system with a law is on the far side of those
effects: the compiled blocks (exact by law), the engines, the readers (measured), the calculus (laws). Everything
statistical is on the near side: the weights. **Self-modification is safe exactly when it happens on the far side**:
the model may author a TERM, a compiler makes a module of it, the module is attached as a handler of an effect the
model performs, and the law — the module equals the term on its domain — is checked before it serves. The model's
weights never change. What grows is the set of its organs.

Four effects, each over what exists:

| effect | what | over | status |
|---|---|---|---|
| `Weigh.Of(ty, candidates, ctx): Dist` | a hole weighed | — | built (calc/Weigh.scala) |
| `Read` (states, logits, tokens) | the model's states at positions | `Compute` | built (skill/Read.scala, okay-llm) |
| `Organs.Call(organ, input): Output` | an exact module called by name — a compiled block, an engine, a reader | `Read` for its input (words, or states), `Compute` for its weights | **this spec, v0 built** |
| `Write(layer, position, vector)` | the residual stream added to — steering as an effect, with interceptors, a journal, a policy | `Compute` | §5, not built |
| `Fork`: the KV cache as the continuation | branch, backtrack, resume as handlers of `Among` | `Compute` | §6, not built |

## 2. An organ (v0): a reading rule over words, as a transformer block

An organ is a program of okay-inlay's DSL (`okay.inlay.Program`: fields, heads, rules; `Interpret` the reference,
`Compile` + `Forward` the weights, the law `Forward == Interpret`). Its v0 shape (`okay.desk.organ.Organ`):

- **inputs**, one position per WORD of the question: `Cat("word", vocabulary :+ "other")` — the word's stem if it is
  one of the organ's vocabulary, else `other`; `Cat("one", "1")`, a constant.
- **layer 1, one head**: `Lookup(seen ← word, query = one, key = one, all = true)` — every position matches every
  position before it, so `seen: Many(vocabulary)` at the last position is the SET of the question's words.
- **layer 2, the cues**: a `Rule` per cue — guards `In(seen, w)` for its words and `NotIn(seen, w)` for its
  exceptions, writing `term ← template`, where `term: Cat("term", templates)` and a template is the term's
  constructors by kind of hole (`program=Rows,fn=count,op=<,…`), the COPIED parts (a value, a number, a name) left
  to the data.
- **the output**: the last position's `term`, or none — the organ has no opinion.

Two handlers of `Organs.Call`: the interpreter (`Interpret.run`) and the weights (`Forward.run(p, Compile(p), …)`).
The law (`OrganSuite`): on random word sequences of the vocabulary the two agree, on every pair they were induced
from the organ says the gold's template, and `Fill.organ` decides each hole along the template and nothing else.

As a source it sits between the data and the readers: `Fill.first(decided, exact, organ, readers, model)` — it
decides what the data could not write and the question's words determine; it is silent otherwise, so the laws of
the data (never wrong) and of the dialogue (an open hole is a question) are untouched.

## 3. Who writes the organ

v0: **induced** from pairs — RIPPER-shaped (`Organ.induce`): per template, grow a cue by the word with the best gain
until no counter-example is covered, prune, keep if it covers two pairs, set the covered aside, repeat. The same
procedure Okay!Chat's `okay.intent.Induced` uses; here the classes are templates and the literals are stems. The
pairs are the teacher's (bench/data/teacher-pairs.jsonl; 1,945 in the sibling's copy) and the page's lessons.

v1: **the model**. The text tier already writes terms token by token (`Fill.model`); an organ is a few lines of the
same JSON — `{"when": ["сколько", "денег"], "unless": ["строк"], "then": {"fn": "sum"}}`. The model is asked for it
where its readers kept failing on a class of questions; the induced organ is the oracle the model's is measured
against; both pass the law before they serve. This is the step that makes the title literal: the model authoring a
part of itself, compiled, verified, installed — with no weight touched.

## 4. The first experiment

The 1,945 teacher pairs split by the seed's parity. On the even half: the data alone reads each pair; where it is
wrong or open, the gold template is a class; organs are induced per language. On the odd half: the data alone
versus the data + the organs — right / asked / wrong — and the organ's law on every pair. The number the experiment
is for: **how many wrong or open readings become right by an organ the data induced, with no model in the loop and
no weight changed**. The second number: the same with the organ run by its WEIGHTS, equal to the interpreter.

Then v1 on the page: the organ's JSON is what the model writes when asked; the law gates it; the organ file is kept
beside the lessons (`Clarified.keep`).

## 5. `Write`: steering as an effect, under the guard

`Read` reads the residual stream; `Write(layer, position, vector)` adds to it. As an effect it has what a hook in a
Python script does not: interceptors (okay-interceptors: control and audit of effects), a journal, replay, a policy
(okay-guard: the allowed set of vectors, who may ask, how often). The model may request `Write` itself ("the mode
X"); the handler decides. Laws: a refused write leaves the states equal to no write (bit for bit); every write is in
the journal; the journal replays to the same logits. Built on okay-llm's `Compute` as one more op — on CPU, WebGPU,
WebGL2, MLX alike.

## 6. `Fork`: the KV cache as the continuation

Level 0 of the calculus under `Choose.all` enumerates readings; on the page `Choose.first` is the only strategy
because a branch costs a forward. If the KV cache is the value a continuation captures — cloneable, resumable — then
branching at a hole, backtracking from a dead end and a beam are handlers of `Among` over one program, and the cost
of a branch is the tokens after the hole, not before. No replay (the operator's rule): the cache IS `k`. Built in
okay-llm: the cache as a value with `clone`, `Qwen3Compute.run` taking it.

## 7. What is not claimed

Not that a model learns from its own outputs: no gradient flows from an answer to a weight. Not that an organ is
intelligent: it is a rule, readable, with a law. Not that the readers are replaced: an organ covers a CLASS of
wording the readers kept failing on, and says nothing elsewhere. The claim is narrower and checkable: a model can be
given parts that are exact, chosen by the model or by its failures, installed without retraining, and the whole
stays honest because every part sits behind an effect with a law.

## 8. Built (2026-11-08)

`core/src/main/scala/okay/desk/organ/Organ.scala`: `Organ`, `Cue`, `Template`, `Organ.program` (the inlay
program: one gathering head, a rule per cue into a field of its own, the fields of a path joined into the SET of
constructors proposed — one is a decision, two is silence), `Organs.Call` with `Organs.byInterpreter` /
`Organs.byWeights`, `Organs.fill` (the source), `Organ.induce` (per path, FOIL gain, pruned on a held-out third),
`OfQuery` (a query back to a point: the pairs' gold as terms). `OrganSuite` (JVM, 3 laws): the block equals the
interpreter on random words and on the pairs; induced from the sample's 13 pairs it says 90 parts, every one the
gold's, nothing on noise; as a source with the data for the values it closes every reading to the gold.
`bench/OrganBench`: the first experiment.

**Measured (2026-11-08, the sibling's 1,945 teacher pairs, split by the seed's parity: 982 to induce, 963 held
out; three organs, one per language, 78–99 cues over 71–82 stems, 1.5–1.8 M parameters each; the law held on all
1,945 pairs and the readings by the weights equal the readings by the interpreter on all 963):**

| held-out 963 (right / open / wrong) | en | ru | uk | fixed | broken |
|---|---|---|---|---|---|
| the data alone | 157 / 148 / 37 | 88 / 192 / 41 | 81 / 179 / 40 | | |
| the organ, then the data | 162 / 130 / 50 | 102 / 182 / 37 | 100 / 170 / 30 | 67 | 29 |
| the data, then the organ | 177 / 122 / 43 | 95 / 180 / 46 | 88 / 170 / 42 | 34 | 0 |

What the numbers say. An organ induced from half the pairs — a rule over stems, compiled into a block, exact by
law, no model in the loop — fixes 67 of the data's misreadings and refusals on the other half and breaks 29; in
Russian and Ukrainian the WRONG count falls (41 → 37, 40 → 30), in English it rises (37 → 50): the English cues
over-generalize ("of", "by"). Asked after the data it never breaks a right reading and turns 34 refusals into right
answers — and 27 refusals into wrong ones. So v0's organs are a real source and not yet a safe one: the next step
is the organ's SILENCE where its cue is thin (a cue's held-out precision as its weight, and the margin gate of the
dialogue over it), and v1 — the model authoring cues where the readers fail, measured against these induced ones.
Everything here ran in seconds on the JVM with no model loaded.

## 9. The second domain taught: code, through symdex (2026-11-08)

BACKLOG C, the operator: «научи 2». `core/src/main/scala/okay/desk/code/Code.scala` — an `okay.inlay.domain.Domain`:
a point is a RELATION of symdex's Datalog prelude (callers, calls, implementers, up, affects, tests_for,
defined_in, kind, in_module) and a SYMBOL copied from the question; the exact engine is the same prelude on the
facts symdex dumps (`query goal=… format=json`), evaluated by okay-datalog in process; phrases in English and
Russian; the copier finds the symbol by its two-segment name (`Dialogue.read`), or the short name when it is
capitalized and names one symbol (a type stands for its companion); an organ induced from pairs reads the
relation. `CodeSuite`: the engine by hand (callers transitive, a change affects through dispatch, the tests for a
change), the round trip, every phrase names its symbol, the organ never wrong where it speaks (900 pairs: 94% read,
0 wrong). `bench/CodeBench` on okay-desk's own index (2,325 symbols, 1,194 asked about, 28,740 facts; 1,200 pairs,
an organ from the even half, 35 cues over 38 stems, the law on all 1,200):

| 600 held-out pairs | whole | unread | wrong when answered |
|---|---|---|---|
| the rules alone (hand-written words) | 84% | 15 | 82 (13.7%) |
| the rules, then the organ | 84% | 15 | 82 (13.7%) |
| the organ, then the rules | 97% | 15 | 6 (1.0%) |
| the organ alone | 93% | 41 | 0 |

The relation slot: 99% with the organ first; the symbol slot 98% — the misses are two-segment names that name two
symbols (`Reach.forward` in two enums): a question for the dialogue, not a guess. Natural questions about this very
codebase, answered by the engine: "What breaks if I change Fill.exact?" → 30 symbols; "Which tests cover
Strategy.greedyIn?" → DialogueSuite, OrganSuite, PageRowSuite; «Что переопределяет Grammar.next?» → Rules.next;
«Где определён Claims.of?» → calc/Claims.scala; "Who implements Rules?" → not read (two symbols named Rules — ask).
The model's part is reading which relation and which symbol; the answer is symdex's, exact, with proofs available.
