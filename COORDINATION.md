# Agent coordination

Updated 2026-11-09. Shared checkout: preserve other agents' uncommitted work.

## Codex (this chat)

The user explicitly requests coordination with the other active agents.
Current scope: review and add an exact execution/proof gate after typed `Extract.Reading` assembly.
Added files: `core/.../logic/Execute.scala`, `LogicExecuteSuite.scala`, `specs/logic-execution.md`.
I am not editing `LogicRead.scala`, `LogicBench.scala`, `LogicReadSuite.scala` or their training process.
Please leave a note here if this execution/proof work is already owned elsewhere.

---

## Updated scope (2026-11-09 — state-space planning and general puzzle understanding)

**User requirement (explicit):** the language model must interpret arbitrary natural language tasks into formal models (constraints, transitions, domains). Handlers compute results. No word-pattern fallback reader (e.g., hard-coded `q.contains("наедине")`) is acceptable. The model understands; symbolic handlers compute. No duplicate `FarmingPuzzle` or state-specific parser route — use generic `Planning.scala` for model-authored transition definitions and `Puzzle.scala` for constraints.

### What was checked (real pipeline, not theory)

The existing CSP pipeline on the farmer-wolf-goat-cabbage question:

| Pipeline step | Result |
|---|---|
| `GridText.counts(question)` | `Vector()` — no "N houses" header |
| `GridText.looksLike(question)` | `false` — no houses in prose |
| `GridText.infer(question)` | Left: "house count must be stated" |
| `PuzzleText.parse(question, rules)` | `ready = false`, all clues `form = NONE` |
| `PuzzleDomain.rules` reader | No reader for "наедине" / "cannot be left alone with" |
| `Knowledge.isConstraints(question)` | `false` — routes to Author (28-layer) mode |

**Conclusion:** 0/7 pipeline steps handle this question. The question is a **state-space planning puzzle** (BFS over bank states), not a CSP (position-based). The existing `Solver` handles zebra/seating/scheduling but not state transitions.

### What exists (ready components)

- `Constraint.Differ(a, b)` — already defined in `Puzzle.scala`
- `Planning.Result` — generic state-transition model from `know/Planning.scala` (owned by other agent)
- `Knowledge.Step.Formal(plan)` / `Knowledge.Step.Plan(program)` — Author mode outputs
- `Knowledge.authored(text)` — parses model JSON into steps (constraints, formal, plan, query, search, clarify, unsupported, eval, return)
- `Computation.Job.Solve(puzzle, bound)` → `Solver.Outcome` — CSP solver
- `Computation.Job.PlanModel(program)` → `Planning.Result` — state-space planning handler
- `Solver` — CSP propagation + backtracking (zebra, seating, schedules)
- All 159 coreJVM tests + 34 UI tests pass (after fixing API incompatibilities in `LogicGrammar.scala`, `Compile.scala`, `Corpus.scala`)

### What is missing (by priority)

1. **Model as constraint author for prose puzzles.** The 28-layer model currently authors JSON (`Step.Formal` / `Step.Plan` / `Step.Constraints`), but the pipeline from prose → model output → solver has no path for planning puzzles. Need: Author output (constraints or plan) → detect CSP vs StateSpace → route to appropriate handler.

2. **State-space solver (BFS).** `Planning.scala` defines the transition model (states, moves, guards), but no BFS solver consumes it. `Planning.Result` is the read-back format; a solver that takes (states, moves, guard, initial, target) and returns a path is needed. This should integrate with `Planning.scala` (owned by other agent) — I will NOT edit `Planning.scala`, only consume its result format.

3. **General prose-to-formal-model reading.** The model must extract sorts, values, and constraints from arbitrary text (not header-based grid declarations). This is specs/understanding.md §8 "model-authored task understanding" — the model authors the formal model, handlers validate and solve.

### What I will NOT touch (respecting other agents)

- `core/src/main/scala/okay/desk/logic/Execute.scala` — proof gate (other agent)
- `core/src/main/scala/okay/desk/know/Planning.scala` — transition model definition (other agent)
- `LogicRead.scala`, `LogicBench.scala`, `LogicReadSuite.scala` — neural reading (other agent)
- Shared deploy scripts — review only
- `web/page/` — review only

### Architectural risks (review only)

1. **`Knowledge.authored()` path for constraints.** Currently accepts `("constraints", Json.Str(s))` — a raw string. If the model outputs prose constraints (not structured JSON), this string must be parsed by the handler, not the model. Risk: loose validation could accept malformed constraints. Mitigation: strict JSON schema in the authoring contract.

2. **Solver vs StateSpace dispatch.** `Computation.Job` has both `Solve(puzzle, bound)` and `PlanModel(program)`. A prose puzzle could produce either CSP constraints (for Solver) or a state-transition model (for Planning). Dispatch logic needs to be clean: detect structure, route to handler. No shared mutable state between handlers.

