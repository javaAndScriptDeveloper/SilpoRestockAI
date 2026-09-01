# Respeecher voice replies — plan

Design: [`../specs/2026-09-02-respeecher-voice-design.md`](../specs/2026-09-02-respeecher-voice-design.md).

## Step 1 — the per-chat switch

- `db/changelog/changes/017-users-voice-replies.yaml` — `voice_replies_enabled boolean not null
  default false` on `users`.
- `entity/User` gains the field.

## Step 2 — the client

- `config/RespeecherProperties` (`prefix = "respeecher"`): `apiKey`, `baseUrl`, `model`, `voiceId`,
  `maxCharacters`, plus `configured()`.
- `client/tts/TextToSpeechClient` — `isConfigured()`, `byte[] synthesize(String text)`.
- `client/tts/RespeecherTtsClient` — Feign, `X-API-Key`, `POST /v1/public/tts/{model}/tts/bytes`,
  returns the WAV bytes.
- `exception/TextToSpeechException`.

## Step 3 — the voice style

`resources/prompts/voice-style-system.txt` — Silpo's guide verbatim, plus the one instruction that
makes it a rewriter rather than a chatbot: return only the spoken version of the message given.

## Step 4 — `service/telegram/VoiceReplyService`

`enabled()`, and `Optional<byte[]> speak(String text)` — rewrite through Claude, synthesise through
Respeecher, empty on any failure.

## Step 5 — sending it

- `TelegramOutboundService.sendAudioReply(chatId, wav)` — multipart `sendAudio`, falling back to
  `sendDocument` when Telegram refuses the format.
- `sendMessage(chatId, text)` also speaks when the chat has voice replies on. Messages with buttons
  stay text-only.

## Step 6 — the command

`/voice` in `TelegramRoutingService`: toggles the flag, or says the feature is unconfigured.

## Step 7 — tests

`StubRespeecherServer` plus `VoiceReplyIntegrationTest`:

1. `/voice` with no key configured says so and stores nothing;
2. `/voice` with a key on turns it on, and the next plain message is also sent as audio;
3. the audio Telegram receives is what Respeecher returned;
4. the text Respeecher is asked to speak is the rewritten one, not the raw message;
5. a message with buttons is not spoken;
6. a refused synthesis still delivers the text;
7. `/voice` again turns it off.

## Step 8 — close out

`.env.example`, README and RUNBOOK entries, `make format`, `./gradlew build`, commit.
