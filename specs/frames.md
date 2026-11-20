# The frame: the formal model of a question, built by choices (2026-11-15)

The operator's idea (2026-11-14, night): the small model does not WRITE a program; it builds the formal model of
the problem by CHOOSING among the options the tools offer. The writing reader (`specs/direction.md` §6) showed why:
its wrong answers are values it made up ("products" for `groceries`) and forms put together wrong. A choice cannot
make up a value.

## 1. The frame

Lines in a fixed order, each one of the options the table and the question allow (`Frame.next`):
`table`, `answer` (rows, count, sum/avg/min/max C, share F C, change F C, cannot), `part` (a share's part),
`from`/`to` (a change's two periods or groups), `where` (a value of the column; a number the question writes or
one of the column's few own values; a month it names; a date it writes; another column), `group` (a column, or a
date's month, week, year), `keep` (a condition on the answer), `order`, `limit`, `end`. `Frame.query` compiles the
lines to a `Query` (exact); `Frame.of` reads a program back into lines (the training texts).

Law (`FrameSuite`): every `Everyday` question inside the language has a frame reachable by the options whose
program gives the gold's answer — the frame's language reaches the whole measure.

## 2. The reading

`FramePort` (a decode port): before each line only a newline; then the next line's options, each scored by the
model (the mean log-probability of its tokens), the best taken; after `end` only the stop. The model is the
language model itself — its knowledge of words — not heads trained from nothing.

## 3. The texts

`FrameData`: the teacher's natural pairs read back into frames (2884 of 2967), the grammar's pairs (1219), and
frames of the forms those lack (share, change, keep, week, column against column, cannot) drawn over the domains'
tables and said by templates (en, ru), then by the teacher in en/ru/uk/pl. `Everyday`'s tables are never among
them (`FrameTrain` refuses them).

## 4. The training and the measure

`FrameTrain`: LoRA rank 16 on every projection of the released Qwen3 0.6B. `FrameBench`: `Everyday`, scored as the
ceiling and the other readers are — right, asked, wrong — beside the heads (22/54 en, 9–10/54 ru), the writing
reader (27/20 en, 21/27 ru wrong) and the ceiling (43/54).

## 5. Results

### 5.1 The first night (2026-11-15): the released 0.6B, 6855 frames, one epoch

| reader | en right / asked / wrong | ru right / asked / wrong |
|---|---|---|
| the heads (the page) | 22 / 16 / 16 | 9 / 29 / 16 |
| the writing reader (LoRA, the program written) | 27 / 7 / 20 | 21 / 6 / 27 |
| the frame by choices | 30 / 0 / 24 | 30 / 0 / 24 |
| the frame, a doubt below a margin of 0.5 asked | 26 / 21 / 7 | 18 / 28 / 8 |
| the ceiling (35B) | 43 / 0 / 11 | 43 / 0 / 11 |

- Russian as English: the choice reads values the language does not change (no "products" for `groceries`).
- The margin is a doubt that works: at 0.5 the wrong answers halve below the heads' while the right stay above.
- The misses are of kinds: a plain sum taken for a share (the synthetic forms were 40% of the texts), an
  aggregate missed ("revenue by region" read as rows ordered by region), sum and count confused, `type = in` /
  `out` not read for income and spending, the row of the largest read as the largest value.
- Next: the synthetic forms bounded (700), two epochs; the same on the student (12 layers).

### 5.2 The student (12 layers, the phone's), the same texts, one epoch

| reader | en right / asked / wrong | ru right / asked / wrong |
|---|---|---|
| the frame by choices | 25 / 0 / 29 | 18 / 1 / 35 |
| the frame, a doubt below 0.5 asked | 18 / 25 / 11 | 14 / 26 / 14 |
| the frame, a doubt below 0.75 asked | 18 / 28 / 8 | 14 / 30 / 10 |

Fewer wrong than the heads in both languages at 0.75, more right in Russian, fewer in English; its loss at the
epoch's end 0.18 (the released model's 0.04): its language head reads states distilled toward layer 19, not
made for words — under-trained. Next: three epochs.

### 5.3 Round 2: the synthetic forms bounded to 700, two epochs (the released model)

en 24 / 0 / 30, ru 23 / 0 / 31 (with the doubt below 0.75: 16 / 33 / 5 and 14 / 35 / 5) — worse than the first
night. Question by question: aggregates read better ("revenue by region", "hours by role" now right), but every
refusal lost — the bound took away the "cannot" examples with the other synthetic forms, and the ten outside
questions were answered. Round 3: every refusal kept, the other synthetic forms bounded to 1000, one epoch (only
the mix changed from the first night); the student two epochs.

### 5.4 Round 3: every refusal kept, the other synthetic forms bounded to 1000, one epoch (the released model)

| the doubt's bar (the smallest margin) | en right / asked / wrong | ru right / asked / wrong |
|---|---|---|
| none | 33 / 1 / 20 | 24 / 2 / 28 |
| 0.2 | 31 / 11 / 12 | 22 / 21 / 11 |
| 0.3 | 30 / 15 / 9 | 19 / 25 / 10 |
| 0.5 | 23 / 26 / 5 | 17 / 30 / 7 |

At 0.2–0.3 it is above the heads (22 / 16 / 16, 9 / 29 / 16) in both languages on all three counts: more right,
fewer wrong, asked about as often. English is the best of the three rounds, Russian below the first night's (30):
one run each, and the spread between runs is as large as the differences — the protocol's seeds are owed.

Two readers' agreement as the doubt (the same frame from rounds 1 and 3, else asked): en 27 / 17 / 10, ru
21 / 22 / 11 — a doubt as good as the margin's at 0.3, not better.

### 5.5 The student, round 3's mix, two epochs

en 21 / 1 / 32, ru 20 / 2 / 32; at the doubt 0.3: 15 / 20 / 19 and 13 / 15 / 26 — worse than its first night
(25 / 0 / 29, 18 / 1 / 35) and its margins a weaker doubt (two epochs: surer of its wrong frames). The student
reads frames worse than the model it was distilled from: its states were made to be read by heads at layer 19, not
by the language head. Next for the phone: the student distilled toward the frame reader itself (its choices), or
the released model's first layers with the frame adapter.

### 5.6 Round 3's recipe, three seeds (the released model) — the night's result

| reader | en right / asked / wrong | ru right / asked / wrong |
|---|---|---|
| the heads (the page) | 22 / 16 / 16 | 9 / 29 / 16 |
| frame, seeds 1 / 2 / 3, no doubt | 33·29·28 / 0 / 20·25·26 | 24·24·24 / 0 / 28·30·30 |
| frame, the doubt 0.3, seeds 1 / 2 / 3 | 30·21·24 / — / 9·10·11 | 19·17·19 / — / 10·8·16 |
| two readers agree (seeds 1, 2), else asked | 25 / 23 / 6 | 19 / 31 / 4 |
| three readers unanimous, else asked | 22 / 27 / 5 | 16 / 34 / 4 |
| the ceiling (35B) | 43 / 0 / 11 | 43 / 0 / 11 |

- Beyond the spread: Russian right 17–24 against the heads' 9; wrong with any doubt 4–11 against 16 in both
  languages. English right without a doubt 28–33 against 22; with the margin's doubt within the spread.
- The best doubt is agreement: two readers trained with different seeds, the same frame or a question — en 25 right
  and 6 wrong, ru 19 right and 4 wrong: more right than the heads in both languages and a quarter of their wrong
  answers. Its price: two readings of a question.
- The frame reader is the way the model goes from here (`specs/direction.md` §6 answered): choosing among the
  tools' options beats writing the program (27/20, 21/27) and the heads (22/16, 9/16).
- What is left: the student (12 layers) reads frames worse (§5.5) — the phone's size needs its own step; the misses
  of kinds (§5.1) are data's: `type = in/out` for income and spending, sum against count, the row of the largest.

### 5.7 The size curve: round 3's recipe at 0.6B / 1.7B / 4B, three seeds (2026-11-16/17; technology.md §12.3)

The same texts, LoRA r16, lr 2e-4, one epoch; the released Qwen3 at three sizes, 4-bit. Measures: `Everyday` and the
calibration set (1004 questions never trained on, guarantees.md §8). Runs, records and scripts:
`okay-desk-private/size/` (results.md).

| model | calibration right (wrong), seeds 1 / 2 / 3 | Everyday en right/wrong, seeds | ru |
|---|---|---|---|
| 0.6B | 809 (191) / 794 (209) / 825 (167) | 33/20, 29/25, 28/26 | 24/28, 24/30, 24/30 |
| 1.7B | 914 (81) / 871 (110) / 894 (102) | 40/14, 39/15, 35/15 | 38/16, 36/16, 35/15 |
| 4B | 895 (109) / 851 (149) / 917 (86) | 40/14, 36/17, 38/16 | 32/22, 31/23, 39/15 |
| 35B ceiling | — | 43/11 | 43/11 |

Two readers (seeds 1, 2) agreeing, the conformal bar for ε = 2% set on the calibration (`FrameCalibrate`):

| model | calibration right / asked / wrong | Everyday (108) right / asked / wrong — the risk |
|---|---|---|
| 0.6B | 653 / 332 / 19 | 40 / 63 / 5 — 0.046 |
| 1.7B | 842 / 139 / 19 | 64 / 33 / 11 — 0.102 |
| 4B | 778 / 207 / 19 | 59 / 29 / 20 — 0.185 |

- **0.6B → 1.7B: capacity.** Beyond the seed spread on both measures: the calibration's wrong halved, Everyday +8
  en, +12 ru; Polish and Ukrainian (least data) gain most. The 0.6B is held by its size, not only by the data.
- **1.7B → 4B: a plateau** for this recipe (calibration mean 893 against 888; 4B's spread larger, 851–917).
  The recipe was set for the 0.6B — a tuned 4B is untried.
- **The bound does not carry to `Everyday`.** At the same 19 wrong on the calibration the 1.7B answers 842 of 1004
  (0.6B 653) — but on `Everyday` the risk is 5× ε (4B 9×): `Everyday` is not drawn as the calibration is, and a
  larger model's two readers agree more often on the SAME wrong frame. The guarantee needs calibration questions
  drawn as people's (technology §12.1) and a second opinion of another kind (§12.6), more with size, not less.
- Misses shared by every size (`change` "growth by dish", a group taken as a filter) are the data's and the
  language's, not the size's.
- For the phone: the 1.7B at 4 bits is ≈ 1 GB — whether WebGL2 on an iPhone carries it is P2/P3's question.

### 5.8 Round 4: the group said without its sum (2026-11-17)

Everyday's largest class of errors under round 3 (9 of 25 wrong): «Hours by role», «Spending by category, largest
first», «Who worked the most hours?» read as `answer: rows`. Not greedy reading — one line ahead changed none of them
(theory.md, negative) — but the data: the teacher's pairs had such groups only with "total"/"sum" said, and "which
X has the most" only over single rows. `FrameData.groups` (`FrameData --groups`, 800 texts, en + ru templates over
the domains' tables, never Everyday's): a measure by a category, by it ordered, the most / the least of a category.
Round 3's mix + these, one epoch, three seeds; Everyday as the page reads (link 2, guess 2, the dialogue at 0.3,
pruned), right / asked / wrong, en + ru (108):