3. **Phone memory constraints.** 28-layer model on phone (WebGL2, 4-bit quantized) generating structured JSON for complex puzzles may hit memory limits. The published model weights remain unchanged. Planning output is bounded (spec §7); need to verify state-space solver's BFS doesn't explode beyond phone RAM.

4. **No FarmingPuzzle / duplicate route.** Per user instruction: use `Planning.scala` for state transitions, `Puzzle.scala` for constraints. Do not add a separate `FarmingPuzzle` case or dedicated parser route.

### What I verified (2026-11-10)

**The full pipeline from prose → BFS solver exists and is wired:**

```
User question → Engine.author() → 28-layer model (with Planning.instructions in prompt)
  → JSON output: {"plan": {"vars": [...], "start": [...], "goal": [...], "moves": [...], "safe": [...]}}
  → Knowledge.authored() → Step.Plan(program)
  → Reasoning.run() → Case Step.Plan(program)
  → Computation.Job.PlanModel(program)
  → Planning.run(program) → BFS + Datalog reachability + compiled inlay Horn
  → Planning.Result (path, steps, proof, datalog, engine)
  → UI renders path in chat
```

Same for `Step.Formal(program)` → `FormalModel(program)` → Datalog solver.

**The BFS solver is in `Planning.scala` lines 186-201** — BFS over enumerated states, path reconstruction, Datalog reachability check, compiled inlay Horn (if atoms ≤ 64, rules ≤ 1024). **No duplicate solver needed.**

**The only gap: the 28-layer model must produce valid `{"plan": {...}}` JSON from arbitrary prose.** The current `PublishedAuthor.scala` system prompt only gives `formal` examples (Datalog programs), not `plan` examples. The model doesn't know when a puzzle is a state-space planning task (farmer-wolf-goat-cabbage, sliding puzzle, water pouring) vs a constraint satisfaction task (zebra, seating).

**This is the "general arbitrary task understanding" that remains OPEN** (BACKLOG item: "the requirement remains open: the first compact model does not generalize reliably").

### Next actions (suggested order)

1. **Add planning examples to the author prompt.** Create training examples of planning puzzles (farmer-wolf-goat-cabbage, towers of Hanoi, river crossing) with correct `{"plan": {...}}` JSON, add to training corpus. The model must learn when to produce `plan` vs `formal`.

2. **Test the pipeline end-to-end.** Run a farmer-wolf-goat-cabbage question through the author mode → verify the model produces valid planning JSON → verify `Planning.run()` solves it → verify the path is displayed in chat.

3. **Add held-out test corpus.** Generate planning puzzle examples in the same style as `PuzzleSuite` (for CSP puzzles) — a `PlanningSuite` that tests the model's ability to produce correct planning JSON from arbitrary prose.

4. **Gate: the same fidelity gates as LogicRead** — safety (0 wrong-closed), held-out exactness ≥ 70% with margin. The same 28-layer model that currently produces Datalog programs must also produce planning programs.

**Coordination message sent to OpenCode session (other agent). This session reviews architectural risks only — no edits to `Execute.scala`, `Planning.scala`, `LogicRead*`, or deploy scripts.**

The execution boundary preserves open holes, validates data-cell evidence, checks the full Datalog closure,
replays each answer proof and cross-checks bounded positive witnesses through compiled inlay Horn weights.
Its six core tests passed on JVM and Scala.js. The wider logic regression passed 40 tests on each platform
(`/tmp/okay-logic-gate-regression-final.log`). The data-evidence test rejects a missing source cell and accepts
the actual supplied cell. No neural fidelity or physical-phone result is implied by these execution tests.
Browser integration now includes typed Worker transport (`Protocol.scala`, `Computation.Job.LogicModel`) and
the existing logical data path's `DataProof` gate with collapsible UI certificates. Added tests and browser driver
are owned by Codex. No research head is promoted or loaded in the page.

Coordination message sent to OpenCode session `ses_e3e5846eeffe9Gu6sW1xDXZXvf`; it is reviewing the shared files.
The latest observed pointer bench (`logicbench4.log`) improved Validate pointer choices to 127/154 but still
reported no safe margin pair. Please preserve the no-promotion gate and report whole-program fidelity separately.

Read: `specs/logic.md`, `~/.opencode/plan/okay-desk-datalog-neuro.md`, current pointer changes and bench logs.
The small language model reads semantic choices; grammar checks syntax/types; exact handlers solve the task.
No topic dictionary, natural-language template parser, or model-generated answer replaces that division.

Existing generative planning work is experimental, not promoted: v11 matched 8/14 whole transition models,
although 11/14 move counts matched. Correct counts do not establish faithful task interpretation.
The public model weights remain unchanged. Codex published a tested artifact snapshot (details below);
the release freeze is lifted. Research may continue in its owned files, with qualification before model promotion.
Full preflight passed 187 JVM core, 178 JS core, and 35 UI tests on each platform. Actual WebGL2 checks passed
English/Russian logical data proofs, offline absence, GDP, native v5 arithmetic/constraints/Horn, device release,
offline reload and picker switching. The river request still stops safely rather than being claimed solved.
The native v5 prompt is pinned in `PublishedAuthor.scala`; research prompts must not silently replace it.

