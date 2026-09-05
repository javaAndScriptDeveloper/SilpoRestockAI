# Профіль-reedit (Анкета reopen) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping «🧾 Анкета» after onboarding reopens the profile form pre-filled with the current answers; if the resubmitted answers differ, ask before regenerating the active shopping list, instead of falling through to the intent router's clarifying question.

**Architecture:** A new `ConversationFlow.PROFILE_REEDIT` (reusing `ONBOARDING`'s step machinery would never dispatch — `TelegramRoutingService.handle()` gates `OnboardingFlowService` entirely behind `!isOnboarded`, which is permanently true once a profile exists). `OnboardingFlowService` gains `reopenForm` (button tap → send the WebApp form, fully pre-filled from the saved `UserProfile`) and `handleReedit` (the two steps of that side flow: awaiting the resubmitted form, then awaiting a regenerate confirm/cancel). The confirm branch calls the already-tested `MealPlanHandoffService.generateFirstPlan` directly — no new plan-generation code.

**Tech Stack:** Spring Boot service classes, existing `ConversationStateService`/`conversation_state` persistence, Jackson for the WebApp JSON payload, plain browser JS for the onboarding WebApp form.

**Spec:** Task 31 (`Комора — Development Plan`, Notion), acceptance criterion 7: *"Editing the Анкета and changing an answer prompts an explicit 'regenerate list?' confirmation rather than auto-regenerating."* No separate design doc — bounded change to already-explored, existing code (see conversation history: `OnboardingFlowService`, `TelegramRoutingService`, `onboarding.js`/`onboarding.html` were all read in full before this plan was written).

## Global Constraints

- Do not touch `dislikedFoods` in the reedit path — the WebApp form never collects it, so overwriting it with `null` would silently erase data the manual-fallback chat flow set during first-time onboarding.
- Do not change first-time onboarding's own flow, steps, or tests (`OnboardingStep`, `OnboardingFlowIntegrationTest`) — this plan only adds a second, independent path that starts after onboarding is already done.
- The confirm-path plan regeneration must go through `MealPlanHandoffService.generateFirstPlan(UUID)` — do not duplicate meal-plan-generation logic.
- Reuse the existing `WebAppOnboardingPayload` record for parsing the resubmitted form — do not define a second payload shape.
- Every new conversation-state transition uses `ConversationStateService.save(chatId, flow, step, context)` / `.load(chatId)`, the same as every other flow in this codebase.
- `make format` and the full `make test` suite must stay green after every task.

---

### Task 1: `ConversationFlow.PROFILE_REEDIT` and routing dispatch

**Files:**
- Modify: `src/main/java/com/silporestockai/model/ConversationFlow.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java:189-247` (the `matches(...)` chain and the flow-check block above it)
- Test: `src/test/java/com/silporestockai/integration/ProfileReeditIntegrationTest.java` (new file, created in this task)

**Interfaces:**
- Consumes: `OnboardingFlowService.isOnboarded(UUID)` (existing), `MainMenuKeyboard.FORM` (existing constant, value `"🧾 Анкета"`).
- Produces: `OnboardingFlowService.reopenForm(User user)` and `OnboardingFlowService.handleReedit(User user, TelegramIncomingUpdate incoming)` — declared here as the two methods `TelegramRoutingService` calls; implemented in Task 2/3/4. For this task, add them as package-visible stub methods on `OnboardingFlowService` that just call `telegramOutboundService.sendMessage(user.getTelegramChatId(), "TODO")` so the routing wiring compiles and its own test can pass before the real behavior exists — Task 2 replaces the stub body.

**Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/ProfileReeditIntegrationTest.java`, modeled directly on `OnboardingFlowIntegrationTest`'s stub-server setup (same `AbstractIntegrationTest` base, same `StubTelegramServer`/`StubMcpServer`/`StubAnthropicServer` triple, same `deliver`/`sendText`/`tapButton`/`sendWebAppData` webhook-simulation helpers — copy those four private methods verbatim, they are the project's established pattern for driving `/telegram/webhook`).

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.AgeBracket;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.DietType;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Base64;
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

@DisplayName("reopening the Анкета after onboarding")
class ProfileReeditIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "666:stub-bot-token";
    private static final long CHAT_ID = 9101L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private MealPlanRepository mealPlanRepository;

    @Autowired
    private ShoppingListItemRepository shoppingListItemRepository;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of());
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
        registry.add("telegram.web-app-base-url", () -> "https://example.test");
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
        shoppingListItemRepository.deleteAll();
        mealPlanRepository.deleteAll();
        userProfileRepository.deleteAll();
        conversationStateRepository.deleteAll();
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

    private void sendWebAppData(int updateId, String json) throws Exception {
        String escaped = MAPPER.writeValueAsString(json);
        deliver(
                """
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "web_app_data":{"data":%s,"button_text":"Заповнити анкету"}}}"""
                        .formatted(updateId, updateId, CHAT_ID, escaped));
    }

    private UUID onboardedUser() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .adultMaleCount(2)
                .adultFemaleCount(0)
                .childrenAgeBrackets(List.of(AgeBracket.AGE_4_7))
                .householdSize(3)
                .hasKids(true)
                .dietaryRestrictions(List.of("nuts"))
                .dietType(DietType.NONE)
                .cookingTimePreference(CookingTimePreference.COOKS_DAILY)
                .weeklyBudget(new BigDecimal("2500"))
                .onlyUaProducer(false)
                .build());
        return user.getId();
    }

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void tappingAnketaReopensTheFormPrefilledWithCurrentAnswers() throws Exception {
        onboardedUser();

        sendText(1, "🧾 Анкета");

        var sent = TELEGRAM.sentMessages().getLast();
        String webAppUrl = sent.path("reply_markup")
                .path("keyboard")
                .get(0)
                .get(0)
                .path("web_app")
                .path("url")
                .asText();
        String encoded = webAppUrl.substring(webAppUrl.indexOf("prefill=") + "prefill=".length());
        JsonNode prefill = MAPPER.readTree(
                Base64.getUrlDecoder().decode(encoded.replace('-', '+').replace('_', '/')));
        assertThat(prefill.path("adultMale").asInt()).isEqualTo(2);
        assertThat(prefill.path("adultFemale").asInt()).isEqualTo(0);
        assertThat(prefill.path("childrenAgeBrackets").get(0).asText()).isEqualTo("AGE_4_7");
        assertThat(prefill.path("restrictions").get(0).asText()).isEqualTo("nuts");
        assertThat(prefill.path("dietType").asText()).isEqualTo("NONE");
        assertThat(prefill.path("cookingTimePreference").asText()).isEqualTo("COOKS_DAILY");
        assertThat(prefill.path("weeklyBudget").asDouble()).isEqualTo(2500.0);

        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.PROFILE_REEDIT);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.silporestockai.integration.ProfileReeditIntegrationTest"`
Expected: FAIL — either a compile error (`MainMenuKeyboard.FORM` not routed anywhere yet, so no WebApp button is ever sent and `TELEGRAM.sentMessages()` is a plain text nudge) or an assertion failure reading `reply_markup.keyboard` (the fallback "Скористайся кнопками..." message has no keyboard).

**Step 3: Write the minimal implementation**

In `ConversationFlow.java`, add the new value:

```java
    /** The Анкета button reopens the profile form after onboarding; awaiting its resubmission or a regenerate confirm. */
    PROFILE_REEDIT
```

In `OnboardingFlowService.java`, add two public stub methods (placed after `isOnboarded`):

```java
    /** Placeholder — replaced with the real prefill-and-send logic in the next task. */
    public void reopenForm(User user) {
        conversationStateService.save(user.getTelegramChatId(), ConversationFlow.PROFILE_REEDIT, "AWAITING_FORM", Map.of());
        telegramOutboundService.sendMessage(user.getTelegramChatId(), "Відкриваю анкету.");
    }

    /** Placeholder — replaced with the real form-resubmission/confirm handling in later tasks. */
    public void handleReedit(User user, TelegramIncomingUpdate incoming) {
        telegramOutboundService.sendMessage(user.getTelegramChatId(), "TODO");
    }
```

In `TelegramRoutingService.java`, add the flow-check branch right after the existing `SPECIAL_MODE_SETUP` check (around line 188):

```java
        if (flow == ConversationFlow.PROFILE_REEDIT) {
            onboardingFlowService.handleReedit(user, incoming);
            return;
        }
```

and add a `matches(...)` branch right after the existing `LIST` branch (around line 193):

```java
        if (incoming instanceof TelegramIncomingUpdate.Text form
                && matches(form.text(), "/anketa", MainMenuKeyboard.FORM)) {
            onboardingFlowService.reopenForm(user);
            return;
        }
```

`TelegramRoutingService` already imports `com.silporestockai.service.onboarding.OnboardingFlowService` (as `onboardingFlowService`) and `MainMenuKeyboard` — no new imports needed there.

**Step 4: Run test to verify it passes (partially)**

Run: `./gradlew test --tests "com.silporestockai.integration.ProfileReeditIntegrationTest"`
Expected: still FAIL on the prefill assertions (the stub sends a plain text message, not a WebApp button) — this is expected; the stub only proves the routing wiring compiles and dispatches. Confirm the flow-state assertion passes and the failure has moved to the `reply_markup` lookup, not a routing/compile error.

**Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/model/ConversationFlow.java \
        src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java \
        src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java \
        src/test/java/com/silporestockai/integration/ProfileReeditIntegrationTest.java
git commit -m "Route the Анкета button to a new profile-reedit flow (task 31, criterion 7)"
```

---

### Task 2: `reopenForm` sends the WebApp form fully pre-filled

**Files:**
- Modify: `src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java`
- Test: `src/test/java/com/silporestockai/integration/ProfileReeditIntegrationTest.java` (test from Task 1 now goes fully green; add the "not configured" and "no profile yet" edge cases)

**Interfaces:**
- Consumes: `UserProfileRepository.findByUserId(UUID)` (existing), `TelegramProperties.webAppConfigured()` (existing), `TelegramOutboundService.sendMessageWithWebAppButton(long, String, String, String, String)` (existing signature: chatId, text, webAppLabel, webAppUrl, fallbackLabel).
- Produces: `OnboardingFlowService.CANCEL_LABEL` (new `public static final String`, value `"Скасувати"`) — Task 4 matches against it in `handleReedit`.

**Step 1: Write the failing test**

Add to `ProfileReeditIntegrationTest`:

```java
    @Test
    void anketaIsUnavailableWithoutWebAppConfigured() throws Exception {
        // Reuses the class-level web-app-base-url stub, so simulate "not configured" the same way
        // OnboardingFlowIntegrationTest style tests do: this scenario is covered by a focused unit
        // path instead — see OnboardingFlowServiceTest.reopenFormWithoutWebAppSaysSo below.
    }
```

Since every test in this class shares one `@DynamicPropertySource` that always configures a WebApp URL, the "not configured" branch is better covered by a plain (non-Spring-context) unit test. Create `src/test/java/com/silporestockai/service/onboarding/OnboardingFlowServiceTest.java`:

```java
package com.silporestockai.service.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.silporestockai.config.TelegramProperties;
import com.silporestockai.entity.User;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.SilpoAuthService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class OnboardingFlowServiceTest {

    @Test
    void reopenFormWithoutWebAppSaysSo() {
        TelegramProperties properties = new TelegramProperties(null, null, null, null, "");
        TelegramOutboundService outbound = mock(TelegramOutboundService.class);
        OnboardingFlowService service = new OnboardingFlowService(
                mock(UserProfileRepository.class),
                mock(com.silporestockai.repository.UserRepository.class),
                mock(ConversationStateService.class),
                mock(ProfileEnrichmentService.class),
                outbound,
                mock(SilpoAuthService.class),
                properties,
                mock(ApplicationEventPublisher.class));
        User user = User.builder().id(UUID.randomUUID()).telegramChatId(42L).build();

        service.reopenForm(user);

        verify(outbound).sendMessage(42L, "Анкета зараз недоступна.");
        verify(outbound, never()).sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString(), anyString());
    }
}
```

Remove the empty placeholder test from `ProfileReeditIntegrationTest` added above (it asserted nothing — delete it, keeping only `tappingAnketaReopensTheFormPrefilledWithCurrentAnswers`).

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.silporestockai.service.onboarding.OnboardingFlowServiceTest" --tests "com.silporestockai.integration.ProfileReeditIntegrationTest"`
Expected: `OnboardingFlowServiceTest` fails (stub `reopenForm` never checks `webAppConfigured()` and always sends "Відкриваю анкету."). `ProfileReeditIntegrationTest` still fails on the prefill assertions from Task 1.

