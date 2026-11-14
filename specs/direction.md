# The direction: tools and the model's reading grow together, measured by the ceiling (2026-11-14)

Decided with the operator after the ceiling measure. The idea stands: exact tools solve (the relational engine,
Datalog, the solvers); the model builds the formal model of the problem from its description. Both sides grow:
the TOOLS (what can be said and computed) and the MODEL (recognizing a problem and expressing it in the tools'
language).

## 1. The ceiling: the measure that separates the two

`CeilingBench`: the `Everyday` questions read by the teacher (Qwen3.6-35B-A3B) into the same query language, run by
the same exact engine, scored as `EverydayBench` scores the phone's model.

| reader | en right / wrong | ru right / wrong |
|---|---|---|
| the teacher (35B), first run | 41 / 7 | 40 / 8 |
| the phone's model (student + readers) | 23 / 17 (8 asked) | 10 / 18 (20 asked) |

- What the TEACHER misses is what the TOOLS miss (the language, the schema it is shown, the measure). The ceiling
  rises only by work on the tools.
- The GAP between the teacher and the phone's model is what the MODEL misses. It closes only by work on the model.
- Neither number is a training set: `Everyday` is never trained on.

## 2. The tools: what the teacher's misses ask for (first run)

| miss | cause | the tool's change |
|---|---|---|
| "restaurant" for `restaurants`; spend over all rows where `type` = out | the schema shows names, not values | the schema with each text column's values (as `Teacher.described`) |
| a month ("in February") | no month form in `Query` JSON (the calculus has one, compiled to a range) | `op: "month"`, value `yyyy-mm`, in `Query` and the prompt |
| a share of a total | not in the language | `share`: an aggregate over a group as a part of its total |
| a change between periods; per month | not in the language | `by_month` grouping of a date; `change` between two periods |
| two columns compared (below reorder level) | a condition has a value, not a column | a condition against another column |
| "who worked night shifts": rows of employees, the gold counts by employee | the measure is stricter than the question | the measure compares the answer's set of names where the question asks "who" |

Each change: its constructor in `Query` (and the calculus where the phone's model reads it), its exact run, its
words (`Understood`), its laws; then the ceiling again.

## 3. The model: closing the gap

The teacher labels, the phone's model learns from its labels:
- The teacher WRITES THE PROGRAM (as in the ceiling) for natural questions over many tables (`Domains`, real open
  tables) in en/ru/uk/pl; kept when the program runs and a second reading (the judge, without the program) agrees.
- The readers (and later the adapter) trained on those pairs; measured on `Everyday` beside the ceiling: the gap
  in right answers, and wrong answers never above the student's present ones.
- The night of 2026-11-14 is the first step (the teacher's rewrites of grammar points, judged by read-back).

## 4. The order

1. The schema with values in the prompt; `month` in `Query`. Ceiling again.
2. The first night's pairs measured (the gap).
3. `share`, `by_month` and `change`, column against column. Ceiling again.
4. The teacher as labeller of natural questions over `Domains` (§3), the readers trained; the gap again.
5. The same for logic: the ceiling on the sealed logic set (the teacher writes the statement), the gap.

## 5. Results (2026-11-14, evening)

The final `Everyday` set: 54 questions a language (48 inside the language, 6 outside: a cause or a forecast); the
seven former outside questions inside since the language grew (§2).

| reader | en right / asked / wrong | ru right / asked / wrong |
|---|---|---|
| the teacher (35B), the language grown, `in` and a group's date part named as grouped | 43 / 0 / 11 | 43 / 0 / 11 |
| the phone's model, the page's skill | 22 / 16 / 16 | 9 / 29 / 16 |
| the phone's model, trained with 2967 of the teacher's natural pairs (rewrites of grammar points over `Domains`, judged by read-back) | 22 / 15 / 17 | 10 / 26 / 18 |

- **The tools.** The share, the change between months, the two columns and the having answer right for the teacher
  where they are read as meant. Its misses now: the six outside questions answered by an approximation (a
  forecast or a cause read as a sum: the prompt's "do not approximate" is not enough), "spend" and "payment" without
  `type = out`, a change shown as two monthly totals where the gold asks the difference, a balance's change summed.
- **The model.** The teacher's natural pairs do not move the phone's readers beyond noise (+1 right in Russian, +1–2
  wrong). The same as the domains (`specs/everyday.md`), the adapter and the gates (`specs/core-joint.md`): more
  and better texts through the same heads over the same frozen states move nothing. The readers' design is the
  limit, not their data. The next step on the model's side is a reader that WRITES the program (constrained by the
  grammar), distilled from the teacher's own programs — measured against the heads by the same protocol.

## 6. The experiment: the reader that writes (from 2026-11-14)

The question: does a small model that WRITES the program close the gap the heads do not?
- **The model:** Qwen3 0.6B as released (28 layers, 4-bit), LoRA rank 16 on every projection (`Train`).
- **The texts:** the teacher's 2967 natural pairs (`Domains` tables, en/ru/uk/pl) and 1500 of the grammar's pairs
  (`Pairs.many`), each as the page's prompt (`Prompt.system`: the schema with values, the language's forms) and
  the program as the answer. `Everyday` is never trained on.
- **The reading:** greedy, free and masked by the grammar (`Grammar`, the port `DeskBench` uses); the program run
  by the exact engine.
- **The measure:** `GenBench` — `Everyday` scored as `CeilingBench` and `EverydayBench` score it (right, asked,
  wrong), beside the ceiling (43/54) and the heads (22/54 en, 9–10/54 ru).
- **Then, by the result:** writing wins → the teacher labels many more natural questions (it writes the programs
  itself), the reader on the student (12 layers) to fit the phone, sampling with agreement by execution as the
  doubt. Writing loses → the model's size is the limit; the student distilled from a larger one.
