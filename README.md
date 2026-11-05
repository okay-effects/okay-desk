# okay-desk — a desk for your data: chat with it in the browser, offline

Open CSV, TSV or JSON files (or a folder of them) from your machine. A small model in the page (Qwen3 0.6B–1.7B
through WebLLM on WebGPU, downloaded once, cached) understands the question and writes a QUERY — relational
(filters, counts, sums, groups, top-N, joins) or Datalog (chains, reachability, rules, on okay-datalog). An exact
engine runs it; the result and the program are shown; the model's one-sentence summary is kept only when every
number in it is in the result. The model never computes a number. Nothing leaves the browser.

```
sbt coreJVM/test                       # the exact engine's laws
sbt web/fullLinkJS && cp web/target/scala-3.3.8/okay-desk-web-opt/main.js web/page/
cd web/page && npx -y serve .          # http://localhost:3000 — Chrome (WebGPU, File System Access)
scripts/page.sh                        # the readers in the page (the cut model + the skill) on the sample, in Chromium; GL=1 for WebGL2
scripts/publish.sh                     # the page to GitHub Pages: https://okay-effects.github.io/okay-desk/ (the weights in parts)
```

A proof of concept of okay-inlay `specs/assistant.md` (the "data" pack): what a small model does well — understand,
write a program, phrase — and what it never does — compute. [specs/data.md](specs/data.md).
