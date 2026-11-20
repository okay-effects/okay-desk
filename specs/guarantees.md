# Guarantees: what a small neurosymbolic model can promise, and how (2026-11-15)

Decided with the operator after the frames' night (`specs/frames.md`). The aim: a method — not a lucky search —
that builds language models as small as possible whose answers are right with a guarantee, for a defined class of
questions. This file is the theory the work follows; the sprint's items point here.

## 1. What cannot be guaranteed, and why

No reader — small, large or human — answers every free-language question right: the language itself does not fix
one meaning ("the largest payment": the amount or the row? "spent": every operation or the outgoing ones?). A right
answer exists only relative to a chosen meaning. So the guarantee is not "always right"; it is built from parts
that can each be guaranteed.

## 2. Three parts, three kinds of guarantee

| part | what it is | its guarantee | ours |
|---|---|---|---|
| computation | the program run, the solver, the proof | absolute: proved or checked by laws against hand results | `Exact`, Datalog, the solvers; `LanguageSuite`, `ExactSuite` |
| the language of problems | the formal model's form and each slot's options (`Frame`) | by construction: finite, enumerable, every form compiles; the model can only choose what the tools offer | `Frame.next`, `Frame.query`; `FrameSuite` (every measured question reachable) |
| understanding | text → the formal model | the only uncertain part: one of §3's three | the frame reader (`FrameRead`) |

## 3. The guarantee of understanding, three strengths

1. **A bounded error rate (conformal risk control).** A calibration set, disjoint from training and from the
   measure, sets the doubt's bar λ so that the expected share of questions answered wrong is at most ε:
   λ̂ = the smallest bar with (n·R̂(λ) + 1) / (n + 1) ≤ ε, R̂(λ) the calibration's share answered wrong at λ. The
   bound holds for questions drawn as the calibration's are (exchangeable): a calibration from the domains bounds
   questions like the domains'; a bound for people's questions needs a calibration of people's questions (their
   corrections, §2.2 of `specs/loop.md`). The doubt's scores: the smallest margin of the frame's choices, and the
   agreement of readers trained apart (`specs/frames.md` §5.6).
2. **Faithfulness to a meaning shown.** The frame said back in words by the exact side (`Understood`, the frame's
   words) before the answer; the answer is the exact result of exactly that meaning. Law: the words determine the
   frame (said, then parsed back: the same frame) — what the person reads is what was computed.
3. **Absolute on a canonical fragment.** The canonical sentences — the frames said by the grammar — are parsed by a
   deterministic parser, the inverse of the saying: right on every one, a law over the whole option space. The
   model's job narrows to bringing free words to a frame; the round trip (frame → canonical words → parser → the same
   frame) checks its frame is one the words can carry.

The promise shown with every answer: **the exact result of a formal model shown in words, that passed an
independent check; the misreadings bounded by a calibrated rate; everything in doubt asked, never answered.**

## 4. Why the model can be small

The engines reason; the model only understands: synonyms, cases, paraphrase, the question's words matched to the
options. Each step is a choice among few options conditioned on the text — recognition, not generation, not
reasoning. It needs knowledge of language (stored in weights), not depth of thought. What was measured
(`specs/direction.md`, `specs/frames.md`):

| learnt | used so |
|---|---|
| the teacher's ceiling, 43/54 with our tools | the tools are enough; the teacher's misses say what the language lacks |
| heads trained from nothing do not learn wordings | understanding goes through the model's own language head, where its knowledge is |
| choosing among options beats writing the program | the model never writes; it chooses |
| two readers' agreement cuts the wrong answers fourfold | the doubt is agreement and margin; calibrated (§3.1) |
| the student matched to layer 19's states reads frames worse | a smaller model is distilled on the CHOICES (the outputs), not on hidden states |
| the data's mix decides (the refusals lost in round 2) | every class, the refusal too, has its examples; the mix is part of the method |

## 5. Looped (recurrent-depth) transformers

The same layers run k times: depth without more weights (NVIDIA's Dory as reported; not checked here). Depth buys
computation steps — reasoning; knowledge lives in weights and does not grow by repeating them. Our design moved the
reasoning to the engines, so the direct gain is smaller for us than for agentic tasks. Its use here: COMPRESSION —
a pretrained model's middle layers made one shared block with a small LoRA per pass (relaxed recursive
transformers, 2024 — to be checked before the trial), fine-tuned on frames, measured on the size ↔ right/wrong
curve beside distillation on choices. At the system's level the loop that gives correctness exists already: the
model chooses one line, the tools narrow the next line's options, the checks look at the result.

