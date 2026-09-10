# Blackout mode: no cold chain, curated cart — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A blackout order never contains a product that needs a refrigerator, and reads as one coherent
meal you can eat with no power rather than a ten-line grocery grab.

**Architecture:** Two independent defects, two fixes. The curated list in `BlackoutModeService` asks for
«сир нарізаний» and «шинка нарізана» by name — that list is rewritten to six shelf-stable lines. Separately,
a search under a shelf-stable name still returns chilled products («паштет» has a chilled aisle, «сік» a
fresh-pressed one), so `MatchingHints` gains a `noColdChain` flag that `CartBuildingService.plausibleFor`
enforces on the candidate pool, alongside the `NOT_GROCERIES_FOR_PEOPLE` floor that already lives there.
The filter runs before the matcher sees anything: a refrigerated product in the pool is the bug, not a
refrigerated product in the answer.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5 + AssertJ, Testcontainers, Silpo MCP (`silpo_find_products_batch`).

**Spec:** Notion task 73 — «Blackout mode: hard-enforce no-fridge/no-cook constraint + curated small cart»
(`https://app.notion.com/p/3d67227def1c8145baecfe21cbb74beb`). Live repro: «світло вимкнули» produced a
10-line ₴1018.84 cart containing Сир Spomlek «Радамер» and Шинка Алан.

## Global Constraints

- Package layout, ArchUnit rules, Spotless (palantir) and Liquibase invariants per `CLAUDE.md`. No new
  entity, so no changeset.
- `make format` before any commit; `make test` must pass.
- Decided with the user 2026-09-10: **six lines, one or two of each**. The kit is deliberately allowed to
  land under Silpo's ₴799 minimum — the existing below-minimum path in `CartConfirmationService` says so
  honestly and offers the top-up. Do not inflate quantities to clear the minimum.
- Task 72's price sanity (`CartBuildingService.cheapestFirst`) is already in the shared resolve path and
  needs no blackout-specific copy.
- Out of scope: task 22's search-first architecture, task 72's hangover kit.

---

### Task 1: Find out whether Silpo's catalog says a product needs cold storage

**Files:**
- Modify (temporarily, reverted at the end of this task): `src/main/java/com/silporestockai/service/CartBuildingService.java`

**Interfaces:**
- Consumes: nothing.
- Produces: an answer that decides Task 3's filter — either a field name to add to `McpResponses`, or the
  finding that no such field exists and a name-marker list is the only option.

The task page asks for this explicitly: *"verify live what's actually available in the product data before
assuming a specific field exists"*. `McpResponses` today knows `name`, `price`, `oldPrice`, `displayRatio`,
`weighted`, `available`, `stock`, `productId` — nothing about storage or category. That is what this
codebase reads, not proof of what the server sends.

- [ ] **Step 1: Add a throwaway probe to the search result handling**

In `CartBuildingService.plausibleFor`, immediately inside the `for (JsonNode candidate : availableOnly(candidates))`
loop, before any other statement:

```java
// TEMPORARY (task 73 probe) — remove before commit.
log.warn("PROBE candidate fields for «{}»: {}", requestedName, candidate.toPrettyString());
```

- [ ] **Step 2: Restart the running app so the probe is live**

The app runs from `make run` and shares `build/classes` with the test suite, so stop it first. Write the
restart into a script file and run the file in a *separate* tool call — a `pkill` pattern typed on the
running shell's own command line kills that shell.

```bash
cat > /tmp/claude-1000/-home-vampir-petProjects-silpoRestockAI/*/scratchpad/restart.sh <<'EOF'
pkill -f 'com.silporestockai.Application'
pkill -f 'gradle-wrapper.jar bootRun'
EOF
```

Then, in a later call: run the script, wait for :8080 to free, `setsid nohup make run &`, and poll
`http://localhost:8080/actuator/health` until it answers `UP`.

- [ ] **Step 3: Fire one live blackout and read the probe**

```bash
set -a && . ./.env && set +a
curl -X POST http://localhost:8080/telegram/webhook \
  -H "Content-Type: application/json" \
  -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
  -d '{"update_id":973001,"message":{"message_id":973001,"date":1,"chat":{"id":218196255,"type":"private"},"from":{"id":218196255,"is_bot":false,"first_name":"Тест"},"text":"/blackout"}}'
```

Read `grep 'PROBE candidate fields' logs/app.log | head -3`. Record every key the live product object
carries. This output is also the **before** half of the acceptance evidence — save the resolved cart's
lines and total.

