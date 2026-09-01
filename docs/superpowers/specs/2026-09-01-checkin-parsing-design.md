# Check-in parsing, text and voice — design

Notion task **12. Free-text/voice check-in parsing (Claude) → inventory delta**. Product brief user
flow #2, steps 2–3. Diffing the delta against the baseline is #13.

## Problem

Task 11 leaves the chat in `CHECK_IN` / `AWAITING_REPORT` and waits. The answer arrives as a sentence
somebody typed one-handed — *"молоко ще є, хліба нема, гречка на межі"* — or as a voice note. It has to
become `CheckinDelta(stillHave, runningLow, goneCompletely)` with item names the rest of the system
already knows, and it has to be recorded whether or not the parse worked.

## Shape

| Piece | Package | Job |
|---|---|---|
| `CheckinParsingService` | `service` | text → delta, voice → text → delta, persist either way |
| `CheckinFlowService` | `service` | the conversation: what to do with the answer, what to say back |
| `CheckinMessageService` | `service.telegram` | the wording (already exists from #11) |
| `SpeechToTextClient` | `client.stt` | one method: bytes in, transcript out |
| `prompts/checkin-system.txt` | resources | the extraction instructions |

The split mirrors onboarding: a service that knows the domain, a service that knows the conversation.

## The prompt carries the baseline

The model is given the household's current baseline item names and told to answer only with names from
that list. Loose phrasing then maps onto real items instead of inventing new ones — *"хліба нема"*
becomes the baseline's `Хліб пшеничний`, not a new item called `хліб`.

Instructions alone are not enough, so the result is **filtered in code** against the baseline names,
case-insensitively. Anything the model returned that is not a baseline item is dropped and logged. The
prompt is the ask; the filter is the guarantee.

## Low confidence is a question, not a silence

An answer that produces an empty delta in all three buckets — the model understood nothing, or the user
sent *"ок"* — must not be recorded as "everything unchanged". That is the one outcome that silently
corrupts the next reorder. The service reports it, and the flow asks one clarifying question naming two
or three baseline items to answer about.

The check-in row is written **either way**: raw text always, parsed delta when there is one. A bad parse
that was recorded can be diagnosed; a bad parse that was dropped cannot.

## Voice: a separate transcription API, and a graceful fallback

Checked before committing, as the task asks: the Anthropic Messages API takes text, images and PDFs —
not audio. There is no Anthropic transcription endpoint, so Claude cannot take an OGG/Opus voice note
directly, and the voice path needs a speech-to-text service of its own.

The choice is an **OpenAI-compatible transcription endpoint** (`POST /v1/audio/transcriptions`,
`whisper-1` by default) behind a one-method `SpeechToTextClient`. It is the smallest new surface that
exists: one key, one URL, one model name, all configurable — pointing `stt.endpoint` at Groq or a local
whisper server needs no code change. Ukrainian is passed as `language` so the model does not guess.

Two deliberate deviations, both narrow:

- **Not Feign.** The endpoint is `multipart/form-data`, which Feign cannot express without adding a form
  encoder dependency. The MCP transport is already the precedent for "JDK `HttpClient` where Feign does
  not fit"; this is the second and last case.
- **No key is not a failure.** With `stt.api-key` blank the client is not built and the flow answers
  *"Голосові поки не розбираю. Напиши, будь ласка, текстом."* — the same sentence onboarding already
  uses. A demo without an STT key still works, in text.

## Failure behaviour

| Failure | What the user sees |
|---|---|
| Claude unreachable or malformed | "Не розібрав. Напиши коротко: чого вже нема?" and a raw-only check-in row |
| Empty delta after filtering | one clarifying question naming baseline items |
| Voice with no STT configured | asked to type instead; nothing recorded |
| Transcription fails | same as above, plus a logged error |
| No baseline at all | the check-in flow is not reachable — #11 only prompts households that have one |

## Out of scope

The diff against the baseline, `inventory_trend` updates and the "nobody eats this" signal are #13.
This task ends at a stored `CheckinDelta`.
