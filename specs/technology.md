# The technology: small, extensible, modular, specialized neurosymbolic language models

The specification of what this project studies and builds. `docs/textbook.md` explains each component and what
is known about it; the other specs are the diaries of the lines of work. This file says what the technology is,
what its parts are and how they meet, how a result is judged, and which questions are open.

Decided by the operator, 2026-11-11.

## 1. The aim, and the way of working

**The aim** is the technology, not one application: language models that are SMALL (a phone's browser holds and
runs them), SPECIALIZED (each reads one kind of thing well), MODULAR and EXTENSIBLE (a language or an ability is
added beside an unchanged base), and NEUROSYMBOLIC (the model reads; exact engines compute). What is learnt here
is meant to be used elsewhere.

**okay-desk is the task the technology is developed on.** Its goal as an application: in a browser on a phone or
a computer, on WebGL2 or WebGPU, with no server, solve logic tasks and answer questions over data from the files
given or from the internet; in English, Russian, Ukrainian and Polish; later, say the answer in natural language.

**This is research.** We do not reach the goal at any price; we study the problem and look for the right, optimal
solution. That fixes how the work is done:

1. A QUESTION BEFORE A CHANGE. A step starts from a question and a guess at its answer, and ends with a
   measurement that could have refuted the guess.
2. NEGATIVE RESULTS ARE KEPT, with the reason believed for them. What did not work is knowledge.
3. ALTERNATIVES ARE NAMED. A choice is written with what it was chosen over and why; an implementation is one of
   several behind one interface, never a hidden decision.
4. NO EXPERIMENT IS WEAKENED TO PASS, and every number in a document comes from an experiment's output.
5. THE NOISE IS MEASURED before a difference is believed (§5).
6. EVERYTHING DISCUSSED IS WRITTEN IN THE PROJECT: the aim, the decisions, the questions — here; the explanation —
   in the textbook; the diary — in the line's spec.
7. THE OPERATOR WANTS TO UNDERSTAND IT, not only to have it: a result is reported with its mechanism, its reason
   and what is still unknown.

## 2. The principles of the technology

1. THE MODEL READS, ENGINES COMPUTE. The model turns text into a formal object and so chooses the engine. It
   solves nothing. What an engine did not produce is never shown.
2. A READING IS A VALUE: typed, small, shown to the person, checked by laws, comparable with a gold one. A reading
   not finished is a value too — a question — never an error.
3. THE MODEL ONLY WEIGHS what the grammar offers. It chooses words, never facts. Exactness is a property of the
   grammar and of the data, not of a strategy.
4. THE PROMISE IS A MEASUREMENT: exact for the question as read, the reading shown, the rate of wrong readings
   known. Unsure, it asks about the place it is unsure of.
5. IT READS, IT DOES NOT GENERATE — one forward of the text; a formal object aligned to the text.
6. THE BASE DOES NOT CHANGE; everything else is a file beside it.
7. EVERYTHING IS AN EFFECT: a program describes, a handler gives the meaning — the device, the model, the source
   of a decision, the strategy, the way an answer is said are all handlers, and so replaceable and testable
   without a model.
8. ONE PROGRAM on the bench and on the page: a number measured is the page's number.

## 3. The components and where they meet

| component | takes | gives | implementations today |
|---|---|---|---|
| text side | a text | tokens, their places in the text | Qwen3's tokenizer; embedding blocks by script |
| base model | tokens | a state at each token | the 12-layer student of Qwen3-0.6B (122 MB) |
| device (`Compute`) | tensors, ops | tensors | `OnCpu`, `OnWebGpu`, `OnWebGl2`, `OnMlx` |
| model (`Read`) | a prompt | states, logits | `OnQwen`, `ComputeReading`, `MlxReading` |
| bus | a model's states | states under a contract | identity, ridge map, distillation |
| reader | states of a text | one decision and its margin | probe, pointer, ask, looks, tags |
| formal language | — | what may be written at a hole | the calculus's grammars; the statement |
| source (`Weigh`) | a hole, its candidates | a distribution | data, readers, tokens, person, lesson, organ |
| strategy (`Among`) | weighed candidates | a term, closed or open | greedy, every, beam |
| compiler (`Den`) | a closed term | an engine's program | per grammar |
| engine | a program, the data | an exact result | `Exact`, okay-datalog, `Solver`, `Horn.Block` |
| gate | a reading, its margins | shown / asked | margins, words never met, the exact filter |
| saying | a result, a language | a sentence | `Answers`, `Claims`, `Say`, `Said`, quotes |
| dialogue | an open term | a question and its options | `Dialogue`, acts, lessons |
| corpus | a space, settings | texts with their gold reading | generator × phrases; drawn; teacher + judge |

