# The phone first: the frame reader on the operator's iPhone (spec, 2026-11-15)

## 0. The operator's priority (2026-11-15)

"I want to see the result clearly, on my own phone — so it matters that it works ON THE PHONE. That a big model can
solve something is no longer surprising. It is surprising when a small model can do the same."

So the next deliverable is not a better number on the Mac: it is the frame reader (`specs/frames.md`) answering the
`Everyday` kind of questions in the operator's iPhone browser, with its guarantees (`specs/guarantees.md`) shown —
and the numbers measured ON the phone beside the 35B's ceiling. This file reorders the line's work around that; the
method, the theory and the measures are the line's own (direction.md, frames.md, guarantees.md) and do not change.

## 1. Where it stands (2026-11-15)

- The reader: the released Qwen3 0.6B (28 layers, 4-bit) + a LoRA (rank 16) choosing each line of a `Frame` among the
  tools' options. `Everyday` (54 a language): two readers agreeing — en 25 right / 23 asked / 6 wrong, ru 19 / 31 / 4;
  the heads on the page now 22/16/16, 9/29/16; the 35B's ceiling 43/0/11 (frames.md §5.6).
- The program: `FrameReading.read` over `Read` + `Person`, one program for the JVM and the page (frames.md §6).
- The page's weighing: `OnQwen` answers `Read.Score` with okay-llm's `Qwen3Compute.score` (packed, one forward per
  line): 8.6 s a question in a desktop browser against mlx-scala's 1.5 s — the vocabulary's logits read back for every
  option's row (frames.md §6.1).
- On an iPhone: WebGL2 only (no WebGPU); the 28-layer model generates there (commit aa6b178); the 12-layer reader
  crashed on a second task until the texture fixes (formalization.md §18.3) — 28 layers have not been tried on it.

## 2. The steps, in order (SPRINT.md "P")

1. **P1 the log-probability on the device.** `Qwen3Compute.score` returns one number per option (the mean
   log-probability of its tokens) computed on the device: a log-softmax and a gather in a shader (WebGL2 and
   WebGPU), only `options` floats read back. Law: equal to the CPU's within 1e-4 (`CacheSuite`). Measure: seconds a
   question in Chromium (desktop and phone profile), against 8.6 s.
