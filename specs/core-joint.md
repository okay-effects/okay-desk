# The core trained for the reading (2026-11-13)

Questions 3 and 8 of `specs/technology.md`; lever 3 of `specs/formalization.md` §13. What was learnt before it:
`specs/core-next.md` (a core compressed toward a teacher's states gains English out of its corpus and loses the
sealed Russian; nearness to a teacher is a proxy the readers do not need; the 4-bit merge loses most of an adapter).

## 1. The question

Are heads over FROZEN states enough, or must the model itself be trained for the reading — and once it is, can the
base be smaller with the reading intact?

## 2. The mechanism

The reader is a set of heads over the core's states (`statement.Reader.Heads`): a tag per token (`Masked`, over the
token's state and the state at its word's end), a frame per place of a sentence (`Gaze`, looks over the sentence's
tokens), a link (`Pointer`), a pick (`Asking`). They are trained by their own hand-written gradients over states the
core gives once; the core never learns from them.

**The gradient through the heads into the core.** A head's loss L is a function of the states it reads. Its
gradient with respect to those states, G = ∂L/∂h, is computed by the head (the same chain rule its training uses,
taken one step further: to its input). The core's adapter is then trained on the surrogate

    S(θ) = Σ_positions ⟨h_θ(position), G(position)⟩  +  λ · z-MSE(h_θ, h_base)

where G is a constant computed at the current θ. ∂S/∂θ = Jᵀ G = ∂L/∂θ: the exact gradient of the heads' loss with
respect to the adapter, with no head rewritten in MLX — the heads stay one implementation, on the JVM and in the
page. The second term keeps the states near the base's (what every other reader of the base — data questions,
facts — still reads), with weight λ.

**Which texts teach the adapter.** Heads trained on a text already read it (99% on the training families): its
gradient is near zero. So the heads are trained on HALF the training corpus (split A) and the adapter on the other
half (split B) and on the teacher's rewrites (other hands, four languages), which the heads never saw: the
adapter learns to make texts readable that the heads were not fitted to — the generalization the sealed set
measures. This is LP-FT (Kumar et al. 2022): heads first, then the backbone with the heads fixed.

**Then the heads again.** After the adapter, the heads are trained afresh on the whole corpus over the adapted
states — the page's recipe exactly — so the comparison with heads alone is the same recipe on two cores.

**Where it lives.** An adapter beside the unchanged base (`Adapted`, two 4-bit planes, WebGL2): no merge (the merge
loses most of it, core-next.md). A module in the sense of §13: a file next to the base.

## 3. The measure (fixed before the run)

- **Primary:** exact with no gate, by language, on SEALED (en, ru) and on the TEACHER TEST (other hands' wording:
  en, ru, uk, pl), the mean over seeds 1–3 of the heads.
- **Hard limits:** silent wrong answers on Sealed not above the baseline's; Test (in-corpus) not below by more than
  one point; the data readers' sample (13/13) and the fact contract unchanged on the same base (λ is for that).
- **Baseline:** heads alone, the page's recipe (`READ_ECHO=2 READ_TRAIN=4000 READ_TEACHER=teacher/all.jsonl`), the
  old core, seeds 1–3 (`okay-desk-private/e2b-{1,2,3}.txt`): Sealed en 40–45, ru 6–9; teacher test en 19–28,
  ru 20–36, uk 6, pl 5–11; Unseen en 59–72, ru 40–55; Collection en 74–91, ru 69–76.
- **A difference counts** when the means differ by more than the seeds' range of either side.

## 4. Order

1. `HeadGrads` — each head's loss and gradient with respect to its input states; the law: the gradient against a
   finite difference of the loss (`HeadGradsSuite`). Tags and frames first: they carry most decisions (a text of
   the unseen families holds ~185 tags and ~24 frames; at 99.4% and 96.5% per decision both lose texts); links and
   picks after.
2. `JointTrain` — the loop of §2 on the old core (MLX, the student with a LoRA): heads from split A; per text of
   split B and the teacher's rewrites, a forward for G, a step on S. The adapter exported to the page's format
   (`SemanticExport`).
3. The measure of §3: `StatementRead` with the adapter (`SEMANTIC_NATIVE_ADAPTER`), three seeds. This answers
   question 3.
