# Telegram Bot Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A webhook-only Telegram integration layer that receives updates, routes text / voice / inline-button callbacks, resumes conversation state across stateless webhook calls, and exposes one outbound API the rest of the app uses instead of the Telegram Bot API.

**Architecture:** `POST /telegram/webhook` takes the raw JSON body, verifies Telegram's secret-token header, parses it with a Jackson 2 `ObjectMapper` this feature owns, and hands the SDK `Update` to `TelegramRoutingService`. The router converts it into a sealed internal model, loads and saves `conversation_state`, and replies through `TelegramOutboundService`, which is the only class holding the Telegram SDK client. Telegram SDK types never leave `controller.telegram` and `service.telegram`; an ArchUnit rule enforces that.

**Tech Stack:** Java 25, Spring Boot 4.1.0, `org.telegram:telegrambots-client:9.0.0`, Hibernate 7 native JSON mapping, Liquibase, Testcontainers PostgreSQL, JDK `com.sun.net.httpserver` stub servers, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-28-telegram-bot-skeleton-design.md`

## Global Constraints

- Base package is `com.silporestockai`. Put classes in the package the `CLAUDE.md` table names; do not invent a parallel structure.
- **Liquibase owns the schema.** `ddl-auto: validate`. Every new `@Entity` needs a changeset under `src/main/resources/db/changelog/changes/`, named `NNN-....yaml`, or *every* `@SpringBootTest` fails.
- **ArchUnit is enforced.** Constructor injection only, no `@Autowired` fields. Every class under `..controller..` ends with `Controller`, under `..service..` ends with `Service`, under `..repository..` ends with `Repository`, under `..job..` ends with `Scheduler`.
- **Spotless (palantir).** Run `make format` before every commit; CI runs `spotlessCheck` before `build`.
- Config idiom is `${ENV_VAR:default}` inline in `application.yml`. Secrets never hardcoded. `.env` is gitignored, `.env.example` is committed.
- `@Slf4j` for logging, never a manual `LoggerFactory`.
- Bot token and webhook secret are never logged and never appear in a response body.
- All user-visible strings are Ukrainian, direct, no decoration (product brief tone).
- Commit subject ≤72 chars, imperative mood. Body explains motivation, not the diff.
- Run tests with `./gradlew test`. Docker must be running.

---

### Task 1: Conversation state persistence

**Files:**
- Create: `src/main/resources/db/changelog/changes/002-conversation-state.yaml`
- Create: `src/main/java/com/silporestockai/model/ConversationFlow.java`
- Create: `src/main/java/com/silporestockai/entity/ConversationState.java`
- Create: `src/main/java/com/silporestockai/repository/ConversationStateRepository.java`
- Create: `src/main/java/com/silporestockai/service/ConversationStateService.java`
- Test: `src/test/java/com/silporestockai/integration/ConversationStateIntegrationTest.java`

**Interfaces:**
- Consumes: `com.silporestockai.integration.AbstractIntegrationTest` (existing base class: `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, Testcontainers PostgreSQL).
- Produces:
  - `enum ConversationFlow { NONE, ONBOARDING, CHECK_IN, CART_CONFIRMATION }`
  - `ConversationState` with `getTelegramChatId()`, `getCurrentFlow()`, `getCurrentStep()`, `getContext()` returning `Map<String, Object>`
  - `ConversationStateService.load(long chatId) -> ConversationState` (never null; a transient `NONE` state when absent)
  - `ConversationStateService.save(long chatId, ConversationFlow flow, String step, Map<String, Object> context) -> ConversationState`
  - `ConversationStateService.clear(long chatId) -> void`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/ConversationStateIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.ConversationState;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.service.ConversationStateService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("conversation_state survives separate transactions the way separate webhook calls need it to")
class ConversationStateIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 4242L;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @BeforeEach
    void clean() {
        conversationStateRepository.deleteAll();
    }

    @Test
    void returnsATransientEmptyStateForAnUnknownChat() {
        ConversationState state = conversationStateService.load(CHAT_ID);

        assertThat(state.getTelegramChatId()).isEqualTo(CHAT_ID);
        assertThat(state.getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
        assertThat(state.getCurrentStep()).isNull();
        assertThat(state.getContext()).isEmpty();
        assertThat(conversationStateRepository.count()).isZero();
    }

    @Test
    void savesAndReadsBackFlowStepAndJsonContext() {
        conversationStateService.save(
                CHAT_ID, ConversationFlow.ONBOARDING, "ask-household-size", Map.of("messageCount", 1));

        ConversationState reloaded = conversationStateService.load(CHAT_ID);

        assertThat(reloaded.getCurrentFlow()).isEqualTo(ConversationFlow.ONBOARDING);
        assertThat(reloaded.getCurrentStep()).isEqualTo("ask-household-size");
        assertThat(reloaded.getContext()).containsEntry("messageCount", 1);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void overwritesTheStateForAChatInsteadOfAppendingRows() {
        conversationStateService.save(CHAT_ID, ConversationFlow.ONBOARDING, "step-1", Map.of("messageCount", 1));
        conversationStateService.save(CHAT_ID, ConversationFlow.CHECK_IN, "step-2", Map.of("messageCount", 2));

        assertThat(conversationStateRepository.count()).isEqualTo(1);
        ConversationState reloaded = conversationStateService.load(CHAT_ID);
        assertThat(reloaded.getCurrentFlow()).isEqualTo(ConversationFlow.CHECK_IN);
        assertThat(reloaded.getContext()).containsEntry("messageCount", 2);
    }

    @Test
    void clearRemovesTheRow() {
        conversationStateService.save(CHAT_ID, ConversationFlow.ONBOARDING, "step-1", Map.of());

        conversationStateService.clear(CHAT_ID);

        assertThat(conversationStateRepository.count()).isZero();
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ConversationStateIntegrationTest*'`
Expected: FAIL — compilation error, `ConversationStateService` / `ConversationState` / `ConversationFlow` / `ConversationStateRepository` do not exist.

- [ ] **Step 3: Write the Liquibase changeset**

Create `src/main/resources/db/changelog/changes/002-conversation-state.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 002-conversation-state
      author: komora
      comment: >-
        Per-chat conversation state. Telegram webhooks are stateless per request, so multi-step flows
        (onboarding, cart confirmation) resume from this row. Keyed by telegram_chat_id for now; task 05
        introduces the users table and the foreign key.
      changes:
        - createTable:
            tableName: conversation_state
            columns:
              - column:
                  name: telegram_chat_id
                  type: BIGINT
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_conversation_state
                    nullable: false
              - column:
                  name: current_flow
                  type: VARCHAR(64)
                  constraints:
                    nullable: false
              - column:
                  name: current_step
                  type: VARCHAR(64)
              - column:
                  name: context_json
                  type: JSONB
                  defaultValueComputed: "'{}'::jsonb"
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
              - column:
                  name: updated_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
```

- [ ] **Step 4: Write the enum**

Create `src/main/java/com/silporestockai/model/ConversationFlow.java`:

```java
package com.silporestockai.model;

/**
 * Which multi-step conversation a chat is currently in. Persisted by name, so entries may be added but
 * existing names must not be renamed without a migration.
 */
public enum ConversationFlow {
    /** No flow in progress — the next message starts one. */
    NONE,
    /** First-run profile collection (task 06). */
    ONBOARDING,
    /** Periodic "what is left in the fridge" exchange (tasks 11 and 12). */
    CHECK_IN,
    /** Reviewing and confirming a proposed cart (task 10). */
    CART_CONFIRMATION
}
```

- [ ] **Step 5: Write the entity**

Create `src/main/java/com/silporestockai/entity/ConversationState.java`:

```java
package com.silporestockai.entity;

import com.silporestockai.model.ConversationFlow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Where a Telegram chat is inside a multi-step conversation.
 *
 * <p>Telegram delivers every update as an independent HTTP request, so nothing survives in memory between
 * two messages from the same person. This row is that memory.
 *
 * <p>{@code context} is mapped straight onto a Postgres {@code jsonb} column through Hibernate's native
 * JSON support — no {@code AttributeConverter}. Task 05 may generalise that later.
 */
@Entity
@Table(name = "conversation_state")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ConversationState {

    @Id
    @Column(name = "telegram_chat_id", nullable = false)
    private Long telegramChatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_flow", nullable = false, length = 64)
    private ConversationFlow currentFlow;

    @Column(name = "current_step", length = 64)
    private String currentStep;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json", nullable = false)
    @Builder.Default
    private Map<String, Object> context = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

- [ ] **Step 6: Write the repository**

Create `src/main/java/com/silporestockai/repository/ConversationStateRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.ConversationState;
import org.springframework.data.jpa.repository.JpaRepository;

/** Conversation state, one row per Telegram chat. */
public interface ConversationStateRepository extends JpaRepository<ConversationState, Long> {}
```

- [ ] **Step 7: Write the service**

Create `src/main/java/com/silporestockai/service/ConversationStateService.java`:

```java
package com.silporestockai.service;

import com.silporestockai.entity.ConversationState;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.ConversationStateRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the conversation state a Telegram chat resumes from.
 *
 * <p>{@link #load(long)} never returns {@code null}: an unknown chat gets a transient {@link
 * ConversationFlow#NONE} state that is not persisted until something calls {@link #save}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationStateService {

    private final ConversationStateRepository repository;

    @Transactional(readOnly = true)
    public ConversationState load(long telegramChatId) {
        return repository.findById(telegramChatId).orElseGet(() -> ConversationState.builder()
                .telegramChatId(telegramChatId)
                .currentFlow(ConversationFlow.NONE)
                .context(new LinkedHashMap<>())
                .build());
    }

    @Transactional
    public ConversationState save(
            long telegramChatId, ConversationFlow flow, String step, Map<String, Object> context) {
        Instant now = Instant.now();
        ConversationState state = repository.findById(telegramChatId).orElseGet(() -> ConversationState.builder()
                .telegramChatId(telegramChatId)
                .createdAt(now)
                .build());
        state.setCurrentFlow(flow == null ? ConversationFlow.NONE : flow);
        state.setCurrentStep(step);
        state.setContext(context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context));
        state.setUpdatedAt(now);
        if (state.getCreatedAt() == null) {
            state.setCreatedAt(now);
        }
        return repository.save(state);
    }

    @Transactional
    public void clear(long telegramChatId) {
        repository.deleteById(telegramChatId);
        log.debug("cleared conversation state for chat {}", telegramChatId);
    }
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew test --tests '*ConversationStateIntegrationTest*'`
Expected: PASS, 4 tests.

If it fails with `Schema validation: wrong column type encountered in column [context_json]`, the Postgres dialect expects `jsonb` — confirm the changeset says `type: JSONB` and that `build/resources/main/db/changelog/changes/002-conversation-state.yaml` exists after the build.

- [ ] **Step 9: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/java/com/silporestockai/model/ConversationFlow.java \
        src/main/java/com/silporestockai/entity/ConversationState.java \
        src/main/java/com/silporestockai/repository/ConversationStateRepository.java \
        src/main/java/com/silporestockai/service/ConversationStateService.java \
        src/main/resources/db/changelog/changes/002-conversation-state.yaml \
        src/test/java/com/silporestockai/integration/ConversationStateIntegrationTest.java
git commit -m "Add conversation state so Telegram flows can resume"
```

---

### Task 2: Telegram library, configuration and the outbound service

**Files:**
- Modify: `build.gradle.kts` (dependency block, after the MCP entry)
- Modify: `src/main/resources/application.yml` (new top-level `telegram:` block)
- Modify: `.env.example` (new Telegram block)
- Create: `src/main/java/com/silporestockai/config/TelegramProperties.java`
- Create: `src/main/java/com/silporestockai/config/TelegramConfig.java`
- Create: `src/main/java/com/silporestockai/exception/TelegramApiFailureException.java`
- Create: `src/main/java/com/silporestockai/model/TelegramButton.java`
- Create: `src/main/java/com/silporestockai/service/telegram/TelegramOutboundService.java`
- Test: `src/test/java/com/silporestockai/support/StubTelegramServer.java`
- Test: `src/test/java/com/silporestockai/integration/TelegramOutboundServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` from Task 1's test run.
- Produces:
  - `TelegramProperties(String botToken, String webhookUrl, String webhookSecret, String apiUrl)`
  - `record TelegramButton(String label, String callbackData)`
  - `TelegramOutboundService.sendMessage(long chatId, String text) -> void`
  - `TelegramOutboundService.sendMessageWithButtons(long chatId, String text, List<TelegramButton> buttons) -> void`
  - `TelegramOutboundService.answerCallback(String callbackQueryId) -> void`
  - `TelegramOutboundService.downloadVoiceNote(String fileId) -> byte[]`
  - `TelegramApiFailureException extends ApplicationException` with `HttpStatus.BAD_GATEWAY`
  - `StubTelegramServer` with `baseUrl()`, `sentMessages()`, `callbackAnswers()`, `setWebhookCalls()`, `voiceBytes()`, `reset()`, `close()`

- [ ] **Step 1: Add the dependency**

In `build.gradle.kts`, directly after the `io.modelcontextprotocol.sdk:mcp` line, add:

```kotlin
    // Telegram Bot API. Webhook mode only — the springboot starters assume long-polling or want to own
    // the endpoint, and we need a plain POST /telegram/webhook controller.
    implementation("org.telegram:telegrambots-client:9.0.0")
```

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Add configuration**

In `src/main/resources/application.yml`, append a new top-level block after the existing `silpo:` block:

```yaml
telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN:}
  # Public HTTPS URL of POST /telegram/webhook. Blank skips webhook registration at startup entirely.
  webhook-url: ${TELEGRAM_WEBHOOK_URL:}
  # Sent by Telegram as X-Telegram-Bot-Api-Secret-Token. Blank disables the check — local use only.
  webhook-secret: ${TELEGRAM_WEBHOOK_SECRET:}
  # Overridden by tests to point the SDK at a local stub. Not a production knob.
  api-url: ${TELEGRAM_API_URL:https://api.telegram.org}
```

In `.env.example`, append:

```bash
# --- Telegram ---
# From @BotFather.
TELEGRAM_BOT_TOKEN=
# Public HTTPS URL of the webhook, e.g. https://<subdomain>.ngrok-free.app/telegram/webhook
# Leave blank to skip webhook registration at startup.
TELEGRAM_WEBHOOK_URL=
# Shared secret Telegram echoes back in X-Telegram-Bot-Api-Secret-Token. Generate with:
#   openssl rand -hex 32
TELEGRAM_WEBHOOK_SECRET=
```

- [ ] **Step 3: Write the failing test**

Create `src/test/java/com/silporestockai/support/StubTelegramServer.java`:

```java
package com.silporestockai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A minimal Telegram Bot API over plain HTTP, enough to drive {@code TelegramOutboundService} in tests:
 * {@code sendMessage}, {@code answerCallbackQuery}, {@code setWebhook}, {@code getFile} and the separate
 * {@code /file/bot<token>/<path>} download host the real API uses.
 *
 * <p>Every request body is recorded so a test can assert what was sent rather than mock the call away.
 */
public final class StubTelegramServer implements AutoCloseable {

    /** Bytes served for any voice-note download. */
    public static final byte[] VOICE_BYTES = "stub-ogg-voice-payload".getBytes(StandardCharsets.UTF_8);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final String botToken;
    private final List<JsonNode> sentMessages = new ArrayList<>();
    private final List<JsonNode> callbackAnswers = new ArrayList<>();
    private final List<JsonNode> setWebhookCalls = new ArrayList<>();

    public StubTelegramServer(String botToken) throws IOException {
        this.botToken = botToken;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /** Base URL to hand to {@code telegram.api-url}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized List<JsonNode> sentMessages() {
        return List.copyOf(sentMessages);
    }

    public synchronized List<JsonNode> callbackAnswers() {
        return List.copyOf(callbackAnswers);
    }

    public synchronized List<JsonNode> setWebhookCalls() {
        return List.copyOf(setWebhookCalls);
    }

    public synchronized void reset() {
        sentMessages.clear();
        callbackAnswers.clear();
        setWebhookCalls.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/file/bot" + botToken + "/")) {
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, VOICE_BYTES.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(VOICE_BYTES);
                }
                return;
            }

            String method = path.substring(path.lastIndexOf('/') + 1);
            byte[] rawBody = exchange.getRequestBody().readAllBytes();
            JsonNode body = rawBody.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(rawBody);
            record(method, body);
            respond(exchange, resultFor(method, body));
        } finally {
            exchange.close();
        }
    }

    private synchronized void record(String method, JsonNode body) {
        switch (method) {
            case "sendMessage" -> sentMessages.add(body);
            case "answerCallbackQuery" -> callbackAnswers.add(body);
            case "setWebhook" -> setWebhookCalls.add(body);
            default -> {
                // getFile and anything else needs no recording.
            }
        }
    }

    private Object resultFor(String method, JsonNode body) {
        return switch (method) {
            case "sendMessage" ->
                Map.of(
                        "message_id",
                        1,
                        "date",
                        1,
                        "chat",
                        Map.of("id", body.path("chat_id").asLong(), "type", "private"));
            case "getFile" ->
                Map.of("file_id", body.path("file_id").asText(), "file_path", "voice/stub.ogg");
            default -> Boolean.TRUE;
        };
    }

    private void respond(HttpExchange exchange, Object result) throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(Map.of("ok", true, "result", result));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
```

Create `src/test/java/com/silporestockai/integration/TelegramOutboundServiceIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.TelegramButton;
import com.silporestockai.service.telegram.TelegramOutboundService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("TelegramOutboundService talks to the Bot API and is the only class that does")
class TelegramOutboundServiceIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "111:stub-bot-token";
    private static final StubTelegramServer STUB = start();

    @Autowired
    private TelegramOutboundService telegramOutboundService;

    private static StubTelegramServer start() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @DynamicPropertySource
    static void telegramProperties(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
    }

    @Test
    void sendsAPlainMessage() {
        telegramOutboundService.sendMessage(777L, "Комора: привіт");

        assertThat(STUB.sentMessages()).hasSize(1);
        assertThat(STUB.sentMessages().getFirst().path("chat_id").asLong()).isEqualTo(777L);
        assertThat(STUB.sentMessages().getFirst().path("text").asText()).isEqualTo("Комора: привіт");
    }

    @Test
    void sendsInlineButtonsAsASingleRow() {
        telegramOutboundService.sendMessageWithButtons(
                777L,
                "Підтвердити кошик?",
                List.of(new TelegramButton("Так", "cart:confirm"), new TelegramButton("Ні", "cart:cancel")));

        var keyboard = STUB.sentMessages().getFirst().path("reply_markup").path("inline_keyboard");
        assertThat(keyboard).hasSize(1);
        assertThat(keyboard.get(0)).hasSize(2);
        assertThat(keyboard.get(0).get(0).path("text").asText()).isEqualTo("Так");
        assertThat(keyboard.get(0).get(0).path("callback_data").asText()).isEqualTo("cart:confirm");
        assertThat(keyboard.get(0).get(1).path("callback_data").asText()).isEqualTo("cart:cancel");
    }

    @Test
    void answersACallbackQuery() {
        telegramOutboundService.answerCallback("callback-1");

        assertThat(STUB.callbackAnswers()).hasSize(1);
        assertThat(STUB.callbackAnswers().getFirst().path("callback_query_id").asText())
                .isEqualTo("callback-1");
    }

    @Test
    void downloadsAVoiceNoteAsRawBytes() {
        byte[] audio = telegramOutboundService.downloadVoiceNote("voice-file-id");

        assertThat(audio).isEqualTo(StubTelegramServer.VOICE_BYTES);
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew test --tests '*TelegramOutboundServiceIntegrationTest*'`
Expected: FAIL — compilation error, `TelegramOutboundService` and `TelegramButton` do not exist.

- [ ] **Step 5: Write the properties and config**

Create `src/main/java/com/silporestockai/config/TelegramProperties.java`:

```java
package com.silporestockai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Telegram bot.
 *
 * <p>Holds no Telegram SDK types on purpose: an ArchUnit rule keeps those inside {@code
 * controller.telegram} and {@code service.telegram}.
 *
 * @param botToken bot token from &#64;BotFather; blank in tests and CI
 * @param webhookUrl public HTTPS URL of the webhook; blank skips registration at startup
 * @param webhookSecret shared secret Telegram echoes in {@code X-Telegram-Bot-Api-Secret-Token}; blank
 *     disables the check, which is acceptable only for local work
 * @param apiUrl Bot API base URL; overridden by tests to reach a local stub
 */
@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(String botToken, String webhookUrl, String webhookSecret, String apiUrl) {

    public boolean webhookSecretConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    public boolean webhookUrlConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
```

Create `src/main/java/com/silporestockai/config/TelegramConfig.java`:

```java
package com.silporestockai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link TelegramProperties} from {@code application.yml}. */
@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {}
```

Create `src/main/java/com/silporestockai/exception/TelegramApiFailureException.java`:

```java
package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Raised when the Telegram Bot API rejects or fails a call we made. */
public class TelegramApiFailureException extends ApplicationException {

    public TelegramApiFailureException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
```

Create `src/main/java/com/silporestockai/model/TelegramButton.java`:

```java
package com.silporestockai.model;

/**
 * One inline keyboard button, in terms the rest of the app can use without the Telegram SDK.
 *
 * @param label text shown on the button
 * @param callbackData opaque payload Telegram sends back in the callback query, at most 64 bytes
 */
public record TelegramButton(String label, String callbackData) {}
```

- [ ] **Step 6: Write the outbound service**

Create `src/main/java/com/silporestockai/service/telegram/TelegramOutboundService.java`:

```java
package com.silporestockai.service.telegram;

import com.silporestockai.config.TelegramProperties;
import com.silporestockai.exception.TelegramApiFailureException;
import com.silporestockai.model.TelegramButton;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * The only way anything in this application talks to Telegram.
 *
 * <p>Domain services depend on this class, never on the Telegram SDK — an ArchUnit rule keeps SDK types
 * inside {@code controller.telegram} and {@code service.telegram}. A concrete class rather than an
 * interface plus an implementation: {@code ...Impl} would fail the {@code servicesAreNamedProperly}
 * ArchUnit rule, and the tests drive a stub Bot API over real HTTP instead of mocking this away.
 *
 * <p>The bot token is never logged.
 */
@Slf4j
@Service
public class TelegramOutboundService {

    private final TelegramClient client;

    public TelegramOutboundService(TelegramProperties properties) {
        this.client = new OkHttpTelegramClient(properties.botToken(), telegramUrl(properties.apiUrl()));
    }

    public void sendMessage(long chatId, String text) {
        SendMessage message = SendMessage.builder().chatId(chatId).text(text).build();
        execute(() -> client.execute(message), "sendMessage");
    }

    public void sendMessageWithButtons(long chatId, String text, List<TelegramButton> buttons) {
        InlineKeyboardRow row = new InlineKeyboardRow(buttons.stream()
                .map(button -> InlineKeyboardButton.builder()
                        .text(button.label())
                        .callbackData(button.callbackData())
                        .build())
                .toList());
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(row).build())
                .build();
        execute(() -> client.execute(message), "sendMessage");
    }

    /** Stops the spinner Telegram shows on an inline button until the bot acknowledges the tap. */
    public void answerCallback(String callbackQueryId) {
        AnswerCallbackQuery answer =
                AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build();
        execute(() -> client.execute(answer), "answerCallbackQuery");
    }

    /** Raw bytes of a voice note. Transcription is task 12; this only fetches. */
    public byte[] downloadVoiceNote(String fileId) {
        try {
            File file = client.execute(GetFile.builder().fileId(fileId).build());
            try (InputStream stream = client.downloadFileAsStream(file)) {
                return stream.readAllBytes();
            }
        } catch (TelegramApiException | IOException e) {
            throw new TelegramApiFailureException("could not download the Telegram voice note", e);
        }
    }

    /** Registers the webhook URL with Telegram. Called once at startup by the registration service. */
    void setWebhook(String url, String secretToken) {
        SetWebhook.SetWebhookBuilder<?, ?> builder = SetWebhook.builder().url(url);
        if (secretToken != null && !secretToken.isBlank()) {
            builder.secretToken(secretToken);
        }
        SetWebhook setWebhook = builder.build();
        execute(() -> client.execute(setWebhook), "setWebhook");
    }

    /**
     * Runs one Bot API call. A lambda rather than a {@code BotApiMethod} parameter: the SDK's {@code execute}
     * is generic over the method's own result type, which a wildcard argument cannot satisfy.
     */
    private void execute(TelegramCall call, String label) {
        try {
            call.run();
        } catch (TelegramApiException e) {
            // The message carries the Bot API error, never the token — the token lives only in the URL path.
            throw new TelegramApiFailureException("Telegram " + label + " failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface TelegramCall {
        void run() throws TelegramApiException;
    }

    private static TelegramUrl telegramUrl(String apiUrl) {
        URI uri = URI.create(apiUrl);
        int port = uri.getPort() != -1 ? uri.getPort() : "http".equals(uri.getScheme()) ? 80 : 443;
        return new TelegramUrl(uri.getScheme(), uri.getHost(), port, false);
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew test --tests '*TelegramOutboundServiceIntegrationTest*'`
Expected: PASS, 4 tests.

`AbstractTelegramClient` has a dedicated `Boolean execute(SetWebhook)` overload, so the lambda resolves to
it rather than to the generic `BotApiMethod` one. `SetWebhook` validates `secret_token` against
`[A-Za-z0-9_-]+`, which is why `openssl rand -hex 32` is the documented way to generate it.

- [ ] **Step 8: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add build.gradle.kts .env.example src/main/resources/application.yml \
        src/main/java/com/silporestockai/config/TelegramProperties.java \
        src/main/java/com/silporestockai/config/TelegramConfig.java \
        src/main/java/com/silporestockai/exception/TelegramApiFailureException.java \
        src/main/java/com/silporestockai/model/TelegramButton.java \
        src/main/java/com/silporestockai/service/telegram/TelegramOutboundService.java \
        src/test/java/com/silporestockai/support/StubTelegramServer.java \
        src/test/java/com/silporestockai/integration/TelegramOutboundServiceIntegrationTest.java
git commit -m "Route every outbound Telegram call through one service"
```

---

### Task 3: Webhook endpoint, routing and the state-proving echo

**Files:**
- Create: `src/main/java/com/silporestockai/model/TelegramIncomingUpdate.java`
- Create: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Create: `src/main/java/com/silporestockai/controller/telegram/TelegramWebhookController.java`
- Test: `src/test/java/com/silporestockai/integration/TelegramWebhookIntegrationTest.java`

**Interfaces:**
- Consumes: `TelegramOutboundService` and `StubTelegramServer` from Task 2; `ConversationStateService`, `ConversationFlow` from Task 1.
- Produces:
  - `sealed interface TelegramIncomingUpdate permits TelegramIncomingUpdate.Text, TelegramIncomingUpdate.Voice, TelegramIncomingUpdate.ButtonTap` with `long chatId()`
  - `TelegramRoutingService.route(Update update) -> void`
  - `POST /telegram/webhook` accepting `text/plain`-agnostic raw JSON, header `X-Telegram-Bot-Api-Secret-Token`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/TelegramWebhookIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("POST /telegram/webhook routes updates and resumes conversation state between calls")
class TelegramWebhookIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "222:stub-bot-token";
    private static final String SECRET = "stub-webhook-secret";
    private static final long CHAT_ID = 9001L;
    private static final StubTelegramServer STUB = start();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private ConversationStateService conversationStateService;

    private static StubTelegramServer start() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @DynamicPropertySource
    static void telegramProperties(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", STUB::baseUrl);
        registry.add("telegram.webhook-secret", () -> SECRET);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
        conversationStateRepository.deleteAll();
    }

    private void post(String body) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private static String textUpdate(int updateId, String text) {
        return """
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "text":"%s"}}"""
                .formatted(updateId, updateId, CHAT_ID, text);
    }

    @Test
    void echoesATextMessageEndToEnd() throws Exception {
        post(textUpdate(1, "молоко закінчилось"));

        assertThat(STUB.sentMessages()).hasSize(1);
        assertThat(STUB.sentMessages().getFirst().path("chat_id").asLong()).isEqualTo(CHAT_ID);
        assertThat(STUB.sentMessages().getFirst().path("text").asText())
                .isEqualTo("Комора: почув — «молоко закінчилось» (повідомлення №1)");
    }

    @Test
    void resumesConversationStateAcrossTwoSeparateWebhookCalls() throws Exception {
        post(textUpdate(1, "перше"));

        assertThat(conversationStateService.load(CHAT_ID).getContext()).containsEntry("messageCount", 1);

        post(textUpdate(2, "друге"));

        assertThat(STUB.sentMessages()).hasSize(2);
        assertThat(STUB.sentMessages().get(1).path("text").asText())
                .isEqualTo("Комора: почув — «друге» (повідомлення №2)");
        assertThat(conversationStateService.load(CHAT_ID).getContext()).containsEntry("messageCount", 2);
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
        assertThat(conversationStateRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsAWrongSecretTokenWithoutRoutingAnything() throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textUpdate(1, "не має пройти")))
                .andExpect(status().isUnauthorized());

        assertThat(STUB.sentMessages()).isEmpty();
        assertThat(conversationStateRepository.count()).isZero();
    }

    @Test
    void answersTwoHundredAndSendsNothingForAnUpdateKindWeDoNotHandle() throws Exception {
        post("{\"update_id\":7,\"poll\":{\"id\":\"p1\",\"question\":\"?\",\"options\":[],"
                + "\"total_voter_count\":0,\"is_closed\":false,\"is_anonymous\":true,\"type\":\"regular\","
                + "\"allows_multiple_answers\":false}}");

        assertThat(STUB.sentMessages()).isEmpty();
        assertThat(conversationStateRepository.count()).isZero();
    }

    @Test
    void answersTwoHundredEvenWhenTheBodyIsNotAValidUpdate() throws Exception {
        post("{\"totally\":\"not an update\"}");

        assertThat(STUB.sentMessages()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*TelegramWebhookIntegrationTest*'`
Expected: FAIL — 404 on `/telegram/webhook`, because no controller exists yet.

- [ ] **Step 3: Write the internal inbound model**

Create `src/main/java/com/silporestockai/model/TelegramIncomingUpdate.java`:

```java
package com.silporestockai.model;

/**
 * The parts of a Telegram update this application acts on, in terms that carry no Telegram SDK types.
 *
 * <p>Everything else Telegram can send (edits, polls, chat member changes) is dropped by the router.
 */
public sealed interface TelegramIncomingUpdate {

    /** The chat that produced the update, and the chat any reply goes back to. */
    long chatId();

    /** A plain text message. */
    record Text(long chatId, long telegramUserId, String text) implements TelegramIncomingUpdate {}

    /** A voice note. Only its file id is carried; fetching bytes is {@code TelegramOutboundService}'s job. */
    record Voice(long chatId, long telegramUserId, String fileId, int durationSeconds)
            implements TelegramIncomingUpdate {}

    /** An inline keyboard button tap. {@code data} is the {@code callbackData} the button was built with. */
    record ButtonTap(long chatId, long telegramUserId, String callbackQueryId, String data)
            implements TelegramIncomingUpdate {}
}
```

- [ ] **Step 4: Write the routing service**

Create `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`:

```java
package com.silporestockai.service.telegram;

import com.silporestockai.entity.ConversationState;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.service.ConversationStateService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Turns a Telegram update into one of the internal {@link TelegramIncomingUpdate} shapes and dispatches it.
 *
 * <p>This class and {@code TelegramWebhookController} are the only places that see the Telegram SDK.
 * Everything downstream receives records that carry no SDK types.
 *
 * <p>The handlers here are placeholders. Task 06 replaces them with the onboarding flow, tasks 10 to 12 add
 * the cart and check-in flows. Until then they echo, which is what proves the webhook, the router, the
 * conversation state and the outbound service are wired together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramRoutingService {

    /** Key in {@code conversation_state.context_json} the placeholder echo counts with. */
    static final String MESSAGE_COUNT = "messageCount";

    private final ConversationStateService conversationStateService;
    private final TelegramOutboundService telegramOutboundService;

    public void route(Update update) {
        toIncoming(update).ifPresentOrElse(this::handle, () -> log.debug("ignoring unsupported Telegram update"));
    }

    private Optional<TelegramIncomingUpdate> toIncoming(Update update) {
        if (update.hasMessage()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            long userId = message.getFrom() == null ? 0L : message.getFrom().getId();
            if (message.hasText()) {
                return Optional.of(new TelegramIncomingUpdate.Text(chatId, userId, message.getText()));
            }
            if (message.hasVoice()) {
                var voice = message.getVoice();
                int duration = voice.getDuration() == null ? 0 : voice.getDuration();
                return Optional.of(
                        new TelegramIncomingUpdate.Voice(chatId, userId, voice.getFileId(), duration));
            }
            return Optional.empty();
        }
        if (update.hasCallbackQuery()) {
            CallbackQuery callback = update.getCallbackQuery();
            if (callback.getMessage() == null) {
                return Optional.empty();
            }
            long userId = callback.getFrom() == null ? 0L : callback.getFrom().getId();
            return Optional.of(new TelegramIncomingUpdate.ButtonTap(
                    callback.getMessage().getChatId(), userId, callback.getId(), callback.getData()));
        }
        return Optional.empty();
    }

    private void handle(TelegramIncomingUpdate incoming) {
        switch (incoming) {
                // TODO(#6): replace the echo with the onboarding flow.
            case TelegramIncomingUpdate.Text text ->
                telegramOutboundService.sendMessage(
                        text.chatId(),
                        "Комора: почув — «%s» (повідомлення №%d)"
                                .formatted(text.text(), countMessage(text.chatId())));
                // TODO(#12): hand the bytes to transcription instead of reporting their size.
            case TelegramIncomingUpdate.Voice voice -> {
                byte[] audio = telegramOutboundService.downloadVoiceNote(voice.fileId());
                telegramOutboundService.sendMessage(
                        voice.chatId(),
                        "Комора: голосове отримав (%d с, %d байт). Розшифровка буде пізніше."
                                .formatted(voice.durationSeconds(), audio.length));
            }
                // TODO(#10): dispatch on the callback data once cart confirmation exists.
            case TelegramIncomingUpdate.ButtonTap tap -> {
                telegramOutboundService.answerCallback(tap.callbackQueryId());
                telegramOutboundService.sendMessage(
                        tap.chatId(), "Комора: кнопка «%s».".formatted(tap.data()));
            }
        }
    }

    /** Increments and persists the placeholder counter, proving state survives between webhook calls. */
    private long countMessage(long chatId) {
        ConversationState state = conversationStateService.load(chatId);
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());
        long count = ((Number) context.getOrDefault(MESSAGE_COUNT, 0)).longValue() + 1;
        context.put(MESSAGE_COUNT, count);
        conversationStateService.save(chatId, state.getCurrentFlow(), state.getCurrentStep(), context);
        return count;
    }
}
```

- [ ] **Step 5: Write the controller**

Create `src/main/java/com/silporestockai/controller/telegram/TelegramWebhookController.java`:

```java
package com.silporestockai.controller.telegram;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.config.TelegramProperties;
import com.silporestockai.service.telegram.TelegramRoutingService;
import io.swagger.v3.oas.annotations.Operation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * The single endpoint Telegram delivers updates to.
 *
 * <p>The body is taken as a raw string and parsed here rather than by Spring's message converter. Spring
 * Boot 4 carries both Jackson 2 and Jackson 3 on the classpath and may bind with either; every
 * {@code telegrambots-meta} type is annotated for Jackson 2. Parsing with a mapper this class owns removes
 * that coupling, and lets unknown fields be ignored — Telegram adds fields to {@code Update} continuously.
 *
 * <p>Except for a failed secret-token check the endpoint always answers {@code 200}. Telegram retries any
 * non-2xx response indefinitely, so a single update the router cannot handle would otherwise loop forever.
 */
@Slf4j
@RestController
@RequestMapping("/telegram")
public class TelegramWebhookController {

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final ObjectMapper updateMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final TelegramProperties properties;
    private final TelegramRoutingService telegramRoutingService;

    public TelegramWebhookController(
            TelegramProperties properties, TelegramRoutingService telegramRoutingService) {
        this.properties = properties;
        this.telegramRoutingService = telegramRoutingService;
    }

    @Operation(summary = "Telegram webhook", description = "Receives Bot API updates. Called only by Telegram.")
    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(
            @RequestHeader(name = SECRET_HEADER, required = false) String secretToken,
            @RequestBody String body) {
        if (!secretAccepted(secretToken)) {
            log.warn("rejected a Telegram webhook call with a missing or wrong secret token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            Update update = updateMapper.readValue(body, Update.class);
            telegramRoutingService.route(update);
        } catch (Exception e) {
            // Never propagate: Telegram retries a non-2xx forever, so one bad update would loop.
            log.error("failed to handle a Telegram update", e);
        }
        return ResponseEntity.ok().build();
    }

    private boolean secretAccepted(String provided) {
        if (!properties.webhookSecretConfigured()) {
            return true;
        }
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                properties.webhookSecret().getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests '*TelegramWebhookIntegrationTest*'`
Expected: PASS, 5 tests.

If the last test fails because a body with no recognised field still deserialises into an empty `Update`,
that is fine — `route` finds nothing to do and sends nothing, which is what the test asserts.

- [ ] **Step 7: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/java/com/silporestockai/model/TelegramIncomingUpdate.java \
        src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java \
        src/main/java/com/silporestockai/controller/telegram/TelegramWebhookController.java \
        src/test/java/com/silporestockai/integration/TelegramWebhookIntegrationTest.java
git commit -m "Receive and route Telegram webhook updates"
```

---

### Task 4: Voice notes and inline button callbacks end to end

**Files:**
- Modify: `src/test/java/com/silporestockai/integration/TelegramWebhookIntegrationTest.java` (add two tests)

**Interfaces:**
- Consumes: everything from Tasks 1 to 3. No production code changes are expected — Task 3 already
  implemented both branches. This task exists because the acceptance criteria name them separately and
  they must be proven through the webhook, not only through direct service calls.

- [ ] **Step 1: Write the failing tests**

Append to `TelegramWebhookIntegrationTest`:

```java
    private static String voiceUpdate(int updateId, String fileId, int duration) {
        return """
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "voice":{"file_id":"%s","file_unique_id":"u1","duration":%d,"mime_type":"audio/ogg"}}}"""
                .formatted(updateId, updateId, CHAT_ID, fileId, duration);
    }

    private static String callbackUpdate(int updateId, String callbackId, String data) {
        return """
                {"update_id":%d,"callback_query":{"id":"%s","chat_instance":"ci",\
                "from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "data":"%s","message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}}}}"""
                .formatted(updateId, callbackId, data, updateId, CHAT_ID);
    }

    @Test
    void downloadsAVoiceNoteAndReportsItsSize() throws Exception {
        post(voiceUpdate(3, "voice-file-id", 7));

        assertThat(STUB.sentMessages()).hasSize(1);
        assertThat(STUB.sentMessages().getFirst().path("text").asText())
                .isEqualTo("Комора: голосове отримав (7 с, %d байт). Розшифровка буде пізніше."
                        .formatted(StubTelegramServer.VOICE_BYTES.length));
    }

    @Test
    void routesAnInlineButtonCallbackAndAcknowledgesIt() throws Exception {
        post(callbackUpdate(4, "cb-1", "cart:confirm"));

        assertThat(STUB.callbackAnswers()).hasSize(1);
        assertThat(STUB.callbackAnswers().getFirst().path("callback_query_id").asText())
                .isEqualTo("cb-1");
        assertThat(STUB.sentMessages()).hasSize(1);
        assertThat(STUB.sentMessages().getFirst().path("text").asText())
                .isEqualTo("Комора: кнопка «cart:confirm».");
    }
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew test --tests '*TelegramWebhookIntegrationTest*'`
Expected: PASS, 7 tests.

If the callback test fails deserialising `message`, check that the JSON includes a numeric `date` field:
`MaybeInaccessibleMessage` uses `date` as its Jackson type discriminator, and `0` selects
`InaccessibleMessage` while any other value selects `Message`.

- [ ] **Step 3: Format and commit**

```bash
make format
./gradlew test
git add src/test/java/com/silporestockai/integration/TelegramWebhookIntegrationTest.java
git commit -m "Cover voice notes and button callbacks through the webhook"
```

---

### Task 5: Webhook registration, the ArchUnit boundary and documentation

**Files:**
- Create: `src/main/java/com/silporestockai/service/telegram/TelegramWebhookRegistrationService.java`
- Modify: `src/test/java/com/silporestockai/architecture/ArchitectureTest.java`
- Modify: `README.md` (Configuration section: a Telegram table and a local-webhook subsection)
- Modify: `CLAUDE.md` (package table `client` row; new Telegram invariant)
- Test: `src/test/java/com/silporestockai/integration/TelegramWebhookRegistrationIntegrationTest.java`

**Interfaces:**
- Consumes: `TelegramOutboundService.setWebhook(String url, String secretToken)` (package-private, Task 2);
  `TelegramProperties.webhookUrlConfigured()`; `StubTelegramServer.setWebhookCalls()`.
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/TelegramWebhookRegistrationIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the webhook registers itself at startup when a public URL is configured")
class TelegramWebhookRegistrationIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "333:stub-bot-token";
    private static final String WEBHOOK_URL = "https://komora.example/telegram/webhook";
    private static final String SECRET = "startup-secret";
    private static final StubTelegramServer STUB = start();

    private static StubTelegramServer start() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void telegramProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", STUB::baseUrl);
        registry.add("telegram.webhook-url", () -> WEBHOOK_URL);
        registry.add("telegram.webhook-secret", () -> SECRET);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Test
    void callsSetWebhookOnceWithTheConfiguredUrlAndSecret() {
        assertThat(STUB.setWebhookCalls()).hasSize(1);
        assertThat(STUB.setWebhookCalls().getFirst().path("url").asText()).isEqualTo(WEBHOOK_URL);
        assertThat(STUB.setWebhookCalls().getFirst().path("secret_token").asText()).isEqualTo(SECRET);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*TelegramWebhookRegistrationIntegrationTest*'`
Expected: FAIL — `setWebhookCalls()` is empty, nothing registers the webhook.

- [ ] **Step 3: Write the registration service**

Create `src/main/java/com/silporestockai/service/telegram/TelegramWebhookRegistrationService.java`:

```java
package com.silporestockai.service.telegram;

import com.silporestockai.config.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Registers the webhook URL with Telegram once the application is up.
 *
 * <p>Gated on {@code telegram.webhook-url} being set, so tests, CI and a bare {@code make run} never call
 * the Telegram API. The URL rotates every time an ngrok tunnel restarts, which is why this is automatic
 * rather than a one-off manual step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramWebhookRegistrationService {

    private final TelegramProperties properties;
    private final TelegramOutboundService telegramOutboundService;

    @EventListener(ApplicationReadyEvent.class)
    public void registerWebhook() {
        if (!properties.webhookUrlConfigured()) {
            log.info("telegram.webhook-url is not set — skipping webhook registration");
            return;
        }
        telegramOutboundService.setWebhook(properties.webhookUrl(), properties.webhookSecret());
        log.info("registered the Telegram webhook at {}", properties.webhookUrl());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*TelegramWebhookRegistrationIntegrationTest*'`
Expected: PASS.

- [ ] **Step 5: Add the ArchUnit boundary rule**

In `src/test/java/com/silporestockai/architecture/ArchitectureTest.java`, add to the static imports:

```java
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
```

and append this rule to the class:

```java
    /**
     * The Telegram SDK is an implementation detail of the two packages that own the channel. Domain services
     * depend on {@code TelegramOutboundService} and the records in {@code model}, never on {@code Update},
     * {@code Message} or any other SDK type.
     */
    @ArchTest
    static final ArchRule telegramSdkStaysBehindTheTelegramPackages = noClasses()
            .that()
            .resideOutsideOfPackages("..controller.telegram..", "..service.telegram..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.telegram..")
            .as("Telegram SDK types must not leak outside controller.telegram and service.telegram");
```

- [ ] **Step 6: Run the architecture tests**

Run: `./gradlew test --tests '*ArchitectureTest*'`
Expected: PASS. A failure naming `config`, `model` or `service` (outside `service.telegram`) means an SDK
type leaked — move it behind `TelegramOutboundService` rather than widening the rule.

- [ ] **Step 7: Document the configuration and the local webhook**

In `README.md`, inside the `## Configuration` section, after the existing `### Silpo MCP` subsection, add:

```markdown
### Telegram

| Variable                  | Default | Purpose                                                       |
|---------------------------|---------|---------------------------------------------------------------|
| `TELEGRAM_BOT_TOKEN`      | *(empty)* | Bot token from [@BotFather](https://t.me/BotFather)          |
| `TELEGRAM_WEBHOOK_URL`    | *(empty)* | Public HTTPS URL of `POST /telegram/webhook`; blank skips registration at startup |
| `TELEGRAM_WEBHOOK_SECRET` | *(empty)* | Shared secret Telegram echoes in `X-Telegram-Bot-Api-Secret-Token`; generate with `openssl rand -hex 32` |

#### Running the webhook locally

Telegram only delivers to a public HTTPS URL, so a local run needs a tunnel:

1. Start the tunnel: `ngrok http 8080` (any equivalent works — Cloudflare Tunnel, localtunnel).
2. Copy the `https://` forwarding URL ngrok prints.
3. Put it in `.env` together with a secret:

   ```bash
   TELEGRAM_BOT_TOKEN=<token from @BotFather>
   TELEGRAM_WEBHOOK_URL=https://<subdomain>.ngrok-free.app/telegram/webhook
   TELEGRAM_WEBHOOK_SECRET=$(openssl rand -hex 32)
   ```

4. `make run`. The app calls `setWebhook` on startup and logs
   `registered the Telegram webhook at …`.
5. Message the bot. Check what Telegram thinks it is delivering to with
   `curl https://api.telegram.org/bot<token>/getWebhookInfo`.

The URL changes every time the tunnel restarts, so step 3 repeats each session. Without
`TELEGRAM_WEBHOOK_URL` the app boots normally and never contacts Telegram.
```

In `CLAUDE.md`, change the `client` row of the package table from

```
| `client` | outbound integrations (`mcp`, `llm`, `stt`, `telegram` subpackages) |
```

to

```
| `client` | outbound integrations (`mcp`, `llm`, `stt` subpackages) |
```

and add this invariant to the "Invariants that break the build" list:

```markdown
- **Telegram SDK types stay in `controller.telegram` and `service.telegram`** — enforced by
  `telegramSdkStaysBehindTheTelegramPackages` in `ArchitectureTest`. Everything else talks to
  `TelegramOutboundService` and the records in `model`. There is deliberately no `client/telegram`: the
  SDK is the HTTP client, so a wrapper would only be a pass-through needing its own exemption.
```

- [ ] **Step 8: Verify everything**

```bash
make format
./gradlew test
./gradlew build
```
Expected: `BUILD SUCCESSFUL` for all three.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/silporestockai/service/telegram/TelegramWebhookRegistrationService.java \
        src/test/java/com/silporestockai/architecture/ArchitectureTest.java \
        src/test/java/com/silporestockai/integration/TelegramWebhookRegistrationIntegrationTest.java \
        README.md CLAUDE.md
git commit -m "Register the Telegram webhook and fence off its SDK"
```

---

## Acceptance criteria mapping

| Notion criterion | Proven by |
|---|---|
| Text message returns an echo end to end | Task 3, `echoesATextMessageEndToEnd` |
| Voice notes downloadable as raw bytes via `downloadVoiceNote` | Task 2, `downloadsAVoiceNoteAsRawBytes`; Task 4, `downloadsAVoiceNoteAndReportsItsSize` |
| Inline button callback queries route correctly | Task 4, `routesAnInlineButtonCallbackAndAcknowledgesIt` |
| Conversation state persists across two separate webhook calls | Task 3, `resumesConversationStateAcrossTwoSeparateWebhookCalls` |
| No Telegram SDK types outside the two packages, checked by ArchUnit | Task 5, `telegramSdkStaysBehindTheTelegramPackages` |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_WEBHOOK_URL` follow the env-var pattern | Task 2, `application.yml` + `.env.example` |
| Local webhook exposure documented | Task 5, README `Running the webhook locally` |

Not covered by automated tests: a live run against the real Bot API. No bot token is available, so the
README procedure has to be run by hand. State that gap in the final commit message.
