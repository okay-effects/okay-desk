# Language-model understanding in the browser

The operator's requirement (2026-11-09): the language model itself reads the entire arbitrary task, understands
its premises and question, and builds the formal model. Topic dictionaries and a symbolic natural-language parser
must not substitute for the model. Inference must run on the phone through WebGL2.

## 1. Implemented experimental path

The shared model picker offers the native 28-layer interpreter alongside the standard reader and WebLLM models.
`Model.authors(modelName)` sends the original question to `Model.Author`, automatically loading the selected
model. `?understanding` remains a compatibility shortcut that initially selects this model; it does not override
later choices. `Model.Authored` polls progress. The native path runs `NativeCompiler` through the actual Qwen3
output head
on `OnQwen`; `Read.Note` publishes progress. WebGL2, WebGPU and CPU use the same program. A separate WebLLM model
is not loaded by the native path.

The model authors the whole JSON program. `Knowledge.authored` accepts a formal model, a typed data query,
source search/open, exact arithmetic steps, a previously computed receipt, clarification or an unsupported capability.
It rejects legacy `constraints` and `deduce`
operations that would hand natural-language interpretation back to a symbolic reader. The new UI path does not
call the dataset-reference gate, `PuzzleText`, `GridText`, `Deduction.goal` or the fact/rule sentence reader.
The existing default path remains available while the new model fails its generalization checks.
The experimental path also bypasses `Turn.decide`: every nonempty user message, including dialogue keywords,
reaches the language model. A model-authored clarification preserves the original question, evidence, receipts
and previous feedback; the next user reply resumes that task through the model instead of a lexical dialogue rule.

## 2. The formal language

`Formal` accepts a model-authored `vars` object (finite integer, Boolean or text domains), `where` expressions and
`ask` expressions. Names and goals are data, with no nationality, animal, cigarette or topic vocabulary.
Expressions include exact rational arithmetic, equality, order, Boolean connectives, implication, all-different,
conditionals and counts. There is no host-language execution or arbitrary JavaScript API.

`Computation.Job.FormalModel` executes in the existing asynchronous CPU Worker pool. Forward checking and bounded
exhaustive search distinguish proved, inconsistent, underdetermined and budget-exceeded results. Budget exits
publish no partial answer. The work bound is 100,000 checks by default; the program bounds are 40 variables,
32 values per domain, 128 constraints and 32 goals. Native authoring has a 3,072-token context and a 1,024-token
output budget on desktop. Native phone authoring is bounded to 1,536 context tokens and 384 output tokens;
it stops explicitly rather than silently truncating the task. Generated JSON is limited to nesting depth 32;
unstructured prose without an opening program object stops after 64 tokens.

A supported Boolean Horn fragment receives an independent proof from `okay.inlay.Horn.Block`'s compiled weights,
checked against the finite model's result. This consumes the model's JSON expressions, never source sentences.
The interface shows declared variables, conditions and requested expressions, the program and its exact result.
A proof establishes consequences of the model's interpretation; it does not certify that the interpretation
faithfully expresses the original task or that an external source is true.

The model can instead request `{"eval":["*",6,8]}`, receive `Tool result r1 = 48`, use `r1` in another expression,
and finish with `{"result":"r2"}`. The CPU Worker computes every receipt using exact rational arithmetic;
returning a nonexistent receipt is rejected. No model-authored numeric answer bypasses the tools. The model sees
the original question and the complete tool dialogue on each turn. Native continuation retains the exact generated
token IDs and advances the KV cache across tool turns. The loop is bounded to 16 model actions per question.

Tool validation reports an operator's required and actual argument counts. `Repair` stops three consecutive
identical failed tool programs with the same error, ignoring JSON whitespace differences. A changed program/error
gets a fresh repair attempt; a successful arithmetic step resets the counter. This policy is shared by the chat
and native bench. It does not interpret task text or manufacture a repair. The held-out average-of-five-counts
question still fails on the actual v5 model, but now stops after three invalid divisions instead of sixteen.
This is a latency and termination improvement, not improved comprehension. JVM and Scala.js tests cover both
stopping and a model-proposed correction that computes the exact average.

## 3. The compact model and its adapter

The original 12-layer backbone was distilled toward an intermediate teacher state for the data readers. Native
unconstrained generation initially produced incoherent text. A generative LoRA adapter is therefore trained on
the workstation, and native inference still uses one 12-layer backbone in the browser.

