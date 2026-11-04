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