4. Only if 3 says yes: the same on a NARROWER base (question 8) — the base chosen once (the MLP narrowed, its loss
   repaired by the same joint training with λ toward the old core) and kept; the reader's adapter beside it.

## 5. Not known before the run

Whether the heads' gradient on other hands' wording is a signal or noise (the teacher's rewrites are few: 437
tasks); whether λ can keep the data readers intact while the tags move; what an adapter of rank 32 costs a phone
(two extra products per projection on WebGL2).

## 6. Results

### The first adapter (2026-11-13): r32

Heads of split A (2000 Train tasks, echo 2, `joint/headsA.bin`); the adapter: rank 32 on every projection, lr 1e-4,
λ 0.1, one epoch over 4,437 texts (split B en+ru and the teacher's 437 rewrites), 30 min on the M4; the heads' loss on
texts they had not seen 0.16 → 0.02–0.06. Then the reader trained afresh by the page's recipe over the adapted core,
seeds 1–3 (`joint/r32-{1,2,3}.txt`), against heads alone (`e2b-{1,2,3}.txt`). Exact with no gate, %:

| set | heads alone (3 seeds) | heads + adapter (3 seeds) |
|---|---|---|
| Test en / ru | 96.7–99.7 / 97.3–99.7 | 99.3–99.7 / 99.7–100 |
| Unseen en / ru | 59–72 / 40–55 | 69–90 / 65 |
| **Sealed en** | 40–45 | **47–63** |
| **Sealed ru** | 6.0–9.0 | **23.7–27.0** |
| Teacher test en / ru | 19–28 / 20–36 | 25–39 / 28–48 |
| Teacher test uk / pl | 6.3 / 5–11 | 25–34 / 16–37 |
| Collection en / ru | 74–91 / 69–76 | 95–100 / 89–92 |

Every set above the baseline's range on every seed, except the teacher's English and Russian (overlapping, 25–36 en
mixed). **Question 3 answered: heads over frozen states were not enough; the core trained through them reads other
hands' wording and the sealed families far better** — the sealed Russian tripled, where six compressed cores before
(core-next.md) moved it by ±3.

Silent wrong answers with no gate rose on Sealed (en 10–13 → 10–14, ru 4–5 → 8–10: more answered, a share of them
wrong); WITH the page's gate there are none in either (Sealed fully asked by the novelty sign in both; Collection
shown en 61–63 / ru 60–63, every one right — heads alone showed ru 58–63 with 3–8 not right).

The other readers of the base, on the adapted core: the data readers' sample 13/13, 13/13 (unchanged); the fact
reader 2 wrong on its test (the contract asks 0) — the pull λ 0.1 does not keep the fact sentences' states. So the
adapter is a MODULE OF THE ABILITY: on while a logic task is read, off for data questions and facts (the base
unchanged for them by construction). Its cost: 21.7 MB beside the 122 MB base (rank 32 kept at 64 by the export).

## 7. After the measure: one training pipeline, okay-forge generalized (agreed with the operator, 2026-11-13)

okay-inlay's `forge/` (specs/forge.md there) is the same method for another task: tasks from a source, a VERIFIER
instead of people, LoRA in Scala, held-out / transfer / regression measures, every result a row of a history. It
trains a 4B to generate code; here a 0.6B reads text into a statement on a phone. Rather than a second pipeline
beside it, forge becomes one pipeline with exchangeable parts, and this line's components move into okay-inlay:

- **a task source**: drawn by a program, rewritten by a teacher, exercises from open textbooks (their answers as the
  judge), human question sets (Spider/BIRD, PAUQ in Russian — checked by execution);
- **a verifier**: the Scala compiler and behaviour checks (forge's), the exact solver read back (the statement), the
  query's result on the data;
- **an objective**: tokens' cross-entropy (forge's), the heads' gradient through the core (`HeadGrads`,
  `JointTrain`), a pull toward a reference state (`CoreDistill`);
- **measures**: held-out, sealed, other hands, seeds, silent wrong answers as a hard limit; one `history.tsv`;
- **the phone's format**: the adapter beside an unchanged 4-bit base (`SemanticExport`, `Adapted`).

Done only once the three seeds of §6 have said whether the adapter helps; the move is a refactoring with the
numbers unchanged (the same runs, the same results).

