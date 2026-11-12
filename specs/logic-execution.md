# Exact execution after neural formalization

Built 2026-11-09, alongside `logic.md` M3 reader work by another agent.

`logic.Execute` is a core boundary after `Extract.Reading`. It never reads natural-language task semantics and
never asks a model for an answer. The neural reader chooses the typed constructors, predicates, bindings and
source spans. The grammar replays those choices and rejects constructors or evidence outside its contract.

- An unfinished reading returns `Outcome.Open` with its typed hole. No partial program is executed.
- A closed reading must cite real cells for any `Evidence.Data` references.
- `Compile.program` constructs a Datalog program and goal as data, without serializing and reparsing task text.
- Semi-naive evaluation computes answers and records derivations; naive evaluation independently checks the
  entire closure once per execution. These handlers share Datalog's language and comparison semantics.
- Each returned binding is grounded to its answer fact. A proof must finish at that fact.
- Proof replay checks that every given fact is a supplied input or a program fact; every derived rule belongs
  to the program; its positive premises appear earlier and unify with its head; comparisons hold; and its
  recorded negative premises are exactly the grounded negative literals, absent from the complete closure.
- Bounded positive ground witnesses also run through `okay.inlay.Horn.Block` compiled weights. Its derived set
  must equal the witness's facts and contain the answer. Other fragments return no inlay certificate; they
  retain Datalog and proof replay. Negation is not silently treated as positive Horn inference.

The reference closure is shared by the answer checks, rather than recomputed once for each result row.
No model-authored numeric answer, guessed completion, or topic-specific natural-language reader is involved.

## Browser integration

`logic.Protocol` carries vocabularies, typed choices and source spans as `Wire` data through
`Computation.Job.LogicModel`. Its result is either an open typed hole or an executable definition with checked
answers. Three laws cover request/reply round trips, incomplete readings and invalid declarations/evidence on
JVM and Scala.js. This transport does not manufacture a reading or select a task domain.

The existing native data readers already author query programs in the shared chat. Their logical queries now
use `DataProof.check`, which sends the actual program through `Execute.definition` and compares its answers
with the existing exact/compiled handler. No gold reading is fabricated for this path. The CPU returns portable
proofs to the page, where Show/Hide proof keeps them collapsed by default; copied chat retains the stored proof.

Expanded certificates are bounded to 32 rules, 256 referenced input rows, 64 distinct input values and 64 answer
rows. Unrelated open tables do not consume this budget. Larger results keep the original exact handler, with a
notice that the expanded certificate is omitted; a disagreement inside the supported gate remains an error.
This preserves the data path without running large naive closures or serializing unbounded proofs on a phone.

`scripts/logic-proof.scala` runs the actual 12-layer model on forced WebGL2 at phone width: English and Russian
dependency questions, CPU proof replay, compiled inlay, an absence query offline, and expandable proof layout.
Only the unused WebLLM module import is stubbed. Model inference, programs, data and Worker execution are real.
The shipped sample tables are checked against `Sample.tables`; a missing `fay` row in the site's customers CSV
was corrected so the absence example has the same facts as the core laws.

## Validation

`LogicExecuteSuite` tests four corpus families, open holes, invalid constructors, source-independent positive
reachability through compiled inlay, invented givens, forged rules, missing premises, reversed proof order and
a false negative premise whose conflicting fact does not occur in the submitted proof. These use supplied
gold readings and hand-constructed corruptions; they are execution laws, not reader-accuracy measurements.

```sh
sbt 'coreJVM/testOnly okay.desk.LogicExecuteSuite' 'coreJS/testOnly okay.desk.LogicExecuteSuite'
```

## Remaining integration

This boundary is not a new selectable browser model. The new general typed reader remains experimental;
its fidelity/calibration and ontology acquisition for unfamiliar domains are still open. The working data path
uses its existing measured readers and the new proof boundary. Physical-phone/backend verification remains
separate from desktop Chromium's WebGL2/phone-width checks. A correct proof is conditional on the definition and
supplied facts; it cannot establish faithful interpretation of arbitrary prose or empirical truth of source documents.
The page should use the reader only after its whole-program gate passes, not merely after these core laws pass.

The published native 28-layer v5 adapter retains `PublishedAuthor.system`, its measured original training prompt.
Research prompt changes had caused repeated arithmetic tool calls on a simple subtraction; browser regression
caught this before publication. The pinned prompt restored arithmetic, unknown-variable and compiled-inlay
cases, explicit device release, offline reload and model-picker switching. Research training uses its own prompt.

## Published verification

Build `8fd650969827`, Pages commit `266ede7ed22659871e4e0bc3d6cb91de18e31932`, was published and verified at
https://okay-effects.github.io/okay-desk/ on 2026-11-09. The public page passed actual 12-layer WebGL2 logical
data queries in English/Russian, checked Datalog/inlay proofs, offline absence and GDP regressions. The native
28-layer v5 page passed arithmetic, finite unknowns, Boolean Horn inference, device release, offline reload and
28 -> 12 -> 28 picker switching. The same artifact passed the reported river's bounded failure/recovery locally;
this is not a solution to that puzzle. Physical-phone behavior is not established by these Chromium checks.

Preflight: 187 JVM core tests, 178 Scala.js core tests and 35 UI tests on each platform passed. Expanded proof
and typed-transport laws are included; the site's sample-fact parity law also passed. Model weights were kept
at the measured v5/12-layer versions. The unqualified general typed heads were not packaged or enabled.
