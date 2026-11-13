# The next core: smaller, and at least as good (2026-11-12)

The operator's request: a new small core that works better than the page's student (`model-small`, §21 of
specs/data.md); the old one is removed only after the operator has checked that the new one is not worse.

## The old core, measured as it is

`local/Qwen3-0.6B-4bit-cut19-vocab-p12-m4`: 12 layers of 1024 (layers 0–10 and 16 of cut19), MLP 3072, 16 query
heads of 128 (q 2048 wide), 8 KV heads, vocabulary 38,285, 4 bits in groups of 64. ~189M parameters of layers
(~106 MB) + 39M of embedding (~22 MB) = 128 MB; the page fetches ~120 MB for English and Russian. Distilled toward
cut19's state at layer 19 (the contract the teacher's `readers.skill` reads) on the desk's data prompts ONLY — not on
the logic tasks the statement reader reads, nor on the fact sentences.

Where it stands against its teacher (cut19 at 19): the sample with the teacher's skill 11/12 en, 7/12 ru (teacher
12/9); the statement reader (backbone ladder, specs/formalization.md): Sealed 21.3/6.0 (27.7/6.7), Collection
93.4/75.8 (95.6/82.4). A frozen e5-small at layer 8 (~14M parameters of layers) equals it on unseen English: its
width is not what limits it.

## The plan

