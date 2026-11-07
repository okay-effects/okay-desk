# The calculus: typed terms with holes (2026-11-07)

The operator asked for the lambda calculus of this direction: a core, a bus, extensions — one thing to understand,
analyse and implement, instead of the Turing machine we had (a transformer, layers, positions, prefixes, pointers,
probes, an assembler of rules, two tiers). okay-desk `core/.../calc/`: `Calculus.scala` (types, terms, the
grammar), `Den.scala` (the denotation), `Refine.scala` (fills, rendering, refinement), `Answer.scala` (answers as
terms); the laws in `CalculusSuite`; the bench `RefineBench`.

## 1. The three primitives

1. **Types, indexed by the data.** A schema gives the types: `Program`, `TableT`, `LinkT(table)`,
   `ReverseLinkT(table)`, `ColumnT(tables, role, optional)`, `OpT(kind)`, `ValueT(table, column)`, `FnT`,
   `OrderT(table, aggregate)`, `DirT`, `LimitT`, `NodeT(edges)`, `DirectionT`. The grammar (`Grammar`) lists the
   constructors of a type over the schema (the tables, a table's columns in a role, a column's values, the links
   the values prove) and the type of a constructor's NEXT argument given the arguments so far — the types depend
   on earlier choices (a column's type on the table chosen; an aggregate's column on the function: `count` and
   `none` make it `none`, no choice). A term is well-typed by construction. `Space` (§25) is this grammar's
   closed terms; the calculus adds the open ones.
2. **Filling, the one operation.** `Fill[T] : (type, candidates, context) → distribution` over the candidates,
   where the context is the question and the term so far. EMPTY means "no opinion". Every source is a Fill:
   - the DATA (`Fill.exact`): what the question writes exactly — a table's name (or a Russian form by the language
     module's dictionary), a column's name, a value of a column, a number with its comparison word, the words of
     a function, a direction, a limit, a negation; one candidate when exactly one is written, no opinion otherwise;
   - the MODEL'S OWN TOKENS (`Fill.model`, the text tier): the term rendered as the program's JSON up to the hole
     (`Render.prefix` — the readers' forced prefixes, now derived from the term instead of listed), the candidates
     scored by the next-token log-probabilities; any model with logits serves;
   - a READER of states (the vector tier): the probes and pointers of `Readers`, one per type — the adapter is
     pending a public surface of `Readers` (a sibling agent edits it);
   - the USER: the answer to "not sure: A or B";
   - the ORACLE (`Fill.oracle`): the gold term's constructor at the hole's path, for the laws.
   Fills compose: `Fill.first` (the first with an opinion); a sum is the other composition.
3. **Denotation.** A closed term is a point of the space (`Den.program`), the point compiles to the query the
   engines run (`Space.compile`), the engines are exact. A term with holes has no denotation: it is a reading not
   finished, a value of its own (an abstention), never an error.

Everything else is a STRATEGY of refinement (`Refine`): which hole (the leftmost), which sources in which order,
when to stop (no opinion, or two candidates within a margin → the term stays open, the hole and its two
candidates named). Greedy is one strategy; a beam over the same fills with the denotation pruning closed subterms
is another; a dialogue (fill the open hole from the user, or refine the last term by one constructor) a third.
None is a new mechanism.

## 2. The laws (CalculusSuite, all passing 2026-11-07)

- **A point and its term**: `Den.program(Den.term(p)) = p` for the sample's 13 gold points and 300 random points;
  every term of a point is closed under the grammar.
- **Reachability** (`read ∘ phrase = id` with a perfect source): refinement from one hole of type `Program` with
  the oracle reaches every one of those 313 points.
- **The data alone never errs**: with `Fill.exact` as the only source on the sample's 26 questions (13 × 2
  languages), 22 close and all 22 are right; 4 stay open at the hole the data cannot decide — the function in "The
  three largest orders" (none, not an aggregate), the order's column in "Самый большой неоплаченный заказ". The
  share that closes is a number of the data's own, before any model.
