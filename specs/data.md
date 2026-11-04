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