- [ ] **Step 4: Decide the filter's input and revert the probe**

- If a storage/category/temperature field exists: add it to `McpResponses` and Task 3's filter reads it
  *and* keeps the name markers as a floor (a null field must not open the gate).
- If no such field exists: the name-marker list in Task 3 stands as written.

Revert the probe line. Nothing from this task is committed.

- [x] **Step 5: Record the finding**

**Answer (live, 2026-09-10 17:41, branch `1edddb40-e664-609c-a1a7-f9004aa8afa6`):** no such field exists.
A product from `silpo_find_products_batch` carries exactly:

```json
{"id":"1ed1ba8b-…","name":"Вода питна «Природне джерело» негазована","slug":"voda-pytna-…-561533",
 "price":15.99,"oldPrice":null,"stock":11,"available":true,"image":"https://images.silpo.ua/…",
 "weighted":false,"step":1,"displayRatio":"0,5л","specialPrices":null,"companyId":"1ec88c5d-…",
 "branchId":"1edddb40-…","externalProductId":561533}
```

No category, no storage class, no temperature, no shelf. The name-marker list in Task 3 is the only
option, and `slug` is only the name transliterated — nothing extra to read.

**Before figures for the acceptance evidence** (same run, `/blackout`): 11 lines, goods ₴778.77, ₴20.23
short of the ₴799 minimum. Сир Spomlek «Радамер» нарізка ₴84.90 and Шинка Алан Куряча в/к, нарізка ₴69.99
were both in the bag, alongside Горіх волоський ₴129.00, Банан ₴85.99 and Яблуко ₴29.99.

---

### Task 2: `MatchingHints` carries a no-cold-chain constraint

**Files:**
- Modify: `src/main/java/com/silporestockai/model/MatchingHints.java`
- Modify: `src/main/java/com/silporestockai/service/AdHocOrderService.java:151`
- Test: `src/test/java/com/silporestockai/unit/MatchingHintsTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `MatchingHints(String personsWords, Map<String, List<String>> alsoSearch, boolean noColdChain)`;
  `MatchingHints.NONE` (unchanged meaning, `noColdChain == false`); `MatchingHints.ofWords(String)`;
  new `MatchingHints.noColdChain()` returning `new MatchingHints(null, Map.of(), true)`.
  Task 3 reads `hints.noColdChain()`; Task 4 calls `MatchingHints.noColdChain()`.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.MatchingHints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("what the product choice knows about a cart beyond its lines")
class MatchingHintsTest {

    @Test
    void aWeeklyPlanConstrainsNothing() {
        assertThat(MatchingHints.NONE.noColdChain()).isFalse();
        assertThat(MatchingHints.ofWords("привези води").noColdChain()).isFalse();
    }

    @Test
    void aBlackoutCartRefusesTheColdChain() {
        MatchingHints hints = MatchingHints.noColdChain();

        assertThat(hints.noColdChain()).isTrue();
        assertThat(hints.personsWords()).isNull();
        assertThat(hints.alsoSearchFor("хліб")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*MatchingHintsTest*'`
Expected: compile failure — `noColdChain()` does not exist.

- [ ] **Step 3: Add the component**

In `MatchingHints`, add `boolean noColdChain` as the third record component, keep the compact constructor,
and add:

```java
    /** Nothing known: a weekly plan, a baseline reorder — every line is exactly its own name. */
    public static final MatchingHints NONE = new MatchingHints(null, Map.of(), false);

    /** Only the person's own sentence, for a cart whose lines already say what to search for. */
    public static MatchingHints ofWords(String personsWords) {
        return new MatchingHints(personsWords, Map.of(), false);
    }

    /**
     * A cart for a household with no working refrigerator (task 73). Not a preference the matcher weighs:
     * a chilled product must never reach the candidate pool, because the mode's whole promise is that
     * nothing in the bag needs cold — see {@code CartBuildingService.needsAFridge}.
     */
    public static MatchingHints noColdChain() {
        return new MatchingHints(null, Map.of(), true);
    }
```

Update the javadoc's bullet list with a third bullet for `noColdChain`. Fix `AdHocOrderService:151` to
`new MatchingHints(personsWords, alsoSearch, false)`.

- [ ] **Step 4: Run the test and the callers**

Run: `./gradlew test --tests '*MatchingHintsTest*' --tests '*AdHoc*'`
Expected: PASS.

