# Read: "Neural Networks Are Already Clean" (N. Cammarata, Goodfire) — what it changes here

Read 2026-11-19 at the operator's word: https://already-clean.goodfire.com/ (the essay's text version, `essay.md`).
Read in full: the main body; of the appendix: Why Networks Generalize, Circuit Racing, Watching Neurons Develop,
Toy Models, More on Circuit Ecology, Unresolved Responses. Not read: the other appendix notes, the figures and the
interactive microscope. It calls itself "an informal research essay and working hypothesis"; the language-model
numbers are "preliminary audits" whose technical paper is not out. Nothing of it was checked by us.

## 1. What it claims

1. **One learning principle, not two.** A network groups things that share a pattern (generalization). The other
   principle the field assumed — SUPERPOSITION as packing: unrelated things put in one neuron because they do not
   collide — is, in the sense of what neurons learn, not what real networks do. The two predict opposite things
   (local against non-local grouping), and watching a neuron grow tells them apart.
2. **The canonical evidence was misread.** The "cat and car" neuron groups by a shared V shape (its negative pole
   is an O; it also takes V-shaped flames); the one published "superposition circuit" (a car into dog neurons) was
   shared POSE, seen through a feature visualization that fell into dogs. The uninterpretable last layer of
   InceptionV1 was a learning failure of that architecture: the same size and data with residual connections and
   LayerNorm gives a last layer 99% readable.
3. **Under pressure neurons get coarser, not tangled.** A layer cut from 480 neurons to 10 and retrained gives one
   "curves and loops" neuron instead of ten curve detectors; a dictionary trained on the crushed layer does not
   bring the ten back. Small models have broad neurons, large ones narrow.
4. **Recursive generalization** is what remains of polysemanticity: a neuron grows offshoots along its facets
   ("iy" → "diy" → "do it yourself"). The grouping is a clue to how the network thinks; a packed one would be none.
5. **Why:** reuse wins a race. A region that already has useful parts and readers takes the error signal before a
   fresh mechanism can grow; generalization starts at the first step; a rare or nonsense fact (an umbrella taught
   to be a bee) is an off-ramp between routes the network already has, mostly in the last blocks, not a hidden
   feature. Toy Models of Superposition prices a shared neuron's interference and not its reuse.
6. **How neurons are to be read**: as a distinction (a gate), with its negative pole; often not on the word that
   carries the meaning but where the model SUMMARIZES (commas, the token before the meaning is needed); with long
   context; tested by explanations that can lose ("blind maximum": a second agent writes, from the explanation
   alone, a sentence that should fire the neuron most). Weak activations can mostly be removed at no cost.
7. Language models: ~90% of neuron poles with one main job in Qwen3-32B by agents' audits; attention is the least
   understood part; a "double negative" regime of SwiGLU neurons fooled the tools for ~4% of neurons.

## 2. How far to believe it

- The vision experiments are concrete and could have failed (crush a layer and look; retrain an architecture and
  count). The language numbers rest on agents' judgments with thresholds the author himself calls soft; "one job"
  is where an "explanation of the gaps" would hide, as he says. Attention and the residual stream are argued
  from "it has not blocked us", not shown.
- The claim is about what NEURONS learn. That a stream holds more directions than dimensions is not denied.
- The author's firm sells interpretability; the essay is also a position in a dispute with decomposition methods
  (sparse autoencoders, transcoders). One source, read once.

## 3. What it changes for us (each a hypothesis until measured)

1. **The small model is coarse, not tangled.** Our size curves (frames.md §5.7; okay-study specs/study.md §8: an
   untrained reader gets 1–2, 13, 32 conclusions of 50 at 0.6B, 1.7B, 4B) read by this essay: the 0.6B has ONE
   neuron where the 4B has ten — it draws the coarse distinction (which terms a sentence is about) and lacks the
   fine one (which of them is negated, `All` against `No`, the direction). Checkable on runs we have: of the 0.6B's
   wrong premisses, the share with the right two terms and a wrong sign, kind or order — SPRINT T8's "lexical
   against structural", said in the essay's words.
2. **So a small model's capacity should be SPENT on the task's distinctions.** A general 0.6B spends its neurons on
   everything; an adapter of low rank can sharpen what is there and cannot re-assign many neurons. If item 1 holds
   and a LoRA does not close the fine distinctions, the next step is not a bigger general model but a small one
   whose whole capacity is trained on the reading (distilled on the choices, technology §6) — the essay's crushed
   layers stayed readable and useful at 48× less.
3. **Where to read.** Our heads read states at chosen positions and "stopped learning" (direction.md); the essay's
   summary motif says the meaning of a clause gathers on its punctuation and on the token before it is needed. For
   any reader of states: the positions tried should include the clause's last token and its comma or period, not
   the content words. (Reading by the next-token probabilities — the frames — is already at such a position.)
4. **A lesson learned grows on what the model has.** Taught facts and rare cases attach to related circuits near
   the output. For our training data: a lesson in the words of a domain the model knows will be learned faster
   and generalize along that domain's facets — and will also sprout offshoots; the refusals and near misses must
   stay in the data (frames.md round 2 lost them), since an offshoot is exactly a near miss accepted.
5. **Programs in weights (okay-inlay).** Our compiled blocks pack on purpose (liveness: registers reused). The
   essay reports that a packed code installed by hand in a trained network mostly did not work and was relied on
   less with training. That agrees with what inlay.md found — a compiled block is a side model the engine
   combines, not a part of the trained weights — and argues against trying again to fold exact blocks INTO a
   trained model by fine-tuning.
6. **The method, which we share and should keep.** "Superposition of the gaps": an unexplained result is first a
   question to the tool, then to the architecture, and last to a new principle. Ours this week: three empty
   teacher replies were the token budget, a notation passed a judge that could not tell two kinds of answer, pmi
   hurt because every option was made of the same dictionary's words. theory.md §12.1's rule — a systematic error
   of the weigher is first a question to the option space — is the same rule.
7. **An explanation that can lose** is our "said back" law turned on a model's part: from the explanation alone,
   write the input that should fit it best, and see. If we ever look inside a reader (to find why it reads `rows`
   for a group, theory §11.x), this is the test to use; and the negative pole is half of a neuron's meaning.

## 4. What it does not give us

No method for making a small model read better; nothing about readers by choices, calibration or guarantees. Its
optimism is about understanding large models with many agents — a laboratory we do not have. What we can take is
items 1–3 as measurements on our own runs, at no new training.