## 6. The order of work

1. **This file; the calibration.** A calibration set of the teacher's natural pairs as frames (new seeds, never
   trained); `FrameBench` reports λ̂ for ε and the risk measured on `Everyday` at λ̂. Next training keeps the
   calibration's seeds out.
2. **The canonical fragment.** `Frame` said in canonical words (en, ru) and parsed back; laws: every frame of the
   option space said and parsed to itself; the parser right on every canonical sentence. The frame reader's output
   checked by the round trip; the words shown as "Understood as".
3. **The page.** The frame reader, two adapters agreeing, the bar λ̂, the words shown, a question where in doubt.
4. **The size.** Distillation on the choices; shared looped middle layers with LoRA; the curve size ↔ right/wrong
   at the same ε; the phone's point chosen.
5. **The same method for logic.** A task → its formal model by choices (variables, constraints), the solver, the
   round trip.

## 7. Built

- §6.2, the canonical fragment: `FrameSay` — a frame said clause by clause in English or Russian (every name and
  value in «»), and parsed back. Law (`FrameSaySuite`): 11 400 frames drawn at random over the option space of the
  measure's and 40 domains' tables, said and read back to themselves in both languages — the words determine the
  frame. ("table «expenses»; share of the sum of «amount»; the part where «category» = «transport»".)
- §6.1, the calibration: `FrameBench` over any set, its records; `FrameCalibrate` — λ̂ for ε by conformal risk
  control, one reader's margin or two readers' agreement; the calibration set: the teacher's natural pairs over the
  domains, seeds 2001–2400 (never trained). Results below when measured.
- The question in doubt (the operator, 2026-11-15): where a line's margin is below the bar, the reader stops and
  asks the person — that line's best options, said in canonical words (and, on the page, each with its answer
  computed) — their choice is the line and the reading goes on; none of them: unanswered; at most two questions. A
  disagreement of two readers asks at the first line where their frames part. Every choice is a pair checked by a
  person: training data, and the calibration of people's own questions (§3.1). `FrameRead.Dialogue`; measured by
  `FrameBench` with `FRAME_DIALOGUE`, the person played by the gold frame: the right after the dialogue, the
  questions it cost.

## 8. The first calibration (2026-11-15): one reader (round 3), 1004 questions never trained on

The frame reader on the teacher's natural questions over the domains' tables (new seeds, 4 languages): right
en 243/263 (92%), ru 214/256 (84%), pl 176/245 (72%), uk 176/240 (73%) — 81% of all with no doubt.

| ε | λ̂ | calibration right / asked / wrong | `Everyday` (other tables) right / asked / wrong | `Everyday`'s risk |
|---|---|---|---|---|
| 0.02 | 0.947 | 461 / 523 / 19 | 21 / 85 / 2 | 0.019 |
| 0.05 | 0.635 | 652 / 302 / 49 | 28 / 71 / 9 | 0.083 |
| 0.10 | 0.278 | 766 / 138 / 99 | 50 / 37 / 20 | 0.185 |

- In its own distribution the bound holds by construction (conformal): at ε = 2% the reader answers 46% of the
  questions and is wrong on under 2%; the rest it asks.
- On `Everyday` — other tables, other people's wording — the 2% bound held (0.019), the looser ones did not
  (0.083 for 5%, 0.185 for 10%): the shift costs a factor near two. A bound for people's questions needs a
  calibration of people's questions (their choices on the page: §3).

### 8.1 The phone's reader calibrated (2026-11-18): round 5 seed 1, merged

The same 1004 questions (records with no dialogue, as the page reads: link 2, guess 2, pruned); test = `Everyday`:

| ε | λ̂ | calibration right / asked / wrong | `Everyday` right / asked / wrong | `Everyday`'s risk |
|---|---|---|---|---|
| 0.02 | 0.705 | 634 / 350 / 19 | 47 / 59 / 2 | 0.019 |
| 0.05 | 0.383 | 749 / 205 / 49 | 65 / 38 / 5 | **0.046** |
| 0.10 | 0.144 | 788 / 116 / 99 | 76 / 20 / 12 | 0.111 |
| 0.15 | 0.051 | 800 / 53 / 149 | 80 / 13 / 15 | 0.139 |

