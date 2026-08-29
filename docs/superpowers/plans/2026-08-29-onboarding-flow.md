# Onboarding Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new Telegram user is walked through connecting their Silpo account, has their profile pre-filled from MCP wherever possible, is asked only for what MCP could not supply, and ends with a persisted `user_profile` and an event task 07 can listen for.

**Architecture:** `TelegramRoutingService` resolves the chat to a `User` and hands every update to `OnboardingFlowService` while onboarding is in progress. That service is a state machine over `conversation_state`: `current_step` names the step, `context_json` accumulates the partial profile between webhook calls. `ProfileEnrichmentService` calls four Silpo MCP tools and lets Claude turn whatever they return into a typed `SilpoProfileSnapshot`; every failure inside it degrades to an empty snapshot rather than throwing.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring Data JPA, `SilpoMcpClient` (task 02), `TelegramOutboundService` (task 03), `ClaudeApiClient` (task 04), the schema from task 05, Testcontainers PostgreSQL, JDK `com.sun.net.httpserver` stubs, MockMvc, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-29-onboarding-flow-design.md`

## Global Constraints

- Base package is `com.silporestockai`. New services in `service` and `service.onboarding`; enums and records in `model`.
- **ArchUnit is enforced.** Every class under `..service..` must end with `Service` — including anything nested. `..controller..` classes end with `Controller`. Constructor injection only, no `@Autowired` fields. Telegram SDK types must not leave `controller.telegram` and `service.telegram`.
- **Liquibase owns the schema.** No new tables in this change; `user_profile` and `conversation_state` already exist.
- **Spotless (palantir).** Run `make format` before every commit; CI runs `spotlessCheck` before `build`.
- `@Slf4j` for logging, never a manual `LoggerFactory`. Never log an OAuth token or an API key.
- **All user-visible copy is Ukrainian, direct, no filler.** No phrasing that reads as generated text. No emoji unless it carries meaning.
- The conversation-state value for "no flow in progress" is `ConversationFlow.NONE`. Task 06's text calls it `IDLE`; do not add a second name.
- Callback data is prefixed `onb:` so later flows can share the channel.
- Run tests with `./gradlew test`. Docker must be running.

---

### Task 1: URL buttons and user accounts

**Files:**
- Modify: `src/main/java/com/silporestockai/model/TelegramButton.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramOutboundService.java`
- Create: `src/main/java/com/silporestockai/service/UserAccountService.java`
- Modify: `src/test/java/com/silporestockai/integration/TelegramOutboundServiceIntegrationTest.java`
- Test: `src/test/java/com/silporestockai/integration/UserAccountServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `TelegramOutboundService.sendMessageWithButtons(long, String, List<TelegramButton>)`; `UserRepository.findByTelegramChatId(long)`; `User` builder; `StubTelegramServer` from task 03.
- Produces:
  - `TelegramButton.callback(String label, String data) -> TelegramButton`
  - `TelegramButton.link(String label, String url) -> TelegramButton`
  - `TelegramButton` record components `label`, `callbackData`, `url` — exactly one of the last two is non-null
  - `UserAccountService.findOrCreate(long telegramChatId) -> User`

- [ ] **Step 1: Write the failing tests**

Append to `TelegramOutboundServiceIntegrationTest`:

```java
    @Test
    void sendsAUrlButtonWhenTheButtonCarriesALink() {
        telegramOutboundService.sendMessageWithButtons(
                777L,
                "Під'єднай Сільпо",
                List.of(
                        TelegramButton.link("Під'єднати Сільпо", "https://mcp.silpo.ua/authorize?x=1"),
                        TelegramButton.callback("Пропустити", "onb:skip")));

        var row = STUB.sentMessages()
                .getFirst()
                .path("reply_markup")
                .path("inline_keyboard")
                .get(0);
        assertThat(row.get(0).path("url").asText()).isEqualTo("https://mcp.silpo.ua/authorize?x=1");
        assertThat(row.get(0).has("callback_data")).isFalse();
        assertThat(row.get(1).path("callback_data").asText()).isEqualTo("onb:skip");
        assertThat(row.get(1).has("url")).isFalse();
    }
```

Change the existing `sendsInlineButtonsAsASingleRow` test to build its buttons through the factory:

```java
        telegramOutboundService.sendMessageWithButtons(
                777L,
                "Підтвердити кошик?",
                List.of(
                        TelegramButton.callback("Так", "cart:confirm"),
                        TelegramButton.callback("Ні", "cart:cancel")));
```

Create `src/test/java/com/silporestockai/integration/UserAccountServiceIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("a Telegram chat maps to exactly one user row")
class UserAccountServiceIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 7101L;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    @Test
    void createsTheUserOnFirstContact() {
        User created = userAccountService.findOrCreate(CHAT_ID);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getTelegramChatId()).isEqualTo(CHAT_ID);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void returnsTheSameUserOnEveryLaterMessage() {
        User first = userAccountService.findOrCreate(CHAT_ID);
        User second = userAccountService.findOrCreate(CHAT_ID);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*TelegramOutboundServiceIntegrationTest*' --tests '*UserAccountServiceIntegrationTest*'`
Expected: FAIL — compilation errors: `TelegramButton.link` / `TelegramButton.callback` and `UserAccountService` do not exist.

- [ ] **Step 3: Extend `TelegramButton`**

Replace `src/main/java/com/silporestockai/model/TelegramButton.java` with:

```java
package com.silporestockai.model;

/**
 * One inline keyboard button, in terms the rest of the app can use without the Telegram SDK.
 *
 * <p>A button either sends a callback back to the bot or opens a URL. Telegram rejects a button that carries
 * both, so the factories are the only way to build one and each sets exactly one.
 *
 * @param label text shown on the button
 * @param callbackData opaque payload Telegram sends back in the callback query, at most 64 bytes; null for a
 *     link button
 * @param url address the button opens; null for a callback button
 */
public record TelegramButton(String label, String callbackData, String url) {

    /** A button that sends {@code data} back to the bot when tapped. */
    public static TelegramButton callback(String label, String data) {
        return new TelegramButton(label, data, null);
    }

    /** A button that opens {@code url}. Used for the Silpo OAuth hand-off, which leaves Telegram. */
    public static TelegramButton link(String label, String url) {
        return new TelegramButton(label, null, url);
    }
}
```