`SemanticTrain` trains rank-16 adapters on all seven projections. `SemanticExport` keeps the original 4-bit
backbone unchanged and writes each low-rank factor as two 4-bit residual planes, padded to rank 64 for the
existing kernels. `Adapted` executes the factors on the selected device without a CPU readback per projection.
The adapter is approximately 21 MiB beside the approximately 122 MiB backbone. Original source buffers are
released after device loading; old fact/data reader heads are disabled for the adapted model.

Merging the first adapter back into a single 4-bit matrix substantially changed generation. Separate factors
restore usable native generation, but approximation can still change tokens; backend agreement and whole-task
accuracy are separate checks. `OnQwen` advances its KV cache during generation, avoiding a repeated forward of
the entire generated suffix on every token. The output head remains on the CPU on a WebGL2 phone.

The experimental profile lives in `model-understanding/`, separate from the unchanged `model-small/` reader
profile. For the explicitly requested public preview, scripts default to the intact 28-layer v5 reference and
its matching adapter. The v2 adapter remains a compact research candidate. The v4 research backbone keeps
original layers 0, 1, 2, 5, 8, 11, 14, 17, 20, 23, 26 and 27, including
the final decoder layers, with the existing reduced vocabulary. This change did not establish reliable generation.
The browser loads only the selected profile. Experimental model assets are versioned with the page build;
publishing and local staging hash both the backbone and adapter to avoid mixing cached generations.

## 4. Training data and checks

`SemanticCorpus` provides supervised programs for arithmetic, finite unknowns, order and Boolean implications.
These are training examples, not runtime language rules. `SemanticCheck` tests ten development questions with
different wording and themes. Initial 512-step training gave 1/10 with separate native adapters. A 4,096-step
curriculum with varied wording, independent language/family sampling and weighted operator/number tokens gave
4/10. The low training loss did not establish general language understanding. These are development results,
not a blind benchmark and not evidence that arbitrary tasks are understood.

