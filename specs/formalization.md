# Formalization: what the language model does, and what it never does

The operator's direction (2026-11-10): the question is the neurosymbolic split. The language model does not try to
solve a task; its work is formalization and the choice of a solver. The framework is not tied to one model: models
are swapped, a stronger one taken when needed, but even a weak one must cope with almost any task. This spec is the
design that follows; `logic.md`, `understanding.md` and `puzzles.md` hold what was built and measured before it.
Status: design, nothing here is built. Owner of the reading work from this date: the Claude session (COORDINATION.md).

## 1. The split

- **The model decides about the text**: what kind of task this is, what a phrase means (one of a closed set of
  logical kinds), which span names a thing, which span fills which role, what is asked.
- **The symbolic side decides about the problem**: consequences, search, counting, arithmetic, consistency, proofs;
  the well-formedness of a reading (the grammar); and every check of a reading that needs no understanding
  (types, coverage of the text, answerability of the goal, the round trip through `Phrase`).
- The model never produces a number, a constant that is not a span of the text, a multi-step inference or a program.
- An unsure decision is a question about one sentence, never a guess and never a refusal of the whole task.

## 2. Why the two earlier paths failed, read as one diagnosis

Both made the model a programmer. Free JSON (`understanding.md`): segmentation, ontology, logic and syntax in one
generative pass — 28 layers pass 12/12 short tasks and 0/8 longer held-out ones. The weighed Datalog grammar
(`logic.md` §5.5): 34 decisions per task, of which Binding (295) and Structure (377) of 1291 holes are the authoring
of rules and of the program's shape, and the copies (the worst stage, 72–82%) must be chosen in the PROGRAM's order,
so the model has to plan a serialization of the whole text. The stages that are local to a phrase were already read
at 97–99% (relation, structure, binding) by a linear head trained on 64 tasks with one phrasing per slot.

So the interface is still wrong in one way: the reading is aligned to the program, not to the text.

## 3. The statement: a reading aligned to the text

1. **The kind of task first** — one decision, and it IS the choice of the solver:

   | kind | the question | solver (exists) |
   |---|---|---|
   | `derive` | what follows from the facts and rules | okay-datalog (`Logic`, `Execute`, the Horn block) |
   | `assign` | which values satisfy the constraints (zebra, seating, truth-tellers, ages) | `Solver`, `Formal` |
   | `plan` | which moves lead from the start to the goal (river, jugs, towers) | `Planning` |
   | `calculate` | which quantity follows from the quantities | exact rational arithmetic (`Arithmetic`, `Formal`) |
   | `query` | what the opened tables answer | `Exact`, `Logic` over tables (the working data path) |

   The choice is checked, not trusted: a kind whose frames cannot cover the text's sentences is refuted exactly.
2. **Sentence by sentence.** The task's states are computed once; each sentence is read at its own reading point
   (the sentence restated after the cached task, as the readers read "where the model decides"). A sentence yields
   one to three **frames** from the kind's closed set, each a constructor and a few copied spans.
3. **Names are copied, kinds are chosen.** A relation is named by its span ("taller than"); the model chooses its
   LOGICAL kind from a closed, topic-free set — an order, an equivalence, an attribute with exactly one value, a
   membership, a plain relation, a quantity, a state variable, a move, a prohibition. What follows from the kind
   (an order is transitive, an attribute is a bijection in a zebra grid) is the solver's, not the model's. No topic
   vocabulary is in the core.
4. **Rules stated in the text** are read by shape — every/no/some, if–then, composition, inverse — again one
   constructor and copied spans, never variables: the compiler introduces them.
5. **The goal** is the frame of the question sentence.
6. **Compilers** take a statement to each solver's program (`Compile`, `Formal`, `Planning`, `Puzzle`, `Query`),
   with the denotation law both ways, as `Den` has it for data.

What this changes in the decision budget is to be MEASURED model-free before any training (`LogicBudget` on the
statement): the expected effect is not only fewer decisions (no bindings, no program structure) but local ones —
each needs one sentence and the task's context, none needs a plan of the whole program.

## 4. The framework: models are handlers

Nothing new is needed in the interface; it is the bus of okay-inlay (`specs/bus.md`) and two effects here:

- `Weigh` — the one operation: candidates of a hole to a distribution. Every model is a handler of it.
- `Read` — states, logits, encode, decode: the vector tier, answered by MLX, WebGPU, WebGL2 or the CPU.

A model enters by one of two tiers and the statement, the solvers and the checks do not change:

- **vector tier** (weak models, the phone): heads over the model's states — a classifier per hole kind, a pointer
  for spans. One forward of the task plus a short suffix per sentence; no generation at all.
- **text tier** (stronger models, any family, a gateway): the model's own log-probabilities over a hole's options,
  or constrained generation of frames under the same grammar.

Handlers chain as `Weigh.first` already does: data (exact facts) → the weak model → a stronger model if one is
present → the person. A model that is silent forwards. Strength changes how often the last handler is reached, not
whether an answer may be wrong.

**The kit.** A model is qualified by one command that prints one table: task class × language → right / asked /
wrong, with size, latency and peak memory on the WebGL2 phone profile. The minimal model is the smallest that
passes the kit; it is a result of the framework, not an assumption of it.

## 5. The standard set

Classes, each with public counterparts for external validity; en and ru first:

| class | examples | public counterpart |
|---|---|---|
| deduction | syllogisms, Horn chains, kinship, reachability, negation as absence | bAbI 15–16, ProofWriter, CLUTRR |
| order and comparison | taller/older/earlier chains, who is the most | bAbI 17–18 |
| assignment | zebra grids, seating, schedules, truth-tellers and liars | ZebraLogic, knights and knaves |
| planning | river crossings, jugs, towers, path finding | bAbI 19 |
| quantities | word problems with exact arithmetic, ages | GSM8K (held-out split already prepared) |
| data | questions over opened tables | the sample 13, GDP 38, the teacher's 388 |

Out of the set for now: induction from examples (sequences, analogies) and reading comprehension without a formal
question. The first number to obtain is model-free: the share of the set that HAS a statement (as `Space` held all
13 golds). A task outside the language is reported as unsupported, not attempted.

**The collection (2026-11-10).** The set exists as data: `okay.desk.tasks` (`core/…/tasks/`), 233 tasks in 12
classes, each with its text in English and Russian, the answer a person expects, the gold statement for an exact
solver and that solver's result; `Task.check` is the law that the two agree (`TasksSuite`: 0 failures, about a second
for all of them on the JVM).

| class | tasks | solver of the gold statement |
|---|---|---|
| Einstein's puzzle (the zebra puzzle and Einstein's riddle in variants, drawn house puzzles) | 33 | ordered grid |
| Who is where (floors, a race) | 22 | ordered grid |
| Who is who (matching) | 16 | finite constraints |
| Comparisons | 20 | finite constraints |
| Deduction | 15 | finite constraints |
| Family | 12 | Datalog |
| Routes and dependencies | 17 | Datalog |
| Knights and liars | 13 | finite constraints |
| Plans (crossings, jugs, towers, lamps, lifts, frogs, a knight) | 29 | planning |
| Quantities | 17 | finite constraints, arithmetic |
| Number puzzles (cryptarithms, squares, queens, colouring) | 17 | finite constraints |
| Data | 22 | tables |

Variants are part of the set on purpose: the same puzzle with another question, told in another order, with a clue
removed (the result is `several`) or a contradicting clue added (`none`), and plans that cannot be done
(`unreachable`) — a reading is right only when the solver's verdict is. The page shows the collection on the examples
screen (`ui/TaskList.scala`): a class filter, ten tasks a page, either language. A task does not show its answer; it
has two buttons. "Answer" opens the task alone and whole with a form: what a person types is checked
(`tasks/Judge.scala`: an answer in words against the exact one — the answer alone, a wrong try does not give the
answer away). "Solve" asks the task in the chat as written (a task over data opens its tables first); when the desk
has finished, its words are checked and the chat says "right", "not right" or "no answer" with the exact solver's
answer. The desk's statements are judged by words, a task over data by the engine's last table row by row, a grid
never (it has every name of the task in it). The gold statements are today's engine programs; step 2 of §7
rewrites them in the statement language and keeps `check` as the denotation law.

## 6. What the minimal model must be able to do