## 8. The gate: whether to show an answer or ask (2026-11-13)

**Found in the browser:** the reader over the adapted core answered 65, asked 15, wrong 0 of 80 page tasks, against
77 / 3 / 0 for the page's reader — while its exact answers with no gate are far higher. The page's gate shows an
answer only if the text has NO WORD NEVER MET in training (and every margin is over its bar): on the unseen
families, the sealed set and the teacher's wording it shows nothing at all, for either reader. The adapter's gain
cannot reach a person through that gate. The page keeps its reader until a gate does better.

**Tools:** `READ_OUT` keeps each task's outcome, margins, novelty and ANSWER; `scripts/gates.scala` tries gates on
such files without reading again; `READ_LOAD=<reader>` measures a saved reader with nothing trained (7 min instead
of 30). The law of the last: today's saved reader (r32, seed 1) gives exactly its run's twelve numbers again
(`joint/load-r32.txt`); yesterday's page reader gives slightly different ones (Sealed en 45.3 → 48.0) — the reading
code changed since (whole-word pieces), not the file.

**Gates tried** on the adapted reader, seed 1 (shown / wrong among shown; `agree` = the reader without the adapter,
heads alone, gives the same answer):

| set | novelty (the page's) | margins | agree | agree + margins |
|---|---|---|---|---|
| Collection en | 63 / 0 | 83 / 5 | 83 / 0 | 76 / 0 |
| Collection ru | 63 / 0 | 71 / 1 | 82 / 2 | 70 / 0 |
| Unseen en | 0 / 0 | 271 / 72 | 176 / 3 | 166 / 3 |
| Unseen ru | 0 / 0 | 145 / 10 | 179 / 26 | 124 / 6 |
| Sealed en | 0 / 0 | 180 / 61 | 158 / 29 | 119 / 16 |
| Sealed ru | 0 / 0 | 69 / 12 | 48 / 18 | 24 / 3 |

What it says: the heads' margins are NOT calibrated out of the corpus (confidently wrong); two readers agreeing is a
far better sign near the corpus (Collection en 83 / 0, Unseen en 176 / 3) but not on the hard unseen texts — the two
share a base and a corpus, and err ALIKE. A gate needs a check independent of the reading: the exact side's (every
clue used, the statement said back and compared with the text), or a reader of another kind. Open; every candidate
is now measured in minutes.

## 9. A gate learnt from outcomes (2026-11-13)

`scripts/learnt-gate.scala`: a logistic model of "this shown answer is wrong" over a task's signs — each head's least
margin relative to its bar, for the adapted reader and the plain one; the words and pairs never met; whether the two
readers give the same answer; the language — fitted on the answers of the UNSEEN families and the TEACHER'S TEST
(645), its threshold chosen there for at most 1% wrong among shown; judged on sets it never saw. Seed 1, the adapted
reader (shown / wrong among shown):

| set | the page's gate (novelty) | the learnt gate, α 1% | α 3% |
|---|---|---|---|
| Collection en | 63 / 0 | **75 / 0** | 78 / 0 |
| Collection ru | 63 / 0 | **75 / 0** | 78 / 0 |
| Sealed en | 0 / 0 | 1 / 0 | 3 / 0 |
| Sealed ru | 0 / 0 | 8 / 1 | 21 / 2 |
| Test en / ru | 295 / 1, 294 / 0 | 296 / 1, 300 / 0 | 299 / 1, 300 / 0 |

The strongest sign is the two readers agreeing (weight −3.6 on the log-odds of wrong); a word never met counts, as
one sign among others instead of a veto. On the collection twelve more right answers in each language, none wrong.
The sealed set stays asked: there the reader is right on a part only, and the gate says so. One seed, one fit —
the next check is seeds 2 and 3. Next: the gate on the page — `statement.Gate` in core (one implementation for the
bench and the browser), its weights a file beside the readers; the page reads a task with both readers.

### 9.1 The learnt gate over three seeds: it does not carry to the sealed set (2026-11-14)

