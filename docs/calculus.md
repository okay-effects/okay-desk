# The calculus of readings

How okay-desk turns a question about data into an exact answer, and why the whole of it is one small calculus.
This is the document to read; `specs/calculus.md` is the diary of how it was found, with every measurement.

## 1. The idea in one paragraph

A question is read by filling typed holes in a term. The data's schema gives the types (the tables, their
columns, the values a column holds, the links the values prove), so a term is well-formed by construction and a
closed term is run exactly by an engine, never by a model. The model does one thing: it weighs the candidates of
a hole. The data itself is a source too, and where the question names something literally the model is not
asked. A term left open is a legitimate answer: "not sure". Answers in words are made the same way, except that
a fact hole can only be filled by a cell of the result, so no figure is ever invented.

## 2. Three primitives

1. **Types, indexed by the data.** `Program`, `TableT`, `LinkT(table)`, `ColumnT(tables, role, optional,
   except)`, `OpT(kind)`, `ValueT(table, column)`, `FnT(required)`, `OrderT(table, aggregate, grouped)`, `DirT`,
   `LimitT(ordered)`, `NodeT(edges)`, `DirectionT`, and the answer grammar's `AnswerT`, `FactT(row, column)`,
   `WordT(slot)`. A grammar (`Rules`, implemented by `Grammar` over a schema and by `Answers` over a result)
   says the constructors of a type and the type of a constructor's next argument given the arguments before it.
   The types are dependent: a column's type depends on the table chosen; an aggregate's column on the function;
   a second condition's columns come after the first's in the table's order (the canonical order).
2. **One operation: weigh.** `Weigh.Of(type, candidates, context): Dist` — a distribution over the candidates
   of a hole, given the question and the term so far. Empty means "no opinion"; the candidate `?` means "not
   this grammar". Every source is a handler of this one operation.
3. **Denotation.** A closed term is a point of the space of meanings (`Space.Program`); the point compiles to
   the query the engines run (the relational engine, or Datalog for closures and absences). A term with holes
   has no denotation: it is a reading not finished.

Everything else is a strategy, not a mechanism.

## 3. Four levels, each a calculus, each described by the one below

- **Level 0, choices.** A state, the moves enabled in it, a step, a chooser. A run takes the best-weighed move
  until no move is enabled, or stops when the chooser has no opinion or two moves are too close. In okay-scala
  this is a program over the standard `Choose.among`: `Choose.first` of it is the run, `Choose.all` of it every
  run. Laws: bounded, deterministic, replayable by its own moves.
- **Level 1, words.** A reading is a word over an alphabet that changes at every step: `next(context, word)`
  gives the letters allowed next, and where the context decides one letter (a name written in the question) it
  gives exactly one, so no chooser is asked. Exactness is a property of the grammar, not a strategy. Law:
  where `next` narrows to one letter along a gold word, it is the gold's.
- **Level 2, typed terms.** The grammar above. A term is its word (the constructors in preorder); the tree is
  rebuilt by replaying the grammar. Laws: a point and its term round-trip; every point is reached from one hole
  by refinement with a perfect source.
- **Level 3, the space.** Level 2 under `Choose.all` with a uniform weigh: every way the choices can go is every
  closed term, and that set equals `Space.enumerate` exactly (26,853 points over the sample's orders table). The
  space is derived, not written twice. Its phrases (`Phrase`, English and Russian with cases) are the inverse of
  reading on the generated language.

None of these is a monad of its own: in okay every program `A ! R` is already the free structure over its row,
so a level is a signature and a program over it, and a handler of `Among` is an algebra (`first`: Maybe, `all`:
List).

## 4. Sources and strategies

The program of reading (`Strategy.program`) is one: the leftmost hole, its candidates, their weighing, a choice
among them in the weighed order, a note of each step. The sources are handlers of `Weigh`, nested in the order
they are asked, each only where the one before was silent (`Fill.first`):

| source | what it is | where it is sure |
|---|---|---|
| the data (`Fill.exact`) | what the question writes: a table's name (in either language, by a dictionary of the data's own words), a column, a value of a column, a number with its comparison, a year, the words of a function, a direction, a limit, a negation | one candidate or none; `?` for words of constructors the grammar lacks |
| the trained readers (`Readers.weigh`) | linear probes and pointers over the model's states, one per constructor | the vector tier |
| the model's own tokens (`Fill.model`) | the term rendered as the program's JSON up to the hole, the candidates scored by the next-token log-probabilities | any model with logits, no training |
| a person | the answer to "not sure: A or B" | the fourth source; not on the page yet |
| the oracle (`Fill.oracle`) | the gold term | the laws only |

Strategies are handlers of `Among`: `Strategy.greedy` (first), `Strategy.every` (all), a beam (not built). A
dialogue is `first` with the person as the outermost source.

