# Asynchronous computation beside a small model

Operator, 2026-11-08: distribute work between CPU and GPU where it is efficient, run independent tasks concurrently,
and let the model invoke CPU computation through an API.

## Contract

The model writes a typed program. `Computation.Job` is its tool API: exact arithmetic, sourced Horn deduction,
constraint solving, relational/Datalog queries and compiled citation lookup. It is not JavaScript, shell code or
direct memory access. Facts, copied operands and source readings retain the same validation as before execution.
The result handler phrases computed results. Query summaries are also computed inside the worker.

`Model.Execute` / `ExecutePlan` are effect requests, answered by the browser executor or a synchronous JVM handler.
The UI program does not select threads or perform these computations itself. The request/reply protocol uses a
closed derived JSON codec: known constructors and exact product fields, preserving typed values and grid keys.

The model-facing operation is, for example:

```json
{"compute":[
  {"id":"first","calculate":"2 + 2"},
  {"id":"second","calculate":"3 * 7","after":[]}
]}
```

It currently accepts calculate and deduce calls. A plan has at most eight uniquely named jobs. `after` names ordering
dependencies; inputs remain explicit, not references interpolated into code. Cycles and missing dependencies fail
before any job starts. Ready jobs start without awaiting unrelated jobs. A failed job skips only its dependants.
The batch returns all results in declaration order; individual worker tickets expose independent futures.

## Placement implemented

- Exact symbolic tools run on CPU in dedicated, long-lived Web Workers. The pool uses at most two workers, leaving
  capacity for the page and model; low reported hardware concurrency reduces it to one. No model weights are
  copied into these workers. The queue is bounded; requests have a size bound and a deadline.
- The native model retains its existing WebGPU → WebGL2 → CPU fallback. A separate single lane serializes forwards
  sharing its mutable prefix/KV cache. CPU workers do not acquire or wait for this lane.
- A ticket can cancel queued work or terminate a busy worker, which is replaced for subsequent work. Worker errors
  and protocol errors return failures rather than silently executing a blocking fallback on the page.
  The worker bundle is fetched once into the page; workers created or replaced later reuse it through Blob URLs,
  including after the network is disabled. Model weights are not part of this bundle.

These are initial placement rules, not a measured global optimum. GPU execution of general Horn/SAT tools is not
implemented in this browser executor. A GPU handler needs an exactness law against the reference before it can be
eligible. A Promise by itself does not move CPU work to another thread: workers do that here. Source parsing,
message serialization, and native model command encoding still run on the page. In particular, WebGL2 readback and
the native model's CPU output head can still block a page turn. Moving the native model execution to its own worker,
transferable buffers, measured dispatch costs and adaptive placement remain planned.

Browser foundations: [Workers](https://html.spec.whatwg.org/multipage/workers.html) execute separately from UI
scripts; [WebGPU](https://www.w3.org/TR/webgpu/) exposes GPU computation and asynchronous resource operations.
The scheduler must respect dependencies and resource ownership instead of globally serializing all these APIs.

## Built and checked

`ComputationSuite` checks request/result round trips, proof and grid preservation, malformed messages, operand
grounding, concurrent graph submission, cycle refusal and failure isolation. `AsyncLaneSuite` checks independent
CPU/model lanes, backpressure and resource release on failure. They run on JVM and Scala.js. The same UI program
also tests the example buttons and a pair of independently computed expressions.

`scripts/execution.scala` runs real Workers in Chromium: two constraint solves overlap, the page's heartbeat
continues, cancellation terminates a running worker, replacement works, and dependency plans complete. Its second
scenario loads the real 12-layer student on forced WebGL2 and performs a native forward while CPU solves run.
`?execution-test` and `?execution-test-model` opt into these diagnostics. This is a host-browser check, not a
physical-phone speed, energy or memory measurement. `scripts/deduction.scala` separately exercises the real model,
source collection (HTTP fixture), exact inference, offline reuse and model reload in the ordinary chat.

`scripts/dev.sh` builds both bundles and serves the chat. The initial screen has examples in the same chat; the
supported symbolic grammar works without loading model weights. Arbitrary prose reading remains a separate,
unfinished learned-reader task (reasoning.md §7).