2. **P2 the frame reader on the page, the phone's way.** Behind the chat's data question: the frame reader with ONE
   adapter (not merged: `Adapted` beside the 4-bit base, so the second reader is a second small file, not a second
   model), the doubt at the margin; the words of the frame shown ("Understood as", `FrameSay`); a question in doubt
   as the cards built (3ec2ce9). Memory on the phone profile measured over 10 questions in a row (GPU MB, textures
   made/deleted — `/tmp/ok/seq.scala`'s counters; a copy kept in `scripts/`). Published.
3. **P3 the operator's iPhone.** He asks 10 `Everyday`-like questions over the page's sample tables (a printed list
   in this spec's §3.1, chosen from the TRAIN side's kinds, not `Everyday`'s own questions); he reports per question:
   the answer, seconds, any reload. The numbers go into §4.
4. **P4 two readers on the phone.** The second adapter's choices for the same lines (two forwards per line, the
   base's weights read once if the device allows — otherwise sequential); agreement as the doubt. Measured on the
   phone profile, then the operator's iPhone. The wrong answers' fourfold cut (frames.md §5.6) is the reason.
5. **P5 the decision on the size** (guarantees.md §6.4), only if P3/P4 say the 28 layers do not fit (a reload, or
   more than ~15 s a question on the iPhone): the student distilled on the CHOICES (not on states), or shared looped
   layers with LoRA — the curve size ↔ right/wrong on `Everyday` at the same ε, the phone's point chosen by P3's
   limits. Not before: the released model's numbers are the ones to beat.

The line's other items (the calibration, the canonical fragment, the data's kinds — SPRINT.md 8–10) continue beside
these; where both touch the page, P2's page is the one they land on.

## 3. What the operator sees when it is done

His iPhone, the page's plain address: a table opened (an expense list, a shop's sales), a question typed in Russian
or English, within seconds: the answer (the engine's numbers), "Understood as: …" in words, and where the readers
disagree a card asking which meaning — no silent guess. Beside it, in the page's "About": the same questions'
numbers for this phone model and the 35B, so the surprise is measured, not claimed.

## 3.1 The operator's ten questions (P3), over the page's sample tables (orders, customers)

Not `Everyday`'s questions (never trained on, never shown to a reader before its measure); kinds the training has:

1. How many orders are unpaid?
2. What is the total amount of paid orders?
3. Which city has the most orders?
4. Show the three largest orders.
5. What did ann spend in November? (a month)
6. Сколько заказов из Львова?
7. Какая средняя сумма оплаченного заказа?
8. Покажи заказы клиентов из Польши. (a join with customers)
9. Кто из клиентов сделал больше всего заказов?
10. Почему bob не оплатил заказ? (outside the language: the right outcome is a refusal, never a number)

For each: the answer shown, right or not (the exact answer computed by the page from the gold program the agent
writes beside this list before P3), seconds, any reload.

## 4. The phone's numbers (filled by P2–P4)

### 4.1 The phone bench on the operator's iPhone (2026-11-17, `scripts/phonebench`)

The published page (build b1e98078c706) served from the Mac on the local network with `bench.js`; every step logged
on the Mac (`okay-desk-private/phonebench/log.txt`). The device: iOS 18.7, Safari, 414×896@2, WebGL2 (float targets),
no WebGPU, "Apple GPU".

| measure | the iPhone | the Mac (M4 Max, Chromium as a phone) |
|---|---|---|
| a 1024² product in a fragment shader | 34 GFLOPS (63 ms) | 650–715 GFLOPS (3 ms) |
| GPU memory taken in an empty page before the tab is killed | ~1.5 GB (killed taking the 1.6th GB) | > 3 GB (the bench's limit) |
| the frame reader (28 layers 4-bit + two adapters) | the tab killed by iOS 19 s after the last weight file, twice, before any answer | stopped by the page's own 512 MiB WebGL2 budget → silently "needs external evidence" |
| loading the reader over Wi-Fi from the Mac | ~4 min (~1.7 MB/s) | — |

What it says: the frame reader does not run on this iPhone at all — memory, not speed, is the first wall. The tab
holds ~1.5 GB of GPU memory in all; the page with the reader goes over it (the weights' CPU copies, two adapters,
the other readers' files the frames path also fetches: statement reader, facts, relations). A 1.7B (~1 GB at 4 bits)
cannot fit beside the rest in a browser tab on this phone. Speed is the second wall: 1/20 of the Mac's shader rate.
Bugs found on the way: BACKLOG P'.

### 4.2 The frame reader runs on the iPhone (2026-11-17, the same day; okay-desk 2e94f84, okay-llm f684612)

The ten questions of §3.1 on the operator's iPhone, the whole run in one tab, no reload: **6 right** (1, 2, 4, 5, 6,
7: 4 unpaid, 1460 paid, the three largest, ann's November, 3 from Lviv, the average 365), **3 asked** (3, 8, 9),
**the refusal right** (10) — the same answers as WebKit as a phone on the Mac. With the model loaded, 704 MB more of
GPU memory were taken before the tab died (the room left). GPU in textures: 240 MB weights, ~360 steady, peak 393.

What it took, each measured (WebKit as a phone on the Mac, then the iPhone):

| change | why | measured |
|---|---|---|
| the tokenizer by the browser's `JSON.parse` (`Tokenizer.of`) | a tree of the 11 MB file in Scala values | −190 MB of the page |
| the weights onto the device a file at a time (`Preloaded`), from the file's bytes (`quantizedFrom`), one reused buffer | all files and three copies of every weight held at once | page peak 1470 → ~870 MB |
| ONE reader on a phone: the adapter MERGED into the weights (`model-frames-merged`, FrameMerge), its bar 0.3 | the adapter beside every projection; bar 1.0 is two readers' | page ~950 → 709 MB, GPU 483 → 402 MB; more answered |
| the prefix cache in halves on a phone; the budget 768 MiB | | |
| a line's options weighed in parts of 16, each normed over all the options' tokens (`OnQwen.parts`, `score over`) | a `where` of 171 options was ONE GPU task of minutes: the iPhone killed the tab in it (traced by the kept last read) | the tab lives; the numbers of one forward |

The wall that was hit was not the model's memory but one GPU task too long: the weighing of 171 options packed into
one forward. A phone needs its work in pieces it can finish.

**Now the wall is time:** loading over Wi-Fi ~3.5 min; a question 25–245 s (median ~150 s) on the iPhone against
2–14 s on the Mac — the iPhone's shader rate is 1/20 of the Mac's (§4.1). Next: fewer and smaller forwards per
question — decide only what changes the answer and the data's exact words first (theory.md §11 E5, E6), options
packed in a trie (E7), the prefix shorter (the tables a question names, not all of them).

### 4.3 Faster on the iPhone (2026-11-17 afternoon; okay-llm d87bf1d, 7232958; okay-desk 80069d5, c8fc5ca, 594b88a)

The same ten questions, the same answers (✅ 1 2 4 5 6 7, ❓ 3 8 9, ⛔ 10 right), seconds a question on the iPhone:

| question | morning (§4.2) | the trie, parts of 48 | + the tables' prefix kept |
|---|---|---|---|
| 1 (with loading the model) | 308 | 248 | 250 |
| 2 | 160 | 66 | **45** |
| 3 ❓ | 30 | 25 | **4** |
| 4 | 94 | 45 | **24** |
| 5 | 243 | 88 | **67** |
| 6 | 223 | 66 | **45** |
| 7 | 197 | 67 | **45** |
| 8 ❓ | 128 | 44 | **23** |
| 9 ❓ | 38 | 35 | **11** |
| 10 ⛔ | 25 | 30 | **5** |

- **The options as a trie** (`Qwen3Compute.scoreTree`, `OnWebGl2` tree shaders): a `where` line's 171 options are 317
  rows, not 1037; the numbers are the segments' — law on MLX (2e-6) and checked ON the iPhone (`?checktree`: 0).
  The heaviest weighing 45–65 s → ~20 s. Parts of 48 options (the rows of 16 packed whole, the size the iPhone
  finished).
- **The prompt's beginning kept between questions** (`Cache.take`, `OnQwen`): the rule and the tables are the same
  for every question over them; only the question's own tokens run — ~35 s less a question on the iPhone.
- **The bench over HTTPS** (self-signed, port 8753): a secure context — the Wake Lock keeps the screen on (the
  silent-video trick no longer works on iOS 18), the Cache API keeps the model for the next visit; a download with
  no byte for 20 s is tried again (the screen had locked mid-file).

Now: a question 5–67 s (median ~35 s) after the first. Left: the first question's prompt (~35 s) and loading
(~3.5 min the first visit, then from the device's cache); E5/E6 (fewer lines weighed); the published page.

### 4.4 Pruned, and the model from the device's cache (2026-11-17, okay-desk efe9a0f)

A condition on a value the question does not name is no longer weighed (theory E5: a text value not linked to the
question, a number it does not write — a column of at most 4 values keeps its own, "the second term"): a `where`
line 172 → 64 options. Everyday on the JVM: 107 of 108 frames as before; shifts-33 went asked → wrong (its
runner-up was a pruned condition — the doubt had leaned on an option no question names). The iPhone, over HTTPS, the
model from the Cache API of the earlier visit:

| question | morning (§4.2) | §4.3 | **now** |
|---|---|---|---|
| 1 (with loading the model) | 308 | 250 | **32** |
| 2 | 160 | 45 | **17** |
| 3 ❓ | 30 | 4 | **4** |
| 4 | 94 | 24 | **11** |
| 5 | 243 | 67 | **30** |
| 6 | 223 | 45 | **23** |
| 7 | 197 | 45 | **26** |
| 8 ❓ | 128 | 23 | **12** |
| 9 ❓ | 38 | 11 | **11** |
| 10 ⛔ | 25 | 5 | **6** |

The ten questions with the model's loading: ~3 minutes, was ~25 this morning. The same answers as the Mac's.

### 4.5 The 4-bit product's reads shared (2026-11-17, okay-llm 40a7446)

The iPhone is bound by the arithmetic of a weighing, not by its passes: the cache grown from the trie's rows with no
forward (okay-llm d0a4fd3) gave ~5%, a line weighed in one part instead of two nothing, 16 heads a draw broke its
numbers (a draw too long — the phone keeps 2). The product itself was the lever: `OnWebGl2.linearShared` reads a
fragment's X once for its four outputs and a group's words two texels at a time — 32 texture reads a group of 64
weights where there were 96; each sum in the old order, the numbers identical (the traced weighings to the digit).

| question | §4.4 | **now** |
|---|---|---|
| 1 (with loading the model from the cache) | 32 | **21** |
| 2 | 17 | **9** |
| 3 ❓ | 4 | **3** |
| 4 | 11 | **6** |
| 5 | 30 | **18** |
| 6 | 23 | **16** |
| 7 | 26 | **16** |
| 8 ❓ | 12 | **8** |
| 9 ❓ | 11 | **7** |
| 10 ⛔ | 6 | **4** |

The ten questions with loading: ~1.8 min (was ~25 this morning). A big weighing 5.0 → 3.1 s on a cool phone; it
heats over the run and slows to ~5.5 s by question 6 — the phone's own limit, a reason to do less, not to do it
faster.

### 4.6 The prompt warmed while the question is typed (2026-11-24, SPRINT P6)

The tables open (`Model.Prepare`, told by `App.loop` when they change): the page runs the frame reader's prompt up to
"Question:" — the rule and the tables, nothing of the question — for each reader and keeps it (`OnQwen.warm`); the
question waits for that and runs only its own tokens. No model yet: the frame reader is loaded in the background
when its files are on the device already (a visit before downloaded them) — never a download nobody asked for; the
loading the question then asks for is that one (`Engine.begin` reuses it).

Measured in Chromium, a returning visitor (one profile kept: the files and the tables open from before), the sample's
two tables (379 tokens of prompt), 15 s / 60 s to type, the same frames:

| | the warming | the first question | the second |
|---|---|---|---|
| desktop, WebGPU, two readers | 0.2–0.3 s | 10.5 / 9.7 → 9.6 / 9.3 s (two runs) | ~5 s, unchanged |
| the phone's profile (WebGL2, the head on the CPU, one reader) | 0.84 s | 9.3 → 6.6 s | 4.0 → 4.6 s |

On the desktop the prompt is cheap (~0.1 s a reader), so the first question's extra seconds are something else (the
GPU programs built at first use, the first weighing's own); on the phone's profile the warming also builds the
programs the first forward needs, and the first question gains 2.7 s. On the operator's iPhone the prompt took tens of
seconds (§4.2): to be measured there.
