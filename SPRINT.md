# SPRINT — the phone model's line (from 2026-11-14)

What is being done now, each item with its check; done items move to their spec's results and leave this file.
The design: `specs/loop.md`, `specs/core-joint.md`, `specs/everyday.md`; the tools: `docs/toolkit.md`.

1. ~~The data readers with domains, measured~~ — done: no change beyond noise; template pairs teach names, not
   people's words (`specs/everyday.md`). Next for data: item 4, then the teacher's natural pairs over the domains.
2. **The teacher's second batch and one turn of the cycle** — running (`cycle-teacher2.sh`). Check:
   `compare.scala adapter-r32 teacher2`; the verdict in `specs/core-joint.md` §11.
3. **The understanding said** — on the page and published (715c961); left: words that show each mistake — (`specs/loop.md` §2.1) — a program → words (en/ru), exact; laws: every point of the
   space said and read back by the data alone to itself; on the page under every data answer. Check: the laws; a
   browser run shows it; `Everyday`'s wrong answers each read in words that show the mistake.
4. ~~A month in the calculus~~ — done (b0a4d55): every month question reads its range; en 23/9/16 (was 21/9/18).
6. **The teacher's natural pairs over the domains** — the teacher's "pairs" mode with `Domains` schemas, en/ru/uk/pl,
   judged by read-back; then the data readers trained with them. Check: `Everyday` by the protocol; "spend", plural
   values, translated values read right.
5. **The data readers' adapter** — the cycle of `docs/toolkit.md` for `Query` (heads of the data readers, the
   adapter through them), after 1 and 4. Check: `Everyday` and the sample by the protocol (seeds, no more wrong
   shown).
