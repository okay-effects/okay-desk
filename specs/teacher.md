# The teacher as the source of language, the engine as the judge (the operator, 2026-11-07)

Status: building. The operator's point 3: the dictionary of Russian forms is a crutch; a large model (Qwen3.6-35B,
local) can say a point of the space the way people actually ask — any language, the values in their own words ("не
оплачено", "просрочено") — while the judge stays exact. Pairs = the space × the teacher; the program is known before
the question exists, so a pair is accepted only when the question is shown to MEAN that program.

## 1. A pair

1. **A schema** with realistic data (`Teacher.schema`: invoices with statuses paid / unpaid / overdue, customers
   with cities, products with categories, a dependency graph) — the seed keeps it reproducible; the pair is stored as
   (seed, language, question, program) and its tables made again from the seed.
2. **A point** of the space (`Space.random`) and its grammar sentence (`Phrase.say`, English) — the skeleton.
3. **The teacher's rewrites**: k ways a person would ask it in the language asked (en, ru, uk), short or chatty,
   names and values in that language's own words where natural; nothing added, nothing left out.
4. **The judge** reads each rewrite WITHOUT the program: it is given the schema (tables, columns, the text columns'
   values) and answers the point as choices of the space — the kind (rows, absent, closure), the table, the join,
   the conditions (column, op, a value OF THE COLUMN — "не оплачено" must become `unpaid`), the group, the aggregate,
   the order and its direction, the limit. The engine compares that reading with the program constructor by
   constructor; numbers must be equal, values exactly the column's. Accepted only when every constructor agrees;
   the reason of every rejection is counted.

## 2. The judge, judged first

Before its verdicts are trusted the judge reads the grammar skeletons themselves, whose programs are known: its
agreement there is its accuracy on plain wording. A rewrite rejected is lost data, never a wrong pair; a rewrite
accepted wrongly is a wrong pair — so the judge's accuracy on skeletons bounds the pairs' error.

## 3. Measures

- the judge on skeletons (agreement); the share of rewrites accepted, and the rejections by constructor;
- the readers trained on the space × phrases against the space × phrases + the teacher's pairs: on held-out
  TEACHER pairs (natural wording — the honest test of language), by constructor, and on the sample's 13 en / ru.

## 4. Measured (2026-11-07)

**The judge, judged** on 40 grammar skeletons whose programs are known (temperature 0, the rule naming counts and
"the largest X among T" as aggregates): 36 agree. Its misses there are false rejections (a pair lost), not false
acceptances.

**The pairs**: 120 points (seeds 1–120), the languages in turn (ru, en, uk), three rewrites each: 242 of 357
accepted in 52 minutes; rejected by kind 31, aggregate 22, group 18, conditions 16, table 11, order 7, direction 6,
limit 3, node 1 (`bench/data/teacher-pairs.jsonl`). Checked by hand, 24 at random: 21 exact and natural ("Какая общая
сумма всех счетов?", "Клиенты без счетов", "Покажи перші 5 рахунків…"); one contradicts itself and was accepted
("Кто из наших клиентов дольше всех с нами? Покажи того, у кого since максимальный" — "the longest" is the smallest
since; the judge read the second sentence); two loose (an "orders" word for invoices; names left in quotes in
English). About one wrong pair in 24.

**The readers** (cut19, layer 19; `TeacherBench`, trained in memory, the page's skill file untouched): A on the
space × the phrases (1,500 pairs), B on the same plus the teacher's 192 training pairs twice (1,884). Measured on 50
held-out teacher pairs (seeds % 5 == 0) and the sample:

| | whole (by constructor) | table | group | aggregate | held-out answered: ru / en / uk | sample en / ru |
|---|---|---|---|---|---|---|
| A | 30% | 86% | 74% | 57% | 4/16, 10/15, 3/19 | 12/13, 12/13 |
| B | 32% | 90% | 83% | 60% | 4/16, 10/15, 3/19 | 11/13, 12/13 |

What it says. The first number is the ceiling it shows: on natural wording the readers answer 63% in English, 25% in
Russian, 16% in Ukrainian — the grammar's pairs and the sample's 13 (12/13) did not show it. 192 teacher pairs move
the constructors a little (table, group, aggregate) and not the answers; at 50 held-out pairs that is within noise.
The way is the same, at scale: thousands of teacher pairs (≈ 13 s an accepted pair here, so a night for 2,000), the
held-out teacher pairs as the standing test of language — they replace the dictionary as the measure.

## 5. At scale (2026-11-07/08)

**The pairs**: seeds 121–861 more (resumable: `teacher-pairs.jsonl.done`; stopped at 861 of 1,100 on purpose, the
GPU shared with another run), 1,945 in all — en 700, ru 631, uk 614; 78% of the rewrites accepted. Held out by
seed % 5 == 0: 388 (en 137, ru 120, uk 131); trained on 1,557.

**The readers** (cut19, layer 19, `TeacherBench`; A trained 5,305 s, B 5,764 s under the contention):

| on 388 held-out teacher pairs | A: the space × the phrases (1,500) | B: + the teacher's 1,557, twice (4,614) |
|---|---|---|
| right, the whole point | 154 (39.7%) | 127 (32.7%) |
| abstained ("not sure: A or B") | 9 | 120 |
| wrong when answered | 225 (58.0%) | 141 (36.3%) |
| answered as the program: en / ru / uk | 83/137, 54/120, 34/131 | 85/137, 45/120, 11/131 |
| by constructor, where answered: table, where, group, agg, order | 79, 73, 75, 67, 74% | 86, 77, 81, 69, 75% |
| the sample: en / ru | 12/13, 12/13 | 12/13, 11/13 |

What it says. The teacher's pairs did not lift the language; they made the readers CAREFUL: 84 fewer wrong answers,
111 more abstentions, 27 fewer right. Where B answers, every constructor is better (the table +7, the group +6). The
abstentions gather in Ukrainian (34 → 11 answered as the program): the two best tables fall within the margin
(`Readers.abstainMargin` 0.3) — trained on the teacher's words in three languages, the table's readers are less
peaked, and the margin turns that into "not sure". Two readings of it, to separate next: the margin is now too wide
for the less peaked readers (measure B with the margin swept — the right/abstained/wrong frontier), or the Ukrainian
table names are genuinely not read (measure the table pointer alone on uk). As a test of language the 388 stand:
English 61%, Russian 45%, Ukrainian 26% answered as the program with the grammar's readers.