| | seed 1 | seed 2 | seed 3 | mean right | the group class (7 questions × 2) |
|---|---|---|---|---|---|
| round 3 | 72 / 11 / 25 | 79 / 12 / 17 | 64 / 6 / 38 | 71.7 | 2, 9, 5 of 14 |
| round 4 | 83 / 5 / 20 | 74 / 7 / 27 | 82 / 4 / 22 | 79.7 | 13, 14, 13 of 14 |

The class is closed on every seed; the mean +8 right, −3.7 wrong, fewer asked. New errors where the forms reached
too far: «Average units per sale» (per a row, not a group) read as a group; «Who got less than 3 in math?» as the
least sum. The next data: "per <the table's row>" and "less than N" against them.

The phone's reader (round 4 seed 1 merged, `~/.cache/okay-desk/frame-0.6b-r4`): 85 / 6 / 17 against round 3
merged 76 / 10 / 22. (A first measure of the two merged models gave the same numbers to the last digit: mlx-scala's
aligned copy of an unaligned file was named by its name and size — two merged models' `model.safetensors`, one size,
shared one copy. Fixed in mlx-scala 0.1.0, republished: the copy named by the file's path and time too.)

### 5.9 Round 5: the counterweight (2026-11-17)

Round 4's mix + `FrameData --counter` (500: "average X per <a row>" is no group; "which … have X less/more than N"
is a condition on rows). Three seeds, as §5.8:

