# The toolkit: making a small reading model and improving it, step after step

What this document is: the HOW of `specs/technology.md` — the commands that build, train, measure and ship the core
and its readers, and the cycle that improves them. Why each part is as it is: `docs/textbook.md` and the line specs
(`specs/core-next.md`, `specs/core-joint.md`, `specs/formalization.md`). The tools live in this repository's bench
for now; they move into okay-inlay as one pipeline (`specs/core-joint.md` §7).

## 1. What is being improved

```
text ──► BASE (4-bit, frozen) ──► states ──► HEADS ──► formal statement ──► EXACT ENGINE ──► answer
            │                                  │                │
            └─ ADAPTER of an ability ──────────┘                └─ GATE: show the answer, or ask
               (beside the base, on only while that ability reads)
```

Four things can be made better, each a file beside the page:

| part | file on the page | what improves it |
|---|---|---|
| the base | `model-small/weights-*.safetensors` | rarely changed (a new base re-trains everything over it) |
| an ability's adapter | `model-small/statement-adapter.safetensors` | `JointTrain` — the core trained through the heads |
| the heads (the reader) | `model-small/statement.reader` | `StatementRead` — trained on the corpus over the base (+adapter) |
| the gate | inside the reader (bars, known words) | measured with `READ_OUT` + `scripts/gates.scala` |

What was learnt about each (in short; the specs hold the numbers):
- the base is NOT improved by distilling it toward a teacher's states — that is a proxy the readers do not need
  (core-next.md: six candidates, none not worse); a 4-bit merge loses most of an adapter;
- the core IS improved by training it through the readers' own heads (core-joint.md §6: sealed Russian 6–9% → 24–27%,
  three seeds), as an adapter beside the base;
- the gate decides what a person sees: today it is the bottleneck (core-joint.md §8).

## 2. The cycle: how the core keeps improving

```
   ┌─► 1. FAILURES  ──► 2. DATA  ──► 3. TRAIN  ──► 4. MEASURE  ──► 5. ACCEPT? ──► 6. SHIP ─┐
   │   what it got     pairs        heads,         the protocol,    better on the    the page, │
   │   wrong or asked  (text →      adapter        seeds, gates     primary, not     a row of  │
   │                   statement)                                   worse elsewhere  history   │
   └────────────────────────────────────────────────────────────────────────────────────────┘
```

1. **Failures.** Where the reader is wrong or asks: the bench's "wrong decisions" and "wrong frames" lines, the
   sets' families, the browser runs (`scripts/statement.scala`), and on the page the tasks a person had to answer
   for it. A failure is a datum, never a rule written by hand (formalization.md §23: a rule is a hole in the pairs).
2. **Data.** A failure becomes training pairs (text → formal statement) only through a VERIFIER: the teacher
   rewrites a task in other words and four languages, and the exact side keeps a rewrite only if it reads back to
   the same statement (`StatementTeacher`); a generator adds the family that was missing (`tasks/Drawn`); later,
   exercises from open textbooks (their answers as the judge) and human question sets checked by execution.
   The sealed set and the outside measures (ZebraLogic) are never training data.
3. **Train.** Heads on half of the corpus, the adapter through them on the other half and the new wording
   (`JointTrain`), then the heads again over the adapted core on all of it (`StatementRead`). Thirty minutes each.
4. **Measure.** The protocol of §4, three seeds, every gate on the same outcomes.
5. **Accept** only if the primary measures rise beyond the seeds' spread and the hard limits hold. A refusal is
   written down with its reason (`technology.md` §1: negative results are knowledge).
6. **Ship.** The files beside the base (`scripts/publish.sh` copies them), the page checked in a browser and as a
   phone (`scripts/statement.scala`, `PHONE=1`); one line in the history.

The cycle is the same for every ability: logic tasks now (Assign), data questions next (Query), then calculations,
rules, data from the internet (`technology.md` §9). An ability brings its formal language and its solver; the
cycle brings the rest.

## 3. The tools

All are run from the repository's root; logs and artefacts go to `~/work/my/okay-desk-private/` (never `/tmp`: it
is wiped on a restart). Long runs are started with `nohup … &` — a background job of the agent is killed at two
hours. One MLX job at a time: two on one GPU slow both and have lost lines before.

### 3.1 Reading and measuring