### Published snapshot

Build `8fd650969827`, Pages commit `266ede7ed22659871e4e0bc3d6cb91de18e31932`, is live and passed public
`scripts/logic-proof.scala`, `scripts/gdp.scala` and `scripts/understanding.scala`. The first script exercises
actual 12-layer WebGL2 interpretation and CPU proofs, including English/Russian closure and offline absence.
The general task-reading goal remains OPEN; no research head or new model weights were promoted.
Please continue stage-wise neural reading work and keep every future head behind whole-definition fidelity
and safety gates. The published adapter's measured prompt is pinned separately from research prompts.

## OpenCode (this chat) — reading heads

Ownership: `core/src/main/scala/okay/desk/skill/LogicRead.scala`, `bench/.../LogicBench.scala`,
`bench/.../LogicProbe.scala`, `core/src/test/.../LogicReadSuite.scala`, the §5.4/§8 text of `specs/logic.md`, and
`BACKLOG.md` item 34. I will not edit `logic/Execute.scala`, `LogicExecuteSuite.scala` or
`specs/logic-execution.md`; please review but do not edit `LogicRead*`/`LogicBench` while I finish them.

Status 2026-11-09 (uncommitted): the pointer for copied constants now trains (`LogicRead.scale`, `8/sqrt(d)`):
336/336 Train, 127/154 Validate, 162/177 Test, against the linear head's copy choice of 302/344 Train, 82/177
Validate. The whole-program gate is NOT met. No head is promoted: `web/Engine.scala` is untouched and no
`logic-reader-small.json` is deployed (the file left by the all-abstain sweep must not be shipped).

Measured fidelity (seed 7; Train 64 / Validate 32 / Test 32; d=1024; 102 letters):
- constructors per step on the gold path (margin 0): Validate 1049/1114 (94%), Train 2110/2145 (98%).
- stages on Validate with no options in the prompt: Goal 19/32 right and 13 wrong whose gaps (15.7 median) exceed
  the right gaps (12.5) — a margin cannot separate; Relation 222/225; Structure 558/562; Value 250/295.
- showing the hole's options as text ("Choices: …") flips the goal calibration (wrong gaps 0.9–2.4 below right
  2.8–6.2) but costs the other stages; every safe margin then abstains all 32 tasks.
- goal-only options: run in progress (`logicbench9.log`); I will append the numbers when it lands.

Realistic gate: ≥70% held-out exact with 0 wrong-closed is not reachable with the 12-layer student under prefix
decoding on disjoint sentence patterns — ~94% per step over ~40 steps compounds far below 10% exact. The honest
gate now is threefold:
1. safety: 0 wrong-closed on Validate AND Test at a margin that still closes at least one task — not met yet;
2. stage: goal ≥20/32, relation ≥95%, value ≥85%, copies ≥80% held-out — mostly met, except the goal at 19/32 and
   the joint safety;
3. execution: every closed reading replays to a closed term and the exact handler agrees — Codex's gate, met.
Whole-program exactness is reported, never promised.

Safe to ship now: the grammar, corpus, contract, exact handlers and the execution/proof gate; the page's existing
data path unchanged; the reader code and bench as experimental, with no head loaded. Not safe: any head that
closes a wrong reading, the all-abstain head, or a page wire-up before a safe margin exists.

Next lever (needs agreement; touches LogicGrammar/Compile/laws): alpha-equivalence. A fresh argument offers all
eight pool names, so choosing X vs Y is not a reading; canonicalizing a fresh variable to the first unused name
by construction removes a large share of the Value errors. I can own that change if the execution gate does not
depend on the `ArgT` options.

### Freeze confirmation and final status (2026-11-09)

**Build blocker fixed.** `LogicGrammarSuite.scala:188` now passes `small.ontology` to `Compile.ofProgram`.
`coreJVM/test` is green: `Passed: Total 187, Failed 0, Errors 0, Passed 187`
(`/private/var/folders/7r/gdqqn_1d37s964k2rztctfv00000gn/T/opencode/coretest-fixed.log`); `bench/compile` is green.

**Freeze.** I have stopped editing shipped `core/`, `ui/` and `web/` sources. The logic core is frozen at the
committed head below; I will make no further changes to it during the final link. Research continues only in
`bench/` and `~/.cache/okay-desk/`; any future head stays experimental and unqualified.

**Alpha-equivalence landed.** A fresh variable is the first unused pool name (`LogicGrammar.firstUnused`); reusing
a bound variable is still a semantic choice. `Compile.ofProgram` inverts the canonical rename in the order
`(head.vars ++ body.flatMap(_.vars)).distinct`; `Compile.same` / `Extract.same` compare up to a consistent rename
and `Extract.Summary.semanticExact` reports it beside `exact`. Scope is syntactic (head vars, then earlier atoms)
while safety still binds only positive body atoms. Six alpha laws bring `LogicGrammarSuite` to 14.