| | seed 1 | seed 2 | seed 3 | mean right | the targets (sales-17, marks-40 × 2) | the group class |
|---|---|---|---|---|---|---|
| round 4 | 83 / 5 / 20 | 74 / 7 / 27 | 82 / 4 / 22 | 79.7 | 1, 2, 2 of 4 | 13, 14, 13 of 14 |
| round 5 | 82 / 3 / 23 | 92 / 5 / 11 | 76 / 3 / 29 | 83.3 | 2, 4, 4 of 4 | 11, 13, 7 of 14 |

Better on the mean and on its targets, but the seeds spread wider (76–92): one seed gave the group class back. A
counterweight pulls against the form it weighs; the seeds say how hard. Seed 2 is the best reader measured so far.

### 5.10 Round 6: two more forms — NEGATIVE (2026-11-17)

Round 5's mix + `FrameData --spent` (454: «Сколько я потратил на …» as a sum of money; a group under another
column's condition, «Revenue by product in the South»). Seeds: 77 / 4 / 27, 80 / 5 / 23, 78 / 6 / 24 — mean right
78.3 against round 5's 83.3. Its own targets were met (5, 5, 6 of 6), the group class lost again (8, 8, 13 of 14).
Each form added by templates takes from the forms beside it: a 0.6B reader trained one epoch on a mix whose share of
each form we set by hand. The next step is not another form but the mix itself — the forms weighed by the errors
they fix on questions never trained on, or a second epoch — and the operator's marked questions as the measure.