- [ ] **Step 4: Honour link buttons in the outbound service**

In `TelegramOutboundService.sendMessageWithButtons`, replace the mapping lambda so it sets whichever field
the button carries:

```java
        InlineKeyboardRow row = new InlineKeyboardRow(
                buttons.stream().map(TelegramOutboundService::toInlineButton).toList());
```

and add this private method next to the other helpers:

```java
    private static InlineKeyboardButton toInlineButton(TelegramButton button) {
        InlineKeyboardButton.Builder builder = InlineKeyboardButton.builder().text(button.label());
        // Telegram rejects a button carrying both, so set exactly the one the caller chose.
        if (button.url() != null) {
            builder.url(button.url());
        } else {
            builder.callbackData(button.callbackData());
        }
        return builder.build();
    }
```

If `InlineKeyboardButton.Builder` is not the exact nested builder type, use `var builder =
InlineKeyboardButton.builder().text(button.label());` instead — the SDK uses Lombok `@SuperBuilder`, so the
concrete type name may be generic.

- [ ] **Step 5: Write `UserAccountService`**

Create `src/main/java/com/silporestockai/service/UserAccountService.java`:

```java
package com.silporestockai.service;

import com.silporestockai.entity.User;
import com.silporestockai.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the person behind a Telegram chat, creating the row on first contact.
 *
 * <p>Onboarding needs a user id before it can build the Silpo authorisation URL, so the row exists from the
 * very first message rather than from the end of the conversation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final UserRepository userRepository;

    @Transactional
    public User findOrCreate(long telegramChatId) {
        return userRepository.findByTelegramChatId(telegramChatId).orElseGet(() -> {
            User created = userRepository.save(User.builder()
                    .id(UUID.randomUUID())
                    .telegramChatId(telegramChatId)
                    .createdAt(Instant.now())
                    .build());
            log.info("registered a new user for chat {}", telegramChatId);
            return created;
        });
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests '*TelegramOutboundServiceIntegrationTest*' --tests '*UserAccountServiceIntegrationTest*'`
Expected: PASS, 5 tests in the outbound class and 2 in the account class.

- [ ] **Step 7: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/java/com/silporestockai/model/TelegramButton.java \
        src/main/java/com/silporestockai/service/telegram/TelegramOutboundService.java \
        src/main/java/com/silporestockai/service/UserAccountService.java \
        src/test/java/com/silporestockai/integration/TelegramOutboundServiceIntegrationTest.java \
        src/test/java/com/silporestockai/integration/UserAccountServiceIntegrationTest.java
git commit -m "Add link buttons and first-contact user creation"
```

---

### Task 2: Profile enrichment from MCP

**Files:**
- Create: `src/main/java/com/silporestockai/model/SilpoProfileSnapshot.java`
- Create: `src/main/java/com/silporestockai/model/OnboardingStep.java`
- Create: `src/main/java/com/silporestockai/model/OnboardingCompletedEvent.java`
- Create: `src/main/java/com/silporestockai/service/onboarding/ProfileEnrichmentService.java`
- Test: `src/test/java/com/silporestockai/integration/ProfileEnrichmentIntegrationTest.java`

**Interfaces:**
- Consumes: `SilpoMcpClient.callTool(String, Map<String,Object>, UUID)` returning `McpToolResponse(text, structuredContent, isError)`; `SilpoAuthService.isConnected(UUID)`; `ClaudeApiClient.completeStructured(String, String, Class<T>)`; `StubMcpServer`, `StubAnthropicServer`, `UserAccountService` from Task 1.
- Produces:
  - `record SilpoProfileSnapshot(Integer householdSize, Boolean hasKids, List<Integer> kidsAges, List<String> dietaryRestrictions, List<String> frequentItems)` with `static SilpoProfileSnapshot empty()` and `boolean isEmpty()`
  - `enum OnboardingStep { AWAITING_CONNECT, CONFIRM_PROFILE, ASK_HOUSEHOLD, ASK_RESTRICTIONS, ASK_DISLIKES, ASK_BUDGET, DONE }`
  - `record OnboardingCompletedEvent(UUID userId)`
  - `ProfileEnrichmentService.enrich(UUID userId) -> SilpoProfileSnapshot` — never throws

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/ProfileEnrichmentIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.model.SilpoProfileSnapshot;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.onboarding.ProfileEnrichmentService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("MCP enrichment fills what it can and never breaks the flow")
class ProfileEnrichmentIntegrationTest extends AbstractIntegrationTest {

    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private ProfileEnrichmentService profileEnrichmentService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_family",
                    "silpo_get_my_food_restrictions",
                    "silpo_get_my_online_orders",
                    "silpo_get_my_favorites"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        MCP.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        MCP.reset();
        CLAUDE.reset();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** isConnected reads the database, so a connected guest is simulated by inserting a token row. */
    private UUID connectedUser(long chatId) {
        User user = userAccountService.findOrCreate(chatId);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .refreshToken(tokenCipher.encrypt("stub-refresh-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user.getId();
    }

    @Test
    void returnsAnEmptySnapshotWhenTheGuestNeverConnected() {
        UUID userId = userAccountService.findOrCreate(7201L).getId();

        SilpoProfileSnapshot snapshot = profileEnrichmentService.enrich(userId);

        assertThat(snapshot.isEmpty()).isTrue();
        assertThat(MCP.callCount("tools/call")).isZero();
        assertThat(CLAUDE.callCount()).isZero();
    }

    @Test
    void callsTheFourProfileToolsAndLetsClaudeNormaliseTheirOutput() {
        UUID userId = connectedUser(7202L);
        CLAUDE.respondWithText(
                """
                {"householdSize":4,"hasKids":true,"kidsAges":[3,7],\
                "dietaryRestrictions":["без горіхів"],"frequentItems":["молоко","хліб"]}""");

        SilpoProfileSnapshot snapshot = profileEnrichmentService.enrich(userId);

        assertThat(MCP.callCount("tools/call")).isEqualTo(4);
        assertThat(snapshot.householdSize()).isEqualTo(4);
        assertThat(snapshot.hasKids()).isTrue();
        assertThat(snapshot.kidsAges()).containsExactly(3, 7);
        assertThat(snapshot.dietaryRestrictions()).containsExactly("без горіхів");
        assertThat(snapshot.frequentItems()).containsExactly("молоко", "хліб");
        assertThat(snapshot.isEmpty()).isFalse();
    }

    @Test
    void keepsGoingWhenOneToolIsNotGrantedForThisGuest() {
        UUID userId = connectedUser(7203L);
        // 403 means the tool is not granted. The three that did answer must still reach Claude.
        MCP.injectStatus("tools/call", 403);
        CLAUDE.respondWithText("{\"householdSize\":2,\"hasKids\":false}");

        SilpoProfileSnapshot snapshot = profileEnrichmentService.enrich(userId);

        assertThat(snapshot.householdSize()).isEqualTo(2);
        assertThat(CLAUDE.callCount()).isEqualTo(1);
    }

    @Test
    void degradesToAnEmptySnapshotWhenClaudeCannotBeReached() {
        UUID userId = connectedUser(7204L);
        CLAUDE.injectStatus(401);

        SilpoProfileSnapshot snapshot = profileEnrichmentService.enrich(userId);

        assertThat(snapshot.isEmpty()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ProfileEnrichmentIntegrationTest*'`