| tool | what it does | how |
|---|---|---|
| `StatementRead` | trains the statement reader over a core and measures it on Validate, Test, Unseen, Sealed (totals only), the teacher's test and the collection | `READ_ECHO=2 READ_TRAIN=4000 READ_TEACHER=…/teacher/all.jsonl READ_TEACHER_TEST=…/teacher/test.jsonl sbt "bench/runMain okay.desk.bench.StatementRead"` |
| its switches | `READ_SEED` (heads' seed), `READ_SEEDS=1,2,3` (several seeds over states read once — kept in memory: fits a small corpus; the page's 4000 tasks + rewrites with 1.18 M tag examples did NOT fit 38 GB and thrashed — use READ_STORE instead), `READ_STORE=<dir>` (the core's states kept on disk, exact: read by the model once, then from the disk by every later run over the same core — name the directory by base, adapter and echo; ~7 GB a core for the page's corpus), `READ_SAVE` (write the reader), `READ_LOAD` (measure a saved reader, nothing trained: 7 min), `READ_OUT` (a line a task: outcome, margins, novelty, answer), `READ_GATE=conformal`, `SEMANTIC_NATIVE_ADAPTER` (the core with an adapter in the page's format) | |
| `scripts/joint-measure.sh <name> <lora>` | an adapter exported to the page's format and the reader trained over it by the page's recipe, seeds 1–3 (`SEEDS="2 3"` for some) | logs `joint/<name>-<seed>.txt`, reader `joint/<name>.reader.bin` |
| `scripts/gates.scala` | gates tried on `READ_OUT` files with no reading again: novelty (the page's), margins, margins×2, and with a second run `agree`, `agree+marg` | `scala-cli run scripts/gates.scala -- out.tsv run.log [other.tsv other.log]` |
| `scripts/core-bench.sh <name> <dir> <layers>` | a whole core compared: the contract to the teacher, the data readers' sample, the fact and relation readers trained on it, the statement reader | `LORA=` / `SEMANTIC_NATIVE_ADAPTER=` for an adapter |
| `scripts/core-seeds.sh <name> <dir> <layers> 2 3` | the fact and statement readers again with other seeds | |
| `scripts/statement.scala` | the real page in Chromium: tasks solved by "Solve", right / asked / wrong and seconds; `GL=1` WebGL2, `PHONE=1` a phone | serve with `PORT=… scripts/dev.sh` (not 8741: Elysion's) |

### 3.1a The cycle and its history

| tool | what it does | how |
|---|---|---|
| `scripts/cycle.sh <name> <base repo> <teacher jsonl>` | one turn: heads on split A → the adapter through them → the reader over the adapted core, seeds 1–3 → rows of the history; resumable (each step's file skips it) | `nohup scripts/cycle.sh next local/Qwen3-0.6B-4bit-cut19-vocab-p12-m4 …/teacher/all2.jsonl &` (~2.5 h) |
| `scripts/history.scala <name> <seed> <log> [note]` | a run's numbers as rows of `~/work/my/okay-desk-private/history.tsv`: set, language, exact and silent with no gate, shown and wrong through the page's gate | |
| `scripts/compare.scala <old> <new>` | the protocol's verdict, set by set: ranges and means over seeds, "up" / "DOWN" only beyond either side's spread, and the hard limits | e.g. `heads-alone adapter-r32` |

### 3.2 Training

| tool | what it does | how |
|---|---|---|
| `JointTrain` | the adapter trained through fixed heads: per text the heads' gradient with respect to the states (`HeadGrads`: tags, frames), a step on ⟨h, G⟩ + λ·pull toward the base | `JOINT_TEACHER=…/all.jsonl sbt "bench/runMain okay.desk.bench.JointTrain <base repo> <heads file> <out.safetensors> <epochs> <rank> <lr> <λ>"` |
| `HeadGrads` | each head's loss and its gradient with respect to its input; the law `HeadGradsSuite` (finite differences of the heads' own forward) | `sbt "bench/testOnly okay.desk.bench.HeadGradsSuite"` |
| `SemanticExport` | a LoRA (MLX) to the page's adapter: two 4-bit planes, rank padded to 64 | `bench/runMain okay.desk.bench.SemanticExport <lora> <out>` |
| `StatementTeacher` | the teacher's rewrites in four languages, judged by read-back | formalization.md §15 |
| `CoreNarrow`, `CoreDistill`, `Merge`, `CoreMergeLoss` | a base narrowed, distilled toward a reference, merged at 4 bits; the merge's loss measured | core-next.md — use with its lessons |

### 3.3 On the page

`Engine` loads `statement-adapter.safetensors` when the model's directory has it and turns it on only while a
logic task is read (`Adapted.active`); the data readers and the fact readers read the base as before.
`scripts/publish.sh` ships `~/.cache/okay-desk/statement-reader.bin` and, when present,
`~/.cache/okay-desk/statement-adapter.safetensors`. The page's files are changed only by an accepted cycle.

## 4. The protocol: when a change is accepted

- **Primary measures**, fixed before a run: exact answers by language on the SEALED set and on the TEACHER'S TEST
  (other hands' wording), and what the PAGE shows through its gate on the collection and in the browser.
- **Seeds**: three. A difference counts when the means differ by more than the seeds' range of either side.
- **Hard limits**: shown wrong answers on the page's gate stay at zero on the collection and the browser run;
  in-corpus Test not below by more than a point; the other abilities' readers on the same base unchanged (the data
  readers' 13/13 sample, the fact reader's contract of no wrong answer).
- **The level, not only the difference**: a set read at 3–7% is a set the method does not read; argue over it only
  as a direction, never as a verdict.
- **Every number from a run's output**, the run's log kept, its line in the spec.

## 5. Where it stands, and what is next (2026-11-13)

Built and measured: the adapter through the heads (logic tasks), its toggle on the page, the fast measure
(`READ_LOAD`) and the gate trials. Not shipped: the page's gate hides what the adapter gained (core-joint.md §8).

Next, in order:
1. **A gate that checks independently of the reading** — the exact side (every clue used; the statement said back
   and compared with the text) or a reader of another kind; a gate LEARNT from the outcomes of the unseen families
   and judged on the sealed set. Accepted when the page shows more right answers with no wrong one.
2. **Ship the adapter** with that gate; the browser and phone runs; a line of history.
3. ~~The cycle as one command with a history~~ — built: `cycle.sh`, `history.scala`, `compare.scala` (§3.1a).
4. **Data questions (Query)** through the same cycle: Spider/BIRD and PAUQ checked by execution, the generator over
   many schemas, the teacher's rewrites; the data readers' heads and their adapter.
5. **The base made smaller** by the same objective (trained through the heads, not toward a teacher), with Russian
   watched first.
6. The toolkit moved into okay-inlay with okay-forge (`specs/core-joint.md` §7).
