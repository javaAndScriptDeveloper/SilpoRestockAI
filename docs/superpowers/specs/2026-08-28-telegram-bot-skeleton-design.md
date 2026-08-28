# Telegram bot skeleton — webhook + message routing

Notion task `03. Telegram bot skeleton (webhook + message routing)` (Phase `0. Setup`, Must have, size M,
depends on `01`). Coordinates with task `05` (schema) on `conversation_state`, which is still
`Not started`, so the changeset for that table ships here.

## Context

Telegram is the primary user channel for the MVP (`docs/PRODUCT_BRIEF.md` → *Канали*): the cheapest way to
accept text and voice notes without building a frontend. This change is plumbing only. Tasks 06
(onboarding), 10 (cart confirmation), 11 and 12 (check-ins) send and receive through the interface defined
here without knowing anything about the Telegram Bot API.

Tone for any user-visible string, per the brief: Ukrainian, direct, nothing decorative.

## Decisions

### Library: `org.telegram:telegrambots-client:9.0.0`

Pulls `telegrambots-meta` (the DTOs). Rejected alternatives:

- `telegrambots-springboot-webhook-starter` — wants to own the endpoint and adds bot-registration
  ceremony; the task asks for an explicit `POST /telegram/webhook` controller.
- long-polling starters — this is a Spring Boot service that should live behind a normal HTTP endpoint.
- hand-rolled DTOs over Feign — the task explicitly asks for a Telegram library, and `downloadVoiceNote`
  needs the `getFile` + `api.telegram.org/file/bot<token>/<path>` two-step the SDK already implements.

### Inbound payloads are parsed by our own Jackson 2 mapper

Spring Boot 4 puts **both** Jackson 2.21.4 (`com.fasterxml.jackson`) and Jackson 3.1.4 (`tools.jackson`) on
the classpath, and Spring MVC may select the Jackson 3 message converter. Every `telegrambots-meta` DTO is
annotated for Jackson 2. Rather than depend on which converter wins, `TelegramWebhookController` takes the
raw body as a `String` and parses it with an `ObjectMapper` this feature owns, configured with
`FAIL_ON_UNKNOWN_PROPERTIES=false` because Telegram adds fields to `Update` continuously.

### SDK types are confined to `controller.telegram` and `service.telegram`

Enforced by a new ArchUnit rule. Consequence: there is **no** `client/telegram` package, and the `client`
row of `CLAUDE.md`'s package table drops its `telegram` entry. The Telegram SDK *is* the HTTP client, so a
`client/telegram` wrapper would be a pass-through that also forces a third package into the exemption.

### `TelegramOutboundService` is a concrete class, not interface + impl

`servicesAreNamedProperly` requires every class under `..service..` to end with `Service`, which rules out
`TelegramOutboundServiceImpl`. Tests drive a stub Bot API over real HTTP instead of mocking, so an
interface would buy nothing. Other services depend on this class directly; Mockito can still mock it.

### Router is named `TelegramRoutingService`

The Notion task suggests `TelegramMessageRouter`, which fails `servicesAreNamedProperly`. Renaming one
class is cheaper than widening a project-wide naming invariant.

### `setWebhook` runs at startup, gated on config

An `ApplicationReadyEvent` listener registers the webhook when `telegram.webhook-url` is non-blank and
skips silently when it is not — so CI, tests and a bare `make run` never touch the Telegram API.

## Design

```
POST /telegram/webhook
        │  raw JSON string + X-Telegram-Bot-Api-Secret-Token
        ▼
controller/telegram/TelegramWebhookController
        │  secret check → parse Update (own Jackson 2 mapper) → always 200
        ▼
service/telegram/TelegramRoutingService
        │  Update → sealed TelegramIncomingUpdate
        ├──────────────► service/ConversationStateService ──► repository/ConversationStateRepository
        ▼
service/telegram/TelegramOutboundService  ──► OkHttpTelegramClient ──► api.telegram.org
```

### New files — main

