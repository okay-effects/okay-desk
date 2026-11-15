# The theory: reading is refining typed holes (2026-11-15)

The operator asked for a theory as simple and as founding as the lambda calculus is beside the Turing machine: few
notions, one rule, a meaning, laws — and every component of the work an instance of it, so an implementation is
right when it obeys the laws, not when it happens to score. `specs/calculus.md` (2026-11-07) was its first form for
data; since then the frames (`frames.md`), the statement for logic (`formalization.md`) and the guarantees
(`guarantees.md`) grew beside it. This file is the one core they all are; where they differ from it, it says how.

## 1. The core: four notions and one rule

1. **A grammar of meanings, indexed by the data.** Types τ and constructors `c : τ₁ … τₖ → τ`, where a constructor's
   next argument type may depend on the arguments already chosen and on the data D (a column's type on the table
   chosen; a value's type on the column). For every type in context the tools give the OPTIONS
   `O(τ | t, D)` — a FINITE, computable set of constructors (tables, columns, a column's values, the operations, the
   question's own numbers and spans). Nothing outside the options can ever be chosen.
2. **Terms with holes.** `t ::= c(t₁ … tₖ) | ?h:τ`. The reading starts at one hole, `?:Root`.
3. **Meaning.** A closed term has a DENOTATION `⟦t⟧_D` computed exactly by an engine (the relational engine,
   Datalog, the grid solver, the planner, exact arithmetic). A term — closed or not — can be REFUTED by a check
   `✗(t, D)` (a type error the grammar cannot see, an empty or several solutions where one is asked, a clue no frame
   uses). Refutation is MONOTONE: a refuted term has no unrefuted completion.
4. **A weigher.** `W(x, t, h, O) → Δ(O ∪ {none})`: given the text x, the term so far t, a hole h and its options O,
   a distribution over the options, or no opinion. THE ONLY LEARNED THING. A model (by its own next-token
   probabilities), the data's exact words, a person, a teacher, an oracle for tests — each is a weigher.

**The one rule — refinement:** `t[?h := c(?h₁ … ?hₖ)]` for an option c of h's type. A READING is a sequence of
refinements from `?:Root` to a closed, unrefuted term; the ANSWER is its denotation.

Everything else is derived: the order in which holes are refined, how weighers are combined, when to stop and ask —
a STRATEGY, a program over the four notions (greedy, beam with refutation, dialogue).

## 2. Derived notions, each one sentence

- **The router** is the refinement of `?:Root`, where `Root = Query | Assign | Plan | Derive | Calculate | Cannot`;
  the options are the abilities the context allows (Query only with a table open). Not a separate reader.
- **A frame** is a reading whose holes are refined in a fixed order, one line per hole (`frames.md`).
- **The statement** of a logic task is a reading of `Assign | Plan | Derive`; a span copied from the text is an
  option of a `Span` type — the sentence's own spans — not a free string.
- **Doubt** is a function of the weighers' distributions at the holes refined (the smallest margin; two weighers'
  disagreement). **Asking** is refining a hole by the person, the weigher with authority.
- **Saying** `σ(t)`: the canonical words of a term; **parsing** `π`: their exact inverse.
- **An ability** is a module of the grammar: its types and constructors, its options, its denotation, its checks,
  its saying — five functions. Abilities compose: the Root is their sum; one nests in another as a hole's type (a
  number in `Calculate` filled by a `Query`).

## 3. The laws: what makes an implementation right

Each is a test in the code (`*Suite`), checked over the whole option space where it is finite:

- **L1 finite, typed options**: every option of every hole is a constructor of its type; the options are finite.
- **L2 reachability**: every meaning in the scope has a path of options reaching a term that denotes it
  (`FrameSuite`: every `Everyday` question inside the language reaches the gold's answer).
- **L3 exact denotation**: the engines against hand-computed results (`ExactSuite`, `LanguageSuite`).
- **L4 monotone refutation**: a refuted partial term has no unrefuted completion — so a strategy may prune at once.
- **L5 the round trip**: `π(σ(t)) = t` for every term of the space (`FrameSay`: 11,400 frames) — what the person
  reads is what was computed.
- **L6 no learned assembly**: from the choices to the term, and from the term to the program, only exact code.

And two PROPERTIES of weighers — measured, not proved:
- **calibration**: the doubt's bar λ̂ by conformal risk control on a calibration set (`guarantees.md` §3.1);
- **invariance to irrelevant options** (IIA): adding options the text does not need does not change the choice
  (`technology.md` §8.1).

## 4. The guarantee

An answer shown is `⟦t⟧` exactly (L3), for a term said in words that are its own (L5), reached by choices that the
tools offered (L1) and the checks did not refute (L4); the share of shown answers whose term is not the meant one is
at most ε for texts drawn as the calibration's (calibration); everything else is asked. The guarantee lives in the
grammar, the engines and the checks; the weigher only has to be calibrated, not right.

## 5. The cost: why a reading succeeds or fails

A reading of n decisions, each right with probability pᵢ, is right with `∏ pᵢ` — at 0.95 a decision, seven decisions
give 0.70. So the grammar is designed for the weigher:
- **few decisions**: what follows from a choice is the grammar's (an order is transitive; `count` takes no column);
- **few options each**: options from the data, the question's own spans and numbers, never free strings;
- **early, decisive holes first**: the Root first (its error costs the whole reading), then what narrows the rest;
- **refutation early**: a check after each hole where one exists (L4) turns a wrong choice into a retried one.

## 6. Learning: fitting the weigher on paths

A gold term has a canonical path (its holes in the strategy's order); each step is a training example (x, t, h, O,
the gold option) — a LISTWISE choice, cross-entropy over the offered options. So:
- **distillation is on choices**: a teacher (the 35B) is a weigher; its paths are labels — no hidden states;
- **any source** of (text, term) pairs serves, kept only when `⟦t⟧` agrees with the source's answer (the verifier);
- **reinforcement** uses the denotation as the reward: the executed answer right, and per decision where the gold
  path is known (a dense reward);
- **the size** of the model is set by the weigher's job — recognising which offered option the words mean — not by
  reasoning, which is the engines'.

## 7. The work so far, in the theory

| component | in the theory | what the measures said |
|---|---|---|
| heads over frozen states (the page's readers) | a weigher trained from nothing beside the model's own language head | they do not learn other people's wording |
| writing the program (generation) | refinement with options = every token: values outside O can be made up | 27/20, 21/27 — the made-up values |
| frames by `Read.Score` | the weigher = the model's own probabilities over the tools' options | 25/6, 19/4 with two weighers agreeing |
| the statement reader (logic) | spans tagged then ASSEMBLED by code — L6 holds; but its decisions are not refinements yet | in-corpus 97–100%, sealed 40–63 / 6–27 |
| the page's "≥ 3 sentences" | a router outside the grammar | to be replaced by the Root (§2) |
| two readers agreeing | two weighers; their errors correlated (one base, one corpus) | independence to be bought |
| the teacher's ceiling | the large weigher on the same grammar | 43/54: what the grammar lacks |

## 8. What it says to do

1. **The Root**: the router as the first refinement, checked by refutation (BACKLOG R.1).
2. **The statement as refinement**: the logic reading re-expressed — Root, then each sentence's frame constructor,
   then its arguments as options among the sentence's spans — read by the same `Read.Score` weigher as the frames;
   the heads stay as one more weigher to compare (technology.md §6 q5).
3. **One module interface** in `core`: `Ability` = options, den, check, say, parse; `Refine` the strategy as a
   program over `Read` (the weigher) and `Person`; the laws L1–L6 as one generic suite every ability passes.
4. **Every new ability by the interface only** — `Calculate` next — the test that the theory is the framework.

## 9. Open

- The order of holes: fixed per ability now; chosen by the weigher's certainty (the surest hole first) is a strategy
  to measure.
- Weighers' combination: agreement now; a product of calibrated distributions is the principled one if both are
  calibrated.
- Copy-options for long texts: a sentence's spans are many; a hierarchy (the sentence, then the span within it) keeps
  the options few.

## 10. Simpler still: three things, one learned relation (2026-11-15, the operator: "can it be simpler and better?")

Two observations collapse the core further.

**(a) The weigher needs no knowledge of any ability.** By L5 every term has its canonical words σ(t), parsed back
exactly. So "which option does the text mean" is always the same question: DOES THE TEXT MEAN THESE WORDS? At a hole,
each option is said as the canonical words of the partial term with it (`σ(t[h := o])`, unfilled holes said as
"something"), and one relation scores them:

    match(x, w) — how well the canonical words w say what the text x says

It is one skill — paraphrase across wordings and languages, the thing language models know best — the same for
tables, puzzles, plans and sums. So:
- a NEW ABILITY needs no training of its own: its grammar, its engine and its saying; the matcher is shared;
- every ability's data trains the same matcher (more data for one skill, not little for each);
- the matcher can be the 0.6B's own probabilities (as `Read.Score` now, over words instead of option tokens) or a
  small multilingual cross-encoder (an NLI-style model, ~300M — technology.md §8.1) — a measured choice;
- what the person is shown and asked about is the same canonical words the matcher saw (L5) — one representation
  for the model, the person and the check.

**(b) Decide only what changes the answer.** Two options whose completions give the SAME answer on the data need no
decision (the engine is cheap on a person's table). So at a hole the options are grouped by the answers they lead to,
and the reader decides between GROUPS; doubt is at the level of answers: the top candidates within the bar agree on
the answer → shown; they differ → the person is asked, with each answer's canonical words beside it. Wrong choices
that do not change the answer stop counting as errors, and the questions asked are only the ones that matter.
(Where the space is too large to complete every option, the grouping applies to the closed subterms the engine can
evaluate — L4's pruning extended from "refuted" to "equivalent".)

**The core, rewritten:**
1. **Tools**: a grammar indexed by the data — finite candidates — and an exact answer for each closed term
   (with refutation).
2. **Words**: the canonical saying σ and its exact inverse π.
3. **Match**: one learned relation, text against canonical words.
Reading: refine holes, at each the candidates said in words, matched to the text, grouped by answer; decide where
the groups differ, ask where the decision is in doubt. Everything of §2–§9 holds; the weigher of §1 becomes
`match ∘ σ`, and doubt becomes a property of answers.

**Why it is also cheaper:** one matcher instead of an adapter per ability; fewer decisions (equivalent options
merged); fewer questions to the person; the training data of every ability pooled; and the matcher's job is the
smallest possible one — no ability's syntax, only meaning against meaning.

**What could break it, to measure first:**
- canonical words of a PARTIAL term may be awkward ("the sum of something of orders") — measured against the frame
  reader's current option tokens on `Everyday`, same protocol (E1);
- grouping by answer needs answers per option: cost on the phone measured; on large tables the grouping on a sample
  of rows is a heuristic, not exact (E2);
- a cross-encoder must see each option with the text — packed like `Qwen3Compute.score` or the options few (E3).

Experiments (BACKLOG R.4–R.6):
- **E1** the frame reader over canonical words instead of option tokens, released 0.6B, three seeds, `Everyday`.
- **E2** grouping by answer: decisions per question and asked/wrong on `Everyday` with the same weigher.
- **E3** one matcher trained on Query frames AND logic statements together; a held-out ability (`Calculate`) read
  with no training of its own — the test of (a).

## 11. Better and cheaper: what the core allows next (2026-11-15)

Each item keeps the core (tools, words, match) and its laws; each is measured by `Everyday` (three seeds) and, where
it is about speed, by seconds a question on the phone profile.

1. **Match by what the TEXT adds (PMI), not by the words' own likelihood.** The frames now take an option's mean
   token log-probability after the text; a frequent or short phrase ("count", "rows") scores high whatever was
   asked — the surface-form competition (Holtzman et al., EMNLP 2021). The fix is known and cheap: score
   `log P(w | x) − log P(w | x₀)`, x₀ a neutral text ("Answer:") with the same schema — the second term is the same
   for every question over that table, computed once and cached. Expected: fewer wrong choices among options of
   unequal length and frequency, no training. (E4)
2. **Exact first.** Where the question writes an option itself — a column's name, a value of a column, a number, a
   month — the data's exact words decide the hole (`Fill.exact` of calculus.md) and the model is not run for it.
   Measure the share of holes decided so: every one is a forward saved on the phone and a choice that cannot be
   wrong by the model. (E5)
3. **The surest hole next.** With answers grouped (§10 b), refine next the hole whose groups split the remaining
   answers most — the decision that matters most is taken while the context is cleanest, the ones that do not matter
   are never taken. A strategy, no new notion. (E6)
4. **One forward over the options' shared words.** Canonical words of a hole's options share their beginnings
   ("the sum of amount", "the sum of price"): scored as a TRIE in one packed forward (each node attending to its
   ancestors), each shared token computed once — `Qwen3Compute.score` with segments that share prefixes. (E7)
5. **A memory of readings.** A text read and confirmed (shown and not corrected, or chosen by the person) is kept with
   its term; the same wording (exactly, or a typo away) over the same table is answered from the memory, the person's
   own choices first — the cheapest reader and the one that learns each person (TeCoD's templates, BACKLOG A.1). (E8)
6. **Soft labels from the teacher.** The 35B's match scores over all options (not only its argmax) as the training
   target: the distribution says which wrong options are near, the signal per example grows. (E9)
7. **Answers grouped also across paraphrases** — only if a doubt remains: the text rephrased by the matcher's own
   canonical words of the two best groups, read again; agreement → shown. A second chance before asking, at the price
   of a reading.
8. **One code path.** Once frames over words match or beat the page's heads on `Everyday` and the logic set, the
   heads and their assembler leave the page (kept in the benches as a weigher to compare): one reader to keep fast,
   small and correct on the phone.

The order, by cost and expected gain: E4 (no training, one line of scoring) → E5 (no training) → E2 / E6 (the
strategy) → E1 (the words as options) → E7 (speed) → E8 (the person) → E3 / E9 (training) → 8 (the code).
