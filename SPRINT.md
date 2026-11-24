# SPRINT — the phone model's line (from 2026-11-14)

What is being done now, each item with its check; done items move to their spec's results and leave this file.
**The theory the work follows: `specs/guarantees.md`** (2026-11-15) — three parts, three guarantees; the order there.
The direction: `specs/direction.md` (the tools and the model's reading grow together, measured by the ceiling);
the design before it: `specs/loop.md`, `specs/core-joint.md`, `specs/everyday.md`; the tools: `docs/toolkit.md`.

**K. THE CORE MODEL FIRST** (the operator, 2026-11-24: "когда будет эта модель ядра?" — planned since 2026-11-18 as
theory.md E1, never done; nothing else in okay-desk before it). One small model judging whether a plain restatement
means what the person asked; the program walks the formal space, engines compute, the restatement is shown:

- K1 **the canonical reader** — measured 2026-11-24 (frames.md §5.15): NOT better on the table domain (the 1004:
  wrong 91 against l2's 49); the clauses stay for showing and for the cross-domain test, not as the tables' options.
- K2 **the domains' restatements and data** (by 2026-11-26) — tables (done), logic grids (`Said`), units and the
  calendar (okay-inlay `Units`); the clauses' words in Ukrainian and Polish too.
- K3 **the core model v1** (by 2026-11-27) — one adapter over the 0.6B (or the looped one, if not worse) trained on all
  the domains at once; measured on a domain it never saw and on each seen one, one table; on the page if it holds.
  MEASURED 2026-11-24 (frames.md §5.16): units 300/300, the tables unchanged (the 1004 928/27/49 against l2's 927/28/49),
  clues never trained 61/300. Next: the clues' texts in the mix; values linked by multilingual embeddings.
- K4 **self-play, every night** (the operator 2026-11-24: "как сделать модель самообучаемой?") — each domain's meanings
  drawn from its grammar and said (templates; the teacher's rewrites where it is free), read by the current adapter,
  the misreadings (the truth known: the meaning was drawn) added to the mix, the adapter trained again, accepted only
  through the gate (not more silent wrong, more right on Everyday, the 1004, each domain's held-out set), the doubt bar
  recalibrated, published. Never trained on the model's own unchecked answers.
- K5 **the person's choices as lessons** — on the page a choice where the reading asked is kept on the device and
  weighs the same line next time (as a link does); with consent, the kept choices join K4's mix.

**P. The phone first** (the operator, 2026-11-15: the result SEEN on his iPhone; a small model doing what a large one
does is the point) — `specs/phone.md`; these go before item 7's later parts and land the page the others use:

- P1 **the log-probability on the device** — `Qwen3Compute.score` returns one number per option, computed by a
  shader (log-softmax + gather), not the vocabulary's logits read back. Check: equal to the CPU within 1e-4; seconds
  a question in Chromium against 8.6 s (frames.md §6.1).
- P2 **the frame reader on the page, one adapter beside the 4-bit base** (`Adapted`, not merged), the frame's words
  shown, the cards in doubt; memory over 10 questions in a row on the phone profile (`scripts/phone-seq.scala`).
  Published. Check: `Everyday` in the browser as on the JVM.
- P3 **the operator's iPhone** — the 10 questions of phone.md §3.1 over the page's sample tables; he reports
  answers, seconds, reloads.
- P4 **two readers agreeing on the phone** — the second adapter, agreement as the doubt; phone profile, then iPhone.
- P5 **the size**, only if P3/P4 say 28 layers do not fit (a reload or > ~15 s a question): distilled on the choices
  or looped shared layers (guarantees.md §6.4).
- P6 **the prompt warmed while the question is typed** — built (phone.md §4.6: the phone's profile 9.3 → 6.6 s the
  first question, the desktop unchanged); left: the operator's iPhone.

**T. The theory's experiments** (specs/theory.md §10–§11; BACKLOG R) — after P, in this order, each against `Everyday`
over three seeds with right / asked / wrong, the phone profile's seconds where speed is the point:

- T1 **E4 PMI scoring** — no training; one change in how `Read.Score` weighs options.
- T2 **E5 exact first** — the data's exact words decide the holes the question writes itself.
- T3 **the Root** (R.1) — the router as the first refinement, refuted by the checks; replaces the page's heuristic.
- T4 **E2 / E6 decide only what changes the answer**, the surest hole next.
- T5 **E1 canonical words as the options**.
- T6 **E7 trie-packed scoring** — speed on the phone.
- T7 **the reverse reading as the second opinion** (BACKLOG M.6; the operator, 2026-11-18) — no training: the frame
  said back in words (`FrameSay`) and the QUESTION scored given it, `log P(question | said frame)`, beside the
  forward choice `P(frame | question)`; doubt = the two directions disagree on the best frame (among the forward's
  top-k). Measured on the 1004 calibration questions (`FrameCalibrate`) and `Everyday`, 0.6B and 1.7B, three seeds.
  Check: the share "agreed AND wrong" against the second adapter's (frames.md §5.7 — two adapters agree on the same
  wrong frame, more with size); the conformal bound of guarantees.md §6.1 holds on `Everyday` or does not; one more
  forward a question on the phone profile, in seconds. A negative result is written with its reason.
- T8 **the anatomy of the size gap** (the operator, 2026-11-18) — no training, the size curve's runs read again
  (frames.md §5.7, `okay-desk-private/size`): every question the 0.6B reads wrong and the 1.7B right, by the frame
  line that first differs (table / answer / part / where's column / where's value / group / order / limit / end)
  and by language. Check: a table of counts per line kind over the 1004 calibration questions and `Everyday`, three
  seeds; the share that is LEXICAL (a word of the question → a column or a value: curable by exact-first, the
  table's synonyms, the memory of readings) against STRUCTURAL (which aggregate, what is grouped or compared: needs
  the model's depth). Decides the model's path: the tools' side and a 0.6B, or the 1.7B on the phone / distilled
  on the choices' distributions.
- T9 **the account of the text** (theory.md §12; the operator, 2026-11-18) — no training, before anything is built:
  the wrong frames of the runs already made (the 1004 calibration questions, `Everyday`; 0.6B and 1.7B), each
  judged for a violation of the license — a content word of the question no part of the frame explains (omission),
  a part of the frame neither said nor a declared default (invention); the same over the RIGHT frames (false
  alarms). The first judge is the teacher (35B) given the question and `FrameSay`'s words, a sample checked by
  hand. Check: the share of wrong frames that violate, the share of right frames that do; against the second
  adapter's "agreed and wrong" (T7). Large and few false alarms → the account is built into the reading as the
  second opinion; small → written down as negative with the kinds of licensed misreadings.
- Then: E8 (memory), E3 / E9 (one matcher, soft labels), one code path.

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
