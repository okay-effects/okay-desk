# okay-desk — chat with your data in the browser (spec, 2026-11-04)

Status: a proof of concept, built 2026-11-04 (operator's direction: "a chat in the browser with an offline model
strengthened with Datalog and whatever analysis needs; the data opened locally through file and folder pickers").
The "data" pack of okay-inlay specs/assistant.md §1; the thesis of specs/bridge.md in a product: the small model
understands and writes the program, the exact handlers compute, the boundary is typed (a JSON query checked
against the schema), and a number never comes from the model.

## 1. The parts

| part | what | where |
|---|---|---|
| tables | CSV / TSV (quoted fields), JSON lines, a JSON array; numbers as numbers; a schema the model reads (kind and samples per column) | `Table` |
| the program | relational: from, join, where (= != < <= > >= contains empty), group + count/sum/avg/min/max, select, order, limit; logical: Datalog rules + a goal over the tables as relations | `Query` (data; JSON in, JSON out) |
| exact handlers | the relational engine; okay-datalog's `Eval` for rules (proofs available) | `Exact`, `Logic` |
| the model | Qwen3 1.7B (default), 0.6B, or Qwen2.5 3B — WebLLM prebuilt, q4f16, WebGPU; `enable_thinking` off | the page, `window.webllm` |
| the prompt | the schema, the two forms with one example each, "never compute numbers, never invent columns"; the program parsed from a ```json block | `Prompt` |
| the loop | question → program → checked (unknown column, op, aggregate → told in words) → run; a failed program sent back once with the problem → the fixed one run; the result (≤ 50 rows) and the program shown; a summary asked from the result only, kept iff every number in it is in the result | the page |
| files | `showOpenFilePicker` / `showDirectoryPicker` (walked 3 levels, csv/tsv/json/jsonl/ndjson), else `<input type=file>` with `webkitdirectory` | the page |

## 2. Laws (core/src/test, JVM)

Parsing (numbers, quotes, the schema); count and sum by group; where, order, limit; a join by key; an unknown column
told; Datalog over the tables (who shares a city); the program found in an answer; a summary with a number not in the
result dropped. All held at the first run.

## 3. Not yet, and honest

- The model's programs are not measured yet: a benchmark of questions over sample tables with gold results (the
  arms: the program's correctness, the retry's help, the summary's faithfulness) — the next step, on the JVM through
  okay-mlx with the same prompt, before the browser numbers.
- A 1.7B writes simple programs well and complex ones (two joins, a rule with negation) less so; the retry with the
  problem in words is the lever; a mask over the program's grammar (the decode port, in WebLLM's logit processor) is
  the next.
- Large files: everything is in memory and the engine is plain Scala; tens of thousands of rows are fine, millions
  are not (okay-store's SQL handler over a browser database later).
- The first load needs the network once (the model from HuggingFace through WebLLM's cache); after that, offline.

## 4. The bench, first numbers (2026-11-04)

`bench/` (JVM, mlx-scala): 12 questions × English and Russian over orders (8 rows), customers (5), edges (6) with
gold programs; the page's loop (the prompt, the program parsed, checked and run, one retry with the problem in
words); right when the result equals the gold as a set of rows (a subset of the gold's columns accepted). Three
runs: the first with one abstract example in the prompt — every small model copied its names ("from": "table",
"group": ["c"]) and all were at 0; the second with examples written over the tables at hand; the third also with
a lenient parser (a `{"query": …}` wrapper, a `{"status": "unpaid"}` shorthand, "join" as a list, `table.column`).

| model (4-bit) | en: right at once, +retry | ru | time, 24 questions |
|---|---|---|---|
| Qwen3-0.6B | 2/12, +0 | 2/12, +0 | 9 s |
| Qwen3.5-0.8B | 0/12 | 0/12 | 15 s |
| Qwen3-1.7B | 4/12, +0 | 5/12, +0 | 18 s |
| Qwen3.5-2B | 4/12, +0 | 4/12, +0 | 20 s |
| Qwen3.5-4B | 10/12, +1 | 10/12, +1 | 37 s |

- **4B does the job** (11 of 12 in both languages; the miss: "the largest unpaid order" answered as the maximum
  amount, a correct answer in another shape). **Below 2B the models do not, as they are**: they write a program
  every time (no "none"), but the wrong one — "not empty" with a value, a join forgotten, a group by the wrong
  column, "where": []. Russian is no worse than English at any size.
- The retry with the problem in words helped the 4B once and the small ones never: their programs run, they are
  just wrong, and nothing tells them so. The lever for the small ones is not words but the mask (a grammar over
  the program and the schema at decoding) and training on generated question → program pairs (okay-forge's
  recipe) — bridge-2's question, here with a benchmark that measures it.
- The prompt matters more than a billion parameters: the 4B went 4–5 → 7 → 10 of 12 by examples over the real
  tables and a lenient parser, with no change of the model.

Next: the mask on the program's grammar in the bench (mlx-scala's decode port) and in the page (WebLLM's logit
processor); the 1.7B and 0.6B trained on generated pairs; then the page's default chosen by this table.

## 5. The mask, measured (2026-11-04)

`bench/Grammar.scala`: the program's grammar as mlx-scala's decode port — the JSON a sequence of segments: PICKS
(a table, a column, an op, an aggregate, the next section or the end: the names from the lists, never from the
model) and FREE text until a quote (a value, an alias, Datalog rules), a bare number where a value is numeric. A
token may cross a segment boundary (`",`, `"}`): the allowed set is computed by reading each token's text through
the grammar. Two lessons on the way, both found with a probe that feeds the port known text without a model:
scoring whole continuations by their log-probability sum favours the shortest (fixed in mlx-scala: the mean), and
FORCING a unique continuation (`"` after a name) keeps the model from its natural merged token (`",`), after which
a lone `,` against `}` is no choice of its own — so nothing is forced: the mask alone, token by token.

| model (4-bit) | free, en / ru | masked, en / ru |
|---|---|---|
| Qwen3-0.6B | 2/12, 2/12 | 2/12, 2/12 |
| Qwen3.5-0.8B | 0/12, 0/12 | 2/12, 1/12 |
| Qwen3-1.7B | 4/12, 5/12 | 3/12, 1+2/12 |
| Qwen3.5-2B | 4/12, 4/12 | 5/12, 4/12 |
| Qwen3.5-4B | 11/12, 11/12 | 10+1/12, 8/12 |

**The mask does not lift the small models here.** It guarantees the FORM — no unknown column, no broken JSON,
never "no program" — but their errors were never of form: they choose the wrong column, op or grouping, and a
mask over names cannot choose for them. On the 4B it costs a little (10–11 → 8–10): the grammar's order of keys and
its bare-number rule differ from what the model would write. So for okay-desk the mask is the guard, not the
lever; the lever for a small model is training on generated question → program pairs (okay-forge's recipe), with
the mask on at generation so the trained choice lands in a well-formed program. That is the next step; until then
the page's honest default is the 4B class on the JVM (okay-mlx) and Qwen3 1.7B in the browser with the program
shown.

## 6. The handler in weights (2026-11-04)

The operator: "you proved it works — use it". `Weights` (core): a Datalog query of the CLOSURE SHAPE — `r(X, Y) :-
t(X, Y). r(X, Z) :- t(X, Y), r(Y, Z).` with the goal `r(a, X)` or `r(X, b)` over a two-column table — is answered by
the transformer compiled from okay-inlay's `Closure` program and run by `Forward` (plain Scala: on the JVM and in the
page alike); the set the question position gathers is everything reachable from the constant (the edges reversed
for "reaching it"). `WeightsSuite`: weights == okay-inlay's interpreter == okay-datalog on 30 random graphs, from
and to — held at the first run. Any other rules, or a table beyond the block's capacity (60 rows, 40 names), go to
okay-datalog: one program, two handlers, the cheaper where it fits (okay-inlay specs/inlay.md §8.6). The page says
when the block answered. What the block does NOT do yet: arbitrary rules (okay-inlay BACKLOG 0, the Inlay handler
of okay-datalog's programs) and large tables (the dense `Forward`; the GPU or sparse heads later).

## 7. The skill as a block beside a minimal model (operator's direction, 2026-11-04)

"Take the smallest LLM that understands speech and, with inlay, teach it to write correct Datalog." The model
(Qwen3 0.6B) supplies the understanding and is not changed; the skill is a BLOCK beside it; only the boundary is
trained — okay-inlay specs/bridge.md's regimes, in the strongest form.

1. **Readers** (trained, kilobytes): linear probes on a middle layer read the program's SLOTS from the model's
   state over the page's prompt — the shape (count, filter, group, top-N, closure…), the aggregate, the op; the
   table and the column as a POINTER among the schema's positions in the prompt (a `Lookup` with learned query and
   key maps — a head, not a classifier); the constant of a condition is a verbatim span of the question, copied by
   the quote mechanism (okay-inlay `Quotes`), no judgement at all.
2. **The assembler** (compiled): the slots → the program's text, with the grammar mask: an unknown column, a broken
   JSON, a wrong key order impossible by construction.
3. **Training**: the readers only, on the generated pairs (`Pairs`: the gold slots are known), minutes; measured
   per slot on held-out pairs and end to end on the bench. Everything after a read is exact, so the program is as
   right as the reads.

Against LoRA (§5, §8): LoRA moves the knowledge into the weights — unverifiable, not removable; a block is a
separate file, checked by a law, swappable, off at will, and its error has a name ("the column was read unsurely").
The proof: one bench, three arms on the 0.6B — as it is, with LoRA, with the readers and the block. The risk: whether
the shape and the column read as linearly from a 0.6B's layer 12 as a polarity and a direction did from the 4B's.

Steps: slot probes on the pairs and their accuracy; the column pointer head; the assembler under the mask; the
bench's third arm.

## 8. LoRA on the pairs (2026-11-04)

`Train`: 600 pairs (292 in Russian), rank 8, one epoch. Qwen3-0.6B: 226 s, mean loss 0.0125.

| arm | before, en / ru | after, en / ru |
|---|---|---|
| free | 2/12, 2/12 | 6/12, 3/12 |
| masked | 2/12, 2/12 | 6/12, 5/12 |

Four minutes of training tripled the English; and the mask now helps the Russian (3 → 5): once the choice is
learned, the mask keeps it in form. The 1.7B next.

Qwen3-1.7B, the same 600 pairs, one epoch (mean loss 0.0171):

| arm | before, en / ru | after, en / ru |
|---|---|---|
| free | 4/12, 5/12 | 10/12, 8+1/12 |
| masked | 3/12, 1+2/12 | 9/12, 9/12 |

A 1.7B trained for a few minutes on generated pairs reaches the untrained 4B (10–11 of 12) — in a 1 GB model that
runs in the browser. Under the mask the two languages are equal (9/12 each). The choice is learnable; the mask keeps
its form. Next: the same choice read by probes and assembled by a block, the model untouched (§7).

## 9. The slots read from the 0.6B (2026-11-05)

`Probes`: the states after a layer, standardized and clipped (the residual's scale and its few huge dimensions
turned the bilinear scores to NaN until then), read by a softmax probe (the shape, 17 classes) and bilinear
pointers (the table and each column slot as a position among the schema's names in the prompt, a "none" key for
an empty slot); 600 pairs to train, 200 held out. Read at the question's last token, the column pointers sat at
the "none" rate (59–65% against 61%): the state there does not hold WHICH column. **Read where the model
decides** — after a forced prefix of the program up to the slot (`{"from": "orders", "where": [{"column": "`), as
the mask reads a choice where it is made:

| layer | shape | table | where column | group column | aggregate column | order column |
|---|---|---|---|---|---|---|
| 12 | 29.0% | 100% | 64.5% | 79.0% | 77.5% | 83.0% |
| 16 | 64.0% | 100% | 87.5% | 83.0% | 93.0% | 85.0% |
| 20 | **93.5%** | 100% | **93.5%** | **94.5%** | **96.0%** | **95.5%** |
| 24 | 86.5% | 100% | 94.0% | 98.0% | 91.0% | 95.0% |
| the "none" rate | | | 61% | 79% | 78% | 85% |

So the slots of a program read from an UNTOUCHED 0.6B at 93–98% by readers of kilobytes (a 1024×64 map or two per
slot), once read at the right place. The understanding is there; it was the reader. Next: the assembler (the slots,
the constant copied from the question, the program under the mask) and the bench's third arm.

## 10. The third arm: readers and an assembler, the model untouched (2026-11-05)

`Readers` (bench): the table pointer at the question's last token; the shape probe and the column pointers after
their prefixes (layer 20); the constant of a condition copied from the question by exact match with the column's
values, or a number; the program assembled — no token generated. Trained on the pairs in ~7 minutes (the states),
kilobytes of readers. A data bias found and fixed on the way: `edges` had appeared in the pairs only with closures,
so the first readers learned "edges present ⇒ a closure" and pointed at it for every bench question (the bench
always has edges); now unused tables appear in ordinary pairs too.

| Qwen3-0.6B | en | ru |
|---|---|---|
| as it is (free) | 2/12 | 2/12 |
| LoRA on the pairs (free / masked) | 6/12 / 6/12 | 3/12 / 5/12 |
| **readers + assembler, model untouched** | **5/12** | **4/12** |

Close to LoRA, with the model's weights unchanged and nothing generated. The misses are READS, each named: a group
column read as `status` for `customer` (the 6%), `<` for `>` (the shape probe holds the op), "three" not read as
3, a join shape not read, a date's column read wrongly so the constant fell back to "02", and a Russian constant
("не оплачено") that is a translation of the table's value, which no exact copy can give — a judgement left to a
reader. Next: an op reader at `"op": "`, number words, a value pointer over the question's tokens (the quote
mechanism), the join shape; then the same on the 1.7B.

**With an op reader, a value-start pointer, number words and the join (2026-11-05):**

| Qwen3-0.6B | en | ru |
|---|---|---|
| as it is (free) | 2/12 | 2/12 |
| LoRA on the pairs (free / masked) | 6/12 / 6/12 | 3/12 / 5/12 |
| **readers + assembler, model untouched** | **7/12** | **5/12** |

The readers now pass LoRA on the same model — with the weights untouched, nothing generated, and the skill in a
file of kilobytes. The value is copied from the question by a read start (dates come out right: `2026-11-02`);
what still misses is a column read (`status` for `customer` in a group, for `day` in a date's condition), the join
shape in English, "the largest unpaid order" read as a plain top-1, and the Russian translated constant. These
are reads, each improvable by data (more phrasings) or a second reader (ask), as bridge-4's lesson: two readers
that fail on different phrasings.

The same readers on Qwen3-1.7B at layer 20: 6/12 en, 4/12 ru — below its LoRA (10/12, 9/12) and below the
0.6B's readers; the where-column pointer reads `status` for nearly everything there. The layer was the 0.6B's
choice; the 1.7B's is measured next (`Probes` per layer), as bridge-1 found the layer matters most.

Qwen3-1.7B, the slots per layer (`Probes`, 600 / 200 pairs):

| layer | shape | table | where column | group column | aggregate column | order column |
|---|---|---|---|---|---|---|
| 10 | 33.5% | 83.0% | 46.0% | 72.5% | 61.0% | 72.0% |
| 14 | 36.5% | 87.5% | 76.0% | 73.5% | 77.5% | 85.5% |
| 18 | **99.0%** | 100% | 87.5% | 88.5% | 94.5% | 93.0% |
| 22 | 95.5% | 100% | **92.5%** | **95.5%** | 94.5% | 92.0% |
| 26 | 95.0% | 100% | 91.0% | 93.0% | 95.0% | 89.0% |

Layer 20 was between 18 and 22 but read worse on the bench; 22 is the 1.7B's layer for the columns, 18 for the
shape. The readers on the 1.7B are rerun at 22.

## 11. Two readers (2026-11-05)

The operator remembered bridge-4's 100%: a trained probe PLUS ask, summed. Here too: for the columns and the op,
the pointer's log-probabilities over the candidates plus the model's own next-token log-probabilities at the same
prefix (`"column": "` → the columns' first tokens; `"op": "` → the ops), no training for the second.

| readers + assembler, model untouched | en | ru |
|---|---|---|
| Qwen3-0.6B, pointers only | 7/12 | 5/12 |
| **Qwen3-0.6B, pointers + ask** | **8/12** | **6/12** |
| Qwen3-1.7B, pointers only (layer 22) | 5/12 | 4/12 |
| **Qwen3-1.7B, pointers + ask (layer 22)** | **9/12** | **7/12** |
| for comparison: 1.7B with LoRA | 10/12 | 9/12 |

The 1.7B's where-column pointer alone had collapsed to `status`; with ask beside it the column reads right. The
readers on the untouched 1.7B are now a question away from its LoRA. What is left, by name: the Russian
constant that translates the table's value (`не оплачено` → `unpaid`: ask over the column's VALUES will say it),
"Три" not read as 3 (a `\b` that does not know Cyrillic), the op `!=` where `=` was meant on the 1.7B, a join read
on a question without one.

**With the translated constant read by ask over the column's values, and Cyrillic number words:**

| readers + assembler, model untouched | en | ru |
|---|---|---|
| Qwen3-0.6B | 8/12 | **8/12** |
| Qwen3-1.7B (layer 22) | 9/12 | **9/12** |
| for comparison: 1.7B with LoRA (free / masked) | 10/12 / 9/12 | 8+1/12 / 9/12 |

Russian caught up with English on both models (6 → 8, 7 → 9): `не оплачено` is now read as `unpaid` by the
model's own judgement over the column's values — a judgement, read, not generated. The readers on the untouched
1.7B now equal its LoRA; on the 0.6B they pass it by two. What is left: the op `!=` read where `=` was meant
(the 1.7B on "unpaid" and "paid": the op reader at `"op": "` before the value is known), a join read on a plain
question, the closure's direction in one Russian question, a group column. Each a read; each with a name.

## 12. Toward no errors on the 0.6B (2026-11-05, in progress)

The operator: "focus on the 0.6B — can it stop erring, with our ideas?" Tried so far, each on the bench:

| change | en | ru |
|---|---|---|
| pointers + ask, the value by ask over values (§11) | 8/12 | 8/12 |
| + exact facts from the data (a value in the question forces its column's condition and the join); pairs rebalanced (joins on any text column, "the largest with a condition") | 9/12 | 7/12 |
| + the shape by two readers (the probe + the model asked which section comes first), the join from the data only | 6/12 | 4/12 |

Two lessons. The exact facts help where they apply (the date's column, the join) but the rebalanced pairs made the
shape probe see joins everywhere — a bias moves when the data moves, so a reader must be judged on held-out PAIRS,
not on the bench's 24 questions: `Readers.measure` now reports each reader alone and with ask on 200 held-out
pairs before the bench runs. And the 0.6B's own prior at the shape prefix is "sum" for everything — ask helps a
column (the choice is among the schema's names) and hurts the shape (a choice among sections the model has no
feel for); so the shape stays with the probe, and the join is the data's decision, never the model's.

**The measure corrected (2026-11-05 15:30).** `Readers.measure` counts a column slot only where the gold has one;
`Probes` had counted the empty slots too (60–85% of them), so its 93–98% was mostly "empty or not". The honest
column accuracy of a pointer alone is ~58% (group 55%, where 59%, order 58%), with ask 66–75%; the aggregate's
column 93% → 100% with ask. Scoping names to their tables (a real bug: shared column names) did not move these
numbers, so the pointers are now trained over the table's own columns only, ask is measured alone, and the pairs
are 1,500. The readers are kept in `~/.cache/okay-desk/readers-<model>-<layer>.bin` (3 MB: a skill is a file).

**The shape as separate decisions, exact first (2026-11-05 16:50):** the aggregate's function read at its own
point (`"fn": "`, a probe plus ask) with the question's words first ("how many" → count, "average" → avg, "top 3"
→ none); group / order / limit from the words ("per", "по каждому", "largest", "топ") with the probe's marginals as
the fallback; a number with a comparison word a condition; a column named in the question taken exactly; the
columns assigned in a disambiguating order (numeric to the aggregate and the order first, text to the group, the
fact's to the condition).

| Qwen3-0.6B, model untouched | en | ru |
|---|---|---|
| readers + assembler | **10/12** | **9/12** |
| for comparison: the 1.7B with LoRA | 10/12 | 9/12 |

The untouched 0.6B now matches the trained 1.7B. Three misses left, each named: a number condition put on `id`
instead of `amount` (the numeric column whose range holds the number is the exact rule), two facts in one question
("unpaid" and "UA": two conditions and a join), a translated column ("по странам" → country, in the joined table).

**12/12 en, 11/12 ru on the untouched Qwen3-0.6B (2026-11-05 17:00)** — a numeric condition's column by the range
that holds the number, every fact a condition, the join from any fact or from a column chosen in a joinable table
(the readers' candidates include those: "по странам" → `customers.country`). The one miss left is a translated
adjective beside a fact ("Неоплаченные … из UA": the UA condition found, the unpaid one not — a judgement over the
main table's values that the exact copy cannot give and the value-ask does not run when a fact was found).

| Qwen3-0.6B (400 MB, 4-bit), the bench | en | ru |
|---|---|---|
| as it is | 2/12 | 2/12 |
| the grammar mask alone | 2/12 | 2/12 |
| LoRA on 600 generated pairs | 6/12 | 5/12 |
| **readers + exact facts + assembler, weights untouched** | **12/12** | **11/12** |
| for comparison: Qwen3.5-4B as it is | 11/12 | 11/12 |

The operator's thesis — a small model strengthened by exactness — measured: an untouched 0.6B with a 3 MB skill
file answers the bench like an untouched 4B, in two languages, with nothing generated. What made it: reading the
slots where the model decides them; two readers (a probe and the model asked) where a judgement is needed; exact
facts from the data and the question's own words wherever the data knows; an assembler that cannot write a
malformed program; and judging every reader on held-out pairs, not on the bench.

## 13. Self-play, the first run (2026-11-05)

`SelfPlay`: the 0.6B writes 5 questions per random schema (en or ru, its own words), the readers answer each with a
program, the judge accepts when the two readers agreed on every slot AND the program said back as a question
reads to the same program; fresh readers trained on the accepted pairs alone, measured against the gold-trained
ones on the same 200 held-out gold pairs. 60 rounds: 296 questions, 273 programs, 197 agreed, 161 round trips
(54% accepted). The result, honestly negative: shape 13.5% against 84.0%; the columns by ask the same (ask needs
no training), by the pointer worse (34–63% against 56–62%).

Why: the consistency judge passes what is CONSISTENTLY WRONG — "How many edges are there in the 'cache'
category?" read as a count over `flights` by both readers, and the round trip (a paraphrase of the wrong program)
agrees with itself; and the accepted set is small and narrow (28 of 161 with a condition). Consistency is not
correctness. The judge must hold exact facts: a table, a column or a value the question names must be in the
program, and nothing named must be absent — the data's own check, as everywhere else in this work; and the
questions must be asked for their variety (a condition, a group, a top-N, a reach). Next run with both.

**The second run, the exact judge and varied questions:** 150 rounds, 713 questions, 618 programs, 126 agreed,
92 accepted (13%). Readers trained on the 92 alone: shape 25.5% (84.0% on the gold), pointers 22–46% (56–62%),
ask unchanged (it needs no training). Still far. The judge now rejects what is wrong (the accepted programs are
right), but the WRITER is the weak link: a 0.6B asked for questions parrots the instruction ("Which rows meet a
condition on a column (e.g., 'category')?"), so the accepted set is small and unnatural. Conclusion: a 0.6B can
answer and can be judged, but cannot yet ask well enough to teach itself from scratch; the bootstrap needs a
better asker — a larger local model writing the questions (the 4B), the 0.6B answering, the exact judge deciding,
the readers learning — or thousands of rounds. Stage 2 stands as designed, with the asker swapped; stage 1's
result (12/12) does not depend on it.

## 14. The cut (2026-11-05)

A layer never looks up, so the state after layer L is the same in a model cut at L: the per-layer measure IS the cut
model's readability. The 0.6B's curve (`Probes`, the corrected measure, 200 held-out pairs):

| layer | shape | where column | group column | aggregate column | order column |
|---|---|---|---|---|---|
| 16 | 53.0% | 67.6% | 55.4% | 71.4% | 27.8% |
| 17 | 57.5% | 63.5% | 46.4% | 71.4% | 27.8% |
| **18** | **85.0%** | **85.1%** | **78.6%** | 80.4% | 58.3% |
| 19 | 88.5% | 85.1% | 75.0% | 80.4% | 63.9% |
| 20 | 83.5% | 85.1% | 71.4% | 83.9% | 55.6% |
| 22 | 83.0% | 81.1% | 75.0% | 85.7% | 61.1% |

A threshold between 17 and 18: the understanding the analysis needs forms by layer 18 and holds to 22; everything
above is free to drop. `Cut`: the first L layers kept as a local repository (`local/<model>-cut<L>` in the cache),
the head tied to the embedding so nothing else to drop. **19 of 28 layers: 244 MB from 320 MB**; readers trained
on it at layer 19; the bench: **11/12 en, 7/12 ru** (the full model 12/11). What the cut lost is in Russian
short questions ("самый большой заказ"): the order and group readers pick `id`. A key column is now excluded from
sorting, aggregating and grouping unless the question names it (an exact rule); the cut at 21 measured next. The
floor by size: the embedding (152k × 1024) is a quarter of the model and does not shrink with the layers.

**The cut at 21** (261 MB): 12/12 en, 7/12 ru — English whole; the Russian misses are joins the readers reach for
(a joined table's column, `country` or `since`, chosen on a translated word). The readers' candidates by kind now
(numeric for an aggregate or an order, text for a group), the table's own columns first, a joined one only by a
clear margin of both readers; measured again on both cuts.

**The cut chosen (2026-11-05 evening): 19 of 28 layers, 244 MB.** With the readers' candidates by kind, the
table's own columns first and a joined table's only for a group by a wide margin:

| Qwen3-0.6B cut, readers + assembler, weights untouched | size | en | ru |
|---|---|---|---|
| full, 28 layers (layer 20) | 320 MB | 12/12 | 11/12 |
| **cut at 19 (layer 19)** | **244 MB** | **12/12** | **9/12** |
| cut at 21 (layer 21) | 261 MB | 12/12 | 8/12 |

A quarter smaller at no loss in English; the Russian loses two questions, both translations of a column or a value
("по каждому клиенту" → customer, "Неоплаченные" → unpaid), where the readers' judgement at this size is the limit.
Below 18 layers the understanding is not there (§14's curve). The floor by size is the embedding; the next cut is
the vocabulary (152k tokens → the languages in use) or 2-bit MLPs, measured the same way.

## 15. The readers in the browser (plan, 2026-11-05)

The thesis as a page: the cut 0.6B (244 MB) and a 3 MB skill file, no generation — a question read into a program
in one pass. What it takes, in order, each step checked on the JVM before the browser:

1. **The readers and the assembler into `core`** (plain Scala, JVM + Scala.js): `Readers` depends only on a
   `States` provider — the residual after layer L at given positions for given token ids — and a tokenizer; the
   probes in a plain binary format (float32 LE, as `Micro`'s) instead of Java serialization. Law: the JVM provider
   (mlx-scala) gives the bench's numbers as before.
2. **A Qwen3 forward in Scala.js on BOTH WebGPU and WebGL2** (the operator's rule: both from the start, as the Go
   game): one `Compute` interface — buffers, dequantized matmul (MLX's 4-bit layout: packed uint32, scales and biases
   per group of 64), RMSNorm, RoPE, attention over the prompt, SwiGLU — with three handlers: WebGPU (WGSL compute),
   WebGL2 (fragment shaders over float textures, the Go game's way), and plain Scala on the CPU (the reference and
   the law on the JVM and in JS alike). Hidden states at layer L for the requested positions; no KV cache, no
   sampling. Law: the three handlers equal each other and mlx-scala on the same prompt (a tolerance per element).
3. **The tokenizer in Scala.js**: mlx-scala's byte-level BPE is plain Scala (llm/Tokenizer.scala) — cross-built.
4. **The page**: the model and the readers fetched once and cached; the question → the program → the exact
   engine, the program shown, with the mode's name ("read, not generated").

Status 2026-11-05: steps 1–3 DONE. Step 1 the skill in `core` (readers-Qwen3-0.6B-4bit-cut19-19.skill, 3 MB; the
bench unchanged: 12/12 en, 9/12 ru). Step 2 in okay-llm (`qwen` and `browser` modules, okay-llm-qwen-browser 0.1.0
locally): `Compute` with OnCpu / OnWebGpu / OnWebGl2, `Qwen3Compute` over it; the laws — ComputeSuite (OnCpu ==
Qwen3Forward, exact), ForwardSuite here (Qwen3Forward == mlx-scala on cut19), okay-llm scripts/selftest.sh in
Chromium on the 16-token prompt: WebGPU diff 6e-5, WebGL2 8e-3 (states up to 7942), the same next token; CPU 11 s,
WebGPU 0.1 s, WebGL2 0.4 s. Step 3 the tokenizer encodes in the page (Nfc by the string's `normalize` on JS).
Step 4 DONE 2026-11-05: the skill is a PROGRAM over the effect `Read` (okay-scala; `Read.sync` answers it in place on
the JVM, `Read.async` by futures through `Async` — the continuation a value of the machine, no replay); `OnQwen`
answers it over `Qwen3Compute` on the page's device, the template by hand (TemplateCheck: the same tokens). The
page (`?selftest`, scripts/page.sh) on WebGPU: en 12/12, ru 9/12 — the JVM's numbers to the question, the same
three Russian misses (2, 7, 8); 2.3–7.6 s a question; on WebGL2 the same 12/12 and 9/12, 4–12 s a question (10–15 forwards over the ~400-token prompt, no prefix cache).
On the cut model `ask` is near zero in the measure (the cut has no head layers: 18%/0%/0%/18%); the pointers and
the exact facts carry the bench. Next: a prefix cache (one forward of the prompt, the prefixes appended) for speed;
the missed Russian phrasings; the model fetched once into the browser's cache.

Real data (2026-11-05, the examples page): the assembler was quadratic (a join searched row by row per candidate:
90 s a question on airports 3376 × S&P 503) and the exact facts matched words of the question ("how MANY" the city
Many, "in" the state IN). Fixed: the join by a set once per table; a value is named when it is a run of the
question's words (up to four), never a stop word, a short code (AK, UA) only in capitals; the TABLE by exact facts
first — named, else the one table a named column belongs to, else the pointer. Live: state AK 263 (3.4 s), city
Boston, companies per GICS Sector — right; "in Alaska" gives the plain count (AK is not written: honest, not wrong).
The law stayed 12/12 and 9/12. The examples page (examples.html): open data whose hosts allow CORS, one or several
opened by `?open=<url>`; each open table closed by its × (the others stay).
