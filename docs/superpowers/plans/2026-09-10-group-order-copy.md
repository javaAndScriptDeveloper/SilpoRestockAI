# Group order copy: a company order, not a drinking mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nothing the household reads frames the group feature as a drinking mode. It reads as one
capability — a shared order for a company, agreed by everyone — and it says plainly, in the moment it
matters, that drinks are all it resolves today.

**Architecture:** Copy only. Three strings are rewritten (`HelpContent.GROUP`,
`GroupEventMessageService.intro()`, `GroupEventMessageService.greeting()`), and honesty about scope gets
two moments it does not have today: a deterministic word list answers a participant who asks for food the
moment they ask, and one prompt rule makes the proposal's own note say the same for anything the word list
misses. No change to `GroupEventService`'s round mechanics, `GroupProposalService`, the data model, or what
gets resolved.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5 + AssertJ, Telegram Bot API, Claude structured output.

**Spec:** Notion task 74 — «Reframe group-event copy: general company order, not drinks-only positioning»
(`https://app.notion.com/p/3d67227def1c811cb9ced392223722a3`). Product feedback on the live «Інструкція»
line «зберу напої на всіх», which reads as «режим бухати».

## Global Constraints

- **The mechanism is not extended to food.** Task 68 scoped it to drinks on purpose — allergies and dietary
  restrictions in a group are a consent problem nobody has solved here — and reopening that is a separate
  product decision, not something a wording fix carries in. `prompts/group-drinks-system.txt` keeps its
  «Тільки напої … Жодної їжі, закусок, льоду, посуду» rule verbatim.
- Task 68's resolution logic must be untouched: `GroupEventIntegrationTest.fullRound` passes unchanged.
- ArchUnit: Telegram SDK types stay inside `controller.telegram` / `service.telegram`. The new word list is
  plain text with no SDK types, so it belongs in `utils`.
- `make format` before the commit; `make test` green.
- Decided with the user 2026-09-10: **both** honesty moments, the immediate ack and the prompt rule.

---

### Task 1: A word list that recognises a request for food

**Files:**
- Create: `src/main/java/com/silporestockai/utils/GroupOrderScope.java`
- Test: `src/test/java/com/silporestockai/unit/GroupOrderScopeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public static boolean asksForFood(String reply)` — true when a group reply names something to
  eat rather than drink. Task 2 calls it from `GroupEventService.collect` and `startRound`.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.utils.GroupOrderScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("a group reply that asks for something to eat, not something to drink")
class GroupOrderScopeTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "чіпси й пиво",
                "візьми шашлик",
                "мені щось поїсти",
                "закуски які-небудь",
                "піца",
                "торт на день народження",
                "сир і ковбаса до вина",
                "давайте м'ясо на мангал"
            })
    void thisIsAskingForFood(String reply) {
        assertThat(GroupOrderScope.asksForFood(reply)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "пиво світле",
                "червоне вино сухе",
                "не п'ю — сік",
                "сьогодні за кермом, вода",
                ".",
                "",
                "віскі, якщо є"
            })
    void thisIsAskingForADrink(String reply) {
        assertThat(GroupOrderScope.asksForFood(reply)).isFalse();
    }

    @Test
    void nothingAtAllIsNotAFoodRequest() {
        assertThat(GroupOrderScope.asksForFood(null)).isFalse();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GroupOrderScopeTest*'`
Expected: compile failure — `GroupOrderScope` does not exist.

- [ ] **Step 3: Write the class**

```java
package com.silporestockai.utils;

import java.util.List;
import java.util.Locale;

/**
 * Whether a group reply is asking for something to eat.
 *
 * <p>The group round resolves drinks and only drinks (task 68's own decision: what a company may eat is an
 * allergy question, and there is no way to take that consent in a group chat). Task 74 keeps the copy from
 * pretending otherwise — and the copy that matters most is the sentence said to the person who just typed
 * «чіпси», in the moment they typed it, rather than a scope note nobody reads.
 *
 * <p>Deterministic and cheap on purpose: this runs on every reply, and a model call to classify «пиво» would
 * be a second of latency for an answer a word already gives. It is not the only guard — the proposal prompt
 * says the same thing for whatever these words miss — so it is allowed to be incomplete, never wrong.
 */
public final class GroupOrderScope {

    private static final List<String> EATEN_NOT_DRUNK = List.of(
            "чіпс",
            "закус",
            "поїсти",
            "їсти",
            "їжа",
            "їжу",
            "шашлик",
            "мангал",
            "піц",
            "торт",
            "суші",
            "бургер",
            "сир",
            "ковбас",
            "м'яс",
            "мяс",
            "хліб",
            "салат",
            "снек",
            "горішк",
            "сухарик",
            "цукерк",
            "печив",
            "фрукт");

    private GroupOrderScope() {}

    /** True when the reply names something to eat. See the class note on why it may be incomplete. */
    public static boolean asksForFood(String reply) {
        String text = reply == null ? "" : reply.toLowerCase(Locale.ROOT);
        return EATEN_NOT_DRUNK.stream().anyMatch(text::contains);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests '*GroupOrderScopeTest*'`
Expected: PASS.

---

### Task 2: Every place this feature is described says «спільна закупка», and says what it buys

**Files:**
- Modify: `src/main/java/com/silporestockai/service/telegram/HelpContent.java` (the `GROUP` constant)
- Modify: `src/main/java/com/silporestockai/service/telegram/GroupEventMessageService.java`
  (`intro(boolean)`, `greeting(String)`, new `drinksOnlyAck(String, long)`)
- Modify: `src/main/java/com/silporestockai/service/GroupEventService.java` (`collect`)
- Modify: `src/main/resources/prompts/group-drinks-system.txt` (one added rule under `note`)
- Modify: `src/test/java/com/silporestockai/unit/HelpContentTest.java`
- Modify: `docs/RUNBOOK.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: `GroupOrderScope.asksForFood` from Task 1.
- Produces: `GroupEventMessageService.drinksOnlyAck(String name, long count)`.

- [ ] **Step 1: Write the failing copy test**

Extend `HelpContentTest.theButtonStillRendersTheWholeInstruction` with the new framing, and add:

```java
    /**
     * Task 74: the pitch is a shared company order, and «напої» appears only where it tells the truth about
     * what this resolves today — never in the headline that sells the feature.
     */
    @Test
    void theGroupPitchIsAnOrderForACompanyNotADrinkingMode() {
        String pitch = HelpContent.FULL.substring(HelpContent.FULL.indexOf("Компанією:"));
        String headline = pitch.substring(0, pitch.indexOf('\n') < 0 ? pitch.length() : pitch.indexOf('\n'));

        assertThat(headline).contains("спільну закупку").doesNotContain("напо");
        assertThat(pitch).contains("Поки що збираю напої");
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*HelpContentTest*'`
Expected: FAIL — the headline still says «зберу напої на всіх».

- [ ] **Step 3: Rewrite `HelpContent.GROUP`**

```java
    /**
     * Task 74: the headline sells the mechanism, which is a company agreeing on one order together — the
     * live copy said «зберу напої на всіх» and read back as «режим бухати», which undersold a consensus
     * feature as a party trick. What it currently resolves is a sentence of its own, because the scope is
     * real (task 68 stopped at drinks deliberately: what a company may eat is an allergy question, and a
     * group chat is not where that consent can be taken) and hiding it in the pitch would be the other kind
     * of dishonesty.
     */
    private static final String GROUP =
            """
            Компанією: додай мене в груповий чат — зберу спільну закупку на всіх за згодою кожного. Кожен пише \
            реплаєм, що йому взяти, організатор закриває список, я пропоную, усі тиснуть 👍 — і кошик у «Сільпо» \
            організатора. Поки що збираю напої; їжу на компанію — ще ні.""";
```

- [ ] **Step 4: Rewrite the two group-chat messages**

`intro(boolean seesEveryMessage)` — the tag line becomes:

```java
        String tag = "Коли треба зібрати спільну закупку на компанію — тегни мене й попроси: «@бот збери на "
                + "п'ятницю, бюджет 2000». Хто попросить, той і організатор. Далі читаю лише реплаї на свої "
                + "повідомлення й свої кнопки.";
```

`greeting(String organizerName)` — the first line becomes the general one and the scope moves into the
line that already tells people what to write:

```java
                Збираю спільну закупку на компанію — в кошик «Сільпо» організатора, оплата як зазвичай. \
                Поки що це напої: їжу на всіх ще не вмію.
```

Everything else in `greeting` stays byte-for-byte.

- [ ] **Step 5: Add the ack for a reply that asks for food**

In `GroupEventMessageService`:

```java
    /**
     * Task 74: said to the person who just asked for food, in the moment they asked. The alternative was
     * silence until the proposal arrived without their crisps in it, which reads as the bot ignoring them.
     */
    public String drinksOnlyAck(String name, long count) {
        return "Записав, %s. Відповіли: %d. Тільки скажу чесно: поки що я збираю на компанію лише напої — "
                .formatted(name, count) + "їжу доведеться взяти окремо.";
    }
```

In `GroupEventService.collect`, replace the ack call at the end:

```java
        upsertParticipant(event, text, body, false);
        long count = participantRepository.findByGroupEventId(event.getId()).size();
        // Task 74: the reply is kept either way — the model reads it and works around it — but a person who
        // asked for crisps is told now, not by the absence of crisps in the proposal.
        String ack = GroupOrderScope.asksForFood(body)
                ? messages.drinksOnlyAck(text.displayName(), count)
                : messages.replyAck(text.displayName(), count);
        telegramOutboundService.sendReply(text.chatId(), text.messageId(), ack);
```

Add `import com.silporestockai.utils.GroupOrderScope;`.

- [ ] **Step 6: One prompt rule for what the word list misses**

In `prompts/group-drinks-system.txt`, extend the `note` bullet (leave the «Тільки напої» rule above
untouched):

```
- note — одне речення для групи: що врахував і що варто перевірити. Якщо хтось просив їжу чи закуски —
  скажи в note прямо, що поки що збираєш на компанію тільки напої, і цього рядка не додавай.
```

- [ ] **Step 7: Run the copy and group tests**

Run: `./gradlew test --tests '*HelpContentTest*' --tests '*GroupEvent*' --tests '*GroupOrderScope*'`
Expected: PASS, `GroupEventIntegrationTest.fullRound` included and unchanged — the acceptance criterion
that no resolution logic moved.

- [ ] **Step 8: Update the two docs that quote the old phrasing**

`CLAUDE.md`'s group-chat invariant quotes «@bot збери напої…» as the one mention read. Any mention with no
round open opens one, so make the sentence say that and drop the drinks-specific example.
`docs/RUNBOOK.md`'s group section: the same, plus a row for the food ack.

- [ ] **Step 9: Format, full suite, live check, commit**

Live check drives a group round and types a food request into it; see the RUNBOOK's group section for the
synthetic-participant recipe.
