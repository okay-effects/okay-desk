# SPRINT — the phone model's line (from 2026-11-14)

What is being done now, each item with its check; done items move to their spec's results and leave this file.
The design: `specs/loop.md`, `specs/core-joint.md`, `specs/everyday.md`; the tools: `docs/toolkit.md`.

1. **The data readers with domains, measured** — running. Readers trained on this core's own states, without
   and with `Domains` pairs (`EverydayBench DESK_TRAIN=1500`, `DESK_DOMAINS=50`). Check: `Everyday` right / asked /
   wrong by language against the page's skill (en 21/9/18, ru 10/23/15); the result in `specs/everyday.md`.
2. **The teacher's second batch and one turn of the cycle** — running (`cycle-teacher2.sh`). Check:
   `compare.scala adapter-r32 teacher2`; the verdict in `specs/core-joint.md` §11.
3. **The understanding said** (`specs/loop.md` §2.1) — a program → words (en/ru), exact; laws: every point of the
   space said and read back by the data alone to itself; on the page under every data answer. Check: the laws; a
   browser run shows it; `Everyday`'s wrong answers each read in words that show the mistake.
4. **A month in the calculus** (`specs/loop.md` §2.3) — `Space.month` drawn, a constructor in the grammar, `Den`,
   `Encode`, `TablesDomain`, the exact source reading month names (en/ru/uk/pl). Check: the calculus laws with
   months; `Everyday`'s month questions read right by the data alone.
5. **The data readers' adapter** — the cycle of `docs/toolkit.md` for `Query` (heads of the data readers, the
   adapter through them), after 1 and 4. Check: `Everyday` and the sample by the protocol (seeds, no more wrong
   shown).
