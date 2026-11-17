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
