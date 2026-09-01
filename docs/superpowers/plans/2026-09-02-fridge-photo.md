# Fridge photo check-ins — plan

Design: [`../specs/2026-09-02-fridge-photo-design.md`](../specs/2026-09-02-fridge-photo-design.md).
Notion task 17.

## Step 1 — the source marker

- `model/CheckinSource` — `TEXT`, `VOICE`, `PHOTO`.
- `db/changelog/changes/015-checkin-source.yaml` — `source VARCHAR(16) NOT NULL DEFAULT 'TEXT'`.
- `entity/Checkin` gains the enum field.

## Step 2 — a photo can arrive

- `TelegramIncomingUpdate.Photo(chatId, telegramUserId, fileId, mediaType)`.
- `TelegramRoutingService.toIncoming` maps `message.hasPhoto()`, taking the largest `PhotoSize`.
- `TelegramOutboundService.downloadVoiceNote` → `downloadFile`; callers and the outbound test follow.

## Step 3 — the prompt

`resources/prompts/checkin-photo-system.txt`: the same three buckets, the same baseline-only rule, plus
what a photo cannot show — nothing invisible goes to `goneCompletely`, no counting, JSON only.

## Step 4 — `CheckinParsingService.parsePhoto(userId, bytes, mediaType)`

`image(...)`, parse the reply as `CheckinDelta`, filter against the baseline, store with
`CheckinSource.PHOTO`. A non-JSON reply stores the raw text and comes back as `needsClarification`.
`store(...)` gains the source; the text and voice paths pass `TEXT` and `VOICE`.

## Step 5 — the conversation

- `CheckinFlowService` handles `Photo`: download, parse, respond.
- `CheckinMessageService.photoDisclaimerText()` appended to the acknowledgement on the photo path only.

## Step 6 — tests

Added to `CheckinParsingIntegrationTest` (the Claude stub already answers there):

1. a photo during an open check-in produces the same delta shape and clears the flow;
2. the row is stored with `source = PHOTO`, and the text path still stores `TEXT`;
3. an item not in the baseline is dropped on the photo path too;
4. a reply that is not JSON asks for words instead of failing;
5. the acknowledgement carries the disclaimer.

## Step 7 — close out

README runbook for the manual demo photos, `make format`, `./gradlew test`, `./gradlew build`, commit,
Notion (demo-photo criterion left unticked with a note), ff-merge into `main`.
