# Cooking-time question first (task 38) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The "who are you at the kitchen" segmentation question (`cookingTimePreference`) is the first thing onboarding asks — in the WebApp form and in the manual-fallback chat chain — because it decides the whole downstream generation path (task 22's `READY_MEALS_ONLY` fork).

**Architecture:** Pure ordering change in the WebApp form (move one `<fieldset>` to the top, reword its legend as a segmentation question). The manual fallback chain never asked this question at all, so a `READY_MEALS_ONLY` household onboarded without the WebApp got the recipe planner — a real gap the acceptance criterion ("first question in both flows") closes: new `OnboardingStep.ASK_COOKING_TIME` with three inline buttons, asked before `ASK_HOUSEHOLD`, and `finish()` persists it on the fallback branch too. No schema change.

**Tech Stack:** Spring Boot, Telegram inline keyboards via `TelegramOutboundService`, `conversation_state.context_json`, MockMvc integration test with stub Telegram/MCP/Claude servers.

**Spec:** Notion task 38 (`3d27227d-ef1c-8100-b2e0-d997c2bd9781`); brainstorm decision in `docs/OVERNIGHT_QUESTIONS.md` → "Session 4 → Task 38".

## Global Constraints

- Copy is informal «ти», Ukrainian, no fluff (task 44).
- `OnboardingStep` names are stored in `conversation_state.current_step` by name — adding an enum value is safe; renaming is not.
- Nothing may be held in a field of a flow service — every answer goes through `context`.
- Run `make format` before every commit; targeted test class before commit, full `make test` at the end of the task.

---

### Task 1: WebApp form — cooking-time fieldset first

**Files:**
- Modify: `src/main/resources/static/webapp/onboarding.html:128-175`

No automated browser test exists for the form (the `prefill` round trip is covered by `ProfileReeditIntegrationTest`, which does not depend on field order). The payload built by `onboarding.js` reads inputs by id/name, so moving markup does not change it.

- [ ] **Step 1: Move the fieldset and reword the legend**

Replace the `<form>` body so the order is: cooking time → adults → children → restrictions → diet → budget. The cooking-time fieldset becomes:

```html
  <fieldset>
    <legend>Як у тебе з готуванням?</legend>
    <div class="chip-group">
      <label class="chip"><input class="chip-input" type="radio" name="cookingTime" value="COOKS_DAILY" checked> Готую потроху щодня</label>
      <label class="chip"><input class="chip-input" type="radio" name="cookingTime" value="COOKS_BATCH"> Готую наперед, раз на кілька днів</label>
      <label class="chip"><input class="chip-input" type="radio" name="cookingTime" value="READY_MEALS_ONLY"> Не готую — лише готова їжа</label>
    </div>
  </fieldset>
```

Everything else in the form stays byte-identical (ids, names, values), so `onboarding.js` and the prefill path are untouched.

- [ ] **Step 2: Sanity-check the JS still finds every input**

Run: `grep -o 'getElementById("[a-zA-Z]*")\|name="[a-zA-Z]*"' src/main/resources/static/webapp/onboarding.js | sort -u` and confirm each id/name still exists in the HTML (`grep -c 'id="adultMale"' …` etc.). Expected: all present.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/webapp/onboarding.html
git commit -m "Ask how the household cooks first, before who it is (task 38)"
```

---

### Task 2: Manual fallback — ask cooking time first, with buttons

**Files:**
- Modify: `src/main/java/com/silporestockai/model/OnboardingStep.java`
- Modify: `src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java`
- Test: `src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java`

**Interfaces:**
- Produces: `OnboardingStep.ASK_COOKING_TIME`; callback data `onb:cook:<CookingTimePreference name>` (constant `OnboardingFlowService.CALLBACK_COOKING_PREFIX = "onb:cook:"`).

- [ ] **Step 1: Write the failing tests**

In `OnboardingFlowIntegrationTest`, add:

```java
    @Test
    void theFallbackAsksHowTheHouseholdCooksBeforeAnythingElse() throws Exception {
        sendText(1, "привіт");
        tapButton(2, "onb:skip");
        sendText(3, "Заповнити вручну");

        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_COOKING_TIME.name());
        var keyboard = TELEGRAM.sentMessages().getLast().path("reply_markup").path("inline_keyboard");
        assertThat(keyboard.findValues("callback_data").stream().map(n -> n.asText()))
                .containsExactly("onb:cook:COOKS_DAILY", "onb:cook:COOKS_BATCH", "onb:cook:READY_MEALS_ONLY");

        // Text is not an answer here — the step stays, the buttons are pointed at again.
        sendText(4, "готую щодня");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_COOKING_TIME.name());

        tapButton(5, "onb:cook:READY_MEALS_ONLY");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_HOUSEHOLD.name());
        assertThat(lastMessageText()).contains("Скільки вас удома?");

        sendText(6, "2");
        sendText(7, "нема");
        sendText(8, "нема");
        sendText(9, "1500");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        assertThat(profile.getCookingTimePreference())
                .isEqualTo(com.silporestockai.model.CookingTimePreference.READY_MEALS_ONLY);
        assertThat(profile.getHouseholdSize()).isEqualTo(2);
    }