1. **The comparison (`CoreBench`)**: every measure the page depends on, for any model directory, written as one
   table: the contract (z-MSE to the teacher at 19, held-out, on three kinds of text: data prompts, logic tasks,
   fact sentences); the sample with the teacher's skill unchanged (13 questions en/ru); the fact reader and the
   relation reader trained on the model (`FactReaderBench`, `RelationsBench`, files written beside, never over the
   page's); the statement reader (`StatementRead`: Test, Unseen, Sealed, Collection); size. The old core is measured
   by the same command first.
2. **Narrower**: MLP neurons ranked by their contribution on the training texts of all three kinds (mean |activation|
   × the norm of their column in down_proj), the weakest removed per layer (3072 → 1536 first); `gate`/`up` rows
   taken as they are (their 4-bit groups run along the input), `down` dequantized, its columns taken, quantized again.
3. **Distilled on all three kinds** toward the same contract (LoRA on every projection, then merged at 4 bits) —
   the old student never saw a logic task; this is the "better" lever, as the corpus was in the backbone trial.
4. Candidates, each through 1: A — the same 12 layers re-distilled on the wider corpus (does the corpus alone help?);
   B — 12 layers, MLP 1536; C — fewer layers as well, if B holds. The smallest that is not worse anywhere wins.
5. **On the page beside the old** (`readers-next`, its own directory): the operator compares in the page; the old
   stays the default until the operator says otherwise. Then and only then the old is removed.

## Results

(filled as they come)
### The old core (`scripts/core-bench.sh old …-p12-m4 12`, 2026-11-12; one run)

| measure | old core |
|---|---|
| weights | 122 MB |
| contract z-MSE: data / tasks / facts | 0.098 / 0.797 / 0.665 |
| sample, the teacher's skill (en / ru of 13) | 13 / 13 |
| fact reader, answered of 160 with 0 wrong (validation / test) | 68 / 106 |
| relation reader, answered of 472 with 0 wrong (validation / test) | 218 / 334 |
| statements, exact no gate en / ru: Test | 97.3 / 94.0 |
| Unseen | 42.7 / 37.0 |
| Sealed (totals only) | 32.3 / 6.7 |
| Collection | 91.2 / 76.9 |

The contract confirms the plan's premise: the old core is near its teacher on the data prompts it was distilled on,
and far on the logic tasks and the fact sentences it never saw in training.

**The operator's decision (2026-11-12):** if the new core is not worse anywhere and smaller, it REPLACES the student
on the page — one core, no second choice; the operator then checks the page. The old model's files stay on disk.

### The candidates (2026-11-12/13), every one over three seeds of the readers

Statements: exact with no gate, en / ru, the range over seeds 1–3 (`scripts/core-seeds.sh`); facts: wrong answers
of the fact reader on its test, three seeds (the contract asks 0). Logs: `okay-desk-private/core-next/<name>/`.

| core | size | Unseen en | Unseen ru | Sealed en | Sealed ru | Collection en / ru | facts wrong |
|---|---|---|---|---|---|---|---|
| old (p12-m4) | 122 MB | 43–48 | 32–37 | 29–32 | 6.3–6.7 | 80–91 / 73–79 | 0 / 0 / 3 |
| n1536: MLP 1536, distilled to cut19 on all kinds | 92 | 43–58 | 29–34 | 31–34 | 4.3–5.0 | 88 / 82 | 17 / 4 / 6 |
| B2: as n1536, facts toward the old core | 92 | 66–80 | 24–26 | 16–19 | 5.3–5.7 | 88–98 / 66 | 2 / 2 / 0 |
| C1536: MLP 1536, distilled to the OLD core | 92 | 61–65 | 23–25 | 22–30 | 3.3–4.0 | 90–99 / 79 | 0 / 1 / 1 |
| C2048: MLP 2048, to the old core | 102 | 64–76 | 23–27 | 19–30 | 3.3–4.0 | 85–91 / 81 | 0 / 0 / 2 |
| D: MLP 2048, to the old core, 70% Russian, 12k | 102 | 75–85 | 29–32 | 20–30 | 3.7 | 87–91 / 82 | 1 / 0 / 1 |
| E: C1536's base + its adapter UNMERGED | 109 | 63–69 | 27–31 | 19–28 | 3.0–3.7 | 93–97 / 79 | 0 / 2 / 1 |
| (cut19, the teacher, one seed) | 181 | 51 | 33 | 32 | 11.0 | 87 / 74 | 14 |

None is "not worse anywhere": every retrained core gains English out of its corpus (Unseen +20–40 points) and
loses Russian on the sealed set (~10 texts of 300) and on the unseen families. The old core stays on the page.

### What was learnt

1. **The distillation optimizes a proxy.** The mean squared distance to a teacher's state, over every dimension and
   token, is not what the readers need — they read a few directions. The teacher itself reads worse than its
   student on the tasks (Test 89 against 97) and fails the fact contract; two candidates equally near their
   teachers behave differently on the sealed set. Being nearer to a teacher is not being better.
2. **The 4-bit merge loses most of what the adapter learnt** (`CoreMergeLoss`, z-MSE to the old core):
   D's narrowed base 0.169 / 0.248 / 0.225 (data / tasks / facts); with its adapter unmerged 0.006 / 0.036 / 0.024;
   merged at 4 bits 0.046 / 0.095 / 0.099. A second round on the merged model recovers a quarter. The page keeps an
   adapter apart from the base (`Adapted`: two 4-bit planes) — the merge is not needed at all.
3. **But unmerged is not enough either** (E): at z-MSE 0.009 to the old core on data, the Russian sealed texts are
   still lost. What the readers keep from a core is fragile: any retraining moves the few Russian sealed texts the
   old core reads (even A, the full width retrained, lost them: 2.7).
4. **Narrowing costs Russian first.** Russian is a minority of the texts and costs more tokens a word; the neurons
   ranked by mean strength are the majority's. Weighting Russian (D: 70%) recovered the unseen families, not the
   sealed set.
5. **The level of the numbers matters more than their differences.** The sealed Russian set is read at 3–7% by
   every core: the system does not read it at all; the differences argued over are ten texts in a region where the
   method fails as a whole. What limits it is the corpus and the objective, not the size of the core.
6. **The protocol.** One run is no verdict (the collection moves 80→91 between seeds of one core); "not worse in
   each of a dozen noisy numbers" is failed by a core retrained from itself. A comparison needs a primary measure
   fixed in advance, seeds, and a margin (non-inferiority), with silent wrong answers as a hard limit.

The next step is not another compression of a copy: the core is trained FOR the reading (`specs/core-joint.md`).