- [ ] **Step 5: No commit yet** — Task 3 makes the flag mean something. Commit at the end of Task 4.

---

### Task 3: The candidate pool refuses anything that needs a fridge

**Files:**
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java` (`plausibleFor`,
  `candidatesUnder`, the `resolve` loop at ~1118-1124)
- Test: `src/test/java/com/silporestockai/unit/CartBuildingColdChainTest.java` (create)

**Interfaces:**
- Consumes: `MatchingHints.noColdChain()` from Task 2.
- Produces: `static boolean needsAFridge(String productName)` (package-private, so the unit test can read
  it without a Spring context); `plausibleFor(ShoppingListItem, List<JsonNode>, boolean noColdChain)`;
  `candidatesUnder(ShoppingListItem, List<String>, Map<String, List<JsonNode>>, boolean noColdChain)`.

The markers below are names, not categories, because Silpo's search is a plain text match and the product
name is the only field every hit is guaranteed to carry. Stems, not whole words: «сир» must catch
«Сир Spomlek Радамер нарізка», and Ukrainian declension makes «ковбас» the only safe form of «ковбаса».

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.service.CartBuildingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("a blackout cart is built from a shelf, not from a fridge")
class CartBuildingColdChainTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Сир Spomlek «Радамер» нарізка 150г",
                "Шинка Алан Куряча в/к, нарізка",
                "Ковбаса Глобино Сервелат в/к",
                "Молоко Яготинське 2.6%",
                "Йогурт Активіа питний",
                "Сметана Президент 20%",
                "Масло вершкове Селянське",
                "Морозиво Ласунка пломбір",
                "Пельмені Геркулес з м'ясом",
                "Салат Олів'є ваговий, охолоджений",
                "Паштет Delikat з печінки, охолоджений"
            })
    void theseNeverEnterTheCandidatePool(String name) {
        assertThat(CartBuildingService.needsAFridge(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Консерви рибні Аквамарин Тунець у власному соку",
                "Паштет Онісс печінковий, консерва 100г",
                "Хліб Київхліб Український нарізний",
                "Вода питна негазована Моршинська 1.5л",
                "Сік Sandora яблучний 0.95л",
                "Печиво Roshen Марія вершкове",
                "Сухарики Флінт з беконом"
            })
    void theseAreShelfStableAndStay(String name) {
        assertThat(CartBuildingService.needsAFridge(name)).isFalse();
    }
}
```

The last two are controls against an over-broad list, and both cost a marker: «вершкове» is a flavour on a
shelf-stable biscuit, so the marker is «вершки» and not the stem «вершк»; «з беконом» is a flavour on a
shelf-stable crouton, so «бекон» is not a marker at all. A blackout line never searches either word, and a
floor that refuses the wrong things is a floor that will be turned off.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*CartBuildingColdChainTest*'`
Expected: compile failure — `needsAFridge` does not exist.

- [ ] **Step 3: Add the marker list and the predicate**

In `CartBuildingService`, next to `NOT_GROCERIES_FOR_PEOPLE`:

```java
    /**
     * Name fragments that mark a product as one the household cannot keep during an outage.
     *
     * <p>Task 73's live repro: «світло вимкнули» came back with Сир Spomlek «Радамер» and Шинка Алан in the
     * bag. Both were asked for by name by the curated list of the day, but the deeper problem is that a
     * shelf-stable line still reaches a chilled shelf — «паштет» has a refrigerated aisle and so does «сік»
     * — and a mode whose whole promise is "nothing here needs a fridge" cannot keep that promise by asking
     * the matcher nicely. So this is a floor under the pool, like {@link #NOT_GROCERIES_FOR_PEOPLE}: a
     * refrigerated candidate the matcher never sees is one it can never pick.
     *
     * <p>Stems rather than whole words, because Ukrainian declines: «ковбас» covers ковбаса/ковбаси/
     * ковбасні. Frozen goods are here too — a freezer without power is a fridge without power, only worse.
     */
    private static final List<String> NEEDS_A_FRIDGE = List.of(
            "сир",
            "шинка",
            "ковбас",
            "сосиск",
            "сардельк",
            "салямі",
            "молоко",
            "кефір",
            "йогурт",
            "ряжанк",
            "сметан",
            "вершки",
            "масло вершкове",
            "творог",
            "морозиво",
            "пельмен",
            "вареник",
            "напівфабрикат",
            "заморож",
            "охолодж");

    /**
     * Whether this product has to be kept cold — by name, because that is the only field every catalog hit
     * carries. See {@link #NEEDS_A_FRIDGE}.
     */
    public static boolean needsAFridge(String productName) {
        String name = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        return NEEDS_A_FRIDGE.stream().anyMatch(name::contains);
    }
```