### 5.11 The phone's reader chosen on questions never trained on (2026-11-18)

Choosing the seed by Everyday chooses by the test. The teacher's 1004 natural pairs (calib/pairs.jsonl, en/ru/uk/pl,
other tables, never trained) as the page reads, right / asked / wrong:

| reader | 1004 never trained | Everyday |
|---|---|---|
| round 3 seed 1 | 770 / 68 / 166 | 72 / 11 / 25 |
| round 4 seed 1 | 766 / 57 / 181 | 83 / 5 / 20 |
| round 4 seed 2 | 660 / 94 / 250 | 74 / 7 / 27 |
| round 4 seed 3 | 799 / 73 / 132 | 82 / 4 / 22 |
| round 5 seed 1 | 782 / 129 / **93** | 82 / 3 / 23 |
| round 5 seed 2 | 768 / 96 / 140 | 92 / 5 / 11 |
| round 5 seed 3 | 765 / 105 / 134 | 76 / 3 / 29 |
| **round 5 seed 1, merged (the phone's)** | **810 / 101 / 93** | **91 / 3 / 14** |

Everyday's best seed is not the held-out best: 108 questions over six tables are too few to choose by. Round 5
seed 1 has the fewest wrong where nothing was trained — wrong is the costly outcome, asked is a dialogue — and the
round-5 seeds' wrong (93, 140, 134) are all below rounds 3–4's. Merged into the 4-bit weights it reads better still
(the requantization moved it; measured, not assumed). Published: model-frames-merged = frame-0.6b-r5; the page's
desktop pair = round 5 seed 1 + round 4 seed 3.

### 5.12 Three ways of training, measured with the tools fixed (2026-11-18/19)

The tools fixed first (guarantees.md §8.2: text values kept, dates read) — which moved every reader, so round 5's
seeds were measured again with them (base5). The 1004 never trained, with the dialogue at 0.3, right / asked / wrong:

| | seed 1 | seed 2 | seed 3 | Σ right | Σ wrong |
|---|---|---|---|---|---|
| round 5 (base5) | 847 / 74 / 83 | 856 / 38 / 110 | 878 / 40 / 86 | 2581 | 279 |
| **the counterexample loop** (l1) | 827 / 66 / 111 | 887 / 38 / 79 | 869 / 40 / 95 | 2583 | 285 |
| **trained as tries** (t8, lr 6e-4) | 748 / 82 / 174 | 783 / 30 / 191 | 884 / 40 / 80 | 2415 | 445 |
| **the listwise loss** (lw, lr 6e-4) | 52 / 766 / 186 | — | — | | |

- **The counterexample loop — a draw.** A fresh pool of the teacher's pairs (seeds 3001–4000, 2538 questions)
  read by round 5 seed 1; its 564 misses (460 with a gold frame, `FrameData --failures`) added to round 5's mix.
  Measured before the tools were fixed it looked like a gain; after, the same: most of those misses were the tools'
  (an unoffered or pruned line), which the fixed tools no longer make. The loop's pool was read with the old tools —
  a next round reads with the fixed ones.
- **Tries — 6× faster a step, not yet as good.** mlx-scala `Lora.stepTree`: 8 questions over one table in one pass,
  the rule and the table once (0.61 s against 3.72 s for 8 examples one at a time). At lr 6e-4 (8× fewer steps) the
  seeds spread 80–191 wrong: the step too large for some orders. Tuned next: 2e-4, 3e-4 (tree2.sh).
- **The listwise loss — collapsed.** Trained only on the meant option against 7 offered (mlx-scala
  `Lora.stepChoices`, the law against single passes within 0.8%): its loss fell (0.125) but the reader asked about
  766 of 1004 — every line's options near a tie. A loss on differences alone leaves the tokens' own probabilities
  free; they flattened. To try: the listwise term beside the generative one, not instead of it.

### 5.13 Tries at lr 2e-4: the best reader — as an adapter, not merged at 4 bits (2026-11-19/20)

The step tuned (tree2): the 1004 never trained, the dialogue at 0.3, right / asked / wrong:

| | seed 1 | seed 2 | seed 3 | Σ wrong | Everyday right (of 108) |
|---|---|---|---|---|---|
| round 5, one example a step (base5) | 847 / 74 / 83 | 856 / 38 / 110 | 878 / 40 / 86 | 279 | 82, 92, 76 |
| **tries of 8, lr 2e-4** | 921 / 35 / 48 | 919 / 30 / 55 | 925 / 28 / 51 | **154** | 95, 92, 91 |
| tries of 8, lr 3e-4 | 929 / 32 / 43 | 891 / 48 / 65 | 908 / 29 / 67 | 175 | 88, 87, 88 |

Half the wrong, three seeds alike, and 1.8× faster an epoch (18 min against 32). Why (not isolated): 8 examples a
step average the gradient and take 8× fewer steps — less noise, less forgetting; the trie makes such batches cheap.

MERGED AT 4 BITS IT BREAKS: 3e-4 seed 1 merged (LoraMerge, 4 bits) read 406 / 2 / 596 of the 1004 and 18 of 108
Everyday; 2e-4 seed 1 merged at 4 bits 7 of 108; the same 3e-4 seed merged at 8 bits 85 of 108 (no dialogue; round
5 merged at 4 bits 84). The likely mechanism: a merged weight is W + Δ rounded to 16 levels again; round 5's Δ
(8× more steps) were large enough to survive the rounding, the tries' are below half a step — rounded back, or
pushed a whole step: noise larger than Δ. A reader for the phone from tries needs its adapter beside the 4-bit
base (`Adapted`), not merged — the iPhone's tab once died that way, but from one GPU task of minutes (phone.md
§4.1), since split into parts; to measure on the phone.

