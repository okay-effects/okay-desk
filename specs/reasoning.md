# One chat, one reasoning process

The operator, 2026-11-08: «Факты о реальном мире модель должна быть способна собрать сама», then «не только для
решения логических головоломок но и для ответа на любой вопрос», then «один чат для всего. Любой вопрос это
головоломка которая возможно требует знаний о реальном мире».

## 1. The rule

There are no user-facing categories or modes for puzzles and ordinary questions. A question calls for a program
that closes its missing knowledge, computes what can be computed, and produces an answer with evidence. The model
authors operations; handlers supply facts and run exact calculations. Missing evidence remains missing.

The generality is the process, not a promise of universal solvability. Source coverage, reading quality, device
capacity and the implemented tool language bound what this version can answer. Provenance proves where text came
from; it does not prove the source's truth, relevance, completeness, or interpretation of a natural-language task.

## 2. The program and handlers

`okay.desk.know.Knowledge.Step` is the shared reasoning language:

- `Search(queries)`: author up to three search queries, collect source excerpts and revision links, then plan again.
- `Constraints(text)`: execute the finite-constraint tool (`puzzle/`), reading back the constraints and showing its
  grid and trace in the conversation. An unknown reading is clarified by buttons IN THE CHAT; the choice is a lesson.
- `Calculate(expression)`: exact arithmetic with copied operands, parentheses, unary signs and `+ - * /`.
  Rational arithmetic keeps repeating decimals as fractions and terminating decimals without rounding.
- `Answer(quotes)`: short exact quotations selected from collected sources, each with a checked source identifier.
- `Deduce(evidence)`: select typed readings; a compiled inlay Horn block computes the conclusion and proof (§7).
- `Insufficient`: stop without pretending the collected evidence settled the question.

Existing relational and Datalog programs remain the data tools in the same chat. The data readers and their
clarification dialogue still handle opened tables. Tool selection is grounded in schema names, language aliases
and cell values; unrelated questions are not forced through an opened table. An unsupported data reading goes to
the general reasoning loop. Constraints and arithmetic can run directly without loading a generating model.

`Model.Reason` is an effect. Recognized readings provide typed operations directly; an explicitly loaded generating
model may propose a JSON operation. `Knowledge.parse` checks it; `Reasoning` executes it and returns source evidence
and failures to the next step.
The loop is bounded to five steps, with repeated queries detected. The prompt treats source text as untrusted
evidence. A model-authored citation URL, invented quotation or uncollected source cannot become an answer through
this protocol. Free prose and model self-review cannot replace an executable inference program (§7).

## 3. Knowledge is shared