Four competences, none of them reasoning: the meaning of words in each language; who did what to whom inside a
sentence; what a pronoun or a description refers to across sentences; choosing among tens of constructors. These are
an encoder's competences and are read best at a model's middle layers (bridge §6: layer 12 of 32; data.md §14: the
0.6B's threshold is layer 18). Hypothesis to test, not a claim: the 12-layer student (about 122 MB at 4 bits) is
above the floor for a text-aligned statement; a 135M model may be at it for English. The ladder to run the kit on:
SmolLM2-135M, the 12-layer student, cut19, Qwen3-0.6B whole, Qwen3-1.7B, Qwen3.5-4B, the 35B through the gateway.

## 7. Order of work, each step with its check

1. The standard set as data (tasks with gold solver programs and answers) and the kit's table → the existing paths
   measured on it as the baseline.
2. The statement language and its compilers, model-free → coverage of the set, the denotation laws, the decision
   budget against `logic.md` §5.5.
3. The corpus at scale: generator × the teacher's rewrites judged by the engines (`teacher.md`), thousands of tasks,
   many phrasings, en and ru → split laws as `Corpus` has them.
4. Heads on the 12-layer student → per-decision accuracy held-out; target 98% with 0 wrong closed at a margin.
5. Exact checks as a filter over the candidates of unsure holes → asked turns into right, wrong stays 0.
6. The ladder → the kit's table per model; the minimal model named by measurement.
7. The page: one path, WebGL2 phone profile, then the physical phone.

If step 4 misses its target with enough data, an adapter trained through the grammar's mask is the next lever, and
it stays a separate low-rank file beside an unchanged backbone (`Adapted`).

## 8. Decided by the operator (2026-11-10)

- **Knowledge packs: yes.** Topical background knowledge ("uncle", "a week has seven days") is a loadable pack of
  exact definitions that the model selects by weighing, never in the core; without a pack the model asks or reads
  the definition from the text. The parametrized families (plans, quantities, number puzzles) are packs as well.
- **A remote stronger model: off.** The page stays offline; a remote model is a handler for benches only.
- **The gate: agreed by the operator (2026-11-10)** as proposed here. §9 shows that exact checks alone do not make
  wrong = 0, so the gate is defined per decision, not per answer:
  1. An answer is shown only for a statement that passes `check`, compiles, and in which every decision is CLOSED.
     A decision is closed when the role or the data settles it; or the model's margin on it is over the threshold of
     its kind; or the exact side closes it — each of its other candidates is refused, signalled, or gives the same
     result (step 5 of §7: the solver as a filter over the candidates of an unsure decision).
  2. A decision that is not closed is ASKED, and asked as itself in the words of the text ("is the question about
     the pet or about the drink?") — one short question, never "please rephrase". Its answer is one more decision
     settled by data.
  3. The thresholds are per kind of decision and are set on held-out tasks so that no shown answer there is wrong;
     the kinds §9 found silent (the attribute asked, a sign, the order of two pieces, a link of a `Derive` statement)
     get the strict ones. What is claimed is the measurement — "0 wrong of N shown, so under 3/N at 95%" — never
     "wrong = 0". The target for the first page: under 1% wrong of the shown, asked reported beside it as the measure
     of a model's strength.
  4. `none` and `several` are answers, shown as such with the clues that clash or the values left open; they are a
     reason to ask only when a decision of that statement was unsure.
  5. The reading is always shown with the answer, in the text's own words, sentence by sentence: the last witness of
     a silent fault is the person, and they must be able to see what was understood without reading a program.

## 9. Built: the statement language (step 2 of §7), model-free (2026-11-10)

**Where.** `core/…/statement/`: `Statement.scala` (the language and `check`), `Assign.scala` and `Derive.scala` (the
compilers; `Compile.of` chooses), `Writing.scala` (gold statements from marks in a task's text), `Budget.scala` (the
decisions of a statement), `Faults.scala` (every statement one decision away from a right one).
`core/…/tasks/Coverage.scala` holds the two tables below; `StatementSuite` (13 tests) and `StatedSuite` (6 tests)
hold the laws. No model is involved anywhere in this section.

**The language as built.** A statement is its kind and, per sentence of the text, the frames read in it. A frame is
a constructor and its pieces; a piece is a span of the text and a pointer to the span that first named the same
thing (itself when new), a claim also carries a sign (said or denied), a question of a grid carries the index of
the attribute asked. Nothing in a statement is a word of its own: every name is a span, so the same statement holds
in English and in Russian. Two kinds of the five are built:

| kind | constructors |
|---|---|
| `Assign`, what is said | Attribute, Same, Differ, Either, Before, After, JustBefore, JustAfter, Beside, At, Middle |
| `Assign`, what is asked | Ask, First, Last, Whether |
| `Derive`, claims | Fact, If, Iff, OneOf, Which, Whether |
| `Derive`, ways | Edge, Link, Path, Round, From, To, Apart, Cycle, Ends, Whether |

The solver is chosen by the statement's shape, never by a decision of its own:

| shape | solver |
|---|---|
| `Assign` with attributes listed, a positional clue, no either-or, every question an `Ask` | ordered grid |
| any other `Assign` | finite constraints |
| `Derive` of claims | finite constraints over booleans |
| `Derive` of ways (edges, links) | Datalog |

`check` refuses a statement that is not of its text (a constructor of another kind, a wrong number of pieces, a
piece outside its sentence, a pointer that is not to an earlier first naming); a compiler refuses what no solver
can be asked (no question, a question of nothing, claims and ways in one statement, a thing set against itself, two
things of one attribute on one position).

**Laws** (`StatedSuite`, over every stated task, both languages): the statement is of its text; the English and the
Russian statements are the same frames; the statement compiles and its solver's result is the task's exact result
(the denotation law — the same `check` the collection had with hand-written programs); no two things of a task
share a name, and the same words never name two things.

**Coverage and the decision budget.** 126 of 233 tasks are stated. A decision is what a reader must choose: the
kind (1), per sentence its constructors and the stop, the end of each piece, a link for a thing named before in
other words, a sign per claim, the attribute asked. `free` counts what the role or the data settles with no choice
(a new thing; a thing named again in the same words). `needs` is the accuracy per decision at which 70% of the
class's tasks are read exactly, `0.7^(1/median)`.

| class | stated | en: mean / median / max | needs | ru: mean / median | links en / ru | free en / ru |
|---|---|---|---|---|---|---|
| Einstein | 33/33 | 96.8 / 105 / 127 | 99.66% | 106.7 / 115 | 0.0 / 9.8 | 49.4 / 39.5 |
| Places | 22/22 | 79.5 / 77 / 106 | 99.54% | 86.8 / 84 | 0.0 / 7.2 | 40.0 / 32.8 |
| Matching | 16/16 | 40.6 / 42 / 53 | 99.15% | 42.4 / 43 | 0.0 / 1.8 | 20.6 / 18.8 |
| Order | 20/20 | 19.6 / 18 / 28 | 98.04% | 20.7 / 19 | 3.2 / 4.3 | 3.6 / 2.5 |
| Deduction | 15/15 | 26.5 / 26 / 40 | 98.64% | 26.5 / 26 | 3.9 / 3.9 | 2.3 / 2.2 |
| Kinship | 4/12 | 50.8 / 52 / 52 | 99.32% | 54.8 / 56 | 9.0 / 13.0 | 5.3 / 1.3 |
| Routes | 16/17 | 28.4 / 30 / 36 | 98.82% | 30.4 / 33 | 5.9 / 7.9 | 6.2 / 4.2 |

Against `logic.md` §5.5 (34.1 decisions a task, 98.96% a decision for 70% exact; measured there 90.1% on the
12-layer student and 92.3% on the 28-layer model): Order, Deduction and Routes are at or under that budget; a grid
is three times over it. A grid's cost is its size, not its difficulty — forty constructors and fifty-five pieces of
the same few forms — and in English the data settles every link (in Russian the cases of a noun leave 2–10 open).
Read plainly: no head at 92% reads a zebra puzzle whole; the exact side has to carry the rest (below), and step 5
is not optional for grids.

**Not stated, and why.** Routes, 1 task: "exactly two flights" counts steps. Kinship, 8 tasks: grandmother, sister,
uncle, cousin are not in the text — they need the "family" pack of §8. Knights and liars, 13: a `Says` constructor
with a frame inside it, and "we", "I", "one of us", which are not spans to copy. Plans, Quantities, Number puzzles
(63): parametrized families, each a pack of its own (§8). Data (22): the calculus of `data.md`, another statement.

**What the exact side makes of a wrong statement** (the model-free part of step 5). For each stated task, every
statement one decision away from the right one was built (a constructor of the same roles; two pieces swapped; a
thing linked to another earlier thing or taken as new; a sign flipped; another attribute asked; a frame dropped)
and run. `refused`: `check`, the compiler or the solver refuses it. `signalled`: the solver answers none, several
or undecided where the right result is definite. `harmless`: the same result. `silent`: another definite result —
nothing exact can tell.

| class | wrong statements | refused | signalled | harmless | silent |
|---|---|---|---|---|---|
| Einstein | 24405 | 22.7% | 51.9% | 19.0% | 6.4% |
| Places | 11342 | 25.3% | 55.3% | 12.4% | 7.0% |
| Matching | 2330 | 17.4% | 44.1% | 29.3% | 9.2% |
| Order | 895 | 27.9% | 27.0% | 28.6% | 16.4% |
| Deduction | 416 | 26.4% | 22.4% | 28.8% | 22.4% |
| Kinship | 542 | 15.9% | 0.0% | 59.6% | 24.5% |
| Routes | 1037 | 28.3% | 0.0% | 43.9% | 27.9% |
| all | 40967 | 23.3% | 49.5% | 19.2% | 7.9% |

| decision | wrong statements | refused | signalled | harmless | silent |
|---|---|---|---|---|---|
| constructor | 5084 | 7.9% | 56.6% | 27.7% | 7.7% |
| order of pieces | 376 | 0.0% | 40.2% | 31.6% | 28.2% |
| link | 33746 | 25.5% | 48.9% | 18.3% | 7.3% |
| sign | 92 | 0.0% | 38.0% | 20.7% | 41.3% |
| attribute asked | 215 | 0.0% | 0.0% | 7.4% | 92.6% |
| a frame dropped | 1454 | 37.4% | 49.2% | 10.7% | 2.8% |

Per task the silent share is 15.3% in the mean, 10.2% in the median, 60.9% at the worst.

An estimate of the gate from these shares, with faults taken as independent and `D` the class's median decisions:
right = (1 − e + e·harmless)^D, shown = (1 − e + e·(harmless + silent))^D, asked = 1 − shown, wrong = shown − right.

| class | at 92% a decision | at 98% | at 99% | at 99.5% |
|---|---|---|---|---|
| Einstein | 0.1 / 99.8 / 0.1 | 18.0 / 79.4 / 2.6 | 42.6 / 54.4 / 3.0 | 65.3 / 32.5 / 2.2 |
| Places | 0.4 / 99.4 / 0.2 | 25.6 / 71.4 / 3.0 | 50.8 / 46.4 / 2.8 | 71.3 / 26.7 / 2.0 |
| Matching | 8.7 / 88.0 / 3.4 | 55.0 / 40.5 / 4.5 | 74.2 / 22.8 / 3.0 | 86.2 / 12.1 / 1.7 |
| Order | 34.7 / 55.5 / 9.8 | 77.2 / 18.0 / 4.8 | 87.9 / 9.4 / 2.7 | 93.8 / 4.8 / 1.4 |
| Deduction | 21.8 / 64.5 / 13.7 | 68.9 / 22.5 / 8.6 | 83.1 / 11.9 / 5.0 | 91.1 / 6.2 / 2.7 |
| Kinship | 18.1 / 48.5 / 33.4 | 65.6 / 15.2 / 19.2 | 81.0 / 7.9 / 11.1 | 90.0 / 4.0 / 5.9 |
| Routes | 25.2 / 49.6 / 25.2 | 71.3 / 15.6 / 13.1 | 84.5 / 8.1 / 7.4 | 91.9 / 4.2 / 3.9 |

(right / asked / wrong, % of tasks.)

What this is and is not. The faults are every possible one, each counted once — not a model's errors, which fall
where the text is hard; links are 82% of them only because a thing can be linked to many others. The run is over the
English statements (the frames are the same in Russian). The gate table is arithmetic over those shares, not a
measurement of any model. What it does establish:

- A grid is well guarded: three wrong statements of four are stopped by the exact side and 6–7% pass unseen. The
  solver's `several` and `none` are the signal, and they cost nothing.
- A statement for Datalog has no signal at all: a wrong edge gives another definite answer or the same one. Routes
  and Kinship need a second witness (a law of the pack — nobody is their own parent — or a margin of the model).
- Three decisions are silent by nature and must be closed by the model's own margin, with a question below it:
  which attribute is asked (92.6% silent), the sign of a claim (41.3%), the order of two pieces (28.2%).
- "Wrong = 0" (§8) is not reachable by exact checks alone in any class; they turn most of wrong into asked. The
  remaining 1–5% at 99% a decision is the margin's to close.
- 13 of the 126 tasks have `none`, `several` or an undecided value as their right answer; there a signal is the
  answer, and the gate must show it as one rather than ask.

**Found on the way, and fixed.** `Solver.solve` on some inconsistent grids ran for minutes, and the page runs that
solver on whatever a model read, so a wrong reading could freeze it. The cause was a clash the arc rules cannot see
before a value is fixed (two values of one sort "on one position", a value "next to" the one it is "with"), found
again at every leaf of a search that placed unrelated values first. Fixed in `puzzle/Solver.scala` by the operator's
instruction: the classes of values on one position are checked before any search (`impossible`), and a value no clue
speaks of is supposed last. Measured by `bench/runMain okay.desk.bench.SolverStressMain` — every grid of the
collection with one clue read wrong (dropped, another form, another value), whole and beside each single other clue:
462,685 puzzles, 62,520 inconsistent, 0.9 ms in the mean, 81 ms the slowest (before: no answer in 20 s on the
first of them). `WrongReadingSuite` keeps the cases. Outcomes and their clashing clues are as before.

**To run.** `sbt "bench/runMain okay.desk.bench.StatementBudget"` (a second),
`sbt "bench/runMain okay.desk.bench.StatementFaults"` (about half a minute) and
`sbt "bench/runMain okay.desk.bench.SolverStressMain"` (seven minutes).

**Next.** Step 3 as planned, with two changes this measurement forces: the corpus is generated in the statement
language (marks in the text, so every task carries its gold decisions), and its grids are small first (three
houses: 43–48 decisions in the collection, four: about 70) — the five-house puzzles are the last rung, not the
first. Step 4's target stays per decision, but is reported per kind of decision, since their costs differ by an
order.

## 10. Built: the corpus and the first reader on the student (steps 3–4 of §7), 2026-11-10

**Where.** `core/…/tasks/Drawn.scala` (the corpus), `tasks/Settings.scala` (eight more settings of a grid),
`statement/Assemble.scala` (a statement from a reader's decisions), `bench/…/StatementRead.scala` (the heads and the
measurement), `bench/…/DrawnShow.scala` (a look at drawn tasks). Laws: `DrawnSuite`, `AssembleSuite`.

**The corpus (step 3).** A task is drawn by a seed from a family and is written together with its statement, in
English and in Russian, so every text carries its gold decisions. 32 families:

- 11 settings of an ordered grid (weight 6 each): houses, floors, race and the eight of `Settings` (queue, cinema,
  offices, ships, garden, train, ranking, talks), from 3 positions × 2 features to 6 × 4. Every clue set is thinned
  to what uniqueness needs, by the exact solver;
- 4 themes of a matching (weight 2), 17 scales of a comparison (weight 1).

Held out of training whole: race, talks, the last matching theme, every fourth scale — the UNSEEN split, the measure
of reading against remembering. The rest is split by a hash of the English text: one in ten to Test, one in ten to
Validate. No task of the collection is in the corpus (`DrawnSuite`); no two things of a setting share a name in
either language (the same suite) — a name said twice would make the gold link ambiguous. The teacher's rewrites of
`teacher.md` are not in yet: all variety is the generators'.

A corpus must be balanced in every decision it teaches, and this one was not. The collection's generator asks
"who …" two times of three and otherwise about the first attribute it finds, so the attribute asked was the first
one open in 92% of the drawn tasks. Every head for that decision then measured the same 97%, on the same tasks, in
both languages: it had learned "the first", and was right in about half of the questions about another. `Grids.generate`
now takes `any = true` from the corpus (any attribute as likely as another); the collection's own tasks are drawn as
before and did not change. The bench prints the table of that decision (`picks: asked>said of open`), so the same
blindness shows at once.

**`Assemble`.** A weak reader decides four kinds of things over the tokens of a text, and `Assemble.assemble` makes
the statement from them with nothing added: a TAG of every token (outside a piece, its beginning, inside it, a
beginning that opens a list), the FRAMES of every sentence (its next constructor or "nothing more"), the LINK of a
name (which earlier piece it names again; asked only when the same words named no thing before), the PICK of a
question (which attribute it asks for). `Assemble.decisions` gives the decisions of a gold statement, and the law is
that they assemble back into it: every text of the corpus and all 182 `Assign` texts of the collection are in reach.
A drawn task is 58 decisions in the median and 139 at most beside its tags. The data closes what it can: a question
does not ask for the attribute a thing it names is a value of, so that one is not offered (a tenth of the picks
become forced). `Budget` and `Faults` (§9) count picks as before this.

**The reader (step 4).** The frozen 12-layer student reads a text once (`State the task: …` as the system line);
four heads read the decisions off its states, d = 1024:

| decision | head | reads |
|---|---|---|
| tag | linear | the token's state and the state at the end of its word |
| frame | linear, one per place in a sentence | the state at the sentence's end and the mean of its tokens |
| link | pointer (two maps, 64) | the mean state of the name against the means of the earlier names |
| pick | token to token, ONE map of 64 | each token of the question against each token of the listing sentence |

Two of these were found by their failures:

- *The end of the word.* A causal state at the first piece of a word does not know the rest of it: «Т» of «Тот» and
  of «Таня» is the same state, and only one of them begins a name. Reading the tag from the token's state together
  with the state at its word's end took whole tasks read exactly from 86.7% / 65.7% (en / ru) to 96.7% / 88.3% on
  the same 1200 tasks; twice the data without it gave 90.3% / 75.0%.
- *One map for a question and a listing.* What a question asks is said by a word or two that the listing sentence
  says too ("which plant … grow" — "each grows a different plant"). Pooled states drown them, and a map per side
  learns the tasks it was shown: from 870 examples, held-out 84.1% / 95.6% with two maps, 97.8% / 98.2% with one;
  from 1768 examples one map reads 100% / 100%. A wider map (128) is no better.

**Measured** (`READ_TRAIN=2400`: 4800 texts, 705,963 tags, 64,598 first frames, 14,757 links, 3,554 picks; twelve
minutes in all, the model's reading under five of them). Per decision on Test, en / ru: tag 100.0% / 100.0% (of
69,425 / 97,015), piece 100.0% / 100.0%, frame 99.9% / 99.8% (of 8,285), link 100.0% / 100.0% (of 141 / 1,822), pick
100.0% / 100.0% (of 230). Whole tasks, 300 texts a language (91 for the collection); *exact* is the gold statement,
*harmless* another statement with the same result, *signalled* a wrong `several` / `none`, *refused* no statement
that compiles, *silent* another definite result:

| set | | exact | harmless | signalled | refused | silent | the gate asks | shown | of them wrong |
|---|---|---|---|---|---|---|---|---|---|
| Validate | en | 97.0% | 0.3% | 1.7% | 0.0% | 1.0% | 20.0% | 240 | 0 |
| | ru | 94.7% | 0.7% | 4.0% | 0.3% | 0.3% | 33.7% | 199 | 0 |
| Test | en | 96.3% | 1.0% | 2.0% | 0.7% | 0.0% | 20.3% | 239 | 0 |
| | ru | 93.3% | 1.7% | 5.0% | 0.0% | 0.0% | 31.7% | 205 | 0 |
| Unseen | en | 38.3% | 0.7% | 8.7% | 45.7% | 6.7% | 68.7% | 94 | 3 signalled, 7 silent (12 refused) |
| | ru | 14.0% | 4.0% | 37.3% | 31.3% | 13.3% | 85.7% | 43 | 7 signalled, 9 silent (14 refused) |
| Collection | en | 64.8% | 1.1% | 2.2% | 30.8% | 1.1% | 52.7% | 43 | 0 (2 refused) |
| | ru | 53.8% | 1.1% | 16.5% | 25.3% | 3.3% | 62.6% | 34 | 0 (1 refused) |

The gate (§8) is a least margin per kind of decision, raised on Validate until no task it lets through is wrong; it
came to frame 2.08, link 2.84 and nothing for tags and picks. On Test it shows 444 answers and none is wrong: the
agreed bar (under 1% wrong among the shown, checked as none of N held out) holds for wordings like the corpus's. By
family, exact or harmless: the collection's Order 87.5%, Matching 71.9%, Einstein 51.5%, Places 40.9%; unseen talks
46.6%, the unseen scales 25–66%, the unseen matching theme 7.8%, race 2.9%.

**What this says.**

- Step 4's target is met where the wording is the corpus's: every kind of decision at 99.8% or more held out, no
  wrong answer through the gate. The price is a question in 20% of English tasks and 32% of Russian ones.
- Nearly all of that price is ONE fault: the direction of an order word (Before read as After, JustAfter as
  JustBefore — floors, ranking, offices). The same word means opposite orders by the setting: "above" is later on
  floors numbered from the bottom and earlier in a ranking. A linear head over one sentence cannot hold a word
  times a setting; the frame's margin has to pay for it. (§11: not so — the pairs are confused inside one
  setting too, and a head that looks at the relation word reads them all.)
- The gate's promise does not carry to wordings never met: on Unseen it lets 16 silent wrong answers through among
  137 shown. Its margins were set on tasks like the training ones; they must be set on held-out families.
- Unseen wordings are mostly lost at the tags, and in a few forms: a value of two words ("green ‹shirt›", "orange
  ‹juice›", "Pall ‹Mall›"), a number as a value ("5‹6›"), a position said in a new way (Same read as At). The corpus
  has one-word values only. Russian links of an unseen setting are 92.3%: forms of names never met.
- Tried and dropped for the pick, each no better than the one before while the corpus was unbalanced: a pointer over
  pooled sentences, with the listing sentence added, with keys at the list's stops, the best single token against
  pooled keys, raw cosine without training (the states of layer 12 are not lexical on their own: 50% before
  training).

**To run.** `READ_TRAIN=2400 sbt 'set bench / javaOptions += "-Xmx18g"' "bench/runMain okay.desk.bench.StatementRead"`
(twelve minutes; `READ_TRAIN=600` in five), `sbt "bench/runMain okay.desk.bench.DrawnShow"`.

**Next.** (1) The direction of an order word: give the frame head the setting (the introduction's state, one hidden
layer), or keep the word's own direction in the statement and let the numbering sentence settle it — whichever
reads floors and rankings alike. (2) Variety in the corpus: values of several words and numbers as values, more
settings, the teacher's rewrites; the measure is Unseen and the collection's Einstein and Places. (3) The gate's
margins set on held-out families. (4) Step 5: the exact side as a filter over the candidates of an unsure decision,
to turn asked into right. Then the ladder (step 6) and the page on WebGL2 with the heads as files (step 7).

## 11. The frame by where the sentence is looked at (2026-11-10)

**The fault.** §10 left one fault inside the corpus's own wordings: the direction of an order word, Before read as
After and JustAfter as JustBefore. §10 blamed a word that means opposite orders by the setting ("above" on floors
and in a ranking). The sentences say otherwise (`wrong frames` in the report): the pairs are confused inside one
setting, on words that mean one thing there — "somewhere to the right of", «ровно на этаж выше», "right behind",
"nearer the lighthouse", "X is luckier than Y". The head read a frame off the state at the sentence's end and the
plain mean of its tokens. A relation is said by a word or two among twenty that describe who it relates: in the
mean that word is a twentieth, and at the end of the sentence the model is busy with the last thing described.

**Tried** (`READ_TRAIN=600`: 15,898 first frames to learn from, 8,424 on Validate and 8,402 on Test to check):

| head of the first frame | Validate | Test |
|---|---|---|
| the end ++ the mean, linear, 30 epochs (§10) | 47 wrong | 33 wrong |
| the same, 120 epochs | 32 | 26 |
| the end alone | 256 | 216 |
| the best token for each constructor (a detector, its maximum over the sentence), ++ the end, 60 epochs | 93 | 86 |
| one learned look ++ the end, 10 / 20 / 40 epochs | 5 / 56 / 0 | 10 / 47 / 0 |
| four learned looks ++ the end, 10 epochs and on | 0 | 0 |

A LOOK weighs the sentence's tokens by a softmax over one learned direction and takes their weighted mean; the frame
is linear over the looks and the end (`Gaze` in `StatementRead.scala`, trained with Adam; a look with no preference
is the plain mean, which is where it starts). Longer training of the old head does not help, and neither does a hard
maximum (it does not even fit its training set). One look gets there but not steadily; four do from the first ten
epochs. Every head stays at 86–90% on the families never trained on: a relation word never met is not read by
looking at it better.

**Measured** with four looks at every place of a frame, all else as §10 (`READ_TRAIN=2400`, twenty-one minutes).
Per decision on Test, en / ru: tag 100.0% / 100.0%, frame 100.0% / 100.0% (of 8,285, none wrong), link 100.0% /
100.0%, pick 100.0% / 100.0%. Whole tasks:

| set | | exact | harmless | signalled | refused | silent |
|---|---|---|---|---|---|---|
| Validate | en | 100.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| | ru | 99.7% | 0.0% | 0.0% | 0.3% | 0.0% |
| Test | en | 99.7% | 0.0% | 0.0% | 0.3% | 0.0% |
| | ru | 99.7% | 0.3% | 0.0% | 0.0% | 0.0% |
| Unseen | en | 28.0% | 0.3% | 20.7% | 46.7% | 4.3% |
| | ru | 18.7% | 4.0% | 34.7% | 30.7% | 12.0% |
| Collection | en | 67.0% | 1.1% | 0.0% | 31.9% | 0.0% |
| | ru | 60.4% | 1.1% | 7.7% | 29.7% | 1.1% |

**What this says.**

- In the corpus's wordings the reader is done: of 1,200 held-out texts, 1,197 give the gold statement, one another
  statement with the same answer, two no statement (a tag); none gives a wrong answer. The four wrong decisions
  left on Test are tags.
- The gate of §10 has nothing left to do there: no task of Validate is wrong, every margin stays at zero, nothing
  is asked — and so everything is shown on Unseen too, 215 wrong answers of 600. A gate set on tasks like the
  training ones is no gate for wordings never met; that was a leak in §10 (16 of 137) and is the whole of it now.
  **Until the margins are set on held-out families the reader does not go on the page.**
- What is left is all out of the corpus: a setting, a theme or a scale never met (§10's list: values of several
  words, numbers as values, a place said in a new way, Russian forms of new names).

**A margin is not a gate for wordings never met** (`READ_OUT=file` writes every task's outcome and least margins;
gates were then tried on the file without reading again). The bench's gate, set on one half of the held-out
families and checked on the other:

| set on | checked on | shown | of them wrong | shown on Test (all of it right) |
|---|---|---|---|---|
| race, scales 3 and 7 | talks, matching3, scales 11 and 15 | 27 of 332 | 3 | en 73%, ru 13% |
| talks, matching3, scales 11 and 15 | race, scales 3 and 7 | 18 of 268 | 1 | en 40%, ru 1% |
| all of Unseen | the collection | 23 of 182 | 0 | en 14%, ru 1% |
| the collection | Unseen | 444 of 600 | 146 | 100% |

A bar high enough for one group of new families still lets wrong answers through on another, and asks about most
tasks the reader has right. The least margins of right and wrong readings overlap out of the corpus (frame: half of
the wrong are above 2.4, a tenth of the right below 3.1; tag: the same medians, 5.0 and 5.5 for a definite wrong
answer): a linear head is as sure of a word it never met as of one it knows. The Russian side is closed whole by the
link's bar.

What the wrong answers are, out of the corpus (782 texts of Unseen and the collection): 288 refused (no statement —
asked anyway), 173 signalled (`several` or `none` where the task has one answer), 50 silent. The silent ones are in
two places: 31 on scales never met (the comparative and the superlative of an adjective the head has not seen are
each given a direction, and not the same one) and 18 on talks (a relation worded anew), one in the collection.

**Next**, replacing §10's list:

1. A task that must have one answer and comes out `several` or `none` is asked, not shown: the exact side says the
   reading is incomplete. This takes 173 of the 223 wrong answers above with no bar at all, and leaves the
   collection at one wrong answer in 182.
2. Scales: read the superlative AGAINST the comparative of the same task (the same word said again — the tied map
   of the pick, §10) instead of giving each a direction of its own; an adjective never met then costs nothing.
3. Variety in the corpus: several wordings of a relation in a setting (there is one now), values of several words,
   numbers as values, more settings. The measure is Unseen and the collection.
4. A sign of novelty that is not a margin: heads taught on different families must agree (each family left out of
   one of them), set and checked across families as above.
5. Then the reader on the page (step 7), the exact side over the candidates of an unsure decision (step 5), the
   ladder (step 6).

## 12. Variety: what the corpus did not have (2026-11-10)

**More of the same does not reach the collection.** The reader of §11 trained on a part of the families
(`READ_GRIDS`, `READ_SCALES`), the tasks in proportion:

| trained on | Unseen en / ru, exact | Collection en / ru, exact |
|---|---|---|
| 10 families, 932 tasks | 11.3% / 7.0% | 67.0% / 61.5% |
| 17 families, 1,655 tasks | 18.7% / 12.0% | 68.1% / 60.4% |
| 25 families, 2,400 tasks (§11) | 28.0% / 18.7% | 67.0% / 60.4% |

Families never met are read better with every family added, and the collection does not move: what it has and the
corpus had not is not another setting but other PHENOMENA, and every drawn family lacked the same ones.

**What was absent** (the collection's refused tasks, read one by one), and what the corpus has now:

- values of several words ("green tea", «Нью-Йорк») — every drawn value was one word; a setting has two-word items;
- numbers as values (a house number, a jersey number) beside numbers as places — `numbered` features;
- a feature only one setting had (a drink, a pet, a sport), so its words meant that setting — each setting draws
  `extra` features from a common stock;
- ONE wording of a relation per setting, so the wording was the setting's mark — `Settings.ways`: other wordings of
  where a thing is, of before, after, just before, just after and beside, mixed per task by its seed;
- four matching themes with the same people — twelve drawn themes with people of their own (`Matching.drawnThemes`;
  the held one stays held).

33 families are trained on, 25 before. The collection's texts are as they were. A hyphen inside a name is a token
of no letters that `pieces` passes by: `Assemble.decisions` now tags it Inside (102 of 4,800 training texts were
out of reach without it in the run below; the next run has them).

**Measured**, the reader of §11 unchanged, `READ_TRAIN=2400`, against §11:

| set | | exact | harmless | signalled | refused | silent | §11 exact |
|---|---|---|---|---|---|---|---|
| Validate | en | 99.0% | 0.0% | 0.0% | 0.3% | 0.7% | 100.0% |
| | ru | 96.8% | 1.1% | 0.4% | 1.8% | 0.0% | 99.7% |
| Test | en | 99.0% | 0.0% | 0.0% | 1.0% | 0.0% | 99.7% |
| | ru | 95.8% | 1.0% | 0.0% | 2.8% | 0.3% | 99.7% |
| Unseen | en | 56.7% | 5.0% | 20.3% | 9.0% | 9.0% | 28.0% |
| | ru | 14.0% | 4.3% | 27.0% | 41.3% | 13.3% | 18.7% |
| Collection | en | 94.5% | 0.0% | 0.0% | 2.2% | 3.3% | 67.0% |
| | ru | 70.3% | 9.9% | 6.6% | 9.9% | 3.3% | 60.4% |

Right answers by family, before → now. Unseen: matching3 7.8 → 82.8, race 3.4 → 42.6, talks 32.4 → 17.6,
comparison3 56.3 → 50.0, comparison7 56.3 → 56.3, comparison11 59.4 → 28.1, comparison15 62.5 → 65.6. Collection:
Einstein 54.5 → 95.5, Matching 71.9 → 96.9, Order 90.0 → 87.5, Places 52.3 → 68.2.

**What this says.**

- The collection in English is read: 86 of 91 statements exact. The phenomena were the fault, not the model.
- In the corpus the reader lost one to four points: the same 2,400 tasks now cover much more. More tasks, not a
  bigger head, is the first thing to try.
- The margins do not gate (again): with the bench's gate 21 en and 37 ru silent wrong answers are shown on Unseen,
  3 and 3 on the collection.
- What is left has one shape. Seven of the nine trained settings of a grid are PLACES (houses, floors, desks, berths)
  and the two orders among them (a queue, a list) have no verbs of their own; both held ones are an ORDER OF EVENTS
  IN TIME (a race, talks): «раньше / позже», "some time before", «на одно место позже», "one right
  after the other, in some order" are read as the nearest spatial word or not at all, and the Russian intro of the
  race («ничьих нет») is tagged as a name. Russian comparatives of scales never met get a direction by guess
  (§11's point 2 stands). Feature picks on Unseen are at 87–90%.
- Unseen is no longer unseen to the one who writes the corpus: its failures were read to choose what to add. From
  here it is a development set; the claim of the gate (§8) needs a SEALED set — families written before any of
  their results is looked at, of which only the totals are read.

**Next.**

1. Order in time as trained settings (a queue, a schedule, a tournament), each with several wordings; more scales;
   more tasks (`READ_TRAIN` 4800).
2. The sealed set, written at the same step and reported by totals alone.
3. §11's points 1, 2 and 4 (several or none is asked; the superlative against the comparative; agreement of heads
   as the sign of novelty), then the page.

### 12.1 Order in time, a sealed set, and novelty signs (2026-11-10, evening)

Done in a private copy (`~/work/my/okay-desk-private/desk`, not yet in the tree): three trained settings that are
an order of events in time (guests arriving, swimmers finishing, musicians going on stage; three wordings each),
eight more scales, and a SEALED set — ten families (four grid settings, two matching themes, four scales) written
by another hand; the corpus's author reads neither their wordings nor their results by family, totals only.
`READ_TRAIN=4000`, 44 trained families (4800 does not fit 18 GB of heap; 4000 ran in 26 GB, 22 min).

| right (exact + same answer) | §12 | now |
|---|---|---|
| Validate en / ru | 99.0% / 97.9% | 98.0% / 97.0% |
| Test en / ru | 99.0% / — | 98.0% / 98.7% |
| Unseen en / ru | 56.7% / 14.0% (exact) | 57.3% / 42.0% |
| Sealed en / ru | — | 29.3% / 10.7% |
| Collection en / ru | 94.5% / 80.2% | 86.8% / 83.5% |

- Sealed is the honest number of "a setting nobody of this corpus wrote": under a third in English, a tenth in
  Russian. Most of the rest is REFUSED (en 46%, ru 83%: no statement comes out), which is safe; shown and wrong
  without a gate are 27 of 300 en and 12 of 300 ru.
- Unseen ru rose threefold (race 49.5%, talks 38.2%): the order-in-time settings did what they were written for.
  The collection's English fell (94.5 → 86.8, 11% refused): the corpus share of the collection's own two settings
  shrank as families were added — the weights of families need care, not only their number.
- NOVELTY SIGNS, measured offline on the run's file (`novel/novel.scala`, bars from Validate alone):
  - the distance of a sentence from the taught ones of its frame (`near`) separates nothing: useless;
  - a PAIR OF WORDS OUTSIDE NAMES THAT TRAINING NEVER HAD (`pair`) closes every text of Unseen and of Sealed —
    1200 texts, none shown, so none shown wrong — and on the collection shows 61.5% with 0 wrong in English and 1
    in Russian; together with the margins at 1% of Validate: 0 wrong of 56 en and of 53 ru.
- What this says: the reader, as it is, answers the wordings it was taught and nothing else, and a lexical sign
  says exactly when that is. That is a gate that holds (§8) and a coverage that is the corpus's own: out of the
  corpus the reader ASKS. Wider reading is not a matter of this gate; it needs a reader that generalizes — more
  writers of the corpus (the sealed set shows how far one hand's variety goes), or a stronger backbone.

### 12.2 The reader on the page (2026-11-10, night)

- ONE PROGRAM here and there: `core/…/statement/Reader.scala` holds the reader — `Seen`, the heads (`Gaze`,
  `Asking`, the tag head, the pointer), the words met (`Known`), `read` with the gate, and the reader as a file
  (`write` / `parse`: 1.9 MB — the heads, 918 words, 3,088 pairs, the bars). `bench/StatementRead` trains it and
  measures by the same `Reader.read`; `READ_SAVE=file` writes it, and the file read back gives the collection's
  numbers again. The page loads `model-small/statement.reader` beside the student (`Engine.state`).
- THE GATE as built: an answer is shown when the statement compiles and has a result, no word outside the names and
  no pair of neighbouring ones is new, and every least margin is over its bar — the bars by language and kind, the
  margin 99 of 100 answers of Validate have. "None" and "several" are answers (§8, point 4). Otherwise the doubt is
  said about its sentence: the wording never taught (the whole words), the sentence read unsurely, or why no
  statement came out.
- Measured with the saved reader (READ_TRAIN=4000, 44 families), shown / of them wrong: Validate en 290 / 3,
  ru 286 / 2; Test en 287 / 1, ru 287 / 2 (of 300 each: 0.3–1.0% wrong of the shown, 3–5% asked); Unseen and
  Sealed 0 shown of 1,200; the collection en 56 / 0, ru 55 / 1 (of 91 each).
- THE PAGE: "Solve" on a task the statement language states (Einstein, Who is where, Comparisons, Who is who: 91
  tasks) goes to the reader — `Model.State` — and the chat shows the answer in the text's names with the reading
  under it, a line a sentence («Петра выше Нины.» → Петра > Нины), or the doubt; the check judges a reader's
  answer by what it names (`Task.agrees`), not by the form of the word. With no reader loaded "Solve" is the chat
  as before. `Said` words it; `ReaderSuite`, `TaskListSuite` (every stated task through the app with a gold
  reader: right) are its laws.
- In a real browser (`scripts/statement.scala`, the first ten tasks of each class, both languages): 59 right, 20
  asked, 1 wrong of 80 on WebGPU; the same answers on WebGL2 and as a phone (WebGL2, the head on the CPU);
  0.1–0.7 s a task. The wrong one is the collection's one (ru, Who is where): its reading is on the screen.
- What this is and is not: the first end-to-end path — text → statement by a model that generates nothing →
  exact solver → answer with its reading — on the phone's profile. Its reach is the wordings of the corpus
  (§12.1). Next: the solver inside the reading (candidates of an unsure sentence filtered by "one solution, every
  clue used": §7 step 5), then a corpus written by a teacher model, measured on the sealed set.

## 13. One model: the goal and the decisions (2026-11-11, by the operator's word)

**The goal.** ONE small model that we build and train, working in a browser on a phone or a computer, on WebGL2 or
WebGPU, with no server: it solves logic tasks and answers questions over data from the files given or from the
internet. Languages: English, Russian, Ukrainian, Polish; another language is a module added, and so is another
ability. The operator left the what and the how to this spec's author; these are the decisions.

**What the model does** (§1 stands): it reads what a person wrote into a FORMAL STATEMENT and chooses the solver.
It solves nothing and computes nothing; exact engines do, and what an engine did not produce is not shown. Unsure,
it asks about the place it is unsure of.

**The base: the 12-layer student of Qwen3-0.6B** (122 MB, 4 bit, the cut vocabulary in blocks by script). Why it
and no other:
- it runs today on WebGL2 on the phone's profile, WebGPU and MLX by ONE forward (`Qwen3Compute` over `Compute`), a
  task read in 0.3–0.7 s; any other architecture is a new engine on four devices before its first measure;
- its tokenizer and its training are multilingual: the four languages are in it, and its embedding is already
  loaded in blocks by script — a language's letters are a file;
- the backbone trial (12.1, `backbone/results.md`): a frozen bidirectional encoder read no better; what holds the
  reader back is the data and how the model is read, not its class;
- the readers of data questions and of facts stand on the same states: one base for every ability.
A stronger model is not ruled out for ever; it is not the next lever, and no work goes to another model until
this one is measured at its limit.

**It reads, it does not generate.** A statement is aligned to the text (§3): tags, frames, pointers — one forward,
no token written. Generating a program token by token is tens of forwards on WebGL2 and is where the small model
is weakest. This stays.

**How it is improved — four levers, in this order, each measured on the SEALED set and on the phone's profile:**
1. THE TASK SAID TWICE (`Reader.see(echo)`): a causal model reads a word before the sentence is whole; at the
   second saying every token has the whole task behind it. No new weights, twice the tokens of one forward.
2. A CORPUS WRITTEN BY A TEACHER: the local large model rewrites every clue many ways, in the four languages,
   names marked; the exact side keeps a rewrite only if it reads back to the same frame. One hand's templates are
   what the reader learnt instead of the meaning (12.1: 29% / 11% on another hand's tasks).
3. THE MODEL ITSELF TRAINED, not only heads over it: a low-rank adapter through the same heads, a separate small
   file beside the unchanged base (`Adapted`).
4. THE SOLVER INSIDE THE READING (§7 step 5): candidates of an unsure decision are kept by "one solution, every
   clue used"; what is still open is asked with its options, and the person's choice is a training example.

**Modules.** The base never changes; everything else is a file loaded beside it:
- a LANGUAGE: its letters (an embedding block, loaded when a token of it appears), its words for the calculus
  (`lang/<code>.json`), its part of the reader (the words met, the bars; an adapter if it needs one);
- an ABILITY: a kind of problem of the statement language (`Problem`: Assign, Derive, Plan, Calculate, Query) with
  its compiler and solver, the heads that read it, the corpus that teaches it. ONE ROUTER reads which problem a
  text is — the choice of the solver — and the text goes to that problem's heads. Logic tasks first (Assign is
  built; Derive, Plan next), then data (Query: the calculus's readers come under the same router), then facts from
  documents and the internet.

**What is not done any more:** other models on the page (the generating ones, the 28-layer author), ladders of
other backbones, a second path to the same answer. They stay under `?models` until the one model covers them.

**The measure of "it works":** on the sealed set and on the collection, by language — shown and right, asked,
shown and wrong (under 1% of the shown); the same numbers from the page on the phone's profile; and the page by
its plain address on a real phone.

### 13.1 What is being built is the technology; okay-desk is its first task (the operator, 2026-11-11)

The aim beyond this page: SMALL, EXTENSIBLE, MODULAR, SPECIALIZED NEUROSYMBOLIC LANGUAGE MODELS, as a technology
to use in other applications. okay-desk is the task it is developed on. So every component is worth doing well and
keeping apart from this page: how such a model is made small (cut, distilled, quantized), how it is taught (a
corpus drawn and written by a teacher, the exact side as the judge), how it is extended (modules beside an
unchanged base), how it is measured (a sealed set, the phone's profile), and how it is CONTROLLED AND REPAIRED —
what it read is always shown, a doubt is said where it is, and a wrong reading is a datum that corrects it.

Later, not now: the answer SAID IN NATURAL LANGUAGE — from the data and the exact result, never from the model's
own belief. How it is made is free (words by rule from the result as `Said` does now, or a small head that orders
given words); what is fixed is that every name and number in it is the engine's.

For the work this means: a result is written down as a component with its interface and its measure (what it
needs, what it gives, what it costs on a phone), not only as a change to the page.

## 14. The task said twice: measured (2026-11-11)

The question (§13, lever 1): the base is causal — a state knows only what came before it. Is part of the failure
on other hands' wording the price of reading a word before its sentence is whole? `Reader.see(echo)` says the task
twice in the prompt and reads the second saying; the same heads, the same corpus (READ_TRAIN=4000, 44 families),
`READ_ECHO=1`. Right = exact, or another statement with the same answer; no gate.

| set (300 texts each; collection 91) | read once | read at the second saying |
|---|---|---|
| Validate en / ru | 98.0% / 99.0% | 100.0% / 99.0% |
| Test en / ru | 98.0% / 98.3% | 100.0% / 99.7% |
| Unseen en / ru | 40.3% / 27.7% | 38.3% / 50.3% |
| Sealed en / ru | 35.0% / 9.0% | 36.0% / 17.4% |
| Collection en / ru | 89.0% / 80.2% | 84.6% / 89.0% |

What it says.
- IN THE WORDINGS TAUGHT the last errors go: 0 wrong of 600 English texts, 4 of 600 Russian (12 and 8 before).
  Those errors were the causal reading's — a tag or a frame decided before the sentence was whole.
- OTHER HANDS' ENGLISH DOES NOT MOVE (Sealed 35.0 → 36.0, Unseen 40.3 → 38.3). The hypothesis is answered for
  English: the failure there is not the direction of reading. It is what the reader was shown (§12.1).
- RUSSIAN MOVES (Unseen 27.7 → 50.3, Sealed 9.0 → 17.4, the collection 80.2 → 89.0): a Russian word's role is in
  its ending and in the words after it. But see the next point before believing the size.
- THE NOISE IS NOT KNOWN. Two runs "read once" that differ by one attribute added to one family gave Unseen ru
  42.0% and 27.7% (§12.1's run, and the run after the houses were given "smokes"). A difference under some
  fifteen points between two single runs on a set of whole families is not yet a finding. Before the next
  comparison: the same configuration several times, heads from different seeds, and the spread reported beside
  every number.
- Under the gate the page would show the same English (63 of 91, none wrong) and less Russian (59 against 66, none
  wrong): its bars are set where 99 of 100 answers of Validate lie, and with almost no error there they sit
  higher.

The cost: twice the tokens of the one forward (reading the 8,000 training texts: 7.7 min against 3.9).

Decided: the second saying stays in the reader as a switch (`Trained.echo`, kept in the reader's file, used by the
page when the file says so) and is NOT the page's reader yet — its gain is where the page already answers. It is
measured again together with lever 2 (a corpus by a teacher), where a reader that no longer errs on what it was
taught is what a clean comparison needs.

### 14.1 The spread of one configuration (2026-11-11)

The same configuration "read once" (READ_TRAIN=4000, 44 families), the heads started and taught in four orders
(`READ_SEED` 1–4; seed 1 is §14's "read once"); right = exact or the same answer, no gate; then §14's single run
at the second saying.

| set | seeds 1 / 2 / 3 / 4 | mean | range | second saying (seed 1) |
|---|---|---|---|---|
| Validate + Test en | 98.0 / 99.0 / 99.0 / 98.9 | 98.7 | 1.0 | 100.0 |
| Validate + Test ru | 98.7 / 98.9 / 98.9 / 98.7 | 98.8 | 0.2 | 99.4 |
| Unseen en | 40.3 / 46.3 / 41.0 / 46.3 | 43.5 | 6.0 | 38.3 |
| Unseen ru | 27.7 / 42.6 / 38.0 / 42.4 | 37.7 | 14.9 | 50.3 |
| Sealed en | 35.0 / 33.7 / 33.3 / 34.7 | 34.2 | 1.7 | 36.0 |
| Sealed ru | 9.0 / 13.3 / 9.0 / 8.7 | 10.0 | 4.6 | 17.4 |
| Collection en (91) | 89.0 / 94.5 / 83.5 / 94.5 | 90.4 | 11.0 | 84.6 |
| Collection ru (91) | 80.2 / 83.5 / 86.8 / 83.5 | 83.5 | 6.6 | 89.0 |

What it says.
- IN THE WORDINGS TAUGHT the spread is a point; out of them it is large — 6 points on Unseen en, 15 on Unseen ru,
  11 on the collection's English (one text there is 1.1 points). Seed 1, the run every earlier comparison of this
  spec used, is the LOWEST of the four on Unseen ru and on the collection's English: some "gains" of §12 may be a
  lucky or an unlucky seed. Out of the corpus, a reader trained on frozen states is a draw, not a function of its
  data — a sign that it hangs on accidents of its examples rather than on what they share.
- The second saying, judged against the spread: IN THE WORDINGS TAUGHT above all four seeds (100.0 and 99.4);
  RUSSIAN out of them above all four seeds (Unseen 50.3 against at most 42.6; Sealed 17.4 against at most 13.3;
  the collection 89.0 against at most 86.8) — a finding, on one seed of its own; ENGLISH out of them inside or
  under the spread (Sealed 36.0 against 33.3–35.0; Unseen 38.3 under 40.3) — no effect.
- Sealed en is steady across seeds (33–35%): that number is the reader's real reach on another hand's English.

Rule from here: a configuration is compared by at least three seeds, its mean and range beside each number, and
a difference counts when it is outside the other configuration's range.

## 15. A corpus written by a teacher (2026-11-11)

The question (§13 lever 2, `technology.md` question 2): other hands' wording is read at 34% (en) and 10% (ru) by
a reader taught on one author's templates (§14.1). Is variety of authors the lever, and can a large local model
supply it with an exact judge?

**The form a teacher gets and gives: marked sentences** (`statement/Marked`). A stated text is taken apart
sentence by sentence; in each, the pieces the statement copies are marked `[key|words]` (`[#|3]` a number), and
the frames are kept over the marks. Law (`MarkedSuite`): taken apart and written again, every stated task of the
corpus and the collection, in both languages, is the same text and the same statement. So a sentence said another
way — another construction, another language — keeps its gold statement as long as it keeps its marks: no reader,
no offset counted.

**The loop** (`bench/StatementTeacher`), for a training task and a language (en, ru, uk, pl):
1. The teacher (Qwen3.6-35B-A3B, local, temperature 0.9) writes the task again, line for line, "as a different
   person would", NOT translating sentence by sentence: other constructions, other verbs, the thing as subject or
   object; every marker kept with its key, its words in the language and in the form the sentence needs. The keys
   it sees are OPAQUE (`t1`, `t2`, …): given `tenant/Egor` it translated the key with the word.
2. Each line is checked by rule: the same keys, once each; one sentence; the marked words in the language (a
   Cyrillic text with a Latin name where the task's Russian has a Cyrillic one, a Polish text with an English
   common noun, is not).
3. THE JUDGE — the same model at temperature 0, given the marks but not the frames — writes every line as
   relations over the keys (`["JustBefore", a, b]`, `["Ask", kind, a]`, …); a line is kept when its reading is the
   gold's, in one direction of the order for the whole task (a reader's convention). Before its verdicts were
   trusted the judge was judged on the ORIGINALS, whose gold is known: 136 of 222 lines at first; 215 of 222 (97%)
   after the setting lines were given unnumbered (numbered, it shifted its answers by one), the question's kind was
   asked as "the kind of the answer", a thing's words or a key's part were mapped back to the key, and equal
   meanings written two ways were compared as one ("a after b" is "b before a"; "the same as #1" is "at 1").
4. Lines not kept are written again, the kept ones given as context, up to three rounds.
5. The task is kept when every line is, and the exact side gives the rewrite the task's result.

The judge's errors lose data (a right rewrite rejected); they do not add wrong examples unless the judge misreads
the rewrite exactly as the gold says while the rewrite says otherwise. A rewrite's other faults are the teacher's
language: some Polish words are wrong ("kreski" for draughts), some Ukrainian cases ("тримає кінь"). They are kept
as they are: people make them too.

Pilot (4 floors tasks × 4 languages): 7 of 16 kept in 4.5 min; the constructions are the teacher's own ("The
floor directly above the position of the cook is inhabited by the person consuming water", «Ten, kto bawi się w
kreski, ma wyżej na kondygnacji kogoś spożywającego kakao»). Running: 200 training tasks × 4 languages, then 60
test tasks × 4 languages as the held-out measure of every language the teacher wrote (`READ_TEACHER`,
`READ_TEACHER_TEST` in `StatementRead`). Measured over three seeds against §14.1's spread.

## 16. The exact side inside the reading (2026-11-11)

§7 step 5, §8 point 1: a decision the heads are unsure of may be closed by the exact side. Built in
`Reader.read`: when the only decisions under their bars are FRAMES of at most three sentences, every reading of
them among the heads' three most likely frames is assembled and solved; when all the readings that give ONE
answer give the same answer — the others refused, or none, or several — that reading is the answer, and the page
says which sentences the solver decided ("The solver decided how to read «12. The person who smokes Lucky Strike
drinks orange juice.»: of the readings the model hesitated between, only this one gives the puzzle a single
answer."). A task whose true answer is none or several is not decided this way: it stays asked.

In the browser (the 80 tasks of `scripts/statement.scala`, WebGPU, the reader of §12.3): 75 right, 5 asked, 0
wrong — against 59 / 20 / 1 before (§12.2). On the phone's profile on the live page: 24 of 24 right. The English
Einstein tasks, all asked before at that one sentence, are answered. The bench counts what the solver decided and
how much of it is right (`the solver decided N: exact …, silent …`), so the rule is measured on held-out tasks in
the runs of §15.

The risk, to be measured there: a reading that is wrong but happens to give one answer, where the right one gives
none or several. For a task whose gold has one answer that cannot happen unless the right reading is not among the
three candidates; the measure is the "silent" count among the decided.

## 17. ZebraLogic: an outside measure, and positions said in words (2026-11-11, in the working copy `next`)

**Why.** ZebraLogic (Lin et al., 2025) is 1,000 public grid puzzles of other authors, 2×2 to 6×6, with published
numbers to set ours against: NeuralExactCover (arXiv 2611.03641) — an off-the-shelf model writing one tool call per
clue, an exact-cover solver checking it and sending "none" / "several" back — solves 2.2% with Qwen3-0.6B, 28.1%
with 1.7B, 84.9% with 4B.

**The gold.** The public set has its solutions blanked. `scripts/zebralogic.scala` reads the benchmark's own
templates exactly (for measuring only — it is never a reader of the page), solves every puzzle by a search over
domains and keeps a puzzle only when it has exactly one solution: a misread clue nearly always leaves none or
several. 971 of 1,000 kept; 18 gave none or several, 11 have a clue the templates do not read.

**What the statement language lacked.** Of the 10,388 clues: 2,501 place a thing by an ORDINAL ("in the third
house"), 1,284 say where it is NOT, 521 how many houses lie BETWEEN two. Added:
- `Con.NotAt` (thing, number) and `Con.Between` (thing, thing, number) — and `Constraint.NotAt`,
  `Constraint.Apart` in the grid solver, propagated like the others;
- `Numbers`: a number said in words, in the four languages (English whole; Russian, Ukrainian, Polish by a stem
  and a short ending — so that "tennis" is not ten, nor «Шестаков» six);
- the assembler gives a frame's number role the piece that is a number wherever the sentence says it ("there is
  ONE house between A and B"), the text's order otherwise (a thing may be named by digits);
- the corpus's houses say positions by ordinals, "does not live in the second house", "have one house between
  them" / «живут через один дом друг от друга»; the collection's houses are unchanged;
- `Assign.compile(whole = true)`: a grid with no question, solved whole.
All the laws hold (core 243, ui 48).

**Also built, to be measured with it:** the second saying in the form of Springer et al. — the model asked to
repeat the task (`READ_ECHO=2`); a gate by conformal risk control (`READ_GATE=conformal`, `READ_ALPHA`): one
threshold per language on the least margin in units of its kind's median, the smallest whose shown answers of
Validate keep (wrong + 1)/(shown + 1) under α — a bound for texts like Validate's, none beyond them.

**Running** (`next-chain.sh`, after the night's chain): the reader with the teacher's corpus, read once and with
"repeat it", each saved and run on ZebraLogic (`bench/ZebraRead`: the whole grid against the gold; and the gate).

## 18. The night of 2026-11-11/12: the teacher's corpus measured, and the page's new reader

**The teacher's corpus helps, beyond the spread** (READ_TRAIN=4000 + the teacher's rewrites; three seeds against
§14.1's four): Sealed en 40–50% (mean 43.2; untaught 33–35), Sealed ru 13–16% (14.7; untaught 9–13), Unseen ru
53–60% (untaught 28–43). On the teacher's held-out rewrites the untaught reader reads ru, uk, pl at 0%; the taught
at ru 29–43%, uk 6–13%.

**What cut the teacher's worth in half, and its repair.** 56% of the rewrites were out of the assembler's reach: a
teacher turns a clue around ("B has A just before it") and the assembler gives roles in the text's order. Loaded,
a rewrite's frames are now turned with it (JustBefore of A, B as JustAfter of B, A; symmetric frames reordered),
and a frame's pieces are taken in the text's order, its number wherever it is (§17). In reach: 349 of 359 training
rewrites (was 159), 112 of 116 test (was 44). And the bench read the sets after Validate WITHOUT the gate's bars,
so the solver inside the reading (§16) never ran there; fixed.

**The two final readers** (one seed each; positions in words, the teacher's corpus in reach, `next`):

| set (right, no gate) | read once | "repeat it" (echo 2) |
|---|---|---|
| Validate + Test en / ru | 93.4 / 96.0 | 98.2 / 98.9 |
| Unseen en / ru | 47.6 / 40.7 | 62.7 / 57.0 |
| Sealed en / ru | 47.4 / 17.7 | 49.6 / 21.3 |
| teacher's test en / ru / uk / pl | 16.7 / 28.0 / 12.5 / 0.0 | 33.3 / 28.0 / 9.4 / 5.3 |
| collection en / ru | 91.2 / 76.9 | 87.9 / 83.5 |
| collection under the gate, saved file | 69 / 57 shown, 1 + 1 wrong | 60 / 62 shown, 0 wrong |

"Repeat it" is the better reader everywhere it matters — and the only one with no wrong answer shown on the
collection; read once, the in-corpus readings fell to 93–96% (the new clue kinds and the teacher's sentences
without the second saying). Single seeds: to be confirmed over three.

**ZebraLogic: 0 of 971.** A statement came out for 636 puzzles and none compiled: "the attributes have different
numbers of things" — the benchmark's lists are read otherwise than its clues (to be looked into: its descriptor
phrases "the person who loves fantasy books" against the listed "fantasy"; values of several words). The honest
outside number today is zero; the reason is in reach of the next step.

**On the page** (build 8c01ac871bfd): the "repeat it" reader. In the browser (80 tasks, WebGPU): 69 right, 11
asked, 0 wrong (the previous reader: 75 / 5 / 0 — the English Einstein tasks are asked again, at "pieces no frame
takes"); live as a phone (WebGL2, CPU head): 29 right, 3 asked, 0 wrong; 0.5–0.8 s a task. A task TYPED into the
chat now goes to the reader first; two own tasks, English and Russian, with an ordinal position, answered right
with their reading.

### 18.1 ZebraLogic: the first outside number (2026-11-12 morning)

The zero of §18 had two causes, both general, neither the benchmark's own:
- the reader gave relations to the setting's sentences with nothing marked in them ("Each house has a unique
  attribute for each of the following characteristics:"): a list of nothing, "? = ? + 1". A sentence with no piece
  now states no frame that takes pieces (`Assemble`). Leaving a clue out never makes a wrong answer — the right
  arrangement still fits every clue kept; at worst no single one does, and the task is asked;
- a frame with too few pieces crashed the compiler; it is now a refusal (`Assign.compile`).

The same reader (`zl-e2`, never shown a ZebraLogic text) on the 971 puzzles with a gold: **147 grids right
(15.1%)**, 20 grids wrong, the rest no statement (335), lists of different lengths (174 — values of several words,
"google pixel 6", read as several things), several (69), none (35), a frame short of pieces (~80). By size: 2×2
23/40 … 4×4 5/39 … 6×6 0/35. For scale (another method, the private copy of the set): an off-the-shelf generating
Qwen3-0.6B with a solver's feedback solves 2.2%, Qwen3-1.7B 28.1% (NeuralExactCover). Under the gate nothing is
shown: the benchmark's words were never met — as it should be while 20 of 167 single grids are wrong.

Next for it, in order of the counts: values of several words in a list; the pieces a frame takes; then the gate
for words met only in the benchmark's style.

### 18.2 The teacher on the new clues; whole words; the page's reader (2026-11-12 afternoon)

The teacher rewrote the houses tasks that say positions in words, "not in", "N between": 60 kept of 308 (long
tasks: one line the judge misreads loses the whole). With them, 437 rewrites taught; the "repeat it" reader (seed 1;
seeds 2, 3 running) against §18's:

| set | §18 (echo 2) | now |
|---|---|---|
| Validate + Test en / ru | 98.2 / 98.9 | 99.7 / 98.7 |
| Unseen en / ru | 62.7 / 57.0 | 81.3 / 62.0 |
| Sealed en / ru | 49.6 / 21.3 | 54.0 / 13.7 |
| teacher's test en / ru / uk / pl | 33.3 / 28.0 / 9.4 / 5.3 | 38.9 / 44.0 / 15.7 / 5.3 |
| collection en / ru (no gate) | 87.9 / 83.5 | 95.6 / 92.3 |
| collection under the gate | 60 / 62, 0 wrong | 70 / 63, 0 wrong |
| ZebraLogic, grids right | 147 (15.1%) | 177 (18.2%) |

Sealed ru fell (21.3 → 13.7): one seed each; §14.1's spread there is 4.6 points — the confirming seeds decide.

A list's item is what lies between its commas (pieces with only spaces between them are one: "google pixel 6"); a
piece is whole words ("Norweg" tagged, "ian" not, is "Norwegian"), and a token inside a word another began leaves
the gate — its tag changes no piece. A typed English task the reader had asked about at "Norweg|ian" is answered.

On the page (build 356ba7f8e77a): in the browser 77 right, 3 asked, 0 wrong of 80 (best so far); live as a phone
32 of 32 right; two typed tasks, English and Russian, answered right.

Confirmed over three seeds (the page's reader is seed 1): Sealed en 54.0 / 53.3 / 49.3 (mean 52.2; untaught 34.2,
the first teacher's corpus 43.2) — the gain holds; Sealed ru 13.7 / 11.0 / 14.0 (mean 12.9) — §18's 21.3 was one
lucky seed, Russian of another hand does not move with this corpus; Unseen en 81.3 / 73.6 / 77.6; the collection
en 95.6 / 94.5 / 78.0 — a seed decides much of it, and the page has the best of three. Next for Russian: more of
the teacher's Russian, and the adapter (lever 3), since heads alone stop here.

### 18.3 The phone: a second task reloaded the tab (2026-11-12)

On a real iPhone the first typed task was answered and the next reloaded the page. The forward keeps every layer's
keys and values of the prompt for reuse (`OnQwen`'s prefix cache) — for a long task said twice some 100 MB of
textures; the next task frees them and allocates its own, and a phone's driver frees late: both live at once, and
the tab is killed. Nothing reuses a statement reading's prefix, so `Engine.state` lets the cache go before and after
each reading. Measured on the live page as a phone (WebGL2 textures): 231 MB at the peak of a reading, 127 MB
between tasks, the same after four tasks in a row (before, the cache stayed and the next reading stacked on it).
"Clear chat" never touched it: the cache is the model's, not the conversation's.

Not enough on the iPhone: the second task still reloaded the tab. The peak itself is now lower: a statement reading
runs the forward keeping nothing (`OnQwen.statesOnce`: no prefix kept, each layer's keys and values let go as the
next layer is computed) — 157 MB at the peak instead of 231, 127 MB between tasks (live page, phone profile, three
tasks in a row). What a real iPhone does with textures freed late is not seen in Chromium: the phone itself is the
test.

Still not enough on the iPhone. Counting WebGL textures made and deleted (Chromium, phone profile) showed the churn:
a task of another length made and deleted some 180 textures. `OnWebGl2`'s pool is by exact height, and a task of
another length asks for other heights — the last task's textures lay in the pool unused while every op of the new
one made and deleted its own. Two changes in okay-llm's `OnWebGl2`: heights in steps (1, 2, 3, 4, 6, 8, 12, …; a
texture at most a third taller than needed, the shaders stop at `total`), so tasks of near lengths share textures;
and a full pool lets go of other heights first, so it follows the work of now. With the phone's pool at 64 MB (24
before): the second task made 16 textures and deleted 9 (was ~180 each), 158–160 MB between tasks, 191 MB at the
peak (three tasks in a row). The trade: some 40 MB more held, nearly nothing freed late. Build 55c047bc2994.

### 18.4 A question after a task (2026-11-12)

On the iPhone, after the zebra was answered, "Who drinks milk?" went the chat's other ways ("needs external
evidence"): a one-sentence text was never a task. It asks about the same things: the page keeps the last task the
statement reader answered (`State.told`), and a short question after it is read with that task's conditions — the
task's own text, its questions cut out, this question after them (`App.followUp`). The same reader, the same exact
solver; a reading not sure of itself goes the chat's other ways, as before. Measured (phone profile): milk → the
Englishman, water → the Norwegian, Kools → the Norwegian, each a whole reading of the task again (no state of the
last reading reused — a later step could keep the statement and read the question alone).