Expected: FAIL — compilation error, `SilpoProfileSnapshot` and `ProfileEnrichmentService` do not exist.

- [ ] **Step 3: Write the model types**

Create `src/main/java/com/silporestockai/model/SilpoProfileSnapshot.java`:

```java
package com.silporestockai.model;

import java.util.List;

/**
 * What could be learned about a household from their Silpo account, before anyone was asked a question.
 *
 * <p>Every field is nullable: the four MCP tools may each answer, refuse, or return nothing, and a guest with
 * no order history yields an entirely empty snapshot. The onboarding flow asks only about the fields that
 * came back null.
 *
 * @param householdSize how many people eat at home
 * @param hasKids whether there are children in the household
 * @param kidsAges their ages, when known
 * @param dietaryRestrictions allergies and diet restrictions
 * @param frequentItems items the guest buys often, used later to seed the first plan
 */
public record SilpoProfileSnapshot(
        Integer householdSize,
        Boolean hasKids,
        List<Integer> kidsAges,
        List<String> dietaryRestrictions,
        List<String> frequentItems) {

    public static SilpoProfileSnapshot empty() {
        return new SilpoProfileSnapshot(null, null, null, null, null);
    }

    /** True when nothing at all was learned, which is the same path as a guest who never connected. */
    public boolean isEmpty() {
        return householdSize == null
                && hasKids == null
                && (kidsAges == null || kidsAges.isEmpty())
                && (dietaryRestrictions == null || dietaryRestrictions.isEmpty())
                && (frequentItems == null || frequentItems.isEmpty());
    }
}
```

Create `src/main/java/com/silporestockai/model/OnboardingStep.java`:

```java
package com.silporestockai.model;

/**
 * Where an onboarding conversation is. Stored by name in {@code conversation_state.current_step}, so a
 * webhook call an hour later resumes rather than restarts.
 */
public enum OnboardingStep {
    /** Welcome sent; waiting for the guest to connect Silpo or to skip. */
    AWAITING_CONNECT,
    /** Showing what MCP found; waiting for confirmation or a correction. */
    CONFIRM_PROFILE,
    /** Asking how many people eat at home. */
    ASK_HOUSEHOLD,
    /** Asking about allergies and diet restrictions. */
    ASK_RESTRICTIONS,
    /** Asking what nobody in the household will eat. */
    ASK_DISLIKES,
    /** Asking the weekly budget. MCP never knows this, so it is always asked. */
    ASK_BUDGET,
    /** Profile saved; the conversation returns to {@link ConversationFlow#NONE}. */
    DONE
}
```

Create `src/main/java/com/silporestockai/model/OnboardingCompletedEvent.java`:

```java
package com.silporestockai.model;

import java.util.UUID;

/**
 * Published once a user's profile is saved.
 *
 * <p>An event rather than a call into a meal-planning service: task 07 has not been designed yet, and
 * inventing an interface for it here would be guessing at someone else's shape.
 *
 * @param userId the user whose profile is now complete
 */
public record OnboardingCompletedEvent(UUID userId) {}
```

- [ ] **Step 4: Write the enrichment service**

Create `src/main/java/com/silporestockai/service/onboarding/ProfileEnrichmentService.java`:

