# AGENTS.md — okay-desk

Chat with your data in the browser, offline (README, specs/data.md). Scala 3, lines at most 120 characters, English.

- `core/` (JVM + Scala.js): `Table` (CSV/TSV/JSON), `Query` (the program as data, JSON in and out), `Exact` (the
  relational handler), `Logic` (Datalog over the tables, okay-datalog), `Prompt` (the model's part). Laws in
  `core/src/test`: every handler against hand-computed results; the summary's faithfulness.
- `ui/` (JVM + Scala.js): THE PAGE AS AN OKAY-UI PROGRAM — `App` (the state, the screen, the messages), the effect
  `Model` (load, program, summary); played by a script on the JVM (`AppSuite`, okay-ui's `Play`) — the same
  program the page runs. Rows: an effect handled leaves the row as written (`Ui + Fetch + Model` minus `Model` is
  `Ui + Fetch`, written out), and a button's form is found by identity: one form value per screen.
- `web/` (Scala.js): `Main` runs `App` by okay-ui's DOM face (`Dom.run`, `follow = true`), `Fetch` by the browser
  (`InThePage.fetching`), `Model` by `Engine` (the readers on okay-llm's forward: WebGPU, else WebGL2, else the CPU;
  WebLLM through `window.webllm` for the models that generate); `Examples` the examples' screen (examples.html);
  `?selftest` the sample through the readers; `Look` the skin's marks. `web/page/main.js` is built, not committed.
- Two screens (§19): the chat (model, Data button, conversation, question) and the data (tables, files, address,
  examples with Open buttons); `Disk` keeps every file opened (Cache API) and the model's files are fetched
  cache-first. Drivers (Playwright, scala-cli): `scripts/page.sh` (the self-test; `PHONE=1` as a phone),
  `scripts/shot.scala` (the page driven as a person would, a screenshot) — buttons by exact text
  (`button:text-is`), since `text=Ask` matches a label first; a slot's button by `text="…"`.
- The rule: the model writes programs, handlers compute; a number the engine did not produce is never shown.
- Commits: plain messages, no attribution trailers.