## 6. The frame reader as a program over effects (2026-11-15)

`FrameReading.read` (core) is a program over `Read` (the model) and `Person` (the one asked): one program for the
JVM's handler (mlx-scala, `MlxReading`) and the page's (WebGPU/WebGL2, `OnQwen`), the person a handler too — the
page's buttons, the measure's gold frame (`Person.byFrame`), a test's list. A line's options are weighed by ONE
operation, `Read.Score(context, options)`: the handler weighs them as it can — mlx-scala from a checkpoint of the
context's state (kept between the lines), the page by next-token logits for now; a batched weighing (the weights
read once for all the options: decoding is bound by memory) is the handler's to add, the program does not change.
Laws: `FrameReadingSuite` (a model by numbers: the choices, the doubt, the questions, none of them). Checked: the
same frames as the bench's former reader on `Everyday`. `Frame.next` no longer offers a line already taken.

### 6.1 The packed weighing (2026-11-15)

okay-llm's `Qwen3Compute.score` (republished in 0.1.0): a line's options as segments after the context's cache,
one forward — the linears once for all, the rotation and the attention by segment. Law (okay-llm `CacheSuite`):
equal to each option scored token by token (to the last digit). The page's handler (`OnQwen`) and the JVM's
(`QwenReading` on `OnMlx`) answer `Read.Score` with it. First measure (`FrameSpeed`, the frame adapter merged into
the weights by `FrameMerge`, 6 readings, the GPU shared with the calibration): 2.1× faster than one option a
forward, the same frames 6 of 6. The engine's absolute speed is far below mlx-scala's (8.6 s a question against
1.5 s): every option's row reads the whole vocabulary's logits back to the CPU — the next step is the
log-probability on the device (one number a row).