```java
package com.silporestockai.service.onboarding;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.model.SilpoProfileSnapshot;
import com.silporestockai.service.SilpoAuthService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Learns what it can about a household from their Silpo account, so onboarding asks as few questions as
 * possible.
 *
 * <p>Nothing here throws. A guest who never connected, a guest with no order history, a tool that is not
 * granted, an MCP outage and a Claude failure all produce an empty snapshot, so the flow has exactly one
 * fallback path to maintain instead of five.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileEnrichmentService {

    /** The tools named by task 06. Each is called independently so one refusal does not lose the rest. */
    private static final List<String> PROFILE_TOOLS = List.of(
            "silpo_get_my_family",
            "silpo_get_my_food_restrictions",
            "silpo_get_my_online_orders",
            "silpo_get_my_favorites");

    private static final String EXTRACTION_PROMPT =
            """
            Ти отримуєш сирі відповіді інструментів профілю «Сільпо» для одного клієнта.
            Витягни з них лише те, що там справді є. Нічого не вигадуй.
            Якщо якогось значення в даних немає — залиш поле порожнім.
            kidsAges — вік дітей числами. dietaryRestrictions — алергії та дієтичні обмеження.
            frequentItems — назви товарів, які клієнт купує регулярно.
            """;

    private final SilpoAuthService silpoAuthService;
    private final SilpoMcpClient silpoMcpClient;
    private final ClaudeApiClient claudeApiClient;

    public SilpoProfileSnapshot enrich(UUID userId) {
        if (!silpoAuthService.isConnected(userId)) {
            log.debug("user {} has not connected Silpo; skipping enrichment", userId);
            return SilpoProfileSnapshot.empty();
        }

        String gathered = String.join("\n\n", collectToolOutput(userId));
        if (gathered.isBlank()) {
            log.info("Silpo returned nothing usable for user {}; onboarding will ask instead", userId);
            return SilpoProfileSnapshot.empty();
        }

        try {
            return claudeApiClient.completeStructured(EXTRACTION_PROMPT, gathered, SilpoProfileSnapshot.class);
        } catch (RuntimeException e) {
            log.warn("could not normalise the Silpo profile for user {}: {}", userId, e.getMessage());
            return SilpoProfileSnapshot.empty();
        }
    }

    private List<String> collectToolOutput(UUID userId) {
        List<String> gathered = new ArrayList<>();
        for (String tool : PROFILE_TOOLS) {
            try {
                McpToolResponse response = silpoMcpClient.callTool(tool, Map.of(), userId);
                if (response.isError() || response.text() == null || response.text().isBlank()) {
                    continue;
                }
                gathered.add(tool + ": " + response.text());
            } catch (RuntimeException e) {
                // A 403 means this guest has not granted the tool; the others may still answer.
                log.info("Silpo tool {} unavailable for user {}: {}", tool, userId, e.getMessage());
            }
        }
        return gathered;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests '*ProfileEnrichmentIntegrationTest*'`
Expected: PASS, 4 tests.

If `keepsGoingWhenOneToolIsNotGrantedForThisGuest` sees fewer than three successful calls, remember
`SilpoMcpClientImpl.execute` drops the cached session on any failure — the next tool call rebuilds it, which
costs an extra `initialize` on the stub but must still succeed.

- [ ] **Step 6: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/java/com/silporestockai/model/SilpoProfileSnapshot.java \
        src/main/java/com/silporestockai/model/OnboardingStep.java \
        src/main/java/com/silporestockai/model/OnboardingCompletedEvent.java \
        src/main/java/com/silporestockai/service/onboarding/ProfileEnrichmentService.java \
        src/test/java/com/silporestockai/integration/ProfileEnrichmentIntegrationTest.java
git commit -m "Learn what Silpo already knows before asking anything"
```

---

### Task 3: The onboarding state machine and the router hand-off

**Files:**
- Create: `src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Modify: `src/test/java/com/silporestockai/integration/TelegramWebhookIntegrationTest.java`
- Test: `src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java`

**Interfaces:**
- Consumes: `UserAccountService.findOrCreate(long)`; `ProfileEnrichmentService.enrich(UUID)`; `ConversationStateService.load(long)` / `.save(long, ConversationFlow, String, Map<String,Object>)` / `.clear(long)`; `TelegramOutboundService.sendMessage` / `.sendMessageWithButtons` / `.answerCallback`; `SilpoAuthService.buildAuthorizationUrl(UUID)`; `UserProfileRepository`; `TelegramIncomingUpdate.Text` / `.ButtonTap` / `.Voice`; `OnboardingStep`; `OnboardingCompletedEvent`.
- Produces:
  - `OnboardingFlowService.isOnboarded(UUID userId) -> boolean`
  - `OnboardingFlowService.handle(User user, TelegramIncomingUpdate incoming) -> void`
  - Callback data constants `onb:connected`, `onb:skip`, `onb:confirm`, `onb:correct`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OnboardingStep;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("a new user is onboarded across several separate webhook calls")
class OnboardingFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "444:stub-bot-token";
    private static final long CHAT_ID = 7301L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of("silpo_get_my_family"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        MCP.reset();
        CLAUDE.reset();
        userProfileRepository.deleteAll();
        conversationStateRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void deliver(String body) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void sendText(int updateId, String text) throws Exception {
        deliver(
                """
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "text":"%s"}}"""
                        .formatted(updateId, updateId, CHAT_ID, text));
    }

    private void tapButton(int updateId, String data) throws Exception {
        deliver(
                """
                {"update_id":%d,"callback_query":{"id":"cb-%d","chat_instance":"ci",\
                "from":{"id":5,"is_bot":false,"first_name":"Тест"},"data":"%s",\
                "message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}}}}"""
                        .formatted(updateId, updateId, data, updateId, CHAT_ID));
    }

    private void connectSilpo() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void greetsANewUserWithAConnectLinkAndRemembersTheStep() throws Exception {
        sendText(1, "привіт");

        assertThat(userRepository.findByTelegramChatId(CHAT_ID)).isPresent();
        var keyboard = TELEGRAM.sentMessages()
                .getFirst()
                .path("reply_markup")
                .path("inline_keyboard")
                .get(0);
        assertThat(keyboard.get(0).path("url").asText()).contains("/authorize");
        assertThat(keyboard.get(1).path("callback_data").asText()).isEqualTo("onb:skip");

        var state = conversationStateService.load(CHAT_ID);
        assertThat(state.getCurrentFlow()).isEqualTo(ConversationFlow.ONBOARDING);
        assertThat(state.getCurrentStep()).isEqualTo(OnboardingStep.AWAITING_CONNECT.name());
    }

    @Test
    void walksAConnectedUserFromConfirmationToASavedProfile() throws Exception {
        sendText(1, "привіт");
        connectSilpo();
        CLAUDE.respondWithText(
                """
                {"householdSize":4,"hasKids":true,"kidsAges":[3,7],\
                "dietaryRestrictions":["без горіхів"],"frequentItems":["молоко"]}""");

        tapButton(2, "onb:connected");
        assertThat(lastMessageText()).contains("4");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.CONFIRM_PROFILE.name());

        tapButton(3, "onb:confirm");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_BUDGET.name());

        sendText(4, "2500 грн");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        assertThat(profile.getHouseholdSize()).isEqualTo(4);
        assertThat(profile.getHasKids()).isTrue();
        assertThat(profile.getKidsAges()).containsExactly(3, 7);
        assertThat(profile.getDietaryRestrictions()).containsExactly("без горіхів");
        assertThat(profile.getWeeklyBudget()).isEqualByComparingTo("2500");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow())
                .isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void asksEverythingWhenTheUserSkipsConnecting() throws Exception {
        sendText(1, "привіт");

        tapButton(2, "onb:skip");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_HOUSEHOLD.name());

        sendText(3, "нас четверо");
        sendText(4, "алергія на горіхи");
        sendText(5, "броколі");
        sendText(6, "2000");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        assertThat(profile.getHouseholdSize()).isEqualTo(4);
        assertThat(profile.getDietaryRestrictions()).containsExactly("алергія на горіхи");
        assertThat(profile.getDislikedFoods()).containsExactly("броколі");
        assertThat(profile.getWeeklyBudget()).isEqualByComparingTo("2000");
        assertThat(MCP.callCount("tools/call")).isZero();
    }

    @Test
    void reAsksRatherThanStoringNonsense() throws Exception {
        sendText(1, "привіт");
        tapButton(2, "onb:skip");

        sendText(3, "не знаю");

        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_HOUSEHOLD.name());
        assertThat(lastMessageText()).isNotBlank();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*OnboardingFlowIntegrationTest*'`
