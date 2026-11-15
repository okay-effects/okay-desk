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