Where the libraries are: the effects — okay-scala; the model's forward and devices — okay-llm; compiled programs,
the bus, domains, the bridge — okay-inlay; Datalog — okay-datalog; the page's screens — okay-ui; the application
and, for now, most of the components above — okay-desk. A component that has settled moves out of okay-desk into
the library it belongs to.

## 4. Modules

A module is a file (or a few) loaded beside the unchanged base.

- **A language**: its embedding block; its `Language` record (one file for reading and for saying); its part of
  each reader (the words met, the bars; an adapter if a measurement shows it needs one).
- **A dataset's words**: its names in a language.
- **An ability**: a grammar (a kind of problem), its compiler and engine, its readers, its corpus, its way of
  saying an answer. A router — a reader like any other — chooses the ability for a text.
- **A reader**: small matrices in a file; valid for every model that gives the same bus.
- **An adapter**: low-rank factors beside the base's weights, kept apart from them.

The test of a module: it is added and removed without retraining or rebuilding anything else, and the laws of the
rest still hold.

## 5. How a result is judged

- THREE COUNTS by language: shown and right; asked; shown and wrong. The target for a page: under 1% wrong of the
  shown, the share asked reported beside it.
- SETS: the wordings taught (Validate, Test); whole families never taught (Unseen — a development set); a SEALED
  set written by another hand, reported as totals only; the collection on the page.
- A CONTROL THAT SHOULD FAIL in every new measure.
- THE SPREAD: a configuration is run several times (heads from different seeds) and the spread stands beside the
  number; a difference inside the spread is not a finding. (Not yet done for the statement reader:
  `formalization.md` §14.)
- THE PHONE'S PROFILE: the same answers from the page on WebGL2 with the head on the CPU; size in MB, seconds a
  task, peak memory. And a real phone, which no measurement here has yet been.
- LAWS WITH NO MODEL for everything on the exact side.

## 6. The research questions

Each has a guess, a way to test it, and a state. The textbook's chapter 16 lists them for a reader; this is where
their state is kept.

1. **Can a causal base be read as if it saw both ways, with no new weights?** Guess: saying the task twice does
   it. Test: the reader on the same corpus, read at the second saying, against the spread of four seeds. State:
   MEASURED (`formalization.md` §14–§14.1) — yes inside the wordings taught (above every seed) and for Russian
   (above every seed on every held-out set); no for other hands' English: that failure is not the direction of
   reading.
2. **Is variety of authors the lever for other hands' wording?** Guess: yes, and a teacher model with an exact
   judge can supply it. Test: a corpus rewritten by the teacher in four languages, measured on the sealed set.
   State: RUNNING (`formalization.md` §15) — the teacher, its judge (97% of lines on originals) and the loop of
   repair are built; 200 training tasks × 4 languages being written.
3. **Are heads over frozen states enough, or must the model itself be trained?** Guess: heads are not enough
   beyond a point. Test: on one corpus, heads alone against heads with a low-rank adapter. State: after 2.
4. **Does the exact side inside the reading turn "asked" into "right" without more "wrong"?** Guess: yes for
   puzzles — a misreading mostly gives none or several solutions. Test: the candidates of unsure decisions
   filtered by "one solution, every clue used". State: open.
5. **Can the calculus and the statement be one formal language?** Guess: yes — a `Problem` is a grammar, a reader
   is a source of `Weigh`. Test: the statement reader re-expressed with its numbers unchanged. State: open.