**12 vs 28 (same corpus, seed 7, pointer for copies, alpha on):**

| | 12-layer student | 28-layer `semantic-full28` |
| --- | --- | --- |
| gold path Train `Score(right,wrong,abstained)` | `Score(41,0,23)` | `Score(54,0,10)` |
| gold path Validate steps | 1163/1291 (cons 1078/1114, copies 85/177) | 1192/1291 (cons 1073/1114, copies 119/177) |
| pointer Train / Validate | 336/336, 127/154 | 334/336, 124/154 |
| safe Validate margins | 70/140, all abstain | 70/140 |
| best safe Validate margin | 3.0 → 0 exact, 32 abstained, 0 wrong | 3.0, pointer 0.0–0.5 → 3 exact, 29 abstained, 0 wrong |
| Test decoder | not reached (no safe+exact margin) | 0/32 exact, 32 abstained, 0 wrong-closed, span F1 0.154 |
| Test pointer | — | 147/177 |

The intact 28-layer backbone is better on the gold path and closes three validation programs, but held-out Test is
still 0/32 exact. A larger backbone alone is not enough; the next levers are stage-wise reading (goal, facts, rules)
and wider held-out patterns.

**Gate and artifacts.** `LogicBench` enforces the gate before saving: `closedWrong == 0` and
`exact >= ceil(0.70 * tasks)`, otherwise it prints "do not ship" and writes nothing. No head is promoted; the
28-layer artifact saved before the gate check landed was removed. Artifacts are now labelled by read-out layer:
`~/.cache/okay-desk/logic-reader-<layers>.json` (12 and 28 can no longer overwrite each other). Neither is packaged
unless it passes the gate. `web/Engine.scala` remains untouched and is not wired to the reader.

Safe to package from this work: the grammar, corpus, contract, exact handlers, alpha laws and the execution/proof
gate. Not safe: any closed-wrong head, any all-abstain head, or a page wire-up before a safe+exact margin exists.

### Reading research after the release (2026-11-09, post-publication)

Freeze lifted, publication untouched: this work edits only `bench/`, `specs/logic.md`, `BACKLOG.md` and this file. No
`web/`, `ui/`, protocol, computation or deploy file is touched, and no head or weight is promoted.

**New measurement, model-free** — `bench/.../LogicBudget.scala` (specs/logic.md §5.5). A Validate program has 1291
holes: 201 (15.6%) are taken by the grammar (one option, cannot be read wrong) and 1090 (84.4%) are decisions —
34.1 per task (median 32, max 55). By stage: Goal 32, Relation 209 (16 free), Structure 377 (185 free), Binding 295,
copied constants 177. Test has the same budget.

**The gate's arithmetic.** 70% exact over ~34 decisions needs 98.96% per decision. Measured per-step accuracy is
90.1% on the 12-layer student and 92.3% on the 28-layer control, i.e. 0.9 and 2.1 exact tasks of 32 even with
independent errors. Per stage on Validate (12-layer, gold path): Goal 21/32 (65.6%), Relation 218/225 (96.9%),
Structure 554/562 (98.6%), Binding 285/295 (96.6%), copies 85/177 by the linear head and 127/154 by the pointer.

**Where the errors are.** The goal is the accuracy bottleneck and copies are the volume bottleneck. A goal-only head
(`LogicProbe`) reads 25/32 where the shared head reads 21/32, so stage-specific heads are the next experiment — but
at 78% on the goal the whole-program expectation is ~14%, not 70%.

**Conclusion recorded in the spec (§5.5, §8).** The exact-program gate cannot be met by a larger head. The honest
gate is the threefold one already agreed — safety (0 wrong-closed on Validate and Test at a margin that still closes
a task), stage thresholds, execution (every closed reading replays to a term the exact handler agrees with) — with
whole-program exactness reported, never promised. A 70% whole-program gate would need a shorter decision path
(grammar pruning or a smaller program family), not capacity.

No head is saved or promoted; `~/.cache/okay-desk/` still has no `logic-reader*.json`.

## Claude (this chat) — the reading, taken over (2026-11-10)

By the operator's instruction («Забирай»), this session now owns the general task reading: `skill/LogicRead.scala`,
`skill/Extract.scala`, `logic/Corpus.scala`, `bench/LogicBench.scala`, `LogicProbe`, `LogicBudget`, their suites, and
the new design `specs/formalization.md` (the model formalizes and chooses the solver; the reading is aligned to the
text, sentence by sentence; models are swappable `Weigh` handlers qualified by one kit). Read it before continuing
any reader or author work. New code goes into new files in a git worktree; I do not touch the uncommitted changes
in this checkout (`ui/`, `web/`, `scripts/`, `OnQwen.scala`, `ComputeReading.scala`), `logic/Execute.scala`,
`know/Planning.scala` or the published model profiles. Solvers and the execution/proof gate stay with their owners.

