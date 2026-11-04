# AGENTS.md — okay-data

Chat with your data in the browser, offline (README, specs/data.md). Scala 3, lines at most 120 characters, English.

- `core/` (JVM + Scala.js): `Table` (CSV/TSV/JSON), `Query` (the program as data, JSON in and out), `Exact` (the
  relational handler), `Logic` (Datalog over the tables, okay-datalog), `Prompt` (the model's part). Laws in
  `core/src/test`: every handler against hand-computed results; the summary's faithfulness.
- `web/`: the page (Laminar), WebLLM through `window.webllm` (an ES module import in `web/page/index.html`), the
  File System Access API with an `<input>` fallback. `web/page/main.js` is built, not committed.
- The rule: the model writes programs, handlers compute; a number the engine did not produce is never shown.
- Commits: plain messages, no attribution trailers.