At 5% the reader answers 75% of the calibration's questions itself (round 3: 65%) and the bound now holds on
`Everyday`'s other tables too (round 3: 0.083 there) — the shift's factor of two is gone at 5%, not yet at 10%. The
page's one-reader bar is λ̂(5%) = 0.38 (was 0.3, set by eye). The dialogue asks at most two lines a question: a
third line in doubt is read on unasked — a small leak beside the bound, the operator's marks will show its size.

### 8.2 The tools fixed, the same reader (2026-11-18)

The frame language's reach measured (`FrameLaws`, okay-frames' law `reach`) on the 970 never-trained questions with
a gold frame: 26 gold lines were never offered — dates said as «11.01.2026», "10 marca 2026", «10 марта» (read as
the number 11.01 or a limit of 10) and «топ-3» (read as −3) — and 120 more were pruned away as the page prunes: a
text value the question says in its own language («автобусом», «на вынос», «тварин», «теплиці») is not linked to
`bus`, `takeaway`, `animals`, `greenhouse`, and an unlinked text value was not offered. Both fixed: dates are days,
not numbers; text values are kept (only unwritten numbers pruned). The same merged reader on the 1004:

| tools | right / asked / wrong (no dialogue) | a question (JVM) |
|---|---|---|
| as published | 807 / 3 / 194 | 0.7 s |
| dates and «топ-3» | 827 / 3 / 174 | 0.7 s |
| text values kept | **863 / 3 / 138** | 0.8 s |

`Everyday` unchanged (84 / 0 / 24). Calibrated again: ε 5% → λ̂ 0.325, the calibration's 795 / 159 / 49 (the reader
answers 79% itself, was 75%), `Everyday` at it 68 / 34 / 6 (risk 0.056). The page's bar 0.33. A wrong answer the
model could not avoid is the tools' to fix: measure reach before training more.

### 8.3 The tries-trained reader on the phone, calibrated (2026-11-20)

The phone now runs the reader trained in tries (frames.md §5.13: lr 2e-4, seed 1) as an adapter beside the 4-bit
base — merged at 4 bits it breaks; the operator's iPhone ran it beside. No dialogue: the 1004 877 / 4 / 123 (round 5
merged 863 / 3 / 138), Everyday 89 / 0 / 19 (84 / 0 / 24); 1.1 s a question on the JVM.

| ε | λ̂ | calibration right / asked / wrong | `Everyday` right / asked / wrong | `Everyday`'s risk |
|---|---|---|---|---|
| 0.02 | 0.416 | 781 / 203 / 19 | 76 / 26 / 6 | 0.056 |
| 0.05 | 0.186 | 838 / 114 / 49 | 82 / 14 / 12 | 0.111 |
| 0.10 | 0.049 | 870 / 31 / 99 | 87 / 4 / 17 | 0.157 |

The shift to other tables doubles the risk again (5% → 11%), so the page's bar is λ̂(2%) = 0.42: on `Everyday` the
same 5.6% as round 5's bar gave, with more answered (76 against 68) and on the calibration a third of the wrong
(19 against 49).

## 9. The dialogue and two readers, measured (2026-11-15)

The dialogue (`FRAME_DIALOGUE`, the person played by the gold frame; the round-3 reader; `Everyday`):

| asked below the margin | en right / unresolved / wrong | ru right / unresolved / wrong | questions to the person a question |
|---|---|---|---|
| none | 33 / 1 / 20 | 24 / 2 / 28 | 0 |
| 0.3 | 35 / 3 / 16 | 31 / 8 / 15 | 0.39 |
| 0.5 | 36 / 6 / 12 | 32 / 8 / 14 | 0.57 |
| 1.0 | 39 / 6 / 9 | 33 / 9 / 12 | 1.04 |

At about one press a question the phone's reader comes near the 35B's ceiling in English (39 against 43) and is wrong
less often than it (9 against 11). The page's bar: 1.0.

Two readers (rounds 3, seeds 1 and 2) agreeing, their smallest margin the score, calibrated at ε = 2%: λ̂ 0.104 —
calibration 653 right / 332 asked / 19 wrong (one reader: 461 / 523 / 19); `Everyday` 40 / 63 / 5, risk 0.046 — the
bound did not carry to the other tables this time. A bound for people's questions needs their own calibration.