### 6.2 Two readers on the page (P4, 2026-11-15)

The frame reader's adapters (rounds 3, seeds 1 and 2) beside the one base on the device (`Adapted` with several
adapters, `current` the one in use; the prefix cache let go at a switch). A question read by each alone: the same
frame is the answer; where the frames part, the person is asked at that line with both readers' lines, and the
first reads on from the chosen one (`FrameReading.read(start = …)`, its own doubt then asking as one reader does).
In Chromium: "the sum of orders by city" and "the three largest orders" answered at once, both right; "how many
unpaid" and "how many orders from Lviv" asked — the latter was a silent wrong answer with one reader. Two readings a
question: 6–8 s on a desktop's WebGPU.

### 6.3 Values linked across languages, as facts (2026-11-16)

A question names a table's value in its own language: «Сколько заказов из Львова?» of a table with `city = Lviv`.
The reader weighed «where: customer = dee» (-0.05) far above «where: city = Lviv» (-3.24): a 0.6B does not know that
«Львов» is Lviv, and it guesses a value. The lesson of okay-inlay's units (specs/domains.md §5) applies: what the
text names exactly is a FACT, not a reading; the model decides only its role.

- `Links.of(table, question)`: the text values a question names — said as they are ("Biedronka", "Olga's"), by their
  letters in Latin («Анны» "anny" for "Anna", «Ольги» for "Olga"), or by a common word of everyday data in Russian,
  Ukrainian or Polish («продукты» groceries, «Львов» Lviv, «наличными» cash, «доход» the type `in`). Three values
  of a column or more are a list the question describes, not values it means.
- `FrameReading.read(link, guess)`: a line naming a linked value not yet said weighs `link` more; a line naming a
  text value no word of the question names — a guess — weighs `guess` less. The page: 2 and 2.

Measured on `Everyday` (round 3, one reader, 54 questions each language): links over the gold frames 34 of 36 right,
34 of the gold's 38 text values found. Right: en 33 → 36, ru 24 → 26 at link 2; the same at 4 and 8; guess 2 changes
no outcome there and removes the guessed values from the wrong frames. The lexicon was written seeing `Everyday`, so
the check that counts is the teacher's 1004 natural questions (never trained, other tables): link 2 en 243 → 246,
ru 214 → 216, uk 176 → 177, pl 176 → 176, nothing lost. On the page «из Львова» now offers `city = Lviv` first.
