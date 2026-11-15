# SPRINT — the phone model's line (from 2026-11-14)

What is being done now, each item with its check; done items move to their spec's results and leave this file.
**The theory the work follows: `specs/guarantees.md`** (2026-11-15) — three parts, three guarantees; the order there.
The direction: `specs/direction.md` (the tools and the model's reading grow together, measured by the ceiling);
the design before it: `specs/loop.md`, `specs/core-joint.md`, `specs/everyday.md`; the tools: `docs/toolkit.md`.

1. **The schema with values; `month` in `Query`** (direction §4.1) — built; check: `CeilingBench` again (first run
   en 41/7, ru 40/8).
2. **The first night's pairs measured** — the teacher's rewrites over `Domains` (1200 points, judged by
   read-back), the data readers trained with them (`night4.sh`). Check: `Everyday` beside the ceiling.
3. **`share`, `by_month`, `change`, a column against a column** (direction §2) — each with its constructor,
   exact run, words, laws. Check: the ceiling's outside questions answered right.
4. **The teacher as labeller of natural questions** (direction §3) — the teacher writes the program, a second
   reading agrees; the readers trained. Check: the gap to the ceiling on `Everyday`.
5. **The ceiling for logic** — the teacher writes the statement of the sealed logic set. Check: the gap.
6. ~~The reader that writes~~ — done: 27/20 en, 21/27 ru; the frame by choices is better (`specs/frames.md`).
7. **The frame reader to the page** (`specs/frames.md` §5.6) — two adapters agreeing, on the released 0.6B in the
   browser (WebGPU), then the phone's size: the student distilled toward the frame reader. Check: `Everyday` in the
   browser as on the JVM; the page asks where the two disagree.
8. **The frames' data** — income and spending (`type`), sum against count, the row of the largest; the teacher
   labelling natural questions as frames. Check: the three seeds again.
9. **Guarantees 1: the calibration** (`specs/guarantees.md` §6.1) — the teacher's natural pairs over new seeds as
   frames (never trained); λ̂ for ε by conformal risk control; the risk measured on `Everyday` at λ̂.
10. **Guarantees 2: the canonical fragment** (§6.2) — frames said (en, ru) and parsed back; laws over the option
   space; the reader's frame checked by the round trip.