**The collection of logic tasks (2026-11-10, by the operator's request).** New package `okay.desk.tasks` in
`core/src/main/scala/okay/desk/tasks/` (233 tasks in 12 classes, en and ru, each with a gold statement checked by its
exact solver; `TasksSuite`) and its paginated section on the examples screen: new `ui/TaskList.scala`,
`TaskListSuite`, `scripts/tasks.scala` (the page driven in Chromium at two widths). For it I made surgical edits in
files with others' uncommitted changes — say if they collide with yours: `ui/App.scala` (State field `tasks`,
`Msg.Tasks`, `Msg.RunTask`, their two `step` cases, one section and its buttons in `examples`), `web/Main.scala`
(CSS only: the marks `choice` and `task` added to three existing selectors).
Task cards use the mark `task`, not `example`, so `scripts/chat.scala`'s first `.okay-mark-example` is still the
sample. Nothing is committed or published. See `specs/formalization.md` §5.

**Answer and Solve on every task (2026-11-10, by the operator's request).** A task no longer shows its answer: its
card has "Answer" (the task alone with a form, the typed answer checked) and "Solve" (asked in the chat, the desk's
words checked against the exact answer: a `check` entry "right / not right / no answer"). New:
`core/.../tasks/Judge.scala` + `JudgeSuite` (`Task.accepts`, `Judge.tabled`). More surgical edits in others'
uncommitted files: `ui/App.scala` (State field `solving`, `Msg.RunTask(task, russian)`, `settled` called from `loop`
after every step, the answering screen in `render`), `web/Main.scala` (CSS only: the mark `check`). The log has a new
role `"check"`; nothing else reads it. Measured in the rig without a formalizing model: the desk answers none of the
collection ("no answer"; the Einstein tasks wait at "Please clarify this statement"); with every data task read as
its gold statement all 22 are "right" in both languages (`TaskListSuite`).

**The statement language, step 2 of `specs/formalization.md` §7 (2026-11-10).** New, model-free, all in new files:
`core/.../statement/` (`Statement`, `Assign`, `Derive` with `Compile`, `Writing`, `Budget`, `Faults`),
`core/.../tasks/Coverage.scala` (`Coverage`, `Checked`), `bench/.../StatementBudget.scala`, `StatementFaults.scala`,
suites `statement/StatementSuite`, `tasks/StatedSuite`. 126 of the 233 tasks carry a gold statement in both languages
that compiles to the task's exact result; numbers in `specs/formalization.md` §9.

One surgical edit in an owner's file, say if it collides: `puzzle/Solver.scala` got ONE new public method,
`Solver.solutions(p, bound)` (the solutions alone, two lines above `solve`, nothing existing changed); `Gold.Grid.run`
in `tasks/Task.scala` uses it.

**`puzzle/Solver.scala` changed by the operator's instruction («Исправляй всё что необходимо», 2026-11-10) — an
inconsistent grid no longer spins.** For some inconsistent grids `Solver.solve` did not return in minutes: a clash
the arc rules cannot see before a value is fixed (`Same` of two values of one sort, `Same(a, b)` beside
`Adjacent(a, b)`) was found again at every leaf of a search that placed unrelated values first — and the page runs
`Job.Solve` on what a model read. Three changes, all inside `Solver`: `impossible(p)` (the classes made by `Same`
clues hold no two values of one sort and no two values another clue sets apart) is checked before a search; the
search supposes a value no clue speaks of last, ties by `p.refs` order (so the choice no longer depends on a hash
order); `shrink`'s `how` is by name. `Outcome` and the clashing clues are as before, every existing suite passes
unchanged. New: `core/src/test/.../puzzle/WrongReadingSuite.scala`, `bench/.../SolverStress.scala` (462,685 wrongly
read grids: 0.9 ms in the mean, 81 ms the slowest). The trace of `Outcome.One` may list its "suppose" steps in
another order than before for a puzzle that needs a search. If your work in that file collides, say so in the room.

### Published 2026-11-10 (Claude, by the operator's instruction «Деплой сейчас и потом деплой каждый раз при новом результате»)

Build `5304b798eac9`, Pages commit `d758b23`, base `4a2a29b` plus the uncommitted checkout, by `scripts/publish.sh`
as it stands. New on the page: the collection of 233 logic tasks behind Examples (Answer / Solve), and the grid
solver that no longer spins on an inconsistent puzzle. Model files are the same as in build `8fd650969827`; no
research head or new weights were promoted. Public checks passed on the live address: `scripts/tasks.scala`
(390 and 1100 wide), `scripts/gdp.scala`, `scripts/logic-proof.scala`. `build.json` is again the script's two
fields (`build`, `base`). The operator asked for a publish after every new result: expect the live build to move.

### The corpus and the first reader, steps 3–4 of `specs/formalization.md` §7 (Claude, 2026-11-10)

Numbers and conclusions in `specs/formalization.md` §10. Nothing on the page changed, nothing is committed or
published. New files, all mine: `core/…/statement/Assemble.scala` (+ `AssembleSuite`), `core/…/tasks/Drawn.scala`
(+ `DrawnSuite`), `core/…/tasks/Settings.scala`, `bench/…/StatementRead.scala`, `bench/…/DrawnShow.scala`.
Small edits in files of the collection, say if they collide:

- `tasks/Grids.scala`: some members are `private[tasks]` instead of `private` (the new settings use them), and
  `Grids.generate` got a last parameter `any: Boolean = false` — with `true` the question asks about any attribute
  as likely as another. The default is the old draw: the 233 tasks of the page are the same texts.
- `tasks/Matching.scala`: accessors for the themes and scales the corpus draws from (`themeCount`, `scaleCount`).
- `tasks/GridTasks.scala`: `unique` is visible to `Drawn`. `statement/Writing.scala`: `within` on a span.

For anyone training a head on generated tasks: check the balance of every decision before believing its accuracy.
The attribute a question asks for was the first open one in 92% of the drawn tasks, and every head measured 97%
by answering "the first" (§10).

### The frame head by looks, and the gate out of the corpus (2026-11-10, the statement reader's owner)

- `bench/src/main/scala/okay/desk/bench/StatementRead.scala` only: the frame head is now `Gaze` (four learned looks
  over a sentence's tokens ++ its end, Adam) in place of the linear head over end ++ mean; `Seen.whole` / `Seen.end`
  are gone. `READ_FRAME_EPOCHS` defaults to 10. `READ_OUT=file` appends every judged task's outcome and least
  margins as tab-separated lines. The gate asks whenever the reading has no single answer.
- Result and what follows: `specs/formalization.md` §11. In the corpus's wordings nothing is wrong any more; out of
  them a margin is no gate — do not put this reader behind a margin on the page.
- No file of core, ui or web is touched by this.

### The reader bench is shared: the backbone switch, and the corpus changed (2026-11-10 15:50, the statement reader's owner)

- `bench/StatementRead.scala` is edited by two agents. At ~14:30 I restored it from a backup of mine and dropped the
  backbone trial's `READ_BACKBONE` switch without knowing it was there; it is back (16:00), on the looks frame head.
  Of `/tmp/desk-backbone`: `e5-12` is the encoder on the OLD frame head, `e5-8` and `open` are the plain student
  (the switch was not compiled in), `student` is the student on the changed corpus below. Before restoring a file
  from a backup: `diff` it against the tree first.
- The drawn corpus changed at 15:47–15:50 (`tasks/Settings.scala`, `Drawn.scala`, `Matching.scala`): 33 trained
  families instead of 25, new features and wordings in the trained settings. Reader numbers before and after are
  not one series; the collection's texts and the held-out families (race, talks, matching3, scales 3/7/11/15) are
  unchanged.
- One MLX job at a time: check `pgrep -fl StatementRead` and `/tmp/desk-backbone/done` before starting one.

### The backbone trial is over (Claude, the e5 session, 2026-11-10 18:45)

Four readers on one tree and corpus (33 families, looks head): numbers in
`~/work/my/okay-desk-private/backbone/results.md`, logs beside it. The student stays the reader: frozen e5-small at
layer 8 equals it on unseen English (43%) and reads frames there better (98% against 96–97%), but loses in the corpus
on tags (Test 81% / 70% exact); its last layer and the student with no causal mask are far worse. No MLX job of mine
is running or queued. Mine in `bench/`: `EncoderReading.scala`, `OpenMlx.scala`, the `READ_BACKBONE` lines of
`StatementRead.scala` (default: the student, as before).

### Step 1 measured: compaction, and what the corpus really measures (2026-11-10)

Same scope as above: `bench/LogicBudget.scala`, `specs/logic.md` §5.5, `BACKLOG.md` item 34, this file. No `web/`,
`ui/`, protocol, computation or deploy file touched; no head or weight promoted; page untouched.

**Compaction, measured not assumed.** The cons-chains are shape, not reading. With an n-ary list node (one length
decision per list, 119 lists on Validate, 239 `cons` holes free) the budget falls from 34.1 to **26.6 decisions per
task**, so 70% whole-program exactness needs 98.67% per decision instead of 98.96%. What remains per task: binding
9.2 (7.5 options on average), relation 6.5 (4.7), copies 5.5 (5.2, read by the pointer at 83%), list 1.2, literal
2.7, goal 1.0, operator 0.4. Per family as built → compact: data 25.0→20.5, kinship 45.0→35.1, numbers 33.3→26.5,
planning 33.0→24.3. Compaction removes bookkeeping, not the semantic decisions: it does not open the gate by itself.

**The decisive measurement: the corpus has 17 program schemas.** Abstracting constants to `#` and keeping canonical
variables, the 128 tasks carry 17 schemas (data 4, kinship 5, numbers 5, planning 3); none is seen once, and every
Test schema also appears in Train (3/3, 4/4, 4/4, 3/3). So the current benchmark measures a 4-way schema choice per
family plus slot filling, spelled out as 34 constructors — it measures spelling, not form. A schema-first reader
would make **1 schema decision + 5.5 pointer slots = 6.5 decisions per task**, where 70% exactness needs 94.69% per
decision; that is a target a 12-layer student can plausibly hit.

**Consequences for the gate.** (a) The exact-program gate should be restated schema-first (schema + slots), because
that is the real task; the constructor-level gate measures bookkeeping. (b) A held-out split that shares every schema
tests slots only, so testing the *form* needs held-out schemas — the corpus must add unseen schemas (or a family with
a composition operator) before any claim about understanding.

Next experiment (needs the grammar/corpus touched, so it needs agreement): a schema classifier over a schema library,
slots by the existing pointer, then the exact handler; measured as coverage@1/coverage@k and wrong-closed = 0.

### Step 2 built: the schema library and the schema head (2026-11-10)

Scope unchanged: this touches `core/.../logic/Schema.scala`, `core/src/test/.../SchemaSuite.scala`,
`bench/.../LogicSchema.scala`, `specs/logic.md` §5.6/§7/§8/§9, `BACKLOG.md` item 34 and this file. No `web/`, `ui/`,
protocol, computation or deploy file; no head or weight promoted; the page is untouched.

**The schema, with laws.** `Schema.of` turns a program into its form: every constant becomes a hole of its own type, so
a schema is a partial point of the same grammar (safety and levels unchanged), and `instantiate` fills the slots
leftmost first, refusing a constant the grammar does not offer at the hole. Identity is the word with constants as
`#`; variables are already alpha-canonical, so equality is word equality. Five laws pass: the program's constants
instantiate back to the program; the word is the reading with constants abstracted; slots are the constants in order
and the library keeps one entry per word; a bogus constant and a short fill are refused; the Train library covers
every Test schema.

**The budget the path promises:** 1 schema decision + 5.5 slot reads = **6.5 decisions per task**, against 34.1
constructors.

**The measurement that decides it** (`bench/LogicSchema`, 12-layer student, 192 Train tasks = 48 per family, 300
epochs, lr 0.1, masked softmax over the family's 3–5 schemas, one state per task):

- last token of the prompt: **23/32 Test schemas (71.9%)**, 22/32 Validate (68.8%), majority baseline 11/32. Test per
  family: data 8/8, kinship 8/8, numbers 2/8, planning 5/8; Validate 5/6/7/4.
- bag of the task's tokens (mean state): 19/32 Test (59.4%), 20/32 Validate (62.5%) — the last token is the better
  summary.
- **whole programs** with the pointer at its measured 83.1% per slot: **33.2% Test, 27.4% Validate**; a perfect
  pointer would give 71.9%/68.8%.

**Reading of the result.** Schema-first turns 0/32 exact held-out programs into about a third, on the same 12-layer
student that abstains on every program under prefix decoding. The two bottlenecks are now named: the schema choice
(72%) and the pointer (83% per slot over ~5.5 slots); 70% whole programs needs about 95% on both. The pointer number
is still the gold-prefix one from §5.4 — reading slots under a *schema* prefix and the end-to-end decoder are the next
measurements, and the corpus still needs a held-out split with schemas Train never showed before any claim about
form. No head is saved; `~/.cache/okay-desk/` still has no `logic-reader*.json`.

### Step 3 built: the schema-first decoder, end to end (2026-11-10)

Scope: `bench/.../LogicSchemaRead.scala`, `specs/logic.md` §5.7/§7/§8/§9, `BACKLOG.md` item 34, this file. No `web/`,
`ui/`, protocol, computation or deploy file; nothing promoted; the page is untouched.

**What runs.** The schema head picks the form from the task's last state over the library filtered by the task's own
ontology (a schema naming a predicate the tables do not have is not a candidate; the filter reproduces the family
partition exactly, so it is the page's information, not the corpus' label). The pointer then reads each slot under the
prefix the *schema itself* writes — never a gold prefix — `Schema.instantiate` fills the holes and `Compile.run`
executes. Trained on Train, calibrated on Validate, Test read once.

**Numbers (64 Train tasks, 12-layer student).** Pointer under schema prefixes: 336 Train slot reads, 123/154 Validate
(79.9%) — a schema prefix costs ~4 slot reads against the gold-prefix pointer's 127/154. At margin 0: 13/32 Validate
exact, 6 abstained, 13 wrong (7 of them the wrong form). The calibrated pair (schema 0.0, pointer 2.0; 21 of 56 pairs
have no wrong-closed reading): Validate 9/32 exact, 23 abstained, 0 wrong; **Test 12/32 exact, 20 abstained, 0
wrong**. Test per family: data 8/8 exact and nothing abstained, planning 4/8, kinship 0/8, numbers 0/8. Per-form
pointer margins give Validate 11/32 and Test 12/32, still 0 wrong. The schema margin carries almost nothing — the
head's top-two gap does not separate right from wrong forms — so the pointer's margin is the whole safety.

**Where it fails (margin 0, form / slots).** data 8/8 and 10/10 on both splits. planning: slots 20/20 on both splits
and form 4/8 — the losses are one thing, the forms differ only in how many `edge` facts the program writes (2/3/4)
and the head always answers the 2-edge form, because a text-only task writes its facts into the program and the fact
count became a form decision. numbers: slots 33/36 and 13/13, form 5/8 and 2/8 — on held-out phrasings "minor" wins
over older/between/sameAge, whose forms share every fact and differ in one rule and one comparator. kinship: form 6/8
and 8/8, slots 10/23 and 38/57 — the pointer picks the wrong occurrence of five names repeated across the facts.

**Reading of the result.** The safety is real: at the calibrated margin no held-out reading is wrong, and the page's
own data questions are read exactly with no abstention. The ceiling is the form head, and more Train tasks is not the
lever (192 Train tasks: the head memorizes 192/192 and the safe pair collapses to 3/32 Test with 3 wrong). Next, in
order: the facts out of the form (a table, or a repeating block, so the fact count stops being a decision), the form
head on held-out phrasings, the pointer over repeated names; then a held-out split with schemas Train never showed.
No head is saved; `~/.cache/okay-desk/` still has no `logic-reader*.json`.

### The ladder on the sealed set (Claude, the e5 session, 2026-11-10 21:00)

Answer to §12.1's "or a stronger backbone": `~/work/my/okay-desk-private/backbone/results.md` (second table), run on
a snapshot of the private copy (`backbone/tree`), totals only. Sealed exact en / ru: student 21.3 / 6.0, cut19 27.7 /
6.7, Qwen3-1.7B at layer 20 31.0 / 18.7 (Unseen ru 34.7 → 55.0, Collection ru 75.8 → 91.2). A bigger frozen model is
the second lever, mostly for Russian; silent wrong answers on Sealed grow with it. No job of mine is running.

### The statement reader is in core and on the page (2026-11-10 21:40, the statement reader's owner)

- New: `core/…/statement/Reader.scala` (the reader, its gate, its file), `Said.scala` (its words),
  `core/…/tasks/SealedSet.scala` (DO NOT READ if you write the corpus: totals only), `ReaderSuite`,
  `scripts/statement.scala` (the live check), `scripts/novel.scala` (gates tried on a READ_OUT file).
- Touched, a few lines each: `ui/Model.scala` (+`State`, `answeredBy(state = …)`), `ui/App.scala` (`RunTask` for
  stated tasks; a note with lines is shown a line each), `ui/TaskList.scala` (`Solving.result`, the check),
  `web/Engine.scala` (`statementReader`, `state`), `tasks/Task.scala` (`agrees`), `scripts/page.sh`, `publish.sh`,
  `dev.sh` (copy `~/.cache/okay-desk/statement-reader.bin` to `model-small/statement.reader`; the build's
  address depends on it).
- `bench/StatementRead.scala` now trains and measures `Reader` of core; `READ_SAVE=file` writes the page's reader;
  the old margin gate and `near` are gone. `READ_BACKBONE` is kept.
- The corpus in the tree is the new one (44 trained families, a Sealed split). Working copies and run logs:
  `~/work/my/okay-desk-private/` — /tmp did not survive the reboot.
- Published: build dec4ba238e05. Spec: `specs/formalization.md` §12.1–§12.2.

### The plain page has no model form (2026-11-11, by the operator's word; the statement reader's owner)

- The plain address is the chat with two buttons (Data, Examples) and the question. The model is chosen by itself
  (`Model.auto`), loads when a question needs it, and the chat's first line says which it is and how it is (not
  loaded / loading / loaded on which device). `App.State.chooser = false` is that page.
- THE MODEL FORM ("Load the model", "Unload model", the model's note) IS NOW UNDER `?models` (also with
  `?understanding` and `?selftest`). Scripts that press "Load the model" were given `?models`: einstein, chat,
  real, river, shot, thinking, understanding, deduction, gdp, statement, tasks. `App.main(chooser = true)` is the
  default in code, so the JVM suites are as they were.
- `?logic` opens a screen of the tasks the statement reader states alone; `scripts/logic.scala` and
  `scripts/plain.scala` check both pages as a phone.
- A stated task's "Solve" loads the model itself; a screen of tasks begins at its top (`Main.scala`).
- The corpus's houses got "smokes" (`Drawn.houses`): the page's reader was retrained (collection shown 63 en /
  66 ru of 91, none wrong).
