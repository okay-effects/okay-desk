# Everyday tables: the measure of usefulness, and where the page stands (2026-11-14)

`specs/technology.md` §9 made data analysis the main ability and asked for a measure of usefulness first. Built:
`core/tasks/Everyday` — six tables like a person's own (expenses, a shop's sales, stock, shifts, marks, a bank
statement; 16–72 rows, drawn by a fixed seed) and 48 questions in English and Russian, 39 with gold programs and 9
outside the query language (a share, a change between months, two columns compared, a week) where the right
outcome is to ask; laws `EverydaySuite`. `bench/EverydayBench` reads them as the page reads a data question —
the calculus, its holes filled by the data, the readers' skill, the model's tokens, the page's margin ln 3 — over
the page's core (the 12-layer student, the teacher's skill file).

## The page today (each question with its own table open)

| | right | asked | wrong, shown |
|---|---|---|---|
| English (48) | 21 | 9 | **18** |
| Russian (48) | 10 | 23 | **15** |

Without the margin: en 18 / 0 / 30, ru 5 / 3 / 40. With all six tables open at once: en 15 / 0 / 33, ru 2 / 3 / 43
(no margin). Logs: `okay-desk-private/everyday-{own,own-margin,all}.txt`.

**What fails** (from the wrong readings): money and quantity words do not reach the right column and function —
"how much did I spend", «сколько я потратил» → count(*) instead of sum(amount); "revenue" → sum(unit_price);
months ("in March", «в феврале») are not read into a date range; "the biggest", "the most" often lose the order and
the limit; a whole table returned when the reading gives up; the questions outside the language are answered
(count(*) again) instead of asked. Russian is far weaker than English.

**Why** (the same lesson as the logic tasks): the data readers were taught on the sample's schema (orders,
customers, edges) and the GDP series; their pairs are one hand's templates over few schemas. A person's tables have
other columns and other words.

## What it means for the page

A third of the answers to everyday questions are shown and WRONG. That breaks the rule "a number the engine did not
produce is never shown" in spirit: the engine computed it, but for the wrong program. The data ability needs, in
this order:
1. a gate that asks rather than answers when the reading is not sure — the margin halves the wrong answers; the
   rest are confident misreadings (count instead of sum);
2. a corpus over MANY schemas and everyday words — `Space` over varied schemas (these six, and more drawn), the
   teacher's rewrites in four languages, months and money words, the outside questions as "ask";
3. the readers retrained on it, and the core's adapter through their heads (`JointTrain`, as for the logic tasks);
4. the same measure again — `EverydayBench` — and in the page on a phone.

## The readers retrained, without and with the domains (2026-11-14)

The skill trained on this core's own states (`EverydayBench DESK_TRAIN=1500`; 20 min each), the same measure:

| data readers | en right / asked / wrong | ru right / asked / wrong |
|---|---|---|
| the page's (the teacher's skill file) | 21 / 9 / 18 | 10 / 23 / 15 |
| retrained on the page's core, the old pairs | 22 / 8 / 18 | 10 / 18 / 20 |
| + half the pairs over `Domains` (14 schemas) | 23 / 8 / 17 | 10 / 20 / 18 |

No change beyond noise. The template pairs over new schemas teach new column NAMES, not the words people use for
them: the grammar says "the total amount of donations", a person says "how much did I spend" — no pair ties "spend"
to a money column. And 15 of the 48 questions cannot be right today whatever the readers learn: 9 outside the
language (the right outcome is to ask; the reading answers) and ~6 about months (the calculus has no month).
Next, in this order: the month in the calculus; the teacher's natural pairs over the domains (its "pairs" mode with
`Domains` schemas, judged by read-back); "ask" taught for what the language cannot say.

## A month in the calculus (2026-11-14)

The `month` op on a date column (`Months`, the grammar, `Den`, `Space`, the grammar's and the language files'
phrases in en/ru/uk/pl, the exact source reading month names; laws `MonthsSuite`, the calculus laws with months
drawn). `EverydayBench`, the page's path: en 23 right / 9 asked / 16 wrong (was 21 / 9 / 18), ru 10 / 23 / 15
(unchanged). Every month question now reads its range right; what is still wrong in them is another part — "spend"
read as a count, "restaurant" against the value "restaurants", «на севере» against North — the words people use,
which the teacher's natural pairs are for. Two questions outside the language (a change between two months) are now
asked, as they should be.

## The teacher's natural data questions over the domains (2026-11-14, queued)

`Teacher pairs` with `TEACH_DOMAINS=1`, en/ru/uk/pl, seeds 1–300 (`okay-desk-private/data-teacher/`), after the logic
cycle frees the GPU; ~130 s a point (three rewrites, each judged by read-back). The probe on seeds 1–2: 6 of 9 kept.
A known weakness of the judge (the same model): it accepted «сколько литров топлива больше 35 на каждой станции» as
a count of rows, where a person more likely means the sum of litres — a lenient judge lets some wrong labels in. To
check later: a second, independent labelling, or the question and the program compared on other data.