## 5. Answers in words

The answer grammar (`Answers`) is the same `Rules`: one constructor, the shape of the answer, fixed by the program
and the result (a count, a scalar, groups, the top row, rows, what a node reaches, what is missing); fact holes
whose only candidate is a cell of the result; word holes with two or three wordings per slot in each language.
`Strategy.greedy` over it is one sentence, `Strategy.every` all its wordings; the data fills the facts, a weigh
chooses the words. On the page the sentence stands beside the table, with the note "every number is a cell".

## 6. The device stack

| layer | the operation | handlers | the law |
|---|---|---|---|
| `Weigh` | a hole weighed | the data, the readers, the model's tokens, a person, an oracle | this document's |
| `Read` | states at positions, logits, tokens, the prompt | `OnQwen` over any `Compute` (the page, and the JVM through `ComputeReading`); `MlxReading` (mlx-scala's forward, for training and the teacher) | the page equals the JVM |
| `Compute` | the tensor ops of a transformer layer | `OnCpu` (the reference), `OnWebGpu`, `OnWebGl2`, `OnMlx` (okay-llm) | each equals `OnCpu` |

The calculus never sees a device; a device never sees a term. "Any hardware" is a handler of `Compute`; "any
model with logits" is the text tier of `Weigh`; "any model with states" is the vector tier after the bus's kit.
A model on the JVM is a `Reading`: one method, `answer(op: Read[X]): X`.

## 7. The laws, and what they measure

Thirty-four laws run with `sbt coreJVM/test` (seconds, no model). Among them: the data alone never errs on the
sample's 26 questions and on 626 phrasings of 313 random points (0 contradictions); every point is reached by
refinement; the reading is the space; every wording of every answer is grounded and reads back; the sources as
handlers give the function form's terms.

The benches need a model (`DESK_DEVICE=mlx|cpu`, `DESK_SOURCE=readers`, `DESK_WORDS=<file>`):

| | held-out grammar pairs (100) | real GDP questions (19, en / ru: right / refused / wrong) | the teacher's natural-wording pairs (en 84 / ru 83 / uk 75: right) |
|---|---|---|---|
| the data alone | 68% whole, 0 wrong | 11/8/0 — 9/10/0 | 38 / 27 / 3 (30 wrong of 242) |
| data, then the model's tokens | 65% | 12/6/1 — 10/6/3 | 51 / 37 / 9 |
| data, readers, tokens | 91% | 12/6/1 — 10/6/3 | 66 / 39 / 25 |
| the readers alone, for reference | 57% | 16/3/0 — 12/3/4 | 63% / 25% / 16% |

Two things the numbers say. On data, the data alone never errs. On people's wording it does — "сколько денег"
is a sum, the rule says a count — thirty times in 242; that is the exact rules' limit, a number per language,
and the next thing to fix is the rules' silence there, not their cleverness.

## 8. How to use it

```scala
val s = Space.Schema(tables); val g = new Grammar(s)
val ctx = Context(s, question, Term.Hole(Ty.Program), words)          // words: the data's own dictionary, or none
val (steps, term) = Strategy.greedy(g, ctx, Fill.exact[Pure])          // the data alone, pure
// with a model: in a row with Read
val fill = Fill.first[Read](Fill.exact[Read], readers.weigh[Read], Fill.model[Read](tables))
val (steps, term) = Read.run(Strategy.greedyIn[Read](g, ctx, fill), reading)
term match
  case Some(t) => Den.run(s, t)                                        // Right((query, result))
  case None    => steps.last                                           // the hole no source decided: "not sure"
// an answer in words
val a = Answer.ofQuery(query, result); Answer.text(a, ru = false)
```

Files: `core/src/main/scala/okay/desk/calc/` — `Calculus.scala` (types, terms, rules, the grammar), `Den.scala`,
`Refine.scala` (context, fills, rendering, the function-form reference), `Weigh.scala` (the effect and its
handlers), `Strategy.scala` (the program and the strategies), `Level0.scala`, `Level1.scala`, `Encode.scala`,
`Answer.scala`, `Answers.scala`. Laws in `core/src/test/scala/okay/desk/`: `CalculusSuite`, `LevelsSuite`,
`WeighSuite`, `StrategySuite`, `AnswersSuite`. Benches in `bench/`: `RefineBench`, `RealRefine`, `TeacherRefine`.

## 9. Not done

The calculus on the page (the page still reads by the readers' assembler); the person as a source and dialogue
as refining the last term; a beam; wordings weighed by the model; meaning as an equivalence class of terms (only
a normal form so far); phrases as a bidirectional grammar in GF's sense; the levels' encodings proved in Lean;
the data's silence on ambiguous wording.