| File | Holds |
|---|---|
| `config/TelegramProperties` | `@ConfigurationProperties("telegram")`; no SDK types |
| `controller/telegram/TelegramWebhookController` | `POST /telegram/webhook` |
| `service/telegram/TelegramRoutingService` | update → internal model → handler |
| `service/telegram/TelegramOutboundService` | `sendMessage`, `sendMessageWithButtons`, `answerCallback`, `downloadVoiceNote` |
| `service/telegram/TelegramWebhookRegistrationService` | `setWebhook` on `ApplicationReadyEvent` |
| `service/ConversationStateService` | `load` / `save` / `clear` |
| `entity/ConversationState` | maps `conversation_state` |
| `repository/ConversationStateRepository` | `JpaRepository<ConversationState, Long>` |
| `model/TelegramIncomingUpdate` + `TelegramTextMessage`, `TelegramVoiceMessage`, `TelegramButtonCallback` | sealed internal inbound model |
| `model/TelegramButton` | `(String label, String callbackData)` |
| `model/ConversationFlow` | `NONE`, `ONBOARDING`, `CHECK_IN`, `CART_CONFIRMATION` |
| `exception/TelegramApiFailureException` | wraps `TelegramApiException` |
| `db/changelog/changes/002-conversation-state.yaml` | the table |

### Modified

`build.gradle.kts`, `application.yml`, `.env.example`, `README.md` (ngrok section), `CLAUDE.md` (package
table row, Telegram invariant), `src/test/java/.../architecture/ArchitectureTest.java` (new rule).

### Schema

```
conversation_state
  telegram_chat_id  BIGINT       PRIMARY KEY
  current_flow      VARCHAR(64)  NOT NULL      -- ConversationFlow name
  current_step      VARCHAR(64)
  context_json      JSONB        NOT NULL DEFAULT '{}'
  created_at        TIMESTAMPTZ  NOT NULL
  updated_at        TIMESTAMPTZ  NOT NULL
```

Column names follow task 05's draft so that task can adopt the table unchanged. The entity maps
`context_json` with Hibernate's native `@JdbcTypeCode(SqlTypes.JSON)` over `Map<String, Object>` — no
`AttributeConverter`, which task 05 can introduce generically later.

### Controller contract

- When `telegram.webhook-secret` is set, `X-Telegram-Bot-Api-Secret-Token` must match under
  `MessageDigest.isEqual`; otherwise `401` and nothing is routed. The secret is never logged.
- Otherwise the endpoint **always answers 200**, including when the handler throws (logged at error).
  Telegram retries any non-2xx indefinitely, so one poison update would loop forever.
- The endpoint is public by design — Telegram cannot authenticate any other way. The secret token is the
  only gate, which is why it belongs in `.env` for any deployment reachable from the internet.

### Routing

`TelegramRoutingService.route(Update)` maps to exactly one of the sealed internal types and drops anything
else at debug level. The handler is a placeholder marked `TODO(#6)`: it loads conversation state,
increments a counter in `context_json`, saves, and echoes
`Комора: почув — «<text>» (повідомлення №N)`. That echo is what proves webhook → router → state → outbound
is wired end to end, and it is deleted when task 06 lands.

### Configuration

```yaml
telegram:
  bot-token:      ${TELEGRAM_BOT_TOKEN:}
  webhook-url:    ${TELEGRAM_WEBHOOK_URL:}
  webhook-secret: ${TELEGRAM_WEBHOOK_SECRET:}
  api-url:        ${TELEGRAM_API_URL:https://api.telegram.org}
```

`api-url` exists so integration tests can point `TelegramUrl` at a local stub; it is not a production knob.

## Testing

`support/StubTelegramServer` — JDK `com.sun.net.httpserver` stub in the shape of `StubMcpServer`, serving
`/bot<token>/sendMessage`, `/answerCallbackQuery`, `/getFile`, `/setWebhook` and
`/file/bot<token>/<path>`, recording every request for assertions.

| Test | Asserts |
|---|---|
| text update | stub received `sendMessage` with the right `chat_id` and echo text |
| two consecutive text updates | second echo says `№2` — state survived two separate webhook calls |
| voice update | `downloadVoiceNote(fileId)` returns the stub's bytes via `getFile` + file download |
| callback query | routed to the callback branch; `answerCallbackQuery` sent |
| wrong secret token | `401`, nothing sent to Telegram |
| unknown update kind | `200`, nothing sent |
| ArchUnit | no `org.telegram..` dependency outside `..controller.telegram..` / `..service.telegram..` |

No live smoke test: no bot token is available. The README documents the ngrok procedure so it can be run
by hand, and the gap is stated in the commit message the way task 02's OTP gap was.

## Out of scope

No conversation flows (onboarding, check-ins, cart confirmation) — those are tasks 06, 10, 11, 12. No
transcription of voice notes (task 12); this change only hands back raw bytes. No message templates beyond
the placeholder echo. No `users` table — `conversation_state` is keyed by `telegram_chat_id`, and task 05
introduces the foreign key.
