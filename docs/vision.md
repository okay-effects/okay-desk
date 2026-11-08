# What we are building, what we expect, what is missing

The operator's question, 2026-11-08: «Расскажи что мы делаем, что ожидаем получить в результате и чего ещё не
хватает и что нужно сделать». This is the answer, kept current. The designs are in `specs/`, the laws in
`core/src/test`, the numbers where they were measured; this file says why.

## 1. What we are building

A model that does not invent. A question — about data, about code, about anything with an exact judge — is read
into a typed term with holes. The model does one thing: it weighs the candidates of a hole. Everything else is a
handler of that one operation (`Weigh`): the data (what the question writes literally), readers over the model's
states, the model's own tokens, organs (rules over words compiled into transformer blocks), a person with buttons.
A closed term runs on an exact engine. A number in an answer is always a cell or an exact derivation. An open hole
is a question, not a guess.

On top of this, a model that grows: its weights never change; what grows is the set of its exact parts, each
behind an effect with a law. The model may author a part (a term, a cue); a compiler makes the part; a law lets it
in or not. Two models with the same weights and different libraries of parts are different specialists, and the
library is readable.

## 2. What we expect to get

- **Practically.** An assistant to data and to code that runs offline in a browser on a phone, learns from its
  user in seconds without training, explains every reading, and never names a figure that is not in the data.
  Built: the okay-desk page; symdex's `ask`.
- **In principle.** Evidence that a model's capability can be grown in checkable parts. Reading a book or being
  corrected, the model writes a term; the compiler makes an organ; the law gates it. Knowledge becomes an artifact
  separate from the weights: portable between models, reviewable, provable.
- **The numbers that bear this out now.** The page on the GDP questions: 31 right, 6 asked back, 0 wrong. The code
  reader: 97% whole, 1% wrong. Organs on the teacher's pairs: fix 67 and break 29 before the data; fix 34 and break
  0 after it. Every compiled block equals its interpreter on everything it was checked on.

## 3. What is missing, in order of weight

1. **An organ is not yet a safe source.** 1–5% wrong where it speaks. Needed: silence where a cue is thin — a cue's
   held-out precision as its tier, the dialogue's margin over the organ's votes. Until then the organ sits after
   the data and fixes less than it could. *Built 2026-11-08: tiers (sure / maybe), a maybe silent alone; measured
   in `bench/OrganBench`.*
2. **The model does not yet write organs itself.** Organs are induced by a procedure. v1: the text tier writes a
   cue as JSON where the readers keep failing; the induced organ is the reference; the law and the oracle gate it.
   Without this the title "a model grows itself" is not literal. *Built 2026-11-08: `learn.Author`, `bench/AuthorBench`
   (needs a model); the checks have laws without one.*
3. **Learning on the page is not closed.** A person's correction refines the term but is not remembered, and organs
   are not induced locally. The closest items to the product. *Built 2026-11-08: lessons — a choice at a hole is a
   cue of the person's organ, asked first, kept in the browser; «не запоминай» takes it back.*
4. **The data's silence on ambiguous wording.** 30 of 242 teacher pairs are misread by a closed term, and no
   margin helps because the exact rule gives one candidate. Needed: disputed words per constructor on which the
   data offers two and asks. *Built 2026-11-08: `ask:<kind>:<a>|<b>` lists, the `Fill.ask` marker.*
5. **Prose is read badly.** The self-read of the documentation verified 4 claims and misread sentences that are not
   questions. Needed: a grammar of claims; until then the book about itself is a demonstration.
6. **Two effects of the spec are not built.** `Write` (the residual stream added to, under a policy and a journal)
   and `Fork` (the KV cache as the continuation; branching as a handler's choice). Without them organs see words
   only, not the model's states — the next frontier: cues over readers, so an organ reads meaning and the law stays.
   *Built 2026-11-08, at the level of the calculus: `Strategy.beam`; the cache sharing is the prefix cache's.*
7. **Not measured with the readers on the teacher's pairs** (MLX was busy): the margin ln 3 is measured on the
   sample and the GDP 19 only.

## 4. What to do, in order

First 1 and 4 together, one mechanism: the organ and the data learn to be silent, the dialogue asks. Then 3:
lessons and organs on the page, the cycle closed at the user. Then 2: the model authors cues, measured against
induction. Then 6, starting with `Fork` (cheaper, gives the beam at once). In parallel, when MLX is free, 7. Item 5
after the code reader is in agents' daily use through symdex.

If one thing today: 1 and 4. Without them everything else grows a system that is sometimes confidently wrong, and
our one real argument is that it is not.

## 5. The map of the documents

| | |
|---|---|
| `docs/calculus.md` | the calculus of readings: types, one operation, denotation, levels, sources, the device stack |
| `docs/learning.md` | how to use the organs, the books, the paradigm, the code reader, the dialogue |
| `specs/calculus.md` | the diary of the calculus with every measurement |
| `specs/pilot.md` | Okay!Chat's deterministic dialogue model brought over: the person as a source, acts, the margin |
| `specs/organs.md` | organs: the effects, the induction, the experiments, the books, the code domain |
| `specs/data.md`, `specs/teacher.md` | the page, the readers, the teacher's pairs |
| `BACKLOG.md` | what is planned and not built, with the spec that holds each design |