6. **Can one router choose the ability?** Guess: yes, a probe on the whole text. Test: the collection's classes
   in held-out wordings. State: open.
7. **How is an answer said naturally with every figure the engine's?** Guess: a grammar of answers, a model
   weighing among wordings. Test: each implementation behind one interface, compared. State: later.
8. **How small can the base go?** Guess: below 122 MB with the reading intact. Test: 2-bit MLPs, a narrower
   student, under the same readers. State: open.
9. **How large is the run-to-run spread of a reader?** Guess: larger than assumed. Test: the same run with heads
   from four seeds. State: MEASURED (`formalization.md` §14.1) — a point inside the wordings taught; 6 to 15
   points out of them. Every comparison is now made over at least three seeds.
10. **Does "in weights" buy anything a CPU tool does not?** Guess: only training through an exact interface
    might. Test: okay-inlay's bridge-2 and bridge-5. State: open, in okay-inlay.
11. **Does it hold on a real phone?** Guess: yes at the measured sizes. Test: the page by its plain address on
    the operator's phone. State: open.

## 7. The order of work

1. The spread of the reader (question 9) — cheap, and every later comparison needs it.
2. A corpus by a teacher, four languages, judged exactly (question 2).
3. Heads alone against heads + adapter on that corpus (question 3).
4. The solver inside the reading, and the dialogue for what stays open (question 4).
5. One formal language and the router (questions 5, 6); then the abilities one by one: Derive and Plan for the
   rest of the logic tasks, Query for data, facts from documents and the internet.
6. Saying answers (question 7).

Each step is written as: the question, the guess, the mechanism, the measurement, what it says, what is not known —
in the line's spec; then the textbook's chapter is brought up to date.

## 8. What is known elsewhere, and what it changes here (2026-11-11)

Each component has prior art; the question is whether we use it as well as it is known to work. Read so far:

- **Logic puzzles from text to a solver.** Mitra & Baral (EMNLP 2015, Logicia: clues learned into ASP);
  ZebraTutor (typed FOL → IDP); NeuralExactCover (arXiv 2611.03641, Nov 2026): an off-the-shelf model writes one
  tool call per clue, an exact-cover solver checks it — none: "overconstrained", several: "underconstrained", the
  model revises. ZebraLogic, all 1,000 puzzles, by Qwen3 size with the loop: 0.6B 2.2%, 1.7B 28.1%, 4B 84.9%, 8B
  88.3%, 14B 94.4%. Their failures are the language module's (18 of 21), names in another form fixed by fuzzy
  matching. CHANGES: (1) ZebraLogic and ZebraExtreme are public puzzles of other authors — an external test beside
  our sealed set, with numbers to compare the 122 MB reader against; (2) the solver's verdict as feedback is what
  §16 does among the reader's candidates.
- **Synthetic data, paraphrased, filtered.** Genie, Schema2QA, AutoQA (Xu et al., EMNLP 2020): grammar-generated
  pairs + automatic paraphrases kept only when a parser trained on the synthetic data reads them back to the same
  program; without the filter about 18 points lower. §15 is this method with a large model as the paraphraser and
  the judge. CHANGES: none needed; their result is the reason to expect the teacher to help.
- **Probing a frozen model vs training it.** Kumar et al. (ICLR 2022): fine-tuning can distort features and lose
  out of distribution; LINEAR PROBING THEN FINE-TUNING (LP-FT) is best in and out of distribution (also for
  language models: Tomihari & Sato, NeurIPS 2024); reading several layers beats the last (Head2Toe). CHANGES:
  lever 3 is LP-FT — the trained heads first, then a low-rank adapter with the heads at a small rate; and a
  reader of more than one layer is a cheap trial.
- **Aligning the program to spans.** Herzig & Berant (ACL 2021): a parser that composes programs over spans of
  the input generalizes compositionally far better (61.0 → 88.9 on compositional splits). The statement aligned
  to the text (§3 of formalization) is on that side.
