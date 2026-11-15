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