Then thread the flag. `plausibleFor` gains a `boolean noColdChain` parameter and, inside the candidate
loop after the `NOT_GROCERIES_FOR_PEOPLE` check:

```java
            if (noColdChain && needsAFridge(name)) {
                log.info("dropping «{}» as a candidate for «{}»: it needs a fridge", name, requestedName);
                continue;
            }
```

`candidatesUnder` gains the same parameter and passes it to `plausibleFor`. The call site in `resolve`
(~line 1122) becomes `candidatesUnder(item, names, productsByQuery, hints.noColdChain())`.

There is deliberately **no** "unless the line itself asked for it" exemption, unlike
`NOT_GROCERIES_FOR_PEOPLE`: under this flag there is no working fridge, so a line that asks for cheese is
a line that cannot be filled.

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests '*CartBuildingColdChainTest*'`
Expected: PASS.

- [ ] **Step 5: Run the whole suite for the signature change**

Run: `./gradlew test`
Expected: PASS except `BlackoutModeIntegrationTest`, which Task 4 rewrites.

---

### Task 4: The blackout kit is six shelf-stable lines

**Files:**
- Modify: `src/main/java/com/silporestockai/service/BlackoutModeService.java:35-56`
- Modify: `src/test/java/com/silporestockai/integration/BlackoutModeIntegrationTest.java:186-196`
- Modify: `docs/RUNBOOK.md` (section «15. Blackout mode»)

**Interfaces:**
- Consumes: `MatchingHints.noColdChain()` (Task 2), the pool filter (Task 3).
- Produces: nothing further tasks depend on.

The list drops сир, шинка, горіхи, яблука and банани. The first two are the repro; the last three are the
grab-bag — an outage kit is a meal, and fruit and nuts alongside tinned fish and biscuits is a shop, not a
meal. What is left is one plate (bread + tinned fish or pâté), one drink (water, juice) and one sweet.

- [ ] **Step 1: Rewrite the failing integration test first**

Replace `searchesOnlyForThingsThatNeedNoStoveOrFridge` in `BlackoutModeIntegrationTest`:

```java
    @Test
    void searchesOnlyForThingsThatNeedNoStoveOrFridge() throws Exception {
        sendText(1, "/blackout");

        JsonNode search = MCP.callArguments("silpo_find_products_batch").getFirst();
        List<String> searched = new ArrayList<>();
        search.path("products").forEach(term -> searched.add(term.asText()));

        assertThat(searched).containsExactlyInAnyOrder(
                "вода питна негазована", "сік", "хліб", "консерви рибні", "паштет консервований", "печиво");
        // Task 73's live repro: cheese and ham were in the bag of a household with no power.
        assertThat(searched).doesNotContain("сир нарізаний", "шинка нарізана");
        assertThat(searched).noneMatch(term -> CartBuildingService.needsAFridge(term));
    }
```

Add `import com.silporestockai.service.CartBuildingService;`.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*BlackoutModeIntegrationTest*'`
Expected: FAIL — the searched terms still contain «сир нарізаний» and «шинка нарізана».

- [ ] **Step 3: Rewrite the curated list**

In `BlackoutModeService`, replace `NO_COOKING_NEEDED` and its javadoc:

```java
    /**
     * What a household can eat during an outage, written down rather than inferred.
     *
     * <p>Silpo's product data carries no "needs no cooking" flag, and guessing one from a product name is how
     * a demo ends up ordering frozen dumplings during a blackout.
     *
     * <p>Six lines, and each one earns its place in a single meal: bread with tinned fish or pâté, water and
     * juice to drink it with, biscuits after. The list this replaced had eleven, and a live «світло вимкнули»
     * turned them into a ₴1018 shop that contained sliced cheese and sliced ham — refrigerated, in a
     * household whose whole problem is that the fridge is off (task 73). Nuts, apples and bananas went with
     * them: they keep perfectly well, but a bag holding tinned fish, fruit and biscuits is a grocery run, not
     * an answer to "the power is out."
     *
     * <p>One or two of each, which puts the kit under Silpo's ₴799 minimum on purpose. The confirmation
     * already says so and offers the top-up; padding an emergency order to clear a delivery threshold is the
     * shop's problem to state, not the agent's to hide.
     */
    private static final List<BlackoutLine> NO_COOKING_NEEDED = List.of(
            new BlackoutLine("вода питна негазована", "2", "шт"),
            new BlackoutLine("сік", "1", "шт"),
            new BlackoutLine("хліб", "1", "шт"),
            new BlackoutLine("консерви рибні", "2", "шт"),
            new BlackoutLine("паштет консервований", "2", "шт"),
            new BlackoutLine("печиво", "1", "шт"));
```

