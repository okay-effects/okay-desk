# The core trained for the reading (2026-11-13)

Questions 3 and 8 of `specs/technology.md`; lever 3 of `specs/formalization.md` §13. What was learnt before it:
`specs/core-next.md` (a core compressed toward a teacher's states gains English out of its corpus and loses the
sealed Russian; nearness to a teacher is a proxy the readers do not need; the 4-bit merge loses most of an adapter).

## 1. The question

Are heads over FROZEN states enough, or must the model itself be trained for the reading — and once it is, can the
base be smaller with the reading intact?

## 2. The mechanism

The reader is a set of heads over the core's states (`statement.Reader.Heads`): a tag per token (`Masked`, over the
token's state and the state at its word's end), a frame per place of a sentence (`Gaze`, looks over the sentence's
tokens), a link (`Pointer`), a pick (`Asking`). They are trained by their own hand-written gradients over states the
core gives once; the core never learns from them.

**The gradient through the heads into the core.** A head's loss L is a function of the states it reads. Its
gradient with respect to those states, G = ∂L/∂h, is computed by the head (the same chain rule its training uses,
taken one step further: to its input). The core's adapter is then trained on the surrogate

    S(θ) = Σ_positions ⟨h_θ(position), G(position)⟩  +  λ · z-MSE(h_θ, h_base)

where G is a constant computed at the current θ. ∂S/∂θ = Jᵀ G = ∂L/∂θ: the exact gradient of the heads' loss with
respect to the adapter, with no head rewritten in MLX — the heads stay one implementation, on the JVM and in the
page. The second term keeps the states near the base's (what every other reader of the base — data questions,
facts — still reads), with weight λ.

**Which texts teach the adapter.** Heads trained on a text already read it (99% on the training families): its
gradient is near zero. So the heads are trained on HALF the training corpus (split A) and the adapter on the other
half (split B) and on the teacher's rewrites (other hands, four languages), which the heads never saw: the
adapter learns to make texts readable that the heads were not fitted to — the generalization the sealed set
measures. This is LP-FT (Kumar et al. 2022): heads first, then the backbone with the heads fixed.

**Then the heads again.** After the adapter, the heads are trained afresh on the whole corpus over the adapted
states — the page's recipe exactly — so the comparison with heads alone is the same recipe on two cores.

**Where it lives.** An adapter beside the unchanged base (`Adapted`, two 4-bit planes, WebGL2): no merge (the merge
loses most of it, core-next.md). A module in the sense of §13: a file next to the base.

## 3. The measure (fixed before the run)

- **Primary:** exact with no gate, by language, on SEALED (en, ru) and on the TEACHER TEST (other hands' wording:
  en, ru, uk, pl), the mean over seeds 1–3 of the heads.
- **Hard limits:** silent wrong answers on Sealed not above the baseline's; Test (in-corpus) not below by more than
  one point; the data readers' sample (13/13) and the fact contract unchanged on the same base (λ is for that).
- **Baseline:** heads alone, the page's recipe (`READ_ECHO=2 READ_TRAIN=4000 READ_TEACHER=teacher/all.jsonl`), the
  old core, seeds 1–3 (`okay-desk-private/e2b-{1,2,3}.txt`): Sealed en 40–45, ru 6–9; teacher test en 19–28,
  ru 20–36, uk 6, pl 5–11; Unseen en 59–72, ru 40–55; Collection en 74–91, ru 69–76.
- **A difference counts** when the means differ by more than the seeds' range of either side.

## 4. Order

1. `HeadGrads` — each head's loss and gradient with respect to its input states; the law: the gradient against a
   finite difference of the loss (`HeadGradsSuite`). Tags and frames first: they carry most decisions (a text of
   the unseen families holds ~185 tags and ~24 frames; at 99.4% and 96.5% per decision both lose texts); links and
   picks after.
2. `JointTrain` — the loop of §2 on the old core (MLX, the student with a LoRA): heads from split A; per text of
   split B and the teacher's rewrites, a forward for G, a step on S. The adapter exported to the page's format
   (`SemanticExport`).
3. The measure of §3: `StatementRead` with the adapter (`SEMANTIC_NATIVE_ADAPTER`), three seeds. This answers
   question 3.
4. Only if 3 says yes: the same on a NARROWER base (question 8) — the base chosen once (the MLP narrowed, its loss
   repaired by the same joint training with λ toward the old core) and kept; the reader's adapter beside it.

## 5. Not known before the run

Whether the heads' gradient on other hands' wording is a signal or noise (the teacher's rewrites are few: 437
tasks); whether λ can keep the data readers intact while the tags move; what an adapter of rank 32 costs a phone
(two extra products per projection on WebGL2).

## 6. Results

(filled as they come)
