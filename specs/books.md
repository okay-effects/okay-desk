# Books compiled into extensions (SPRINT K6; BACKLOG D.28; the operator, 2026-11-25)

"Can the model learn from books — read one and build itself a specialized extension, used when needed?" Yes, where the
book is exact: its knowledge becomes a THEORY (data an existing exact engine runs), a problem LANGUAGE (a `LineSpace`:
the lines a question is read into), the words to SAY a reading back, a GENERATOR of problems — and then self-play
(K4) teaches the one core model to read the new domain, the gate keeping the others whole.

## 1. The extension

| part | what | who makes it |
|---|---|---|
| theory | the book's laws as data: parameters, rates, thresholds, equations — each with its quote from the book | the teacher reads the book; every quote checked verbatim against the text |
| law of the check | the book's worked examples: the theory must reproduce them exactly | taken from the book |
| language | a `LineSpace`: the question's slots and their options | written once per kind of domain |
| sayer | each reading said back in plain words (four languages) | with the language |
| generator | problems drawn from the language, solved by the engine, said by the teacher | the engine + the teacher |
| reader | the core adapter trained by self-play on the new domain | K4, through the gate |

A theory value the book does not state verbatim, or a theory that does not reproduce the book's examples, does not
enter. The exercises with answers are the held-out measure.

## 2. The pilot: "pay" — UoP against B2B, take-home pay in Poland, 2026

Why: the operator's own question now (an employment offer against B2B as a JDG); exact; checkable; useful to anyone
working in Poland. The books (public domain: Polish statutes are not protected, art. 4 of the copyright act):

- the PIT act, consolidated text Dz.U. 2026 poz. 592 (`api.sejm.gov.pl/eli/acts/DU/2026/592`): the scale (12% to
  120 000 zł, 32% above, the reducing amount 3 600 zł a year, 300 zł a month), the employee's costs;
- the lump-sum (ryczałt) act, Dz.U. 2025 poz. 843: the rates by activity, what is deducted from revenue;
- ZUS, "Wysokość składek … w 2026 r." (zus.pl): the bases and the amounts (full 5 652 zł → 1 926.76 zł; preferential
  1 441.80 zł → 456.18 zł; health on ryczałt 498.35 / 830.58 / 1 495.04 zł by revenue; the yearly cap 282 600 zł) —
  its tables are the worked examples the theory must reproduce.

Sources kept in `okay-desk-private/books/pay/src`.

The questions: "How much will I take home on UoP at 20 000 gross?", "B2B 1000 zł a day, 21 days, ryczałt 12%, full
ZUS — and UoP 20 000?", in four languages. The lines: `contract: uop | b2b`, `gross: N` / `revenue: N` (or `rate: N`
and `days: N`), `tax: scale | linear | lump`, `lump rate: R`, `zus: full | preferential | start`, `sickness: yes | no`,
`compare: …`; the answer: the monthly take-home with each part said (contributions, health, tax).

## 3. Steps

1. The theory by hand from the sources with quotes, the engine, the law (the ZUS tables, the PIT scale) — the
   reference the teacher's extraction is measured against.
2. The teacher's extraction: the same theory read from the sources by the 35B, each value with its quote; checked
   (quotes verbatim, the law reproduced); the share of values right against step 1.
3. The language, the sayer, the generator; the domain in the core's mix; self-play; the measure.
4. On the page: the extension loaded when a question is about pay.