**Step 3: Write the minimal implementation**

Replace the `reopenForm` stub in `OnboardingFlowService.java`:

```java
    public static final String CALLBACK_REEDIT_CONFIRM = "reedit:confirm";
    public static final String CALLBACK_REEDIT_CANCEL = "reedit:cancel";
    public static final String CANCEL_LABEL = "Скасувати";

    private static final String REEDIT_STEP_AWAITING_FORM = "AWAITING_FORM";
    private static final String REEDIT_STEP_AWAITING_CONFIRM = "AWAITING_CONFIRM";

    /** Known chip codes from the WebApp form — anything else in a profile's restrictions is free text. */
    private static final List<String> RESTRICTION_CHIP_CODES = List.of("nuts", "lactose", "gluten", "seafood");

    /**
     * Reopens the profile form after onboarding, pre-filled with the saved answers — task 31's Анкета button.
     *
     * <p>Only reachable once a profile exists (see {@link #isOnboarded}), so {@code findByUserId} not finding one
     * would be a routing bug, not a real state to handle gracefully.
     */
    public void reopenForm(User user) {
        if (!telegramProperties.webAppConfigured()) {
            telegramOutboundService.sendMessage(user.getTelegramChatId(), "Анкета зараз недоступна.");
            return;
        }
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        String formUrl = telegramProperties.webAppBaseUrl() + "/webapp/onboarding.html?prefill=" + fullPrefillOf(profile);
        telegramOutboundService.sendMessageWithWebAppButton(
                user.getTelegramChatId(),
                "Онови анкету — поточні відповіді вже підставлені.",
                "Заповнити анкету",
                formUrl,
                CANCEL_LABEL);
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.PROFILE_REEDIT, REEDIT_STEP_AWAITING_FORM, Map.of());
    }

    private static String fullPrefillOf(UserProfile profile) {
        try {
            Map<String, Object> prefill = new LinkedHashMap<>();
            putIfPresent(prefill, "adultMale", profile.getAdultMaleCount());
            putIfPresent(prefill, "adultFemale", profile.getAdultFemaleCount());
            putIfPresent(prefill, "childrenAgeBrackets", profile.getChildrenAgeBrackets());
            List<String> restrictions = stringListOf(profile.getDietaryRestrictions());
            if (restrictions != null) {
                List<String> known = restrictions.stream()
                        .filter(RESTRICTION_CHIP_CODES::contains)
                        .toList();
                List<String> other = restrictions.stream()
                        .filter(value -> !RESTRICTION_CHIP_CODES.contains(value))
                        .toList();
                putIfPresent(prefill, "restrictions", known);
                if (!other.isEmpty()) {
                    prefill.put("restrictionsOther", String.join(", ", other));
                }
            }
            if (profile.getDietType() != null) {
                prefill.put("dietType", profile.getDietType().name());
            }
            if (profile.getCookingTimePreference() != null) {
                prefill.put("cookingTimePreference", profile.getCookingTimePreference().name());
            }
            if (profile.getWeeklyBudget() != null) {
                prefill.put("weeklyBudget", profile.getWeeklyBudget());
            }
            String json = MAPPER.writeValueAsString(prefill);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }
```

