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
  WebLLM through `window.webllm` for the models that generate); `Clipboard` by `OnClipboard` (awaited writes);
  `?selftest` the sample through the readers; `Look` the skin's marks. `web/page/main.js` is built, not committed.
- Three screens (§34): chat (model, conversation, question, Copy/Clear), data (tables, files, address), examples
  (sample questions and data). `Disk` keeps every file opened (Cache API) and the model's files are fetched
  cache-first. Drivers (Playwright, scala-cli): `scripts/page.sh` (the self-test; `PHONE=1` as a phone),
  `scripts/shot.scala` (the page driven as a person would, a screenshot) — buttons by exact text
  (`button:text-is`), since `text=Ask` matches a label first; a slot's button by `text="…"`.
- One native reader model on the page (§33): `readers-small` (the student, `model-small/`, layer 12), the default.
  The cut and component-pruned core stay in research benches. A question's steps: `Read.Note` from the readers,
  `Model.Ask` / `Model.Thought` polled as the loading; `scripts/thinking.scala` watches them in the page.
- The rule: the model writes programs, handlers compute; a number the engine did not produce is never shown.
- Commits: plain messages, no attribution trailers.
- Plans: `BACKLOG.md` (what is not built, with the spec that holds each design); a spec's "built" section records what landed.
- Learning on purpose: organs (`okay.desk.organ`), books and the paradigm (`okay.desk.learn`), the code domain (`okay.desk.code`) — `docs/learning.md`, `specs/organs.md`.

## The aim (the operator, 2026-11-11)

This project is the task a TECHNOLOGY is developed on: small, extensible, modular, specialized neurosymbolic
language models — the model reads a text into a formal statement, exact engines compute. We study the problem and
look for the right, optimal solution; we do not reach a goal at any price. Read `specs/technology.md` (the aim,
the principles, the components, the open questions, how a result is judged) and `docs/textbook.md` (each
component explained, with what worked and what did not) before a new line of work, and bring both up to date
when a measurement changes what is known. A negative result is written down with its reason. ONE model is
developed (`specs/formalization.md` §13); other models and paths are not extended.
