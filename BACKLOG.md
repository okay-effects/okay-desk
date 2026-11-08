# BACKLOG — okay-desk

What is planned and not built, with the spec that holds the design. Done items move to the spec's "built" section
and leave this file. The operator decides the order; the numbers that motivate each item are in the specs.

## A. The dialogue and the sources (specs/pilot.md, docs/calculus.md §10)

1. **Lessons per person** («надо было: по странам», Okay!Chat's `Support.Remembered`): the pair phrase → the hole's
   choice kept on the disk beside `Clarified.keep`; the next question of the same person, the same words (or a typo
   away) decide the same hole ahead of the rules. A `Weigh` handler first in the chain, one file. Measure: the
   second time a wording is corrected it never asks again.
2. **The data's silence on ambiguous wording** (30 of 242 teacher pairs misread by wording; a wrong closed term is
   not a question — only an open hole is): per constructor a list of disputed words where the exact source gives
   TWO candidates instead of one, so the dialogue asks. Measure on the pairs: wrong → asked, right kept.
3. **Word lists grown by induction** (Okay!Chat's `okay.intent.Induced`, our `Organ.induce`): cue rules from pairs
   per constructor, a person reviews, compiled into `lang/<code>.json`; the GF law and "the data alone never wrong"
   as the gate. The source of item 2's lists.
4. **The margin measured with the readers** on the teacher pairs when MLX is free (ln 3 is measured on the sample
   and the GDP 19 only).
5. **Acts in Ukrainian and Polish on the page**: the engine passes its loaded languages to the act layer.
6. **The typo layer** on the same word lists (bounded edit distance, exact-format slots never corrected).
7. **Word lists built by a DSL** (Okay!Chat's `intent.Dsl`: stems, gaps without `.*`, `(?iU)` and `\b` emitted),
   the shipped file equal to the builder by a law.
8. **The encoder's centroids as the vector tier for a service without a model** (`Fill.centroid`, the 23 MB encoder).
9. **A need as a term in Okay!Chat** (specs/pilot.md §3.3): `TradeT / PlaceT / WhenT / MoneyT`, the intake as
   `Strategy.greedy` with the person last, matching by slot as the denotation. The other side's work.

## B. Organs (specs/organs.md)

10. **Silence where a cue is thin**: a cue's held-out precision as its weight; the dialogue's margin over the
    organ's votes; the "open → wrong" count (27 of 963 after the data) to zero.
11. **Organs on the page**: the user's corrections induce cues locally, compiled, the law checked in the browser,
    kept beside the lessons; the page understands the wording next time with no model.
12. **v1 — the model authors organs**: where the readers keep failing on a class, the text tier writes the cue's
    JSON; the induced organ is the oracle it is measured against; the law gates both.
13. **The nightly cycle** (Okay!Chat's learning loop): journal → induction → review → compile → law → ship; the
    number to drive down: the share of holes the model or the person had to decide.
14. **`Write`: steering as an effect** (§5): the residual stream added to, with interceptors, a journal, a policy
    (okay-guard); laws: a refused write changes nothing, the journal replays to the same logits. One more `Compute`
    op on every device.
15. **`Fork`: the KV cache as the continuation** (§6): clone and resume in okay-llm; branching, backtracking and a
    beam as handlers of `Among`; the cost of a branch the tokens after the hole.

## C. Applications of organs (the operator's question, 2026-11-08: «как применять на практике»)

16. **Organs as a portable format of knowledge between models**: a large model writes organs for a small one
    (distillation without gradients); organ files signed, versioned, checked by the law on any device; a library.
17. **Guardrails that cannot be prompted away**: a forbidden-request rule as an organ in the forward pass, exact and
    proved, raising a flag that `Write`'s handler acts on under okay-guard's policy.
18. **Circuit certification**: compile the circuit that should compute X, compare with the model's states on the
    same inputs (the bridge, readers → exact machines): "the model contains a computation equivalent to P", a number.
19. **Learning by a community without weights or data leaving**: each page induces organs locally; organs are
    reviewable and merge without conflict (two disagreeing cues give silence by construction); shared as files.
20. **Agents exchanging skills as organs** (okay-agent, rozum rooms, symdex): a skill as a compiled, typed,
    law-checked artifact instead of a paragraph of instruction.
21. **Regulation as weights**: a tax rule, a tariff, a protocol written as cues by the expert, compiled, the law its
    certificate; one artifact for the model, the spec and the regulator.
22. **Okay!Chat's intents as organs**: the pairs from the journal induced nightly, reviewed, compiled, attached as a
    handler; the invariant "no model supplies data" by construction, milliseconds on CPU.
23. **Product chat over a shop's data** (okay-mall, okay-shop): the shop's frequent wordings as organs, the model
    for the rare ones, the person's buttons for the ambiguous; a query costs nothing.

## C'. Taught (2026-11-08)

- **Code through symdex** — built: `okay.desk.code.Code` (specs/organs.md §9); next: the ambiguous symbol as a
  question of the dialogue, the organ in symdex's MCP as a `ask` tool (a question in words → the Datalog goal), the
  Ukrainian and Polish phrases, the facts fetched live from `symdex serve` instead of a dump.

## D. Learning on purpose (specs/organs.md §9, the operator's question «может ли модель учиться, читая книги или
методом проб и ошибок»)

24. **Reading as induction**: a book (a manual, a schema's documentation, a glossary) read by the model into pairs
    (a phrasing → a term) and from the pairs into organs — the organ is what the model "learned", readable, with the
    law; the book's examples are the held-out set. Measure: pairs from the text alone that read right afterwards.
25. **Trial and error against an oracle that is not the model**: the exact engine is the judge. The model proposes a
    cue, the engine runs the term on the data, a result that matches the question's own answer (a pair's gold, a
    person's yes, a consistency check across phrasings) keeps the cue; a mismatch discards it. Search over cues, not
    over weights; the law at every step. Measure: cues found by search alone on the held-out half.
26. **Curiosity as an effect**: the model performs `Ask(question)` to a source (a person, a document, an engine) when
    its readers abstain on a class of questions; the answer becomes a pair; pairs become organs. The loop closes
    inside the calculus: abstention → question → pair → organ → fewer abstentions.
27. **Self-consistency as the teacher**: the same question phrased three ways (the space's phrases, the teacher's
    rewrites) must read to one term; where readings disagree, the disagreement is a pair for induction and a question
    for the person. No gold needed.