```

Then update every existing fallback test that walks the chain so it answers the new first question — insert `tapButton(N, "onb:cook:COOKS_DAILY")` right after `sendText(N, "Заповнити вручну")` and renumber the following update ids: `asksEverythingWhenTheUserSkipsConnecting`, `reAsksRatherThanStoringNonsense`, `resumesFromTheSavedStepAfterTheUserGoesSilent`, `correctionOverwritesADetectedField`, `anOnboardedUserIsNotOnboardedAgain`. Change the step assertions immediately after «Заповнити вручну» in `asksEverythingWhenTheUserSkipsConnecting`, `degradesToAskingWhenSilpoIsUnreachable` and `fallsBackToAskingWhenTheSnapshotHasNothingTheConfirmationScreenCanShow` from `ASK_HOUSEHOLD` to `ASK_COOKING_TIME`; in the last one, the `contains("Скільки вас удома?")` assertion moves after a `tapButton(4, "onb:cook:COOKS_DAILY")`.

- [ ] **Step 2: Run the test class to see the new test fail**

Run: `./gradlew test --tests 'com.silporestockai.integration.OnboardingFlowIntegrationTest'`
Expected: compile error on `OnboardingStep.ASK_COOKING_TIME` (fail).

- [ ] **Step 3: Add the enum value**

In `OnboardingStep`, before `ASK_HOUSEHOLD`:

```java
    /**
     * Asking how the household cooks — first, because it picks the whole planner path (task 22's ready-meals fork).
     * Buttons only; a typed answer is re-asked.
     */
    ASK_COOKING_TIME,
```

- [ ] **Step 4: Implement in `OnboardingFlowService`**

1. Constant next to the other callbacks: `public static final String CALLBACK_COOKING_PREFIX = "onb:cook:";`
2. Every `askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user)` entry into the fallback (in `presentWebAppForm` when the WebApp is not configured, and in `handleAnswer` on the `FALLBACK_LABEL` branch) becomes `askNext(chatId, OnboardingStep.ASK_COOKING_TIME, context, user)`.
3. `handleButton`: before the `log.debug("ignoring callback …")` line add

```java
        if (step == OnboardingStep.ASK_COOKING_TIME && data.startsWith(CALLBACK_COOKING_PREFIX)) {
            context.put(KEY_COOKING_TIME, CookingTimePreference.valueOf(data.substring(CALLBACK_COOKING_PREFIX.length())));
            askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user);
            return;
        }
```

4. `handleAnswer` switch: add `case ASK_COOKING_TIME -> askCookingTime(chatId);` (a typed answer re-shows the buttons, step unchanged — no `save` needed).
5. `askNext` switch: `case ASK_COOKING_TIME -> askCookingTime(chatId);`
6. `following`: `case ASK_COOKING_TIME -> OnboardingStep.ASK_HOUSEHOLD;`
7. `answered`: `case ASK_COOKING_TIME -> context.get(KEY_COOKING_TIME) != null;`
8. New private method:

```java
    private void askCookingTime(long chatId) {
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                "Спершу головне: як у тебе з готуванням?",
                List.of(
                        TelegramButton.callback("Готую потроху щодня", CALLBACK_COOKING_PREFIX + CookingTimePreference.COOKS_DAILY),
                        TelegramButton.callback("Готую наперед, раз на кілька днів", CALLBACK_COOKING_PREFIX + CookingTimePreference.COOKS_BATCH),
                        TelegramButton.callback("Не готую — лише готова їжа", CALLBACK_COOKING_PREFIX + CookingTimePreference.READY_MEALS_ONLY)));
    }
```

9. `finish()`: move `profile.setCookingTimePreference(cookingTimeOf(context));` out of the `if (adultMale != null || adultFemale != null)` branch so the fallback path persists it too.
10. Update the class Javadoc sentence about the fallback ("The budget is collected by the WebApp form itself …") to mention that the fallback asks cooking time first, by buttons.

- [ ] **Step 5: Run the test class**

Run: `./gradlew test --tests 'com.silporestockai.integration.OnboardingFlowIntegrationTest'`
Expected: all green (14 tests).

- [ ] **Step 6: Format and commit**

```bash
make format
git add src/main/java/com/silporestockai/model/OnboardingStep.java src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java
git commit -m "Ask the manual-fallback onboarding how the household cooks, first (task 38)"
```

---

### Task 3: Docs

**Files:**
- Modify: `docs/RUNBOOK.md:177-191` (section «4. Finish the profile»)
- Modify: `docs/OVERNIGHT_QUESTIONS.md` (append the task-38 decision)

- [ ] **Step 1: RUNBOOK** — add the first row to the table: «Спершу головне: як у тебе з готуванням?» → tap one of three buttons; fix the stale keyboard sentence to the current 2×2 menu (📝 Список / 🗓 Заплановані, 🧾 Анкета / ❓ Інструкція). Add `cooking_time_preference` to the verify SQL.
- [ ] **Step 2: OVERNIGHT_QUESTIONS** — record: fallback never asked cooking time (gap), buttons not text, legend reworded.
- [ ] **Step 3: Commit** `git commit -m "Document the cooking-time-first onboarding (task 38)"`

---

### Task 4: Full gate

- [ ] `make test` green, then Notion task 38 → **In review** with the live checklist (form order on a phone, fallback buttons) — the WebApp order is a visual check nobody but the user can make.