- **Answers**: an answer is a term (`Answer.A`) whose fact holes are cells of the result and whose words come
  from a grammar (en, ru): every fact is a cell (`grounded`), every fact's text is in the sentence and every
  number in the sentence is a fact (`readBack`) — for all 13 gold results in both languages. A figure in an
  answer is a reference, never a token.

## 3. What the old pieces are, in the calculus

| before | now |
|---|---|
| the assembler's rules (`exact first`) | `Fill.exact`, one function per type, no opinion when unsure |
| the readers' prefixes (`Probes.prefixes`) | `Render.prefix(term, type)`: the term so far as JSON |
| a probe (shape, op, fn, parts) | a `Fill` for a finite type from a state |
| a pointer (table, columns, value start, name) | a `Fill` for a name type from a state |
| the masked generation (WebLLM) | `Refine` with `Fill.model` as the only source |
| the abstention "not sure: A or B" | an open term: `Refine.Done.unsure` |
| the space (§25) | the closed terms of the grammar |
| the phrases (§25) | the inverse of refinement on the generated language |
| the answer's sentence | a term with fact holes, `Answer` |

## 4. Answers, explanations, dialogue, actions

The calculus is symmetric. Reading is text → term, generation is term → text, and both are typed holes filled
from sources: in an answer the DATA fills the fact holes and a grammar or a model fills the word holes. The same
shape covers an explanation (a Datalog proof as a term → "why": which rows, which rule), a dialogue (the last term
plus one constructor: "а только в Киеве?"), and an action (a term whose denotation is an effectful program on
okay: `Den[T] = A ! Es`, the handlers the engines — the effects rule "the model's output is data, handlers run
it" is a consequence, not a convention). Free text without a term structure is the degenerate type `Token*`; even
there the fact holes stay bound to the data.

## 5. What is not known yet

- **Locality**: is "the question and the term so far" enough context for every fill? It is for the sample and the
  generated pairs; a question that needs the answer of a previous one (dialogue) extends the context by the last
  term, by design.
- **Which fills are linear**: a reader of states is a linear map from the bus; the text tier is the model's own
  distribution; the kit (specs/bus.md) measures, per type, how far the linear fill carries. The bus is then
  exactly: the representation from which the linear fills of all types work.
- **The core as a growing thing**: a core trained so that the types' fills are linear from its states (a
  representation objective) is the core "grown under laws" of okay-inlay; the readers fold into weights as one
  head that yields a term. Not built; the calculus says what to train and what to measure.

## 6. The bench

`RefineBench`: refinement with the data first and the model's tokens next (the text tier) on a model, on the
sample (26) and on held-out pairs by constructor; the data-alone share printed apart (its law: never wrong).
Numbers recorded in specs/data.md §26 as they come.

## 7. The levels (the operator, 2026-11-07): each a calculus of its own, each described by the one below

The simplification the operator asked for, done as levels. A higher level is an INSTANCE of the lower one — its
states, moves and enabling said in the lower one's terms — and keeps its own laws. `Level0.scala`, `Level1.scala`,
`Encode.scala`; the laws in `LevelsSuite`.

**Level 0 — the choice calculus.** A state; the moves ENABLED in it (none: terminal); a STEP by a move; a CHOOSER
that weighs the enabled moves (empty: no opinion); an OPEN state accepts any move the chooser names. The run:
weigh, take the best, step — until terminal, or until the chooser has no opinion or two moves are within the
margin: the run stops in a non-terminal state, a value (an abstention). Nothing about words, types, programs or
models. Its laws: a run is bounded; the same chooser gives the same run; a run replayed by its own moves is the
same run.

**Level 1 — the word calculus.** A reading is a WORD over an alphabet that changes at every step: the state is a
context and the word so far, `next(ctx, word)` the letters allowed after it (None: complete), an interpretation
takes a complete word to a domain (a table, a sentence, an effect). The exact facts live in `next`: where the
context decides a letter, `next` returns ONE letter and no chooser is asked — exactness is a property of the
grammar, not a strategy. An instance of level 0 (moves = letters, step = append, enabled = next). Its laws: where
`next` narrows to one letter along a gold word, it is the gold's (`narrowsRight`, with `contradiction` naming
the place it fails); a complete word interprets.

