# The loop: what the model understood, said; a person's correction, learnt (2026-11-14)

Decided with the operator after the gate experiments (`specs/core-joint.md` §8–§10) and the everyday measure
(`specs/everyday.md`). Three changes of principle, then the order of work.

## 1. Why the present way is not enough

- **The texts are ours, not people's.** Every training text is drawn by a generator or rewritten from one by the
  teacher: one hand. Every failure measured is on another hand's wording (the sealed set, the teacher's test, the
  everyday tables).
- **A wrong reading cannot be told from a right one by the reader's own signs.** Margins, novelty, a second reader,
  a learnt combination, the solver's tightness: none separates them on unfamiliar wording (§8–§10 of core-joint).
- **The query language cannot say common questions.** "In March", "what share", "how did it change by month",
  "below its reorder level" (two columns), a sum of two columns: nine of the 48 everyday questions are outside it,
  and the calculus has no month.

## 2. The three changes

### 2.1 The understanding said, a correction one tap away

Every answer is shown with HOW THE QUESTION WAS UNDERSTOOD, in plain words made by the exact side from the program
(not by the model): «Сумма по столбцу amount, где category = groceries, за март: 412,50». Where the reading had a
doubt, its options are buttons. A silent misreading becomes a visible one; the person's tap is a verified pair.
- Component: `Understood` — a program (data) or a statement (logic) → words in en/ru/uk/pl, exact (`Said` is its
  first piece for statements); laws: every program of the space said and read back by the data alone to itself.
- On the page: under every answer; a tap on a wrong part opens that hole's options (the dialogue, `Dialogue.read`).

### 2.2 The loop: corrections become training

A correction or a chosen option is a pair (text → program) verified by the person. Kept on the device (the lessons,
`learn.Lessons`, already); exported with consent as JSON lines; the cycle (`docs/toolkit.md`) takes them as a task
source beside the generator and the teacher; a new adapter and heads ship as files. The model improves where people
use it.

### 2.3 The query language for everyday questions

The calculus and `Query` grow: a month or a period ("in March", "in Q1", "last week" against the data's dates); a
share ("what part of"); a change between periods; two columns compared; a column computed (price × units). Each
with its constructor in the grammar, its denotation (`Den`), its words, its laws (a term and back, the oracle reaches
it). The everyday questions outside the language become inside; "ask" stays for what is still not.

## 3. The training texts: what there is, and what to do

### 3.1 What there is (2026-11-14)

| ability | training texts | held out for measure |
|---|---|---|
| logic (Assign) | `tasks/Drawn`: ~45 families by template (houses/Einstein grids, comparisons 0–24, matchings, arrivals, cinema, concert, floors, garden, offices, queue, ranking, ships, swim, train), en+ru; Train 4000 tasks; the teacher's rewrites of Train tasks: 437 + the second batch (running), en/ru/uk/pl, kept by read-back | Validate, Test (same families), Unseen (7 families), Sealed (other families and wordings, never looked at), the teacher's test (60 tasks × 4 languages), the collection (233 hand-written tasks), ZebraLogic (outside) |
| data (Query) | `Pairs.fromSpace`: generic tables (products, employees… with name/category/price/qty), points of the space said by `Phrase` en/ru; legacy templates; teacher pairs of an earlier run (242, invoices/customers); `Domains` (14 everyday schemas, new) | the sample (13 questions), the GDP questions (`RealBench`), `Everyday` (6 tables, 48 questions) |
| facts | `FactReaderBench` forms (train/validate/test templates, en/ru) | its test forms and the audit sentences |

### 3.2 What is wrong with them

1. One hand: templates and their rewrites; the phrasing a person uses is rarely there.
2. Few schemas and generic column names for data; no money words, no months.
3. Two languages by template; Ukrainian and Polish only through the teacher.
4. Clean text only: no typos, no colloquial forms, no follow-ups ("а в феврале?"), no voice-like input.
5. No "ask" examples: the questions the language cannot say are never taught as such.

### 3.3 What to add, in order of expected gain

1. **People's corrections** (§2.2) — the true distribution; small at first, growing with use.
2. **Human question sets with formal labels**, checked by execution or proof, licences checked first:
   text-to-SQL (Spider, BIRD; PAUQ for Russian) → `Query`; math word problems with answers (GSM8K and the like) →
   `Calculate`; natural-language logic with formal forms (FOLIO) → `Derive`. Part of each held out.
3. **The teacher as a LABELLER of real texts**, not a rewriter of ours: given a real table and a question (from the
   sets above, from open textbooks' exercises), it writes the program; kept when the result is right (a known
   answer) or two independent labellings agree.
4. **Open textbooks' exercises with answers** (logic: forall x, Open Logic Project; statistics: OpenIntro) — the
   answer key is the judge.
5. **Variety by rule**: typos, colloquial and short forms, follow-ups in a dialogue, numbers in words; the
   questions outside the language labelled "ask".
6. **Ukrainian and Polish natively**: the teacher writes, a native source checks where one exists (school
   olympiads, textbooks).

The sealed set, ZebraLogic, `Everyday` and every held-out part of a new set are never training data.

## 4. The order of work

Sprint (`SPRINT.md`): the running measurements closed; the understanding said on the page; the month in the
calculus; the everyday measure with domains. Backlog (`BACKLOG.md`, "The loop"): corrections exported and taught;
the language's share, change, two columns, computed columns; the human sets and the teacher as labeller; variety
by rule; Ukrainian and Polish natively.