Expected: FAIL — the router still echoes, so no connect keyboard is sent and no conversation state is written.

- [ ] **Step 3: Write the flow service**

Create `src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java`:

```java
package com.silporestockai.service.onboarding;

import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OnboardingCompletedEvent;
import com.silporestockai.model.OnboardingStep;
import com.silporestockai.model.SilpoProfileSnapshot;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.SilpoAuthService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Walks a new user from their first message to a saved profile.
 *
 * <p>Telegram delivers every update as an independent request, so the whole conversation lives in
 * {@code conversation_state}: {@code current_step} names where it is, {@code context_json} accumulates the
 * answers. A user who goes silent for an hour resumes where they stopped.
 *
 * <p>Questions the Silpo profile already answered are skipped. The budget is always asked, because MCP
 * cannot know what someone intends to spend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingFlowService {

    public static final String CALLBACK_CONNECTED = "onb:connected";
    public static final String CALLBACK_SKIP = "onb:skip";
    public static final String CALLBACK_CONFIRM = "onb:confirm";
    public static final String CALLBACK_CORRECT = "onb:correct";

    private static final String KEY_HOUSEHOLD = "householdSize";
    private static final String KEY_HAS_KIDS = "hasKids";
    private static final String KEY_KIDS_AGES = "kidsAges";
    private static final String KEY_RESTRICTIONS = "dietaryRestrictions";
    private static final String KEY_DISLIKES = "dislikedFoods";
    private static final String KEY_BUDGET = "weeklyBudget";

    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

    /** Enough Ukrainian numerals to cover a realistic answer to "how many of you are there". */
    private static final Map<String, Integer> WORD_NUMBERS = Map.ofEntries(
            Map.entry("один", 1),
            Map.entry("одна", 1),
            Map.entry("двоє", 2),
            Map.entry("два", 2),
            Map.entry("дві", 2),
            Map.entry("троє", 3),
            Map.entry("три", 3),
            Map.entry("четверо", 4),
            Map.entry("чотири", 4),
            Map.entry("п'ятеро", 5),
            Map.entry("п'ять", 5),
            Map.entry("шестеро", 6),
            Map.entry("шість", 6),
            Map.entry("семеро", 7),
            Map.entry("сім", 7));

    private final UserProfileRepository userProfileRepository;
    private final ConversationStateService conversationStateService;
    private final ProfileEnrichmentService profileEnrichmentService;
    private final TelegramOutboundService telegramOutboundService;
    private final SilpoAuthService silpoAuthService;
    private final ApplicationEventPublisher events;

    public boolean isOnboarded(UUID userId) {
        return userProfileRepository.findByUserId(userId).isPresent();
    }

    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = incoming.chatId();
        ConversationState state = conversationStateService.load(chatId);
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());

        if (state.getCurrentFlow() != ConversationFlow.ONBOARDING) {
            greet(user, chatId, context);
            return;
        }

        OnboardingStep step = OnboardingStep.valueOf(state.getCurrentStep());
        switch (incoming) {
            case TelegramIncomingUpdate.ButtonTap tap -> {
                telegramOutboundService.answerCallback(tap.callbackQueryId());
                handleButton(user, chatId, step, tap.data(), context);
            }
            case TelegramIncomingUpdate.Text text -> handleAnswer(user, chatId, step, text.text(), context);
            case TelegramIncomingUpdate.Voice ignored ->
                telegramOutboundService.sendMessage(
                        chatId, "Голосові поки не розбираю. Напиши, будь ласка, текстом.");
        }
    }

    private void greet(User user, long chatId, Map<String, Object> context) {
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                """
                Привіт. Я Комора — беру на себе тижневі закупи їжі.
                Під'єднай акаунт «Сільпо», і я візьму звідти склад сім'ї та обмеження, щоб не питати зайвого.""",
                List.of(
                        TelegramButton.link("Під'єднати Сільпо", silpoAuthService.buildAuthorizationUrl(user.getId())),
                        TelegramButton.callback("Пропустити", CALLBACK_SKIP)));
        save(chatId, OnboardingStep.AWAITING_CONNECT, context);
    }

    private void handleButton(
            User user, long chatId, OnboardingStep step, String data, Map<String, Object> context) {
        if (step == OnboardingStep.AWAITING_CONNECT && CALLBACK_SKIP.equals(data)) {
            askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user);
            return;
        }
        if (step == OnboardingStep.AWAITING_CONNECT) {
            enrichThenConfirm(user, chatId, context);
            return;
        }
        if (step == OnboardingStep.CONFIRM_PROFILE && CALLBACK_CONFIRM.equals(data)) {
            askNext(chatId, OnboardingStep.ASK_BUDGET, context, user);
            return;
        }
        if (step == OnboardingStep.CONFIRM_PROFILE && CALLBACK_CORRECT.equals(data)) {
            // Corrections walk the same question chain. Clearing the detected values first is what makes
            // every field genuinely overwritable — otherwise askNext would skip the questions it already
            // has answers for.
            context.remove(KEY_HOUSEHOLD);
            context.remove(KEY_RESTRICTIONS);
            context.remove(KEY_DISLIKES);
            askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user);
            return;
        }
        log.debug("ignoring callback {} at step {}", data, step);
    }

    private void enrichThenConfirm(User user, long chatId, Map<String, Object> context) {
        SilpoProfileSnapshot snapshot = profileEnrichmentService.enrich(user.getId());
        if (snapshot.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, "Нічого не знайшов у профілі «Сільпо». Запитаю сам.");
            askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user);
            return;
        }
        putIfPresent(context, KEY_HOUSEHOLD, snapshot.householdSize());
        putIfPresent(context, KEY_HAS_KIDS, snapshot.hasKids());
        putIfPresent(context, KEY_KIDS_AGES, snapshot.kidsAges());
        putIfPresent(context, KEY_RESTRICTIONS, snapshot.dietaryRestrictions());

        telegramOutboundService.sendMessageWithButtons(
                chatId,
                "Ось що знайшов:\n" + describe(context) + "\nВсе вірно?",
                List.of(
                        TelegramButton.callback("Все вірно", CALLBACK_CONFIRM),
                        TelegramButton.callback("Виправлю", CALLBACK_CORRECT)));
        save(chatId, OnboardingStep.CONFIRM_PROFILE, context);
    }

    private void handleAnswer(
            User user, long chatId, OnboardingStep step, String answer, Map<String, Object> context) {
        switch (step) {
            case ASK_HOUSEHOLD -> {
                Optional<Integer> size = parseCount(answer);
                if (size.isEmpty()) {
                    telegramOutboundService.sendMessage(chatId, "Не зрозумів. Напиши числом, скільки вас удома.");
                    return;
                }
                context.put(KEY_HOUSEHOLD, size.get());
                askNext(chatId, OnboardingStep.ASK_RESTRICTIONS, context, user);
            }
            case ASK_RESTRICTIONS -> {
                context.put(KEY_RESTRICTIONS, splitAnswer(answer));
                askNext(chatId, OnboardingStep.ASK_DISLIKES, context, user);
            }
            case ASK_DISLIKES -> {
                context.put(KEY_DISLIKES, splitAnswer(answer));
                askNext(chatId, OnboardingStep.ASK_BUDGET, context, user);
            }
            case ASK_BUDGET -> {
                Optional<BigDecimal> budget = parseAmount(answer);
                if (budget.isEmpty()) {
                    telegramOutboundService.sendMessage(chatId, "Не зрозумів суму. Напиши числом, наприклад 2500.");
                    return;
                }
                context.put(KEY_BUDGET, budget.get().toPlainString());
                finish(user, chatId, context);
            }
            default ->
                telegramOutboundService.sendMessage(chatId, "Скористайся, будь ласка, кнопками вище.");
        }
    }

    /** Moves to {@code step}, skipping any question the Silpo profile already answered. */
    private void askNext(long chatId, OnboardingStep step, Map<String, Object> context, User user) {
        OnboardingStep target = step;
        while (target != OnboardingStep.ASK_BUDGET && answered(context, target)) {
            target = following(target);
        }
        switch (target) {
            case ASK_HOUSEHOLD -> telegramOutboundService.sendMessage(chatId, "Скільки вас удома?");
            case ASK_RESTRICTIONS ->
                telegramOutboundService.sendMessage(chatId, "Є алергії чи дієтичні обмеження? Якщо ні — напиши «нема».");
            case ASK_DISLIKES ->
                telegramOutboundService.sendMessage(chatId, "Що вдома точно не їдять? Якщо все їдять — напиши «нема».");
            case ASK_BUDGET -> telegramOutboundService.sendMessage(chatId, "Який бюджет на тиждень, у гривнях?");
            default -> {
                finish(user, chatId, context);
                return;
            }
        }
        save(chatId, target, context);
    }

    private static OnboardingStep following(OnboardingStep step) {
        return switch (step) {
            case ASK_HOUSEHOLD -> OnboardingStep.ASK_RESTRICTIONS;
            case ASK_RESTRICTIONS -> OnboardingStep.ASK_DISLIKES;
            default -> OnboardingStep.ASK_BUDGET;
        };
    }

    private static boolean answered(Map<String, Object> context, OnboardingStep step) {
        return switch (step) {
            case ASK_HOUSEHOLD -> context.get(KEY_HOUSEHOLD) != null;
            case ASK_RESTRICTIONS -> context.get(KEY_RESTRICTIONS) != null;
            case ASK_DISLIKES -> context.get(KEY_DISLIKES) != null;
            default -> false;
        };
    }

    private void finish(User user, long chatId, Map<String, Object> context) {
        UserProfile profile = userProfileRepository
                .findByUserId(user.getId())
                .orElseGet(() -> UserProfile.builder()
                        .id(UUID.randomUUID())
                        .userId(user.getId())
                        .build());
        profile.setHouseholdSize(intOf(context.get(KEY_HOUSEHOLD)));
        profile.setHasKids(context.get(KEY_HAS_KIDS) instanceof Boolean flag ? flag : null);
        profile.setKidsAges(intListOf(context.get(KEY_KIDS_AGES)));
        profile.setDietaryRestrictions(stringListOf(context.get(KEY_RESTRICTIONS)));
        profile.setDislikedFoods(stringListOf(context.get(KEY_DISLIKES)));
        profile.setWeeklyBudget(
                context.get(KEY_BUDGET) == null ? null : new BigDecimal(context.get(KEY_BUDGET).toString()));
        userProfileRepository.save(profile);

        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        telegramOutboundService.sendMessage(chatId, "Записав. Готую перший план на тиждень.");
        events.publishEvent(new OnboardingCompletedEvent(user.getId()));
        log.info("onboarding completed for user {}", user.getId());
    }

    private void save(long chatId, OnboardingStep step, Map<String, Object> context) {
        conversationStateService.save(chatId, ConversationFlow.ONBOARDING, step.name(), context);
    }

    private static void putIfPresent(Map<String, Object> context, String key, Object value) {
        if (value != null && !(value instanceof List<?> list && list.isEmpty())) {
            context.put(key, value);
        }
    }

    private static String describe(Map<String, Object> context) {
        List<String> lines = new ArrayList<>();
        if (context.get(KEY_HOUSEHOLD) != null) {
            lines.add("— людей удома: " + context.get(KEY_HOUSEHOLD));
        }
        if (Boolean.TRUE.equals(context.get(KEY_HAS_KIDS))) {
            lines.add("— діти: " + String.valueOf(context.getOrDefault(KEY_KIDS_AGES, "є")));
        }
        if (context.get(KEY_RESTRICTIONS) != null) {
            lines.add("— обмеження: " + String.join(", ", stringListOf(context.get(KEY_RESTRICTIONS))));
        }
        return String.join("\n", lines);
    }

    private static Optional<Integer> parseCount(String answer) {
        Matcher matcher = FIRST_NUMBER.matcher(answer);
        if (matcher.find()) {
            return Optional.of(Integer.parseInt(matcher.group().split("[.,]")[0]));
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        return WORD_NUMBERS.entrySet().stream()
                .filter(entry -> lower.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static Optional<BigDecimal> parseAmount(String answer) {
        Matcher matcher = FIRST_NUMBER.matcher(answer);
        return matcher.find() ? Optional.of(new BigDecimal(matcher.group().replace(',', '.'))) : Optional.empty();
    }

    private static List<String> splitAnswer(String answer) {
        String trimmed = answer.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("нема") || lower.equals("немає") || lower.equals("ні")) {
            return List.of();
        }
        return List.of(trimmed.split("\\s*,\\s*"));
    }

    private static Integer intOf(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> intListOf(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        return ((List<Object>) list)
                .stream().map(item -> item instanceof Number number ? number.intValue() : null)
                        .filter(java.util.Objects::nonNull)
                        .toList();
    }

    private static List<String> stringListOf(Object value) {
        return value instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : null;
    }
}
```

