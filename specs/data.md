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