**Level 2 — typed terms with holes** (§1). Described by level 1 (`Encode`): a term IS its word — the constructors
chosen in preorder; the tree is rebuilt by replaying the grammar (`tree(word(t)) = t`); the leftmost hole's
candidates are the letters allowed next, narrowed by the data; a level-2 fill is a level-1 chooser; the
denotation is the interpretation of the complete word. Its laws (§2) stay; the encoding's law: a level-2
refinement and the level-1 reading with the same sources take the same word (checked on the sample's 26
questions), and the level-1 oracle reaches every point.

**Level 3 — the space and its phrases** (§25 of data.md): points, phrases in two languages, answers as terms.
Described by level 2: a point is a complete word whose interpretation is the point's result. Its own law, the
strongest so far: a point's phrase, read by the DATA ALONE (level 1 with no chooser), never contradicts the point
— 313 points phrased twice (en, ru), 626 readings, 0 contradictions, and 460 of the 626 close to the point by the
data alone. The remaining 166 stop at a hole only a chooser can fill.

What simplifies at each step down: level 2 drops the strategies (one loop), level 1 drops the types (a type is
the alphabet after a word) and the "exact first" strategy (a singleton alphabet), level 0 drops the words. What
each level keeps as its own: the laws above. The bus (specs/bus.md) is a level-1 notion: the representation of
the context from which the chooser is linear per alphabet.

## 8. The one operation as an effect (2026-11-07, after the operator's "хорошо")

The category-theoretic reading made concrete: a hole is a pending OPERATION, and the calculus is an effect of
okay-scala. `Choose.scala`: one operation `Pick(type, candidates, context): Dist`; a reading is a program of
`Choose` (`Choose.refine`, the same loop as `Refine.greedy` with the pick performed instead of a fill called); the
SOURCES are handlers of `Pick` — `Choose.by(fill)` the last one (its opinion is the answer, empty included),
`Choose.first(fill)` a source that answers when it has an opinion and otherwise FORWARDS the operation to the
handler outside it (the row `Choose + Choose`: the inner handler leaves the outer effect), `Choose.nobody` no
source at all. The composition of sources is the nesting of handlers; "the data first, then the model, then a
person" is three handlers around one program. This is the okay rule — the model's output is data, handlers run
it — as a theorem rather than a convention, and the free monad over one operation is level 0.

Laws (`ChooseSuite`): the data as the only handler gives the same term and the same abstention as the exact fill
in function form on the sample's 26 questions; the data first with the oracle outside closes every question to
its point, and the oracle is asked exactly when the data alone leaves the term open; no source at all is never
wrong. 26 laws in all for the calculus.

## 9. On the standard effects (the operator, 2026-11-07): one new operation, the rest known

The operator's point: single out the special effect, build it on simpler, known ones. okay-scala's standard
effects are the known ones — `Choose.among` (multi-shot nondeterminism, with `first` and `all`), `Writer`,
`Reader`, `Fail` — and the calculus's ONE new operation is `Weigh.Of(type, candidates, context): Dist` (the former
`Pick`, renamed: it weighs, it does not choose). `Strategy.scala`: the reading is ONE program — the leftmost
hole, its candidates, their weighing, `among` them in the weighed order, a `tell` of each step; an abstention is
`Choose.none` (no way on). The STRATEGY is the handler of `Among`:

- `Choose.first` — greedy: the best-weighed way (`Strategy.greedy`); the law: the same term as `Refine.greedy`
  with the same source, and when open, the same hole (StrategySuite);
- `Choose.all` with a uniform weigh — every way: THE ENUMERATION OF THE SPACE by the reading itself
  (`Strategy.every`); the law: the same set of points as `Space.enumerate` (3652 = 3652 over orders). This law
  found three constraints the space had and the grammar lacked (a group without an aggregate, an order on one
  aggregate without groups, a limit without an order); they are now in the grammar's types (`FnT(required)`,
  `OrderT(…, grouped)`, `LimitT(ordered)`, a group's `except`), so the space is DERIVED from the grammar and the
  nondeterminism handler, not written twice;