- [ ] **Step 4: Hand off from the router**

In `TelegramRoutingService`, replace the placeholder `handle` method and its `countMessage` helper with a
dispatch, and add the two new dependencies:

```java
    private final UserAccountService userAccountService;
    private final OnboardingFlowService onboardingFlowService;
    private final TelegramOutboundService telegramOutboundService;

    private void handle(TelegramIncomingUpdate incoming) {
        User user = userAccountService.findOrCreate(incoming.chatId());
        if (!onboardingFlowService.isOnboarded(user.getId())) {
            onboardingFlowService.handle(user, incoming);
            return;
        }
        // TODO(#11): scheduled check-ins and the reorder cycle answer here.
        telegramOutboundService.sendMessage(
                incoming.chatId(), "Профіль уже є. Регулярні чек-іни та перезамовлення додам далі.");
    }
```

Remove the now-unused `ConversationStateService` and `MESSAGE_COUNT` field, the `ConversationState` import
and the `LinkedHashMap` / `Map` imports if nothing else uses them. Add imports for `User`,
`UserAccountService` and `OnboardingFlowService`.

- [ ] **Step 5: Rewrite the echo assertions**

In `TelegramWebhookIntegrationTest`, task 03's echo is gone. Replace
`echoesATextMessageEndToEnd`, `resumesConversationStateAcrossTwoSeparateWebhookCalls`,
`downloadsAVoiceNoteAndReportsItsSize` and `routesAnInlineButtonCallbackAndAcknowledgesIt` with these, and
delete the `ConversationFlow` and `ConversationStateService` imports if they become unused:

```java
    @Test
    void aFirstTextMessageStartsOnboarding() throws Exception {
        deliver(textUpdate(1, "привіт"));

        assertThat(STUB.sentMessages()).hasSize(1);
        assertThat(STUB.sentMessages().getFirst().path("chat_id").asLong()).isEqualTo(CHAT_ID);
        assertThat(STUB.sentMessages().getFirst().path("text").asText()).contains("Комора");
        assertThat(conversationStateRepository.count()).isEqualTo(1);
    }

    @Test
    void aSecondMessageContinuesTheSameConversationRatherThanStartingANewOne() throws Exception {
        deliver(textUpdate(1, "привіт"));
        deliver(textUpdate(2, "ще раз"));

        assertThat(conversationStateRepository.count()).isEqualTo(1);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void voiceNotesAreAcknowledgedRatherThanDropped() throws Exception {
        deliver(textUpdate(1, "привіт"));
        deliver(voiceUpdate(3, "voice-file-id", 7));

        assertThat(STUB.sentMessages().getLast().path("text").asText()).contains("Голосові");
    }

    @Test
    void routesAnInlineButtonCallbackAndAcknowledgesIt() throws Exception {
        deliver(textUpdate(1, "привіт"));

        deliver(callbackUpdate(4, "cb-1", "onb:skip"));

        assertThat(STUB.callbackAnswers()).hasSize(1);
        assertThat(STUB.callbackAnswers().getFirst().path("callback_query_id").asText())
                .isEqualTo("cb-1");
    }
```