General questions use the Wikipedia search adapter, with no fixed list of question topics. The model can reformulate
queries after seeing results. Up to three excerpts are collected per query, with their page revision, canonical
revision URL and service retrieval date. The context contains up to four excerpts of 1,200 characters and the first
3,000 characters of the question. Collected sources are bounded to twelve, with 3,000 characters per source.
Search uses MediaWiki's [Search API](https://www.mediawiki.org/wiki/API:Search),
[TextExtracts](https://www.mediawiki.org/wiki/Extension:TextExtracts#API), and
[CORS support](https://www.mediawiki.org/wiki/API:Cross-site_requests).

Structured world holes in constraints use the Wikidata adapter described in `puzzles.md` §11, under the SAME
network permission as ordinary research. The application authors all queries and collects all candidate snapshots;
the person supplies neither answers nor JSON files. Sources are tool-specific, not user-selected question modes.

`World`'s inverse fact lookup is compiled by okay-inlay and run by `Forward`. `Knowledge.ground` also compiles a
source lookup head: only a collected identifier can supply a source and its provenance. Exact substring checks
bind the quotation to that selected source. Both compiled lookups are checked against `Interpret` and ordinary
hand-computed results. A quotation is limited to 600 characters and 100 words total per source per answer.

Sources and structured facts have separate disk entries under the internal prefix. General caches include the full
question as well as its hash, so a hash collision cannot supply another question's evidence. Retrieval dates remain
visible; caches are snapshots, not assertions of current truth.

## 4. The page

`App` has one `Ask` input. The former puzzle screen and its Read/Solve buttons are removed. Results, source links,
read-back, proof steps and clarifications all appear in the conversation. Data management is still a separate
screen for opening and closing files; it is not a reasoning mode.

Network access is off initially. A missing source produces the same in-chat choice **Use online sources** or
**Stay offline** for any question. Permission applies for the visit and can be revoked with **Turn off online
sources**. Offline cached evidence and exact tools remain usable. HTTP failures do not silently become facts.

The default is the native 12-layer student (`readers-small`). It runs on WebGPU, else WebGL2 with a small texture
pool and the output head on the CPU, else the CPU. The same chat collects facts and runs exact tools without
loading another generating model. Source collection does not require a free-text decoder. Recognized local
premises can go straight to the compiled inference machine; an unknown result can request external evidence.

## 5. Built and checked

`KnowledgeSuite`: operation parsing, compiled citation lookup equal to the interpreter, invented quotations and
source IDs rejected, revision provenance, cache identity, exact arithmetic and refused invented operands.
`ReasoningUiSuite`: one conversation containing arithmetic, a constraint problem needing world facts, then a
general question; explicit network permission, autonomous collection, caching and offline reuse, failure paths.
`AppSuite`: the existing data dialogue and the classic constraint task with an unknown reading clarified in chat.
The suites run on JVM and Scala.js; `web/fullLinkJS` builds the browser program. `scripts/puzzles.scala` drives
the SAME question box on desktop and phone, optionally with live source requests (`WORLD_LIVE=1`).

## 6. Not built

General web search, additional source adapters, source refresh policy,
source disagreement dialogue, learned entity disambiguation, more exact tool families, a larger open-question
evaluation set, and learned readers from arbitrary prose into typed facts and rules. Exact inference does not
certify source truth or the linguistic reading. These remain in `BACKLOG.md`.

## 7. Conclusions from compiled programs (built 2026-11-08)

The operator clarified the goal: the smallest practical model on a phone with WebGL2, using the exact machines
from okay-inlay to reason over collected facts. Free prose followed by another model judging it is not that design.
The separate writer and self-review experiment was removed from the application; `synthesize` is refused.

The common `Deduce` operation selects source spans, not model-authored axioms or prose conclusions. `Deduction`
reads each WHOLE selected sentence into a typed fact or rule, validates that the goal reads the actual question,
grounds universal rules over the named subjects, and runs `okay.inlay.Horn.Block` through `Compile` and `Forward`.
The result is proved, refuted by explicit negative evidence, unknown, or conflicting. A missing fact is never false;
conflicting premises do not imply arbitrary conclusions. The handler phrases the result and presents its proof,
including the source sentence for every used premise and rule. No fluent model-written assertion bypasses it.
Data answers also use the exact claim grammar; the separate model summary effect has been removed.

The first reading family is deliberately limited: `A is a B`, `A is not a B`, `Every B is a C`, negative universal
rules, and ground conjunctions `If A is a B and A is a C, then A is a D`. Questions are `Is A a D?`. Russian readings
include `A — B`, `A — не B`, `Каждый/Каждая/Каждое B — C` and `A — C?`, without guessing inflections. Subjects and
categories are copied and normalized, not a hard-coded list of topics. Unrecognized source prose stays unread.
This first implementation used a symbolic reader. The learned extension is recorded in §8; arbitrary prose remains open.

Capacity is explicit: 24 selected readings, 12 external sources, 64 grounded atoms, 64 grounded rules, up to eight
premises per ground rule. Over-capacity requests fail rather than using a partial knowledge base. A source or rule
can still be false or read in the wrong context. The UI therefore calls the result **exact inlay Horn inference,
conditional on the cited readings**. Provenance and exact derivation are separate from empirical truth.

`HornSuite` in okay-inlay checks compiled == interpreter == independent least-fixed-point evaluation on 100 random
programs, plus hand-computed conjunctions, cycles, alternative derivations and proofs. `DeductionSuite` checks
multi-hop sourced reasoning, explicit refutation, unknowns, conflicts, conjunction, source/goal fabrication and the
Russian forms on JVM and Scala.js. `ReasoningUiSuite` checks autonomous collection, local premises, cached evidence,
and refusal of free prose in the SAME chat. `scripts/deduction.scala` loads the real 12-layer student with WebGPU
hidden, executes a data question on WebGL2, then sourced inference and offline reuse; HTTP evidence is a fixture.
The original test used the symbolic grammar; §8 extends it to actual model reading before the compiled block.
The manifest is cache-first as well as the weights; reloading the model with the network disabled is also checked.
A narrow viewport and a host GPU do not establish physical-phone memory or speed.

The next contract was the learned reader over this interface: copied source spans and typed meanings,
abstention/clarification,
held-out language and source tests, then reduction of the core measured against that contract. The existing data
reader and its student weights are reused; no additional prose-generating model is part of the phone path.


## 8. The student reads new facts and rules (built 2026-11-08)

`FactReader` reads the SAME 12-layer student's hidden states. A small learned readout selects one of five
constructors: fact, negative fact, universal inclusion, universal exclusion, or unread. Another tags the source
words as first argument, second argument, or outside. Both arguments must be contiguous, disjoint, exact word
spans. No entity, category, number or axiom is generated from memory. The readout uses the final prompt state and
pooled word states for the constructor, and local/global states and their interaction for argument roles.

`FactReaderBench` trains on 2,560 synthetic linguistic examples, not asserted world facts, using the native
`ComputeReading` forward on MLX. English and Russian supervision covers class membership, explicit negation,
universal inclusion/exclusion and uncertain/reported/non-universal statements that must stay unread. Validation
and test have disjoint wording templates and entity names from training. Validation chooses confidence margins;
the final run accepted 68/160 validation examples and 106/160 test examples with zero accepted errors, at
constructor/role margins 0.5/1.0. This is a finite development experiment, not a guarantee for arbitrary prose.
Earlier versions failed: the last-state-only constructor misread polarity, and pooled features with the smaller
training set still accepted erroneous negations on the test. Those versions produced no installable artifact;
the constructor and argument correctness contract was retained while supervision was expanded. A subsequent
new-category audit exposed a dropped modifier (`natural satellite` read as `satellite`). Training was expanded
with composed multiword categories and confidence now covers outside tokens as well as selected arguments. The
final audit accepted five of nine cases, with zero accepted errors; the formerly wrong reading now abstains.
These nine cases are development regressions, not an independent test set.

The installed artifact is `model-small/facts.skill` (568,761 JSON bytes in this run), generated by:

```sh
sbt 'bench/runMain okay.desk.bench.FactReaderBench'
```

The default output is `~/.cache/okay-desk/facts-readers-small.json`. Local serving and packaging scripts stage it
when present. It is optional: a missing, invalid or wrong-layer readout cannot fabricate semantic support. The
ordinary chat loads the selected model when a typed membership question contains unread premises, or when its
collected sources do. It reads up to 24 new bounded source sentences per question, remembers attempted spans,
and reports abstentions. Canonical symbolic premises remain available without loading weights. Questions still
use the explicit `Is A a B?` / Russian dash grammar; this is the first reading family, not a general goal reader.

The accepted model readings travel with the typed `Deduce` CPU request. Its worker rechecks collected source
identity, whole sentence equality, copied word boundaries, allowed constructors and quotation budgets before
running inlay. Source text is data, never instructions or executable code. These checks establish provenance and
shape; only evaluation measures whether the natural-language interpretation was right. The proof retains the
source sentence and labels learned premises/rules **Model read**. As before, its conclusion is conditional on
those readings. The reader does not judge a prose answer that it previously wrote.

`FactReaderSuite` checks copied arguments, polarity, typed Worker round trips, multi-hop inference from learned
premises, and rejection of invented/partial source spans, overlapping arguments and unknown constructors.
`ReasoningUiSuite` checks automatic model loading and model-read premises through the SAME chat and tool path.
`scripts/deduction.scala` now supplies a marked HTTP fixture with new entity/category combinations and phrases
outside the canonical grammar, reads them with the real student on forced WebGL2, and checks the compiled proof,
source revision links, explicit negative reading and offline reuse. It also checks an abstained source rule,
then a proof after a model-read reformulation in the question. This does not measure accuracy on unrestricted
live encyclopedia prose. `FactReaderCheck <sentences.txt>` inspects an installed readout without retraining it.

Remaining: richer relations, conjunction and variable binding learned from prose, general question goals,
source-level ambiguity/context/quantifier tests on real documents, clarification after abstention, and training
or reducing the core against that expanded contract. A source can be outdated, false, hypothetical or quoted;
exact derivation does not make its interpretation or empirical truth exact.


## 9. Named relations and external documents (built 2026-11-08)

The existing student now reads six binary relation families: located-in, capital-of, part-of, orbits, parent-of and
borders, including explicit negative facts. `RelationsBench` trains a joint constructor readout and argument
readout on 4,640 linguistic examples. The established class readout must agree with class readings from this new
readout; adding relations does not give the new head sole authority over the existing class language.

Named arguments are complete copied candidates, including multiword names. The model ranks those candidates
using its argument-role scores. This replaced independent word decisions that sometimes dropped a name's last
word. The constructor also needs an explicit registered predicate cue in the original sentence, and explicit
binary negation must agree with its polarity. Recognized modal, reported and historical scopes abstain because
the current terms have no modal/temporal argument. These conservative checks are not a universal scope parser.

Validation chooses confidence thresholds separately for each constructor. The final experiment accepted 218/472
validation examples and 334/472 test examples, with zero accepted constructor/argument errors. The 24 positive
test examples per relation had accepted counts: location 17, capital 12, part 17, orbit 21, parent 12 and border 16.
Templates and names are disjoint from training; negatives and uncertain statements also use separate templates.
`RelationsAudit` rechecks the saved artifact without retraining or choosing thresholds. The installed bundle is
`model-small/relations-v1.skill`, 1,366,497 JSON bytes in this run, in addition to the existing class readout.
This development suite is small; its zero errors do not establish zero error on unrestricted source prose.

Earlier experiments failed either precision or per-family coverage, particularly on parenthood. A live audit also
exposed an unrelated historical sentence being classified as an orbit fact. It is now a regression: a sentence
without an orbit cue cannot license that constructor. Long numeric/source details initially caused abstention on
the Paris lead; supervision was expanded with numeric units and trailing clauses. These sources have consequently
become development regressions, not an independent held-out corpus.

`RelationsLive` fetches current Wikipedia extracts with revision identifiers and retrieval dates. It checks ALL
accepted readings against its manually audited expected set, then proves the capital-of-France and
location-of-Poland answers. Source segmentation keeps decimals and parenthesized details inside the sentence;
a quoted numeric detail is never parsed as a new arithmetic answer. This audit reads real external text, not a
synthetic HTTP response, but covers only a few documents.

The same question input accepts `Where is A?`, `What is the capital of B?`, `What does A orbit?`,
`What borders B?`, `What is A part of?` and explicit binary yes/no forms. These goal forms are still registered
grammars, not a learned reader for arbitrary questions. Open goals enumerate the derived bindings and preserve
proofs; missing evidence remains unknown. Research queries use the goal's named subject and relation-specific
phrases, avoiding a full question string that can retrieve irrelevant articles such as capital punishment.
There are no topic-specific answers or remembered entity identifiers in that plan.

Source rules can explicitly compose relations:

```
If X is part of Y and Y is part of Z, then X is part of Z.
If X is the capital of Y, then X is located in Y.
```

The registered rule grammar supports the six predicates in both positions of a chain and in its conclusion,
and single-premise transfer between predicates. No such rule is assumed merely because a relation exists. The
host enumerates bounded structural ground instances; `okay.inlay.Horn` computes truth through compiled weights.
Repeated premises are deduplicated, cycles cannot create a seed, and capacity overflow fails. An empty selected
premise set returns unknown. `RelationsSuite` compares grounded compiled closure with independent reachability
on 30 random graphs, and checks cross-relation rules, missing rules, negative evidence, names and source scope.

`Open` adds documents beyond encyclopedia search. A question may provide an HTTPS URL on its own line, followed
by its question. With online permission, the browser fetches HTML, plain text or Markdown; HTML is parsed into an
inert document, scripts/navigation are removed, and only text is sent to the readers. Documents are bounded to
1 MB before parsing and 12,000 stored characters. Source identity includes the address and a content hash, with
retrieval date and link retained. Cache entries allow offline reuse. Sites must permit CORS; unsupported formats
and HTTP/CORS failures stay explicit. This is not a server-side proxy or unrestricted general web search.

`scripts/relations.scala` runs the ordinary chat at phone width with WebGPU hidden: actual live Wikipedia
requests, automatic student loading, model-read bindings, then a separately labelled HTML fixture exercising a
sourced composition rule, inert scripts and offline reuse. The source revision links and proof are checked.
A host WebGL2 test does not replace measurements on physical phones.

The publication script requires both readouts, builds the chat and worker, and versions both against their
combined bundle hash. Reader requests carry that version as well, so an old cache cannot pair a new protocol
with a stale worker or readout. General question semantics, arbitrary rules, entity/coreference resolution,
robust temporal/modal interpretation and a much larger blind real-document evaluation remain open.


Publication verified on GitHub Pages on 2026-11-09: build `e49662ef7a04`, pages commit
`876034cb0cdfffefbc7f234289f9583af0a11b76`. The same `scripts/relations.scala` passed against
`https://okay-effects.github.io/okay-desk/`, including live sources and actual WebGL2 model execution.
The browser test exposed empty extracts for some full Wikipedia search results. Search now explicitly requests
introductory sections (`exintro=1`); the live public-site check covers this adapter path as well as title lookup.

## 10. Planned: a shared semantic compiler

The bounded house vocabulary is coverage for current inputs. General reading needs a shared typed program:
source-declared entities, domains and predicates; variables and goals; equality, ordering, adjacency, arithmetic,
cardinality, finite quantification and rules. Names and category values are copied from the question or collected
evidence, rather than selected from a fixed list of nationalities, animals or relation names. Different tasks
compose these primitives through the same chat and typed tool API.

The small model authors this program incrementally, with constrained outputs and source spans per premise.
Validation checks types, bindings, bounds and tool availability. Exact handlers return results, counterexamples,
conflicts or missing premises; these can guide the next operation. Type checking and a valid proof establish
consequences of the chosen premises, not correctness of the linguistic interpretation. Ambiguous scope, negation,
coreference and domain closure require read-back or clarification instead of an invented premise. External
evidence retains source, date and conflicts; absence of a statement does not establish its negation.

Extend the okay-inlay domains and their executable interpreters to generate programs, solved examples and
counterexamples for training the 12-layer reader. Phrase variation must be checked separately: a paraphrase is
not automatically a faithful label. Evaluation must hold out names, category schemas, paraphrase sources and
combinations of constraints, include changed premises and impossible/ambiguous cases, and measure whole-program
reading as well as slot accuracy. The exact handlers must have independent hand-computed laws.

The browser keeps neural reading on WebGL2 and bounded symbolic search in asynchronous CPU Workers. Inlay
compilation is used for supported exact blocks, with the interpreter as the reference; adaptive placement still
needs measured costs. Physical-phone memory, latency and responsiveness are acceptance checks. Universality here
means extensible composition without a separate parser per puzzle; solvability remains bounded by the formal
language, evidence, reading quality and computation budget. This semantic compiler is not yet implemented.