- a beam is a third handler of the same program; a dialogue is `first` with the person as the outer weigh.

So the stack is: level 0 = the free monad over `Among` (known); level 1 = words with `Weigh` inside `next`
(the one new thing); level 2 = the typed program above; level 3 = the space, which is level 2 under `all`. The
sources of `Weigh` are handlers (§8): the data, the model's tokens, a reader of states, a person, an oracle —
`by`, `first` (forwards when silent), `nobody`. 28 laws for the calculus, all passing.

## 10. Generation by the same program (2026-11-07)

`Rules` is now the interface of a term language (the constructors of a type, which open arguments, the type of the
next argument); `Grammar` (the programs over a schema) and `Answers` (the sentences about a result) implement
it; `Strategy.program` runs over any `Rules`. The answer grammar: one constructor (the shape, fixed by the
program and the result: Count, Scalar, Groups, Top, Rows, Reach, Missing, Nothing), FACT holes `FactT(row,
column)` whose only candidate is the cell — the data fills them, no chooser is asked — and WORD holes
`WordT(slot)` whose candidates are the wordings of the slot in a language (two or three each, en and ru): a
model's tokens, a person or a uniform weigh choose among them. So generation IS reading over other rules:
`Strategy.greedy` gives one sentence, `Strategy.every` all the wordings; the interpretation `text` is the
sentence. A figure in a sentence is a cell by construction for EVERY term of the grammar, not only the shapes a
hand-written `Answer.of` produced.

Laws (`AnswersSuite`): over the 13 gold results in both languages, every wording (54 sentences) is closed, its
facts are cells of the result, and the sentence reads back (every fact in it, every number a fact or the count);
greedy with a uniform weigh is the first wording. 29 laws for the calculus.

What the model does here: it chooses WORDS, never facts. The model-weighed wording (the sentence so far as the
prefix, the wordings as candidates — the same `Fill.model` with a renderer for answer terms) is the next bench;
the answer's phrase is the `Phrase` of §3 in the other direction, the same adjunction.

## 11. The device stack, reviewed (the operator, 2026-11-07): one forward, four handlers

The operator asked where `OnMlx` was. It was not: on the JVM the forward was mlx-scala's own (`MlxReading`), a
second implementation beside okay-llm's `Qwen3Compute` (and `Qwen3Forward`, a third, the plain-Scala reference)
— the law "the page equals the JVM" bridged two programs. Now okay-llm has `OnMlx` (module `mlx`, artifact
okay-llm-qwen-mlx): `Compute` on Apple's GPU through mlx-scala, thin — quantized matmul in MLX's layout,
rms_norm, rope, matmul + softmax with the causal mask of the positions. Its law: `Qwen3Compute` on MLX equals it
on the CPU (states within 1e-3 of their scale, the same next token; the cached prefix then the rest equals the
whole). And okay-desk's `ComputeReading` answers `Read` on the JVM by the page's own `OnQwen` over
`Qwen3Compute(OnMlx())` — the same forward, the same prefix cache, the same template as the browser: the sample
read by it on Apple's GPU, 13/13 en, 13/13 ru (`DESK_DEVICE=mlx`, or `cpu`).

The stack, each layer an effect or a trait with handlers and a conformance law:

| layer | the operation | handlers | the law |
|---|---|---|---|
| `Weigh` | a hole weighed | the data, the model's tokens, a reader of states, a person, an oracle | §2, §8, §9 |
| `Read` | states at positions, logits, tokens, the prompt | `OnQwen` over any `Compute`; `MlxReading` (mlx-scala's forward, for training and the teacher) | the readers' kit; the page == the JVM |
| `Compute` | the tensor ops of a layer | `OnCpu` (the reference), `OnWebGpu`, `OnWebGl2`, `OnMlx` | each == `OnCpu` |

The calculus never sees a device; a device never sees a term. "Any hardware" = a handler of `Compute`; "any model
with logits" = the text tier of `Weigh`; "any model with states" = the vector tier after the bus's kit.

What is still to simplify and generalize, in order: `Qwen3Forward` is a third copy of the forward (keep `OnCpu` as
the reference, drop it); the three loops of refinement (`Refine.greedy`, `Weigh.refine`, `Strategy.program`) are
one — keep the one on the standard effects; `Compute`'s ops are Qwen3's (`headNormRope`, `swiglu`) — an
architecture as DATA (q/k norms or not, the activation, the rope, tied embeddings, biases) over a smaller set of
ops gives Llama, Gemma, Qwen2.5 on the same four handlers; training stays on MLX (autograd) — the boundary is
inference hardware-neutral, training not yet.

## 12. The levels as rows and programs, not monads (the operator's question, 2026-11-07)

"Which free monad at level 0 — a new one?" None is new. In okay every program `A ! R` is already the free
structure over the operations of its row `R` (the kernel: a program is data, a handler interprets it), so a level
is not a monad of its own but a SIGNATURE (a row) and a PROGRAM over it:

- **Level 0**: the row `{Among}` — okay-scala's standard `Choose` — and the program `Choice.program`: among the
  enabled moves, in a chooser's order, until terminal. `Level0.scala`'s trait `Choice` (enabled, step) is the
  coalgebraic presentation of the same thing, a transition system; the program is the algebraic one. Its
  handlers are the strategies, and a handler of a free monad is an algebra: `Choose.first` (the Maybe algebra —
  the first way) gives the run; `Choose.all` (the List algebra — every way) gives every run.
- **Level 1**: the row `{Among, Weigh}` and the program over words: `next` gives the enabled letters, `Weigh`
  orders them. One new operation; the row is still free, as every row is.
- **Level 2**: the same row, the program parameterized by the typed grammar (`next` derived from the types).
- **Level 3**: not a new program — the image of level 2's program under the `all` algebra: every way the choices
  can go = every closed term = the space. "Under all" means handled by `Choose.all`.

The law (`Level0Suite`): `Choose.first` of the level-0 program with the oracle's order is the run of the
transition system; `Choose.all` of it over the small schema gives 3655 complete words that denote exactly the
3652 points of `Space.enumerate` (three words are spellings of the same point). So the space is reached from the
choice calculus alone, with no typed grammar in the statement — the typed grammar is what makes `next` say the
right letters.

## 13. The simplifications done (the operator: "реализуй все", 2026-11-07)

- **One forward.** okay-llm's `Qwen3Forward` (the third copy) is gone; `OnCpu` is the reference and `OnMlx` the
  independent implementation it is checked against (states within 1e-3 of their scale, the same next token). The
  config lives in `Qwen3Config.scala`: the architecture as data is what config.json says (sizes, rope, eps) plus
  what the FILE says — a layer's attention or MLP present or not, the head tied to the embedding or its own
  `lm_head` (new, with the law: an untied head that copies the embedding gives the tied head's logits, on the
  device and on the CPU head, diff 0.0). The q/k norms stay Qwen3's: making them optional means a change in all
  four handlers' shaders, and there is no model in the cache to check it against — not done, said.
- **One loop.** `Weigh.refine` is gone. `Strategy.program` on the standard effects is THE reading; `Refine.greedy`
  stays as the function-form reference the laws compare it with. `Strategy.greedyIn[R]` runs the same program in
  a wider row (the model's tokens need `Read`); the bench reads through it. The sources' laws (`WeighSuite`) are
  now the one program with handlers nested around it.
- **Once per question.** The question's words and their runs (`Context.words`, `Context.grams`) are computed once
  per question, not at every hole; OnMlx keeps its causal masks per shape.
- **The JVM by the page's program.** `ComputeReading` (OnQwen over Qwen3Compute on OnMlx or OnCpu) answers `Read`
  on the JVM; the law `ForwardSuite` is now "the page's program on MLX equals mlx-scala's forward" (states at every
  position, the next token, the template's tokens). `OnQwen`'s prefix cache no longer answers the cached sequence
  itself from the cache (it holds no final state for its last row): it runs it whole.
- **Not done, and why.** `Reading` (the sync interface) beside `Read` (the effect): removing it touches
  `Readers.On`, a sibling agent's file in flight. CUDA/ROCm: another handler of `Compute`, when there is such a
  machine. The architecture as fully general data (activation, rope kind, biases): needs a model of another family
  to hold a law against; the cache has Qwen3 only.

31 laws in okay-desk's core, 8 in okay-llm (6 qwen, 2 mlx), all passing.

**Measured after the simplifications (2026-11-07, the JVM by the page's program on OnMlx, `DESK_DEVICE=mlx`):**
the sample 13/13 en, 13/13 ru; held-out 100: the data alone 55% whole / 44% open / 1 wrong ("The smallest count
among items": a column named `count` — fixed: the word alone no longer names the function when a column is called
count), data + the model's tokens 65% whole, order 91%, limit 94%, group 91%, agg 86%, where 78%. OnMlx frees an
op's intermediates at once (the bench of a hundred questions was killed at 137 before).

## 14. The answer in words on the page, and the calculus on real data (2026-11-07)

**Words on the page.** After the result's table the page now says it: a model that generates still summarizes
(kept only when faithful, as before); the readers' mode, which generates nothing, gets the answer grammar's
sentence — `Answer.ofQuery(query, result)` (the page holds the query, not the point; the law `OfQuerySuite`: it
equals `Answer.of(point, result)` on every gold, and reads back) said by `Answer.text` in the question's language.
Its note on the page: "said from the result by the answer grammar: every number is a cell of it". The app's law
(`AppSuite`): "How many orders are unpaid?" answers "4 rows." beside the table.

**Real data.** `RealRefine` (bench): the calculus — the data first, the model's tokens next, no trained reader —
on the sibling agent's hand-written GDP questions (RealBench, data.md §28), by the page's program on MLX
(`DESK_DEVICE=mlx`). The outcomes as RealBench counts them (right / refused / wrong). Numbers below as they come.

**Real data, measured (the GDP questions of data.md §28, the core, no trained reader, the JVM by the page's
program on MLX).** The grammar grew for it: a SECOND condition on the table ("the GDP of Ukraine in 2020"; the
canonical order of two conditions is the table's order of columns, in the grammar, the denotation, the draw and
the exact fill alike — the law "the reading is the space" holds again, 26,853 = 26,853 over orders), time
values as an open type (a year written in the question), a source's explicit refusal `?` (the words of
constructors the grammar lacks — growth, share, comparison — stop the reading open instead of handing it to the
model), codes only in capitals, "none" only when nothing is written.

| the calculus, the core | en right / refused / wrong (of 19, 3 outside) | ru |
|---|---|---|
| the data alone | 10 / 9 / 0 | 6 / 12 / 1 |
| the data, then the model's tokens | 12 / 6 / 1 | 6 / 6 / 7 |
| (the sibling's trained readers, §28, for reference) | 16 / 3 / 0 | 12 / 3 / 4 |

The Russian gap is the data's own words ("Украины", "ВВП"): the sibling's `Words` dictionary, to be plugged into
the exact fill when committed. The data alone never errs in English; its one Russian error is a country's name
it cannot read, so it closes on the year alone — `Words` closes that too.

## 15. The sources joined: the data's words, the trained readers (2026-11-07, after the sibling's commits)

- **`Context.words`**: the exact fill reads a dataset's own names (`skill.Words`, data.md §29 — the sibling agent's
  dictionary made once per dataset by a generating model) where it read the generator's `Phrase.Russian`; `Words`
  falls back to the generator's by itself. The Russian gap on real data was exactly these names.
- **`Readers.weigh`**: the trained readers as a `Fill` — the VECTOR TIER of `Weigh`, one public method added to the
  sibling's `Readers`: the hole's type picks the probe or pointer that reads that constructor (the kind probe for
  `Program`, the two table readers for `TableT`, the role's pointer with ask for a column with the constructor
  probe's "-" as the mass of `none`, the function probe, the op probe, the direction, the order and the limit
  probes); one prompt per question cached; no opinion on a value's text, a link, a node. So the three sources of
  the design are now real handlers of one operation: the data, the readers, the model's tokens — in that order
  (`Fill.first`), each asked only where the one before was silent.
- The benches take `DESK_SOURCE=readers` (the readers between the data and the tokens) and `DESK_WORDS=<file>`
  (RealRefine). Numbers below as they come.

**Measured (2026-11-07, evening).** The three sources in order — the data, the trained readers (`Readers.weigh`),
the model's tokens — on cut19, the JVM by the page's program on MLX:

| | held-out 100 (the grammar's pairs) | the sample | real GDP, with `Words` (en / ru, right / refused / wrong of 19) |
|---|---|---|---|
| the data alone | 68% whole, 32% open, 0 wrong | 26/26 | 11/8/0 — 9/10/0 |
| data, tokens | 65% (§13) | 13/13, 13/13 | 12/6/1 — 10/6/3 |
| data, readers, tokens | **91%** whole, 2 open | 13/13, 13/13 | 12/6/1 — 10/6/3 |
| the readers alone, §25 | 57% | 13/13, 13/13 | §28: 16/3/0 — 12/3/4 |

And on the TEACHER'S pairs (`TeacherRefine`: 242 pairs of natural wording in en, ru, uk, specs/teacher.md, none of
them seen by anything here; right / open / wrong):

| | en (84) | ru (83) | uk (75) |
|---|---|---|---|
| the data alone | 38 / 38 / 8 | 27 / 42 / 14 | 3 / 64 / 8 |
| data, tokens | 51 / 6 / 27 | 37 / 4 / 42 | 9 / 0 / 66 |
| data, readers, tokens | **66** / 4 / 14 | **39** / 3 / 41 | **25** / 0 / 50 |
| (the sibling's readers alone, teacher.md) | 63% | 25% | 16% |

What it says: on natural wording the law "the data alone never errs" does NOT hold — "Сколько всего денег"
(how much money: a sum, the rule says count), "посчитай среднее" (compute the average: the rule says count),
"Клиент с именем gleb" (a value both tables hold) — 30 wrong of 242; these are the exact rules' limits on
people's wording, a number now, by language. The readers as the middle source lift English to 79% and Ukrainian
from 12% to 33% with no Ukrainian anywhere in their training (the words of a question are not what they read).
Ukrainian has no dictionary and no pairs: the teacher's pairs are the way for it.

## 16. `Reading` merged into `Read` (2026-11-07)

There were two lists of what a skill needs of a model: the effect `Read` (its operations: states at positions,
logits, encode, decode, prompt, a note) and the JVM's sync interface `Reading` with the same five methods. Now one:
`Reading` is a handler of `Read` in place — one method, `answer[X](op: Read[X]): X`; `Read.sync` wraps it,
`Read.run` runs a program on it; every call site (`Readers.On`, the benches) is unchanged. The backends:
`MlxReading` answers by its own MLX methods, `ComputeReading` by the page's `OnQwen` awaited — one line. The
browser's `OnQwen` was a `Read` handler already (futures through `Async`); so a model is a handler of `Read` on
every platform, and nothing else.

## 17. The answer as a conclusion (the operator, 2026-11-07, evening)

"Not a retelling of the data — an inference, one sentence that answers the question." `Claims` (calc): a claim is
what the result lets one CONCLUDE — a count against the whole and its share; the leader with its margin over the
next and against the average; one figure over n rows, and whose it is for a minimum or a maximum; the first and
the last of groups and the gap; who is missing, by name when few; what a node reaches. Every figure is derived
EXACTLY here from the result and the table (the whole counted, the next row and the average by the same engine);
the model's part is which claim answers the question and the words. Law (`ClaimsSuite`): every gold result has a
claim, its sentence in both languages states only the claim's figures (cells and derivations), the derivations
recompute. The page says the claim beside the table ("concluded from the result: every number is a cell of it or
an exact derivation"). E.g. "4 of 8 orders, that is 50%." — "Only fay of 6 customers has no orders." — "cid has
the largest amount: 200, 150% more than bob (80); 2.1× the average." — "By sum, bob leads (1080) and dee trails
(45): a gap of 1035 across 5 customer groups."