`GsmPrograms` prepares additional supervised programs from the authored calculation annotations in the MIT-licensed
[GSM8K training split](https://github.com/openai/grade-school-math). It rejects ambiguous numeral reuse,
ungrounded operands, inconsistent calculation annotations and unsupported expressions. Of 7,473 source examples,
2,372 are training candidates and 286 are held out by a deterministic question-hash split. The official test
split is not used. Checks establish arithmetic consistency of accepted programs, not a proof of every label's
linguistic fidelity. The downloaded corpus and generated pairs live in the local cache, outside the repository.

`ToolPlans` expands the 2,372 accepted training questions into 10,123 next-action lessons with exact intermediate
tool feedback. The v3 continuation trained on longer problems reached 7/10 development answer agreement but only
1/32 on the first held-out slice. A coincidentally correct final answer also occurred with an incorrect expression;
these counts must not be called formalization fidelity. The fresh full-depth 12-layer v4 candidate trained for
4,096 steps reached 3/10 development agreement and 1/32 on a different held-out slice (offset 32).

`SemanticAcceptance` evaluates six pairs of tasks differing in a premise or requested quantity. All tasks in a
pair must pass, including underdetermined and inconsistent cases. It writes model programs, tool feedback and
gold programs to a JSON report. V4 passed 2/12 tasks and 0/6 complete pairs; the unadapted intact 28-layer reference
passed 3/12 and 1/6. This is a development gate, not a blind benchmark or a universal semantic verifier.
`SemanticCorpus` now also varies Boolean names, negative implications, missing premises, contradictory premises,
finite bounds and requested quantities. These are offline training examples, never runtime text-parsing rules.

On this mutation gate, v2 passed 6/12 tasks (2/6 groups), v3 passed 5/12 (1/6), and v4 passed 2/12 (0/6).
A separate intact 28-layer reference trained for 1,024 steps on the expanded synthetic corpus passed 12/12
(6/6 groups), using the same native decoder, reduced vocabulary, JSON language and exact handlers.
This is a training/control result on development tasks; it does not establish phone-sized-model accuracy or
generalization to arbitrary topics. A larger held-out run was interrupted after excessive workstation memory
pressure, so no score is reported for it. Workstation training and native MLX reading now cap the free-buffer cache
at 256 MiB, and heavy model runs are performed sequentially.
The subsequent sequential eight-task held-out slice (offset 64) completed with 0/8 answer agreement. Thus 12/12
on short mutation tasks does not demonstrate broader word-problem competence. The 12-layer v6 warm start from v2,
trained for 1,024 steps on the expanded corpus, passed only 4/12 mutation tasks (1/6 groups). Neither candidate
qualifies for promotion to the default chat. V6 is a failed compact candidate, not a shipped improvement.

`FormalSuite`, `NativeCompilerSuite`, `AdaptedSuite` and `AuthorUiSuite` check the exact handlers, decoder budgets,
adapter execution, worker protocol and original-task transport on JVM and Scala.js. Fake logits in a protocol
test and a mocked UI model are explicitly not evidence of model comprehension.

`scripts/understanding.scala` drives the actual 12-layer model in a phone-sized Chromium viewport with WebGPU
hidden. The native model authors programs for the apple and finite-unknown development questions and for a
training-phrased Boolean implication. The CPU Worker computes the results; the implication has an inlay proof.
The latter requests also work offline. On the host browser the three requests took approximately 5.2, 8.1 and
7.5 seconds with the 4,096-step adapter. These are integration checks, not physical-phone measurements or a
generalization benchmark. `window.okayUnderstanding` records the raw model output and execution backend for
these checks.

The v4 profile was subsequently tested on the same real WebGL2 path. The apple question returned 5 in about
6.2 seconds, and the unknown-integer question returned 5 through an equivalent subtraction. The Boolean question
failed: the model requested unrelated arithmetic and returned 13. The driver reports this as a failed model
consequence; arithmetic correctness does not make it an answer to the task. This profile must not replace the
working default chat.

The intact 28-layer v5 reference also passed the three actual browser checks through WebGL2, with WebGPU hidden
and no WebLLM inference. It authored the apple expression, the bounded integer model and the Boolean implication;
the CPU Worker executed them and the implication received a compiled inlay proof. The latter two checks ran
offline after the model loaded. Host Chromium times were approximately 9.0, 14.2 and 12.8 seconds. This validates
execution of the stronger research profile, not its broad-task accuracy or its memory/latency on a physical phone.

## 5. Remaining acceptance work

The requirement is not met by these candidates. Broader natural-language training, independent held-out evaluation,
new names and schemas, premise mutations, missing/ambiguous premises, negation, quantifiers, coreference and
multi-condition problems are required. Goal correctness and whole-formalization fidelity must both be measured.
The language and tools also need richer predicates, source-grounded facts/rules and long-task processing.
The experimental path must not be promoted as universally reliable based on a few working demonstrations.

## 6. Public preview and model release

The explicitly requested 28-layer v5 profile is packaged at `model-understanding/` and is selected in the same
model picker as the other models. There is no separate try-model link. The standard 12-layer reader remains
the default. The preview identifies its 28 layers,
approximately 305 MiB of weights/adapter, additional working memory and pending physical-phone validation.
The browser audit records the actual loaded layer count; the WebGL2 driver requires 28 rather than trusting a label.
Model files include the original Apache 2.0 license and a description of the modifications and measured limits.

`Unload model` awaits a typed model effect that releases the device, KV cache and runtime references. It preserves
chat, open data and cached files. Tests cover state preservation on JVM and Scala.js. Actual WebGL2 testing confirms
the old context becomes lost and the model reloads from its cache with the network disabled, preserving the answer.
The driver selects plain actions by exact text because they render as links rather than HTML submit buttons.

Published and verified on 2026-11-09: build `c6dd4ce2ac98`, Pages commit
`2371a70a8934cb3d75092128ff606cf903b60a56`, at https://okay-effects.github.io/okay-desk/?understanding.
The actual public page passed all three native 28-layer WebGL2 cases, the compiled inlay proof, explicit context
release and offline model reload with chat preservation. First answer including the public download took about
25.8 seconds on host Chromium; subsequent questions took 13.5 and 12.5 seconds. These are not phone measurements.
The standard chat's localized GDP regression also passed through its 12-layer WebGL2 reader and exact worker.

Picker update: build `0903e9a54272`, Pages commit `39594e539c832a907ff47248422f3cd141fda72c`.
The public page exposes five model choices and no try-model/standard-model links. Browser checks select the native
author from the shared picker, execute its programs through WebGL2 and switch 28 -> 12 -> 28 layers without
navigation or chat loss. The old `?understanding` URL only preselects the author for compatibility.

## 7. River-task restart report

The reported Russian text is retained in `core/src/test/resources/puzzles/river-ru.txt`. Actual v5 inference
generated an unfinished arithmetic expression with hundreds of nested additions, exhausting 1,024 output tokens.
It did not build a transport model. The previous chat treated decoder failures like repairable program errors,
potentially retrying that full generation sixteen times. This is now a terminal typed generation failure;
invalid completed programs still receive repair feedback. No topic-specific text reader is added.

The native decoder stops excessive nesting while the program is being generated. The device is unloaded after a
generation/runtime failure, the question remains visible, and further questions can load the model again.
An in-flight question is kept locally before generation and recovered once after a page restart without automatic
rerunning. Completed outputs clear the recovery record. This preserves the interrupted question, not all history.

The okay-llm runtime consumes old KV-cache layers as their successors are computed. The reusable-prefix API remains
available; tiny real-forward ownership laws pass on JVM and Scala.js. Unrequested state readbacks are skipped.
Native phone WebGL2 stores KV tensors in FP16, keeps a bounded texture pool and checks its 512 MiB texture budget
before allocation. Driver allocation errors are reported explicitly. This budget does not count all browser memory.
The exact CPU tools remain unchanged. Terminal authored steps release the working KV cache while retaining weights.

`?cache-test` checks actual FP16 texture round trips and rejection before an oversized allocation. Measured error
was approximately 0.00101, below the 0.002 test bound. `scripts/river.scala` runs the actual model through forced
WebGL2, checks no unexpected navigation, question retention, a subsequent exact answer offline and restart recovery.
The final local river run stopped at the nesting limit with approximately 449 MiB peak counted textures.
The arithmetic, bounded-integer and compiled-inlay browser cases also passed with compact KV storage.
This does not prove physical-phone stability, and the model still does not solve the river task.

Published stability build `3141fe8f1365`, Pages commit `3da4168208e0b1931b94f02056e6287734d20522`.
The public `?cache-test` and the actual reported river request passed the same checks: no unexpected navigation,
approximately 449 MiB peak counted textures, a subsequent exact answer offline, and recovery after a simulated
restart. The model's unfinished nested-addition draft is diagnostic evidence, not an executed solution.
Runtime sources in `../okay-llm` require locally published `qwenJVM`, `qwenJS` and `browser` artifacts to rebuild.

## 8. Model-authored finite action models

`Planning` accepts a finite transition definition authored by the language model: variable domains and optional
entity labels, initial state, target state, guarded simultaneous effects, and safety predicates. It never receives
or parses the task text. `NativeCompiler` and the WebLLM prompt describe this protocol alongside the existing
constraint, arithmetic and evidence tools. The same model effect handles all questions; no topic router selects a
river parser. The original reported question is an inference regression, excluded from the training corpus.

A generic `choose` effect selects a bounded subset of two-valued variables equal to a specified old-state value.
The model declares the subset domain, minimum/maximum cardinality and equality condition. The exact handler
expands the combinations, avoiding error-prone model enumeration. `set` effects support unrelated finite integer,
Boolean and text domains. All right-hand sides use the old state; safety holds initially and after each transition.

The worker enumerates at most 256 states. Datalog derives the reachable set using `initial`, `edge` and `reachable`.
Breadth-first search independently checks that set and selects a shortest path. When the graph fits the inlay Horn
block bounds, the compiled block must derive exactly the same set. A second Datalog run checks the returned path's
proof. The chat displays the language model's definition, the path and its certificate. A proof is conditional on
the interpretation; it does not prove that the language model faithfully read the user's text.

`PlanningSuite` checks the seven-step river model, changed capacities, forbidden empty moves, integer resource
models, unreachable goals, bounds, wire round trips and full transition semantics. These are hand-authored handler
laws, not model-understanding scores. `AuthorUiSuite` separately checks unchanged transport of a proposed model
through the same UI program on JVM and Scala.js. `PlanningCheck` runs actual native inference and compares the
entire legal-state/edge graph, initial state and goal with a gold model, including unreachable states. Agreement on
only the final move count cannot pass that comparison. Labels and value encodings are compared strictly, which can
reject equivalent alternative naming; reported programs must also be inspected.

The first planning adapter (v7, 512 steps from v5, 75% finite planning and 25% existing semantic tasks) matched only
5/12 development transition models and none of the three reported-wording mutations. Its explicit action lists
confused capacity constraints. It is not a release candidate. Subsequent training uses declarative subset actions,
varied initial/target states, empty-move restrictions and narrative paraphrases. Training generators are offline
supervision only and are never called to interpret chat questions.
