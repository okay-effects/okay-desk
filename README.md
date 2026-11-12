# okay-desk — one chat for questions, reasoning and data

Open CSV, TSV or JSON files (or a folder of them) from your machine. The native 12-layer student reads questions
into typed programs on WebGPU, WebGL2 or CPU. Exact handlers run relational queries, Datalog and sourced Horn
inference; the result, program and evidence are inspectable. Answers in words are phrased from exact results.
The model never computes a number. Questions stay local unless online sources are enabled.

```
sbt coreJVM/test                       # the exact engine's laws
./scripts/dev.sh                       # build the chat + CPU worker and serve http://localhost:8734/
scripts/page.sh                        # the readers on the sample in Chromium; GL=1 for WebGL2
scripts/publish.sh                     # publish the page and split weights to GitHub Pages
```

The page is an [okay-ui](https://github.com/okay-effects/okay-ui) program: one screen shown again after every press, its
effects (`Model`, okay-http's `Fetch`) answered by the browser — or by a test script on the JVM (`sbt uiJVM/test`).

A proof of concept of okay-inlay `specs/assistant.md` (the "data" pack): what a small model does well — understand,
write a program, phrase — and what it never does — compute. [specs/data.md](specs/data.md).

**The calculus of readings** — how a question becomes an exact answer, and why the whole of it is one small
calculus: [docs/calculus.md](docs/calculus.md) (the document); [specs/calculus.md](specs/calculus.md) (the diary,
with every measurement).


What we are building and why: `docs/vision.md`.
How to use the organs, the books and the code reader: `docs/learning.md`.

**One question box** — ask a question, paste a problem, or ask about opened data. There is no separate puzzle mode.
The model proposes operations; handlers gather evidence, compute arithmetic, solve finite constraints and check
citations. When evidence is missing, **Use online sources** enables autonomous collection for that visit;
**Turn off online sources** disables it. Sources and dates accompany answers, and collected evidence is cached.
General search currently uses Wikipedia; structured world holes use Wikidata. okay-inlay compiles the exact
world-fact selector, citation lookup and Horn inference into transformer weights. Answers in words are phrased
by the handler from the checked result. It presents the conclusion and the supporting derivation;
free prose and a model's self-review do not substitute for a proof. The default is the native 12-layer student,
which runs on WebGPU, WebGL2 or CPU. No extra generating model is loaded to reason over collected evidence.
The first common fact/rule reader is a bounded symbolic grammar; reading arbitrary prose into facts and rules is
still being built. See [specs/reasoning.md](specs/reasoning.md) and [specs/puzzles.md](specs/puzzles.md).

**Examples** opens the sample questions; each runs in the same chat. Header-free English/Russian house grids
can declare their house count, category values and uniqueness rule in prose. The bounded reader shows the
interpreted constraints; the exact solver answers every recognized question from the computed grid.
Exact tools run in a bounded CPU Worker pool while the model uses its own execution lane. The typed CPU API
also accepts dependency plans; independent calls start together. See [specs/execution.md](specs/execution.md) for
the implemented placement rules, diagnostics and the remaining work toward adaptive CPU/GPU dispatch.

For a local build of the new inference block, publish the sibling library first:

```sh
(cd ../okay-inlay && sbt coreJVM/publishLocal coreJS/publishLocal)
# For the device-release API used when switching models:
(cd ../okay-llm && sbt qwenJVM/publishLocal qwenJS/publishLocal browser/publishLocal)
```

`scripts/deduction.scala` exercises the real student on forced WebGL2, followed by sourced inference in the same
chat. It mocks the source service, not the model. It is a browser compatibility check on the host GPU, not a
measurement on a physical phone.


The 12-layer student can also read new membership facts and universal rules into the inlay inference tool
([specification](specs/reasoning.md#8-the-student-reads-new-facts-and-rules-built-2026-11-08)). Train its small
readout with `sbt 'bench/runMain okay.desk.bench.FactReaderBench'`, then run `scripts/dev.sh` to stage it with the
model. This first family copies source arguments and abstains on uncertain readings; arbitrary prose and general
question goals still need further work. Source truth and the model's interpretation remain separate from exact
logical derivation.


For the expanded named relations, train `sbt 'bench/runMain okay.desk.bench.RelationsBench'` after the class
readout. Check the saved artifact with `RelationsAudit`, and live source reading with `RelationsLive` in the same
bench package. `scripts/relations.scala` checks real online research in the browser. The chat's **Find a sourced
answer** example exercises this path. An HTTPS document URL on its own line can precede a question; HTML, plain
text and Markdown need browser CORS access. Supported relations and measured limits are in
[the reasoning specification](specs/reasoning.md#9-named-relations-and-external-documents-built-2026-11-08).


The page offers one native model: the 12-layer student (`readers-small`, about 122 MB of model files), with the
trained fact/relation readouts. The 19-layer cut and component-pruned core are research checkpoints and are no
longer listed or packaged for the site. The three generative WebLLM choices remain available.


All sample questions and data sets are under **Examples**. A nonempty chat has **Copy chat** (including proofs,
source links and programs) and **Clear chat**; clearing keeps the loaded model and open data.


Dataset dictionaries are consulted before routing a question: `ВВП` can refer to an open `gdp` table even before
the model loads. `scripts/gdp.scala` checks this cold-start path through WebGL2 against the real GDP series.
Growth compares the first and last available points; causal explanations require evidence beyond that calculation.

The experimental model-authored path is documented in [understanding](specs/understanding.md).
With locally trained artifacts available, `scripts/dev.sh` stages the native program-authoring profile; select
the 28-layer model in the shared model picker. The language model writes the formal program and chooses exact CPU
tools directly. The old `?understanding` address still selects it initially; later choices work normally.
The [experimental 28-layer profile](https://okay-effects.github.io/okay-desk/?understanding) uses about 305 MiB
of weights and adapter files, cached after download; working memory is additional. Model interpretations can be
wrong. This profile remains separate from the default chat because broader generalization checks fail.
**Unload model** releases the active model's device while keeping the chat, open data and cached model files;
**Load the model** can then restore it from the cache, including offline after a successful first download.
`SemanticAcceptance MODEL_DIRECTORY REPORT_JSON` in the bench runs premise/goal mutation checks and records the
actual model output. `SEMANTIC_NATIVE_ADAPTER` selects its matching adapter; neither gold answers nor natural-language
parsers participate in model inference.