The same fitting (unseen families + the teacher's test, α 1%) for each seed's pair of readers (`joint/out-{base,r32}-
{2,3}.tsv`; seed 1 above). Shown / wrong among shown:

| seed | Collection en | Collection ru | Sealed en | Sealed ru |
|---|---|---|---|---|
| 1 | 75 / 0 | 75 / 0 | 1 / 0 | 8 / 1 |
| 2 | 80 / 0 | 74 / 0 | 95 / 8 | 18 / 2 |
| 3 | 81 / 0 | 56 / 0 | 168 / 24 | 60 / 13 |
| the page's gate (novelty), seeds 1–3 | 60–63 / 0 | 45–63 / 0 | 0 | 0 |

With the threshold at NO wrong answer on the fitting sets (α 0), seed 3 still shows 8 wrong of 116 on the sealed
English and 6 of 38 on the Russian. The collection gains on every seed with none wrong; the sealed set — the stand-in
for what a person types — gets silent wrong answers. A gate fitted on one kind of unfamiliar text (the unseen
families) is not calibrated for another (the sealed ones): the shift between them is itself unseen. NOT SHIPPED.

What this says about the problem: a gate cannot make a reading more reliable than it is; on wording no corpus had,
the reader is right 60–85% of the time and its own signs (margins, novelty, a second reader of the same base and
corpus) do not tell which. Two ways on, both measured by the same tools:
1. a check of the reading by the EXACT SIDE at the level of each clue — the solution found must satisfy each
   sentence as the OTHER reader read it, every clue must be used — independent of how sure the heads are;
2. the reading itself made right more often on unfamiliar wording — the corpus's variety (the teacher in four
   languages, exercises from textbooks), the cycle of `docs/toolkit.md`.

## 10. The exact side's sign: is every clue needed? (2026-11-14)

`ClueCheck`: a clue is spare when dropping it leaves the same definite answer. On the GOLD statements, every clue is
needed in 70.6% of Test, 81.8% of Unseen, 85.7% of Sealed, 67.5% of the collection; ~5% of all clues are spare (the
generators do not make minimal tasks). As a gate over the readings (`spare` in `READ_OUT`; seeds 2 and 3, the adapted
reader; shown / wrong):

| set | tight (no spare clue) | agree & tight | agree & tight & margins |
|---|---|---|---|
| Test en | 204 / 1, 204 / 1 | 202 / 1, 199 / 1 | 194 / 0, 197 / 0 |
| Collection en | 56 / 0, 54 / 0 | 52 / 0, 43 / 0 | 44 / 0, 41 / 0 |
| Sealed en | 192 / 31, 200 / 23 | 133 / 16, 123 / 11 | 92 / 10, 70 / 7 |
| Sealed ru | 99 / 21, 88 / 22 | 17 / 4, 21 / 7 | 7 / 2, 14 / 5 |

It does not separate: it drops a third of the right answers in the corpus (their tasks have spare clues), and on
the sealed set the readings it keeps are still wrong one time in seven to ten. A misreading that gives a definite
answer is mostly as tight as a right one.

**What the gate experiments (§8–§10) established.** On wording no corpus had, the reader is right 60–85% of the
time, and none of its own signs — margins, novelty, a second reader of the same base and corpus, a learnt
combination, the solver's tightness — tells the right from the wrong well enough to show answers with no silent
error. The page's novelty gate stays: it is the only one with no wrong answer on the sealed set, at the price of
asking. The lever is the READING on unfamiliar wording itself: more and more varied training text (the teacher's
rewrites were the one measured lever — formalization.md §18, and the adapter learnt most from them), then the same
adapter and heads over it. Next: the teacher's corpus at scale.

## 11. The reading on unfamiliar wording: the teacher's corpus at scale (2026-11-14, running)

The question: does more of the one lever that moved the sealed set — rewrites by the teacher in four languages,
judged by read-back — keep moving it, through the same adapter and heads? Run: `StatementTeacher 200 400` (Train
tasks 200–400, en/ru/uk/pl) → `teacher/train2.jsonl`; the first batch took 4 h 27 min for 200 tasks and kept 359 of
800 rewrites (45%; most refusals one line off after three rounds of repair). The gateway runs on :8093 — :8090 is
held by a Docker container of okay-chat. Then: the adapter trained again with the doubled rewrites
(`JOINT_TEACHER`), the reader by the page's recipe with them, seeds 1–3, against r32 (§6) — Sealed and the
teacher's test as the primary measures.
