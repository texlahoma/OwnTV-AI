# Seam contracts (third-party callback boundaries)

A checklist for changing any code that crosses a **third-party callback boundary** — Media3's
`AudioSink`/renderer, sherpa-onnx's streaming recognizer, the player's listener callbacks, the
coroutine arrangement that carries audio from one to the next.

It exists because a whole class of bug in the AI-subtitle audio path was chased as a *tuning*
problem when it was really a *contract* problem, and because a round of it shipped a fix that was
wrong in the opposite direction (see [Case log](#case-log)).

## Scope — what is general, what is local

- **General (transfers to any project):** "Two kinds of correctness", the checklist, the mock rules,
  the invariant table, the pre-ship checklist. These are about *method*.
- **Local (only true here):** the "Seams in this repo" register. It is deliberately specific — its
  value is that the answers are on file.
- **Version-pinned:** every register entry is pinned to the versions we ship (Media3 1.11, the
  sherpa-onnx build under `app/src/main/assets/ai`). Re-read an entry against the vendored source
  after a dependency bump. The method does not rot; the register can.

## Two kinds of correctness — only one is derivable

- **Derivable**: given the input and the model's training level, the gain is a closed-form number;
  the resampler ratio is arithmetic; the queue depth is a latency/jitter trade-off. These are solved
  once and never "tuned". If one of these were wrong it would be a mistake, not a mystery.
- **Not derivable**: whether `handleBuffer` returning `false` has already consumed part of the
  buffer, whether a format query goes through `supportsFormat` or `getFormatSupport`, what
  `isReady` gates. Here correctness is defined by **the counterparty's state machine**, not by our
  reasoning. The only way to know is to read it, in the version we ship.

Getting a seam wrong does not present as "obviously broken". It presents as *symptoms that look
tunable*: dropped frames, caption lag, wrong words, high CPU. That is the trap — the symptom invites
parameter fiddling, when the parameter is not the problem.

## The failure mode this file prevents

1. Reading **half** the contract. `handleBuffer` returning `false` means "not yet, same buffer comes
   again" (renderer half) **and** "the delegate already consumed the head of it" (sink half). Either
   half alone gives a plausible, wrong answer. Both were read only after a device bug.
2. Testing with a **hand-written double that encodes your own assumption** instead of the
   counterparty's behaviour. It passes, you ship, it fails on device. A mock that mirrors your guess
   is worse than no test, because it manufactures confidence.

## Checklist — before writing code at any seam

- [ ] **Every branch, not just the happy one.** For each return value and each throw: what does it
      *mean*, and what **state** does it leave behind?
- [ ] **Return value ≠ state change.** A boolean is rarely the whole story. `false` may already have
      mutated the counterparty's state (a buffer position, a queue, a frame counter).
- [ ] **Buffer/object ownership and lifetime.** When is it valid? When freed? Is it *reused*? Does
      anyone move its position/limit? Who copies, who views?
- [ ] **Identity and ordering.** Is the same object re-submitted? Are callbacks ordered? Can they be
      reentrant, or overlap on another thread?
- [ ] **Which thread/context.** Audio/renderer thread, worker, main? What is forbidden on each?
- [ ] **The observable invariant.** Write down the single number that proves conformance, and how to
      read it on a device (e.g. `aud/wall`). If there is no such number, that is the first thing to
      add.
- [ ] **Read the source of the version we ship** (vendor it), not its name, not a blog.

## Seams in this repo (and the gotcha that bit us)

### Media3 `AudioSink.handleBuffer`

The tap lives in the sibling core repo (`OwnTV_Core`), at
`player-core/src/main/java/tv/own/owntv/player/PlaybackAudioTap.kt` (`TapAudioSink`).

The tap sits on this to hand decoded PCM to the recognizer.

- `false` is **not** a rejection: `MediaCodecAudioRenderer` keeps the output buffer pending and calls
  `handleBuffer` again with the **same object**. The codec buffer is released only on the accepted path.
- `DefaultAudioSink` **queues the head** of the input and advances `buffer.position()` before it
  returns `false`, and it accepts once the input has been queued in full
  (`if (!inputBuffer.hasRemaining())`). One exception matters: when the audio output has **stalled**
  it returns `true` with bytes still remaining **and calls `flush()`** — those bytes are discarded,
  not played, and that flush is the discontinuity the tap must honour. (The empty-buffer and encoded
  early returns are other `true` paths; neither carries PCM for us.)
- Therefore: **read the buffer once, on its first submission**, carry the decoded samples across the
  retries, and feed the consumer **once when accepted**. Key the retry by buffer **identity**: the
  renderer re-submits one buffer at a time and `DefaultAudioSink` asserts it
  (`checkArgument(inputBuffer == null || buffer == inputBuffer)`), so identity is a safe key **for the
  sink we ship**. Clear the pending state on `flush()`.
- Invariant: `aud/wall` ≈ **1.0** during continuous playback. Both bugs below were spikes of this.

### Media3 `AudioSink` format query

- Media3 1.11's renderer asks **`supportsFormat(Format)`**; `getFormatSupport` alone is dead code and
  the capture then runs on the 24 kHz/mono defaults — real 48 kHz audio parsed against them = noise.
  *(Provenance: the tap's own kdoc, which records the d-build failure; not re-derived from the 1.11
  jar here — re-check on a Media3 bump.)*
- While the tap is armed, an **encoded** format must be refused here, or the renderer bypasses/offloads
  the bitstream and `handleBuffer` receives non-PCM that gets misread as samples.

### Media3 `AudioSink.flush`

- Fires on initial enable **and on every seek / live reposition**. The next buffer is **not** a
  continuation: restart stateful decoders, and **invalidate the pending buffer** (its decode belongs to
  the previous stretch).

### sherpa-onnx `OnlineRecognizer` / `OnlineStream`

*Provenance: our recognizer's own code and kdoc, not the upstream C++ — re-check on a sherpa bump.*

- `isReady(stream)` is a **model-chunk** gate, not a per-feed one: drain with `while (isReady) decode`.
  One decode per feed falls permanently behind on live streams (isReady stays true, backlog grows).
- `acceptWaveform(samples, sampleRate)` must be given the **true** rate; a wrong rate is silently
  time-stretched audio.
- Contextual bias (hotwords) lives **on the stream**, and only the modified-beam-search decoder
  supports it; changing the list means **recreating the stream**.

### Thread affinity

- The tap callback runs on the **renderer/playback thread** — the thread that must never block.
  Nothing heavy may run there; resample, downmix, queue push and gain all belong on the ASR worker. A
  violation shows as `tap` duty > 100% and, downstream, drops.

## Mocks: how to write one that counts

- A double must reproduce the counterparty's **state transitions**, not its happy-path return values.
  For `AudioSink`, that means: on a "not yet" reply, **advance `buffer.position()`** the way
  `DefaultAudioSink` does, or the test cannot see the bug it is supposed to catch.
- Drive the double from the **observable effects** described in the checklist above (consume, advance,
  reorder, fire `flush`), not from an invented model.
- Where a contract is subtle, add a **conformance test** that pins the real behaviour and names the
  vendored source it came from, so the assumption is checked in one place.

## Invariant assertions (what to assert, not eyeball)

| Invariant | Where |
| --- | --- |
| `aud/wall` ≈ 1.0 while playing | device status line; measure *playing* time when available (wall includes menu time) |
| tap duty < 100 % of the buffer span | device status line |
| a refused-then-accepted buffer is tapped **once, whole** | `TapAudioSinkFormatCaptureTest` |
| a retried buffer keeps its head, not just its tail | `TapAudioSinkFormatCaptureTest` |

## Before shipping a change at a seam

- [ ] I read **every** branch of the counterparty's method, in the version we ship.
- [ ] I know what each return value does to its **state**, not just its name.
- [ ] My test doubles reproduce the counterparty's **state transitions**.
- [ ] I wrote the invariant as an **assertion**, not by eyeballing a device number.
- [ ] I did **not** "tune a parameter" to hide a contract violation.

## Case log

- **`aud 472/284s` (≈1.66×)** — tapping on *every* submission re-read the shrinking remainder of each
  retried buffer: audio duplicated, queue overflowed (`drop 1248`), CPU burned. Lesson: reading the
  renderer half only.
- **`aud 125/358s` (≈0.35×)** — the "fix" tapped only on the *accepted* submission, whose remainder is
  empty: every retried buffer contributed **no** audio. The unit test passed because its mock did not
  advance the position. Lesson: the sink half, and a double that mirrored the guess.