`паштет` becomes `паштет консервований` deliberately: the bare word is the one that reaches a chilled
aisle, and naming the tin is cheaper than relying on the filter alone.

- [ ] **Step 4: Pass the constraint down**

In `buildBlackoutOrder`:

```java
    /** Builds the emergency cart and puts it through the usual confirmation. Ad-hoc: the baseline is untouched. */
    public void buildBlackoutOrder(User user, OrderTrigger trigger) {
        log.info("building a blackout order for user {}", user.getId());
        cartConfirmationService.present(
                user, items(user.getId()), OrderType.AD_HOC, false, trigger, MatchingHints.noColdChain());
    }
```

Add `import com.silporestockai.model.MatchingHints;`. Confirm `CartConfirmationService.present` already has
the six-argument overload taking `MatchingHints` (it does — `CartConfirmationService:125`).

- [ ] **Step 5: Run the blackout tests**

Run: `./gradlew test --tests '*BlackoutMode*'`
Expected: PASS.

- [ ] **Step 6: Update the runbook**

In `docs/RUNBOOK.md` under «15. Blackout mode», replace the expectation with the six lines, note that the
cart is expected to come back under ₴799 with the top-up offer, and add a check: no line in the cart is a
product `needsAFridge` would refuse.

- [ ] **Step 7: Format, full suite, commit**

```bash
make format
./gradlew test
git add -A
git commit -m "Keep the fridge out of a cart for a household with no power

Blackout mode asked for sliced cheese and sliced ham by name, and a live
«світло вимкнули» duly delivered both in a ten-line ₴1018 bag. The mode's
promise is that nothing in it needs cooking or cold; cheese and ham break
the second half of that outright.

The curated list is now six lines that make one meal — bread, tinned fish,
pâté, water, juice, biscuits — and a household with no fridge is a property
of the cart, not of the list: MatchingHints carries noColdChain, and the
candidate pool drops anything chilled or frozen before the matcher is shown
it. A search for «паштет» reaches a refrigerated aisle even when the line is
shelf-stable, and a preference the model weighs is not a promise.

The kit lands under Silpo's ₴799 minimum on purpose. The confirmation says
so and offers the top-up.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Prove it against the live catalog

**Files:**
- Modify: `docs/RUNBOOK.md` (add a `### Task 73:` live-check entry)

**Interfaces:**
- Consumes: everything above.
- Produces: the acceptance evidence the task page asks for.

- [ ] **Step 1: Restart the app on the new build**

Same script-file discipline as Task 1 Step 2.

- [ ] **Step 2: Send the real phrase, not the command**

The repro was the sentence, which goes through `IntentRouterService`, not `/blackout`:

```bash
set -a && . ./.env && set +a
curl -X POST http://localhost:8080/telegram/webhook \
  -H "Content-Type: application/json" \
  -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
  -d '{"update_id":973010,"message":{"message_id":973010,"date":1,"chat":{"id":218196255,"type":"private"},"from":{"id":218196255,"is_bot":false,"first_name":"Тест"},"text":"світло вимкнули"}}'
```

Reset `conversation_state.current_flow` to `NONE` first if a previous run left the chat in
`CART_CONFIRMATION`.

- [ ] **Step 3: Read the answer, not the code**

```bash
grep 'Telegram -> chat' logs/app.log | tail -3
grep 'it needs a fridge' logs/app.log
grep '«.*» -> ' logs/app.log | tail -10
```

Check, against the task's acceptance criteria: six lines or fewer resolved; not one of them a product
`needsAFridge` refuses (сир and шинка specifically); the total is an emergency purchase, not a weekly shop.

- [ ] **Step 4: Write down what actually happened**

Add the cart — every line, its price, the total — to `docs/RUNBOOK.md` under `### Task 73:`, next to the
before figures captured in Task 1. Commit the runbook with the evidence.
