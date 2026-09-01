# Check-in parsing, text and voice — plan

Design: [`../specs/2026-09-01-checkin-parsing-design.md`](../specs/2026-09-01-checkin-parsing-design.md).
Notion task 12. No schema change: `checkin` exists from task 05.

## Step 1 — the STT client

- `config/SttProperties` (`prefix = "stt"`): `apiKey`, `endpoint`, `model`, `language`, `timeout`,
  plus `apiKeyConfigured()`. Defaults in `application.yml` under the `${ENV_VAR:default}` idiom.
- `config/SttConfig` binding it.
- `client/stt/SpeechToTextClient`: `String transcribe(byte[] audio, String filename)` and
  `boolean isConfigured()`.
- `client/stt/SpeechToTextClientImpl`: JDK `HttpClient`, hand-built `multipart/form-data` (file,
  `model`, `language`), reads `text` out of the JSON reply. Blank key → `isConfigured()` false and
  every call throws `SpeechToTextException` before touching the network. The key is never logged.
- `exception/SpeechToTextException` (502), like `CartBuildException`.

## Step 2 — the prompt

`resources/prompts/checkin-system.txt`: extract into three buckets, use only names from the supplied
baseline list, verbatim; unknown or unmentioned items go in no bucket; never invent an item.

## Step 3 — `service/CheckinParsingService`

- `CheckinResult parseText(UUID userId, String rawText)` — baseline names → prompt → `CheckinDelta`
  → filter to baseline → persist `Checkin` → return.
- `CheckinResult parseVoice(UUID userId, byte[] audio)` — transcribe, then the text path; the stored
  raw text is the transcript.
- `model/CheckinResult(CheckinDelta delta, String rawText, boolean needsClarification)`.
- A model failure persists the raw row with a null delta and comes back as `needsClarification`.

Filtering is a pure static method, tested directly: case- and whitespace-insensitive match against
baseline names, first-seen baseline spelling kept, unknown names dropped and logged.

## Step 4 — the conversation

- `service/telegram/CheckinMessageService` gains `clarificationText(List<String> baselineItems)`,
  `acknowledgementText(CheckinDelta)` and `voiceUnsupportedText()`.
- `service/CheckinFlowService.handle(User, TelegramIncomingUpdate)`: text → parse; voice → download
  via `TelegramOutboundService.downloadVoiceNote` then parse, or the "type it instead" line when STT
  is not configured; button taps ignored. On success: acknowledge, clear the conversation state. On
  `needsClarification`: ask, stay in `CHECK_IN`.
- `TelegramRoutingService`: `ConversationFlow.CHECK_IN` routes here, next to the cart branch.

## Step 5 — tests

`CheckinParsingIntegrationTest` (stub Anthropic, stub Telegram, real Postgres):

1. three realistic Ukrainian phrasings bucket correctly, including a messy one with typos and no
   punctuation;
2. an item the model returned that is not in the baseline is dropped;
3. the `checkin` row is written with raw text and parsed delta;
4. a model failure still writes the raw row and asks a clarifying question;
5. an all-empty delta asks a clarifying question rather than recording "unchanged";
6. after a good parse the conversation state is cleared;
7. a voice note with no STT key configured is answered with the "type it instead" line;
8. a voice note with STT configured is transcribed and parsed — stub STT server over HTTP.

`SpeechToTextClientTest` covers the multipart body shape and the blank-key behaviour against a stub.

## Step 6 — close out

`make format`, `./gradlew test`, `./gradlew build`, commit, Notion (the "real voice sample" criterion
stays unticked with a note — it needs a live STT key), ff-merge into `main`.