Add the one new import this needs: `java.nio.charset.StandardCharsets` is already imported (used elsewhere in the file); no other new imports required — `UserProfile`, `Base64`, `LinkedHashMap`, `List`, `Map` are already imported.

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.silporestockai.service.onboarding.OnboardingFlowServiceTest" --tests "com.silporestockai.integration.ProfileReeditIntegrationTest"`
Expected: both PASS.

**Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java \
        src/test/java/com/silporestockai/service/onboarding/OnboardingFlowServiceTest.java \
        src/test/java/com/silporestockai/integration/ProfileReeditIntegrationTest.java
git commit -m "Pre-fill the reopened Анкета with the household's saved answers"
```

---

### Task 3: `handleReedit` — no-change resubmission

**Files:**
- Modify: `src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java`
- Test: `src/test/java/com/silporestockai/integration/ProfileReeditIntegrationTest.java`

**Interfaces:**
- Consumes: `WebAppOnboardingPayload` (existing private record, same file — no signature change), `UserProfileRepository.save` (existing).
- Produces: `OnboardingFlowService.applyPayload(UUID userId, WebAppOnboardingPayload payload)` — a new private method returning a small `record ProfileChange(UserProfile profile, boolean changed)` (declared alongside `WebAppOnboardingPayload`). Task 4 reuses this same method for the changed-answer path — this task only exercises the `changed == false` branch.

**Step 1: Write the failing test**

Add to `ProfileReeditIntegrationTest`:

```java
    @Test
    void resubmittingIdenticalAnswersSkipsTheConfirmStep() throws Exception {
        onboardedUser();
        sendText(1, "🧾 Анкета");

        sendWebAppData(2, """
                {"adultMale":2,"adultFemale":0,"childrenAgeBrackets":["AGE_4_7"],\
                "restrictions":["nuts"],"restrictionsOther":"","dietType":"NONE",\
                "cookingTimePreference":"COOKS_DAILY","weeklyBudget":2500}""");

        assertThat(lastMessageText()).contains("Змін немає");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.silporestockai.integration.ProfileReeditIntegrationTest"`
Expected: FAIL — the current `handleReedit` stub always replies "TODO" and never resets the flow.

**Step 3: Write the minimal implementation**

Replace the `handleReedit` stub:

```java
    /** The two steps of the Анкета-reedit side flow: awaiting the resubmitted form, then awaiting a confirm/cancel. */
    public void handleReedit(User user, TelegramIncomingUpdate incoming) {
        ConversationState state = conversationStateService.load(user.getTelegramChatId());
        String step = state.getCurrentStep();
        if (REEDIT_STEP_AWAITING_FORM.equals(step)) {
            handleReeditFormSubmission(user, incoming);
            return;
        }
        if (REEDIT_STEP_AWAITING_CONFIRM.equals(step)) {
            handleReeditConfirmation(user, incoming);
            return;
        }
        telegramOutboundService.sendMessage(user.getTelegramChatId(), "Скористайся, будь ласка, кнопками вище.");
    }

    private void handleReeditFormSubmission(User user, TelegramIncomingUpdate incoming) {
        long chatId = user.getTelegramChatId();
        if (incoming instanceof TelegramIncomingUpdate.Text text && CANCEL_LABEL.equals(text.text().trim())) {
            conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
            telegramOutboundService.sendMessageWithMainMenu(chatId, "Гаразд, анкету не змінюю.");
            return;
        }
        if (!(incoming instanceof TelegramIncomingUpdate.WebAppData webAppData)) {
            telegramOutboundService.sendMessage(chatId, "Натисни кнопку «Заповнити анкету» або «" + CANCEL_LABEL + "».");
            return;
        }
        WebAppOnboardingPayload payload;
        try {
            payload = MAPPER.readValue(webAppData.data(), WebAppOnboardingPayload.class);
        } catch (Exception e) {
            log.warn("could not parse reedit WebApp payload for chat {}: {}", chatId, e.toString());
            telegramOutboundService.sendMessage(chatId, "Не вдалось прочитати анкету. Спробуй ще раз.");
            return;
        }
        ProfileChange change = applyPayload(user.getId(), payload);
        if (!change.changed()) {
            conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
            telegramOutboundService.sendMessageWithMainMenu(chatId, "Змін немає — залишаю все як є.");
            return;
        }
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                "Анкету оновлено. Оновити поточний список під нові відповіді?",
                List.of(
                        TelegramButton.callback("Так, оновити", CALLBACK_REEDIT_CONFIRM),
                        TelegramButton.callback("Ні, залишити", CALLBACK_REEDIT_CANCEL)));
        conversationStateService.save(chatId, ConversationFlow.PROFILE_REEDIT, REEDIT_STEP_AWAITING_CONFIRM, Map.of());
    }

    private void handleReeditConfirmation(User user, TelegramIncomingUpdate incoming) {
        // Implemented in Task 4.
        telegramOutboundService.sendMessage(user.getTelegramChatId(), "TODO");
    }

    /**
     * Applies a resubmitted WebApp payload directly onto the saved profile.
     *
     * <p>Unlike {@link #finish}, this always has every WebApp field in hand — no {@code KEY_*}/context-map
     * indirection needed. {@code dislikedFoods} is deliberately untouched: the form never collects it.
     */
    private ProfileChange applyPayload(UUID userId, WebAppOnboardingPayload payload) {
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        List<AgeBracket> brackets = payload.childrenAgeBrackets() == null ? List.of() : payload.childrenAgeBrackets();
        List<String> restrictions =
                new ArrayList<>(payload.restrictions() == null ? List.<String>of() : payload.restrictions());
        if (payload.restrictionsOther() != null && !payload.restrictionsOther().isBlank()) {
            restrictions.add(payload.restrictionsOther().trim());
        }
        DietType dietType = payload.dietType() == null ? DietType.NONE : payload.dietType();

        boolean changed = !Objects.equals(profile.getAdultMaleCount(), payload.adultMale())
                || !Objects.equals(profile.getAdultFemaleCount(), payload.adultFemale())
                || !Objects.equals(profile.getChildrenAgeBrackets(), brackets)
                || !Objects.equals(profile.getDietaryRestrictions(), restrictions)
                || !Objects.equals(profile.getDietType(), dietType)
                || !Objects.equals(profile.getCookingTimePreference(), payload.cookingTimePreference())
                || !Objects.equals(profile.getWeeklyBudget(), payload.weeklyBudget());

        int adults = (payload.adultMale() == null ? 0 : payload.adultMale())
                + (payload.adultFemale() == null ? 0 : payload.adultFemale());
        profile.setAdultMaleCount(payload.adultMale());
        profile.setAdultFemaleCount(payload.adultFemale());
        profile.setChildrenAgeBrackets(brackets);
        profile.setHouseholdSize(adults + brackets.size());
        profile.setHasKids(!brackets.isEmpty());
        profile.setKidsAges(brackets.stream().map(OnboardingFlowService::midpointAge).toList());
        profile.setDietaryRestrictions(restrictions);
        profile.setDietType(dietType);
        profile.setCookingTimePreference(payload.cookingTimePreference());
        profile.setWeeklyBudget(payload.weeklyBudget());
        userProfileRepository.save(profile);
        return new ProfileChange(profile, changed);
    }

    private record ProfileChange(UserProfile profile, boolean changed) {}
```

Add the two new imports this needs: `java.util.ArrayList` and `java.util.Objects` are both already imported in this file (used elsewhere) — no new imports required.

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.silporestockai.integration.ProfileReeditIntegrationTest"`
Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java \
        src/test/java/com/silporestockai/integration/ProfileReeditIntegrationTest.java
git commit -m "Save a resubmitted Анкета immediately; skip confirmation when nothing changed"
```

---

### Task 4: `handleReedit` — changed-answer confirm/cancel

**Files:**
- Modify: `src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java`
- Test: `src/test/java/com/silporestockai/integration/ProfileReeditIntegrationTest.java`

**Interfaces:**
- Consumes: `MealPlanHandoffService.generateFirstPlan(UUID)` (existing, synchronous when called directly — see that class's own javadoc) — inject `MealPlanHandoffService` into `OnboardingFlowService`'s constructor.
- Produces: nothing new for later tasks — this is the leaf of the reedit flow.

**Step 1: Write the failing tests**

Add to `ProfileReeditIntegrationTest` a `fullWeekJson()` helper (copy verbatim from `MealPlanHandoffIntegrationTest.fullWeekJson()` — same shape, same stub convention) and two tests:

```java
    private static String fullWeekJson() {
        StringBuilder days = new StringBuilder();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            days.append(
                    """
                    {"day":"%s","meals":[\
                    {"type":"BREAKFAST","name":"Вівсянка","ingredients":[{"name":"пластівці","quantity":0.3,"unit":"кг"}]},\
                    {"type":"LUNCH","name":"Борщ","ingredients":[{"name":"буряк","quantity":0.5,"unit":"кг"}]},\
                    {"type":"DINNER","name":"Рис з овочами","ingredients":[{"name":"рис","quantity":0.4,"unit":"кг"}]}]}"""
                            .formatted(day.name()));
        }
        return "{\"days\":[" + days + "]}";
    }

    @Test
    void changedAnswersAskBeforeRegenerating() throws Exception {
        onboardedUser();
        sendText(1, "🧾 Анкета");

        sendWebAppData(2, """
                {"adultMale":2,"adultFemale":1,"childrenAgeBrackets":["AGE_4_7"],\
                "restrictions":["nuts"],"restrictionsOther":"","dietType":"NONE",\
                "cookingTimePreference":"COOKS_DAILY","weeklyBudget":2500}""");

        assertThat(lastMessageText()).contains("Оновити поточний список");
        var buttons = TELEGRAM.sentMessages().getLast().path("reply_markup").path("inline_keyboard").get(0);
        assertThat(buttons.get(0).path("callback_data").asText()).isEqualTo("reedit:confirm");
        assertThat(buttons.get(1).path("callback_data").asText()).isEqualTo("reedit:cancel");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        assertThat(userProfileRepository.findByUserId(userId).orElseThrow().getAdultFemaleCount())
                .isEqualTo(1); // saved immediately, before any confirm

        CLAUDE.respondWithText(fullWeekJson());
        tapButton(3, "reedit:confirm");

        assertThat(mealPlanRepository.count()).isEqualTo(1);
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void cancellingLeavesTheCurrentListAlone() throws Exception {
        onboardedUser();
        sendText(1, "🧾 Анкета");
        sendWebAppData(2, """
                {"adultMale":2,"adultFemale":1,"childrenAgeBrackets":["AGE_4_7"],\
                "restrictions":["nuts"],"restrictionsOther":"","dietType":"NONE",\
                "cookingTimePreference":"COOKS_DAILY","weeklyBudget":2500}""");

        tapButton(3, "reedit:cancel");

        assertThat(lastMessageText()).contains("залишаю");
        assertThat(mealPlanRepository.count()).isZero();
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.silporestockai.integration.ProfileReeditIntegrationTest"`
Expected: FAIL — `handleReeditConfirmation` still replies "TODO" for both the confirm and cancel taps.

**Step 3: Write the minimal implementation**

Add the constructor dependency and replace `handleReeditConfirmation` in `OnboardingFlowService.java`:

```java
    private final MealPlanHandoffService mealPlanHandoffService;
```

add it as the last constructor parameter (Lombok's `@RequiredArgsConstructor`... — this class does **not** use Lombok's constructor generation, it has a hand-written constructor already visible only implicitly via field declarations; check the top of the class: it currently has no explicit constructor either, meaning Spring is using an implicit all-args constructor via `@RequiredArgsConstructor`-equivalent — **actually this class has no `@RequiredArgsConstructor` annotation and no `private final` constructor visible in the read file**, which means it must be relying on a single non-Lombok constructor not shown in the excerpt, OR every field being `final` with Lombok's class-level annotation not present — re-check the class declaration line before writing this step, since the exact mechanism determines whether adding a field is a one-line change or requires touching a constructor signature.)

```java
    private void handleReeditConfirmation(User user, TelegramIncomingUpdate incoming) {
        long chatId = user.getTelegramChatId();
        if (!(incoming instanceof TelegramIncomingUpdate.ButtonTap tap)) {
            telegramOutboundService.sendMessage(chatId, "Скористайся, будь ласка, кнопками вище.");
            return;
        }
        telegramOutboundService.answerCallback(tap.callbackQueryId());
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        if (CALLBACK_REEDIT_CONFIRM.equals(tap.data())) {
            telegramOutboundService.sendMessage(chatId, "Готую новий список під нові відповіді.");
            mealPlanHandoffService.generateFirstPlan(user.getId());
            return;
        }
        telegramOutboundService.sendMessage(chatId, "Гаразд, залишаю поточний список.");
    }
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.silporestockai.integration.ProfileReeditIntegrationTest"`
Expected: PASS.

**Step 5: Run the full suite**

Run: `make test`
Expected: all green (this closes out task 31's acceptance criterion 7).

**Step 6: Commit**

```bash
git add src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java \
        src/test/java/com/silporestockai/integration/ProfileReeditIntegrationTest.java
git commit -m "Ask before regenerating the list on a changed Анкета resubmission (task 31, criterion 7)"
```

---

### Task 5: `onboarding.js` — apply the full prefill in the browser

**Files:**
- Modify: `src/main/resources/static/webapp/onboarding.js` (the `applyPrefill()` function)

**Interfaces:**
- Consumes: the prefill JSON shape produced by `OnboardingFlowService.fullPrefillOf` (Task 2) — keys `adultMale`, `adultFemale`, `childrenAgeBrackets`, `restrictions`, `restrictionsOther`, `dietType`, `cookingTimePreference`, `weeklyBudget`, plus the pre-existing `householdSize` key used only by first-time onboarding's CORRECT-callback re-entry.
- Produces: nothing consumed elsewhere — this is a leaf, browser-only change with no Java-side test surface.

**No automated test possible for this step** — there is no JS test harness in this project (confirmed: no `package.json`/JS test runner exists under `src/main/resources/static/webapp/`), and a headless-browser test would be new infrastructure disproportionate to one function. Task 2's `tappingAnketaReopensTheFormPrefilledWithCurrentAnswers` test already proves the *server* encodes the right prefill JSON; this task is verified by a human opening the WebApp form in a real Telegram chat and confirming the fields actually populate. Add this to `docs/RUNBOOK.md`'s "Task 31" manual-verification section (do not mark this task's acceptance criterion "Done" from code alone):

```markdown
| Tap «🧾 Анкета» after onboarding | The WebApp form opens with every field already filled in — adults, kids' age brackets, allergy chips, diet-type and cooking-time selection, and the weekly budget — matching what onboarding originally collected |
```

**Step 1: Write the implementation directly (no failing test to write first — see note above)**

Replace `applyPrefill()` in `onboarding.js`:

```js
function applyPrefill() {
  const params = new URLSearchParams(window.location.search);
  const raw = params.get("prefill");
  if (!raw) return;
  try {
    const prefill = JSON.parse(atob(raw.replace(/-/g, "+").replace(/_/g, "/")));
    if (prefill.adultMale !== undefined || prefill.adultFemale !== undefined) {
      if (prefill.adultMale !== undefined) document.getElementById("adultMale").value = prefill.adultMale;
      if (prefill.adultFemale !== undefined) document.getElementById("adultFemale").value = prefill.adultFemale;
    } else if (prefill.householdSize) {
      document.getElementById("adultMale").value = Math.ceil(prefill.householdSize / 2);
      document.getElementById("adultFemale").value = Math.floor(prefill.householdSize / 2);
    }
    for (const bracket of prefill.childrenAgeBrackets || []) {
      addChildRow(bracket);
    }
    for (const value of prefill.restrictions || []) {
      const input = document.querySelector(`input[name="restriction"][value="${value}"]`);
      if (input) input.checked = true;
    }
    if (prefill.restrictionsOther) {
      document.getElementById("restrictionsOther").value = prefill.restrictionsOther;
    }
    if (prefill.dietType) {
      const input = document.querySelector(`input[name="dietType"][value="${prefill.dietType}"]`);
      if (input) input.checked = true;
    }
    if (prefill.cookingTimePreference) {
      const input = document.querySelector(`input[name="cookingTime"][value="${prefill.cookingTimePreference}"]`);
      if (input) input.checked = true;
    }
    if (prefill.weeklyBudget !== undefined && prefill.weeklyBudget !== null) {
      document.getElementById("weeklyBudget").value = prefill.weeklyBudget;
    }
  } catch (e) {
    // Malformed or absent prefill is not fatal — the form just starts blank.
    console.warn("could not apply onboarding prefill", e);
  }
}
```

Note: `addChildRow` is called before `document.getElementById("addChild").onclick = ...` is wired up two lines earlier in the file — check the actual line order when editing; `addChildRow` itself is a plain function declared above `applyPrefill()`'s call site already, so calling it from inside `applyPrefill()` is safe regardless of the click-handler wiring order.

**Step 2: Manual verification**

Follow the new `docs/RUNBOOK.md` row above in a real Telegram chat once this task's queue item reaches a live-verification checkpoint. Do not claim this step "tested" from the Java suite — it isn't.

**Step 3: Commit**

```bash
git add src/main/resources/static/webapp/onboarding.js docs/RUNBOOK.md
git commit -m "Prefill every Анкета field on reopen, not just household size"
```

---

## Self-Review Notes

- **Spec coverage:** every clause of acceptance criterion 7 ("reopens... pre-filled with current answers" → Tasks 2/5; "if the user changes anything... explicit confirm/cancel... never silently regenerates" → Tasks 3/4) has a task. The wider task 31 spec's other six criteria are already Done per the Notion page and out of scope here.
- **Type consistency:** `ProfileChange` (Task 3) is consumed by `handleReeditFormSubmission` only, matches across tasks. `CALLBACK_REEDIT_CONFIRM`/`CALLBACK_REEDIT_CANCEL` (Task 2) match the string literals asserted in Task 4's test and used in Task 3's `TelegramButton.callback(...)` calls.
- **Known open item, flagged rather than hidden:** Task 4, Step 3 flags that `OnboardingFlowService`'s constructor mechanism must be checked before adding `mealPlanHandoffService` as a field — the executor must read the top of the actual current file (it may have changed shape across Tasks 1-3) rather than assume Lombok's `@RequiredArgsConstructor` applies, since the excerpt seen while writing this plan did not show a constructor. If it turns out to be a hand-written constructor, add the parameter there instead of relying on annotation processing.