- **Reading a causal model both ways.** Springer et al., "Repetition improves language model embeddings" (ICLR
  2025): the input said twice, read at the second saying — with the model ASKED TO REPEAT it ("rewrite the
  sentence"), which the authors name as a key ingredient. Our §14 said it twice with no such instruction.
  CHANGES: try their prompt.
- **Abstention with a guarantee.** Conformal abstention / selective conformal risk control: a threshold set on a
  calibration set so that the error among answered is under α with a stated probability — under exchangeability,
  which does not hold out of the corpus (hence the words-never-met sign). CHANGES: the bars (now "99 of 100 of
  Validate") become a conformal risk bound; out of the corpus the words still decide.
- **Decision / System One models** (TypeSafe Jev, Liquid d1, Sept–Oct 2026): one forward, typed answers with
  probabilities — the reader's form, named. GLiNER2 / GLiNER2.5-multi (mDeBERTa 287M): schema-driven spans and
  relations in many languages — the closest ready model to a statement reader; a candidate base (question 3).

## 9. The direction and the tool (the operator, 2026-11-13)

**What the model is for.** A phone's small model does not chat and does not know the world — a large model in the
cloud does both better. What it does better than any of them: it turns a person's request into an EXACT PROGRAM
OVER THAT PERSON'S OWN DATA — private (the data stays on the phone), exact (an engine computes every number),
checkable (the program is shown), offline. So:

1. **Data analysis is the main ability** (`Query`): filters, sums and means, groups, top-N, periods compared,
   changes in percent, joins, dates — over files a person opens (spreadsheets, statements, sales, stock, marks).
2. **Calculations** (`Calculate`): everyday arithmetic said in words — discounts, percents, a loan, a bill split,
   units — the model writes the expression, the engine computes.
3. **Constraints** (`Assign`): logic tasks are its TRAINING GROUND (perfect labels, endless generators, exact
   judges); its real use is schedules, shifts, seating — the same reading of conditions.
4. **Rules** (`Derive`, Datalog): who is entitled to what, chains of "if … then".
5. **Data from the internet** as a source for 1: the model reads WHAT IT SEEKS into a typed need (entities chosen
   from a catalogue — Wikidata, World Bank, Eurostat, OWID —, the measure, the period, the unit, the computation)
   and WHAT IT FOUND into typed evidence (columns, units, period, source); the exact side checks that the evidence
   meets the need, else searches again or asks; every number shown carries its source. Structured open sources
   first, tables of Wikipedia next, a general search last.

One base, the abilities as modules, one router choosing the ability. Not on the phone: questions about the world,
long writing, free conversation; an answer is said from the engine's result.

**The measure of usefulness comes first**: a set of realistic tasks — tables like real ones (expenses, a shop's
sales, stock, a schedule, shifts, marks, a bank statement) and questions that need the internet, with checked
answers (the internet's against a fixed snapshot), in the four languages. It replaces puzzles as the main measure;
puzzles stay the measure of the reading of conditions.

**The aim includes THE TOOL, not only the model.** What is built is a way of making such models: for a new ability
or a new application, one states its formal language and its solver, and the tool provides the rest —
- task SOURCES: a program's generator, a teacher's rewrites, exercises from open textbooks, human question sets
  (Spider/BIRD, PAUQ), each turned into (text → formal statement) pairs;
- VERIFIERS that keep a pair only if it is right: the exact solver read back, the query's result on the data, a
  textbook's answer, the compiler;
- OBJECTIVES: the heads' gradient through the core (`HeadGrads`, `JointTrain`), a pull toward a reference state,
  tokens' cross-entropy;
- the BASE chosen and made small (cut, narrowed, distilled — `specs/core-next.md` for what that costs);
- MEASURES by a fixed protocol: held-out, sealed, other hands, seeds, silent wrong answers as a hard limit; one
  history of runs;
- the PHONE'S FORMAT: an adapter and heads beside an unchanged 4-bit base, loaded in a browser on WebGL2.
It grows from okay-inlay's okay-forge (`specs/core-joint.md` §7): one pipeline with exchangeable parts, okay-desk
its first user.
