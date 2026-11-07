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
