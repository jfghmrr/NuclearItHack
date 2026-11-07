# Logic

## Main flow

Import, Preflight, Decode, VAD, Language detection, ASR, Diarization, Merge, Validate, save JSON, 3 s pause, LLM, Done. Stages run strictly one after another. Only one heavy job runs at a time, in FIFO order.

## Rules

- ASR text is never edited after recognition. The merger moves whole `Word` objects and never changes `Word.text`.
- The LLM names a topic and tags utterance numbers. Item text, speaker and time are copied by code. The grammar only allows real utterance numbers.
- The contract JSON is written before the LLM starts, is byte-identical with or without the LLM, and is never written when validation fails.
- An LLM failure ends in `Done(LlmStatus)` and never fails the job.
- A new import cancels a running LLM in under 1 s; the protocol becomes `DEFERRED` and gets one retry after 30 s of idle.
- Progress is real: no invented percentages and no looping animation.

## Job states

```text
Queued -> Running(IMPORT..VALIDATE) -> TranscriptReady -> [WaitingModel] -> Running(LLM) -> Done
WaitingModel -> Done(DEFERRED)
Running -> Paused -> Running                (overheating)
Running -> Interrupted -> Running           (resume from checkpoint)
Running -> Failed
TranscriptReady, Running(LLM) -> Done(LlmStatus)
Done(DEFERRED or SKIPPED_BY_BUDGET) -> Running(LLM) -> Done   (one retry after 30 s idle)
```

`txt_status` values: `IDLE`, `QUEUED`, `RUNNING:<STAGE>`, `TRANSCRIPT_READY`, `WAITING_MODEL`, `DONE`, `DONE_LLM_FAILED`, `FAILED:<CODE>`, `CANCELLED`. A thermal pause stays `RUNNING:<STAGE>`.

## LLM budget

`T_llm = max(15 s, min(0.10 T, 90 s, 0.95 T - t_core))`. The first window always gets an attempt with a 45 s generation limit, which excludes GGUF copy and model load.

## Merger invariants

Words are neither lost nor changed; timing is `0 <= start_ms < end_ms <= duration + 50 ms`; output is sorted by `start_ms`; each word is in exactly one utterance; speakers are `speaker_1..speaker_K` with no gaps, numbered by first appearance; the result is deterministic; punctuation never moves apart from its word.

## Edge cases

Silence gives `{"transcript":[]}` and the `AUDIO_EMPTY` warning. Unsupported or corrupt files fail in preflight before models load. Overlapping speech gives one word stream, each word has one speaker, and utterance intervals may overlap.
