# Respeecher voice replies — design

Adds a spoken voice to the bot, using Respeecher's Space API, behind a per-chat `/voice` toggle.

## What Respeecher actually is

The task arrived as "migrate STT to Respeecher". The Space API cannot do that: its own comparison table
reads **"Space API — Text-to-speech only"**, and its endpoints are `POST /v1/public/tts/{model}/tts/bytes`,
SSE, WebSocket and a voice list. The Marketplace API is speech-to-*speech*. Neither transcribes.

The prompt Silpo shared says the same thing more clearly than the docs do — pronunciation hints with
stressed syllables, "never read aloud URLs", hesitation phrases before a tool call, numbers as words.
That is a guide for how an agent **speaks**. It even lines up with a Space API feature: the Ukrainian
model `ua-rt` supports explicit stress marking.

So Whisper keeps the voice *check-in* path (task 12, unchanged), and Respeecher gives the bot a voice.

## Off unless asked, twice

Two independent switches, both defaulting to off:

- **The deployment**: no `RESPEECHER_API_KEY`, no voice. `/voice` says so and every reply is text, exactly
  as today.
- **The chat**: `/voice` toggles `users.voice_replies_enabled`. A user who never runs it never hears
  anything.

Nothing about the existing text path changes when both are off, which is the point.

## The text a person reads is not the text a person hears

Our messages are shaped for a chat window: itemised carts, prices as digits, checkout links. Silpo's
guide forbids most of that out loud — no lists, no digits, no URLs, two items maximum per sentence.

Rather than maintaining two copies of every string, the outgoing message is **rewritten for speech by
Claude**, with Silpo's guide as the system prompt. One extra call, only when a voice reply is actually
being produced. The guide lives verbatim in `prompts/voice-style-system.txt`, so tuning the voice is a
text edit rather than a code change.

Messages that carry inline buttons stay text-only. A cart with three buttons is a thing you tap, and
reading it aloud two items at a time would be worse than not speaking at all.

## WAV, and what Telegram will take

Respeecher answers with a WAV file; `output_format` only controls the sample rate. Telegram's
`sendVoice` accepts OGG/Opus, MP3 or M4A — not WAV — and its own documentation says other formats
"may be sent as Audio or Document".

Transcoding would mean an Opus or LAME encoder and a native dependency, for a stretch feature. So the
audio goes out through `sendAudio`, falling back to `sendDocument` if Telegram refuses it. Both play
inline in Telegram clients; neither needs a codec on our side.

## Failure is silence, not an error

Every step is best-effort: no key, a refused rewrite, a refused synthesis, a refused upload. Each one is
logged and the text message the user was already getting stands on its own. A voice reply is an
enhancement to a message that has already been delivered — it must never be able to swallow one.

## Out of scope

Streaming (SSE/WebSocket) — the bytes endpoint is right for short chat replies. Voice selection per
user, and reading the voices list. Transcoding to Opus.
