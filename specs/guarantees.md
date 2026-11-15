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