Add `@Autowired private UserRepository userRepository;` to that class, and clean `userProfileRepository`,
`conversationStateRepository` and `userRepository` in its `reset()` method.

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests '*OnboardingFlowIntegrationTest*' --tests '*TelegramWebhookIntegrationTest*'`
Expected: PASS, 4 onboarding tests and 7 webhook tests.

If `walksAConnectedUserFromConfirmationToASavedProfile` stops at `CONFIRM_PROFILE`, check that
`StubAnthropicServer.respondWithText` was set before the button tap — enrichment runs inside that call.

- [ ] **Step 7: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java \
        src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java \
        src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java \
        src/test/java/com/silporestockai/integration/TelegramWebhookIntegrationTest.java
git commit -m "Walk a new user from hello to a saved profile"
```

---

### Task 4: Resumption, correction, and the documentation

**Files:**
- Modify: `src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java`
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything from Tasks 1 to 3. No production code changes expected — these behaviours fall out of the state machine, and the tests exist to prove they actually do.

- [ ] **Step 1: Write the tests**

Append to `OnboardingFlowIntegrationTest`:

```java
    @Test
    void resumesFromTheSavedStepAfterTheUserGoesSilent() throws Exception {
        sendText(1, "привіт");
        tapButton(2, "onb:skip");
        sendText(3, "нас четверо");

        // Nothing in memory carries between webhook calls; only conversation_state does.
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_RESTRICTIONS.name());

        sendText(4, "нема");

        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_DISLIKES.name());
        assertThat(conversationStateService.load(CHAT_ID).getContext())
                .containsEntry("householdSize", 4);
        assertThat(userProfileRepository.count()).isZero();
    }

    @Test
    void correctionOverwritesADetectedField() throws Exception {
        sendText(1, "привіт");
        connectSilpo();
        CLAUDE.respondWithText("{\"householdSize\":4,\"hasKids\":false}");
        tapButton(2, "onb:connected");

        tapButton(3, "onb:correct");
        sendText(4, "нас двоє");
        sendText(5, "нема");
        sendText(6, "нема");
        sendText(7, "1800");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        assertThat(profile.getHouseholdSize()).isEqualTo(2);
        assertThat(profile.getWeeklyBudget()).isEqualByComparingTo("1800");
    }

    @Test
    void anOnboardedUserIsNotOnboardedAgain() throws Exception {
        sendText(1, "привіт");
        tapButton(2, "onb:skip");
        sendText(3, "2");
        sendText(4, "нема");
        sendText(5, "нема");
        sendText(6, "1500");
        TELEGRAM.reset();

        sendText(7, "а що далі?");

        assertThat(userProfileRepository.count()).isEqualTo(1);
        assertThat(lastMessageText()).contains("Профіль уже є");
    }

    @Test
    void degradesToAskingWhenSilpoIsUnreachable() throws Exception {
        sendText(1, "привіт");
        connectSilpo();
        MCP.injectStatus("initialize", 500);

        tapButton(2, "onb:connected");

        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_HOUSEHOLD.name());
        assertThat(userProfileRepository.count()).isZero();
    }
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew test --tests '*OnboardingFlowIntegrationTest*'`
Expected: PASS, 8 tests.

If `correctionOverwritesADetectedField` skips the household question, the `CALLBACK_CORRECT` branch is not
clearing the detected keys before calling `askNext` — `askNext` skips any question the context already
answers, which is correct behaviour for confirmation and wrong for correction.

- [ ] **Step 3: Document the flow**

In `README.md`, after the `#### Running the webhook locally` subsection, add:

```markdown
#### Onboarding

The first message any new chat sends starts onboarding. The bot creates the user row, offers a Silpo
connect link, and — once connected — reads `silpo_get_my_family`, `silpo_get_my_food_restrictions`,
`silpo_get_my_online_orders` and `silpo_get_my_favorites`, letting Claude turn whatever they return into a
profile snapshot. Only the fields Silpo could not supply are asked, plus the weekly budget, which it never
knows.

Skipping the connect step, an unreachable Silpo, and a guest with no order history all take the same
fallback: the bot asks directly. Onboarding ends with a saved `user_profile` and an
`OnboardingCompletedEvent`, which meal plan generation will listen for.
```

In `CLAUDE.md`, add to the "Invariants that break the build" list:

```markdown
- **Conversation state is the only memory between webhook calls.** Nothing may be held in a field of a
  flow service — Telegram delivers every update as an independent request, and two updates can land on
  different instances. `OnboardingFlowService` keeps its partial profile in `conversation_state.context_json`
  for exactly this reason.
```

- [ ] **Step 4: Verify everything**

```bash
make format
./gradlew test
./gradlew build
```
Expected: `BUILD SUCCESSFUL` for all three.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java \
        README.md CLAUDE.md
git commit -m "Prove onboarding resumes, corrects and degrades"
```

---

## Acceptance criteria mapping

| Notion criterion | Proven by |
|---|---|
| A new Telegram user completes onboarding end-to-end and `user_profile` is persisted | Task 3 `walksAConnectedUserFromConfirmationToASavedProfile` |
| MCP data is fetched and shown for confirmation rather than silently assumed | Task 3 `walksAConnectedUserFromConfirmationToASavedProfile` asserts the confirmation message carries the detected values |
| The user can correct any auto-detected field | Task 4 `correctionOverwritesADetectedField` |
| Graceful fallback for a guest with empty MCP history, tested | Task 2 `returnsAnEmptySnapshotWhenTheGuestNeverConnected`, `degradesToAnEmptySnapshotWhenClaudeCannotBeReached`; Task 3 `asksEverythingWhenTheUserSkipsConnecting`; Task 4 `degradesToAskingWhenSilpoIsUnreachable` |
| Conversation state resumes if the user goes silent mid-flow | Task 4 `resumesFromTheSavedStepAfterTheUserGoesSilent` |
| Integration test covering the full happy path across multiple simulated webhook calls | Task 3 `walksAConnectedUserFromConfirmationToASavedProfile` — four separate `POST`s |

The connect step is a deliberate addition to the task's step list; state it in the final commit. The
placeholder echo from task 03 is removed here, as task 03 said it would be.
