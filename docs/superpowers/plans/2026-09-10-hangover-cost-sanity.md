# Hangover-relief cost sanity (task 72) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A hangover-relief cart buys the cheap standard remedies (регідрон, активоване вугілля, ordinary
domestic water) instead of the premium imported ones (Elekta Mix ₴309 ×2, Evian ₴99 ×2, ₴1034 total on a
live run 2026-09-09), without removing the household's right to name a brand.

**Architecture:** Two layers, and the second is shared with every other quick-order flow (#24 ad-hoc,
#36 dish ingredients, #19/#52 blackout), all of which reach the catalog through
`CartBuildingService.resolve()` → `ProductMatchingService.choose()`:

1. *Candidate pool* — the hangover kit's own line names are the premium categories («ізотонік»,
   «сорбент»), so the cheap staples never enter the pool at all. Renaming the lines to the staples puts
   them in; the existing second pass (`alternativeTerms`) covers a branch that stocks neither.
2. *Ranking* — candidates reach the matcher in Silpo's own order, which is premium-first, and the
   prompt's "не преміальний" guidance is prose at the bottom with no water or pharmacy example. Showing
   the capped candidate list **cheapest-first** and naming the rule explicitly fixes the bias for every
   flow at once.
3. *Explicit brand* — the hangover flow drops the person's own sentence. Carrying it to the matcher as a
   per-line note lets a named brand beat the cheap default, judged where the candidates are.

**Tech Stack:** Java 25 / Spring Boot 4, JUnit 5 + AssertJ integration tests on Testcontainers Postgres,
`StubMcpServer` and `StubAnthropicServer` for the Silpo and Claude sides, Spotless (palantir).

**Spec:** Notion task «72. Hangover-relief cost sanity: prefer cheap staples over premium imported
brands» (`3d67227d-ef1c-8166-b00f-d02c5cda095c`); this file carries its acceptance criteria verbatim
below.

## Global Constraints

- Acceptance criteria (from the task): cheap staple items instead of Evian/Elekta Mix, verified against
  real live search results; total consistent with a quick emergency purchase, not >₴1000 for three basic
  items; an explicitly named brand still wins; the fix lands in the shared resolver, not in #32 alone.
- Out of scope: no change to task 22's search-first architecture; the blackout correctness bug is #73.
- `make format` before committing; ArchUnit forbids field injection and non-`Service`-suffixed classes in
  `service`; every new cache name must be in `application.yml` (none added here).
- Ukrainian user-facing copy; comments and commit messages in English, explaining *why*.

---

### Task 1: Show the matcher its candidates cheapest-first

**Files:**
- Modify: `src/main/java/com/silporestockai/service/ProductMatchingService.java` (make
  `MAX_CANDIDATES_SHOWN` visible as `public static final int`)
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java` (`plausibleFor`, and the
  second pass's union, order the kept candidates by price)
- Test: `src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `ProductMatchingService.MAX_CANDIDATES_SHOWN` (int, 15) and a private static
  `List<JsonNode> cheapestFirst(List<JsonNode> candidates)` in `CartBuildingService`.

- [ ] **Step 1: Write the failing test**

In `CartBuildingIntegrationTest`, a line whose Silpo answer ranks a premium product first:

```java
    /**
     * Task 72: Silpo ranks a ₴309 imported electrolyte drink above a ₴32 rehydration sachet, and the
     * matcher was shown that order. The live hangover cart came back at ₴1034 for three basic items.
     * The candidate list the matcher reads is now cheapest-first — Silpo's ranking is relevance, not
     * "the ordinary version of this thing".
     */
    @Test
    void showsTheMatcherItsCandidatesCheapestFirst() {
        UUID userId = connectedUser(8433L);
        scriptCartTools();
        MCP.respondToTool("silpo_find_products_batch", """
                {"queries":[{"query":"регідрон","products":[\
                {"name":"Напій розчинний Elekta Mix 8 з електролітами","productId":"p-1","price":309,\
                "displayRatio":"20г","stock":10,"available":true},\
                {"name":"Регідрон Оптім порошок","productId":"p-2","price":32,\
                "displayRatio":"18.9г","stock":10,"available":true}]}]}""");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        scriptVerifiedCart();

        cartBuildingService.buildCart(userId, List.of(item("регідрон", "1", "шт")));

        String prompt = CLAUDE.requests().getFirst().toString();
        assertThat(prompt.indexOf("Регідрон Оптім")).isLessThan(prompt.indexOf("Elekta Mix"));
    }
```

The class needs the Anthropic stub if it has none yet — copy the `StubAnthropicServer` block
(`startClaude()`, `@DynamicPropertySource`, `@AfterAll`, `reset()`) from
`ProductMatchingIntegrationTest`, and stub a choice with
`CLAUDE.respondWithText("{\"choices\":[{\"lineIndex\":0,\"candidateIndex\":0,\"reason\":\"дешевший\"}]}")`.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*CartBuildingIntegrationTest.showsTheMatcherItsCandidatesCheapestFirst'`
Expected: FAIL — Elekta Mix is printed first, so the two `indexOf` values are the wrong way round.
(Stop `make run` first: the app and the test share `build/classes`.)

- [ ] **Step 3: Implement**

In `ProductMatchingService`, widen the constant so the cap is applied in one place only:

```java
    public static final int MAX_CANDIDATES_SHOWN = 15;
```

In `CartBuildingService`, return the kept candidates ordered by price, capped at what the matcher will
actually read:

```java
    /**
     * The candidates the matcher reads, cheapest first.
     *
     * <p>Silpo's ranking is relevance, and relevance puts a ₴309 imported electrolyte drink above a ₴32
     * rehydration sachet and Evian above Моршинська. The prompt has said "prefer the ordinary one" since
     * task 49 and the fast model still took the premium line often enough to bring a hangover cart to
     * ₴1034. Order is not judgement, so it is settled here: the cap keeps Silpo's own relevance (the
     * fifteen it thinks likeliest), and within that window price decides what is read first. A candidate
     * with no price at all sorts last — unknown is not cheap.
     */
    private static List<JsonNode> cheapestFirst(List<JsonNode> candidates) {
        return candidates.stream()
                .limit(ProductMatchingService.MAX_CANDIDATES_SHOWN)
                .sorted(java.util.Comparator.comparing(candidate -> McpResponses.findNumber(candidate, McpResponses.PRICE)
                        .orElse(new BigDecimal("999999"))))
                .toList();
    }
```

Return `cheapestFirst(kept)` from `plausibleFor`, and apply it to the second pass's `union` before it is
added to `candidatesFor` (`candidatesFor.add(cheapestFirst(union))`).

- [ ] **Step 4: Run the test and the neighbours**

Run: `./gradlew test --tests '*CartBuildingIntegrationTest' --tests '*CartSecondPassIntegrationTest'
--tests '*ProductMatchingIntegrationTest' --tests '*ReadyMealsSearchFirstIntegrationTest'`
Expected: PASS. A sibling test that asserted a candidate index is now asserting against a re-ordered
list — fix the expectation, not the ordering.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit  # message written at the end of the task, see Task 5
```

---

### Task 2: Name the cheap staples in the matcher prompt

**Files:**
- Modify: `src/main/resources/prompts/product-match-system.txt`
- Modify: `src/main/resources/prompts/search-terms-system.txt`
- Test: `src/test/java/com/silporestockai/integration/ProductMatchingIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1's cheapest-first ordering (the prompt rule and the ordering say the same thing).
- Produces: nothing other code calls.

- [ ] **Step 1: Write the failing test**

```java
    /**
     * Task 72: «Вода мінеральна» came back as Evian at ₴99 a bottle on a live hangover cart while
     * Моршинська sat in the same candidate list at ₴25. A bare line is the ordinary domestic product.
     */
    @Test
    void takesOrdinaryDomesticWaterOverAnImportedPremiumOne() {
        CLAUDE.respondWithText("{\"choices\":[{\"lineIndex\":0,\"candidateIndex\":0,\"reason\":\"звичайна\"}]}");

        List<Integer> chosen = productMatchingService.choose(List.of(new ProductMatchRequest(
                "вода мінеральна",
                BigDecimal.valueOf(2),
                "шт",
                List.of(
                        packaged("Вода мінеральна Моршинська негазована", "25.99", "1.5л", "40"),
                        packaged("Вода мінеральна Evian негазована", "99.00", "0.5л", "12")))));

        assertThat(chosen).containsExactly(0);
        String prompt = CLAUDE.requests().getFirst().toString();
        assertThat(prompt).contains("Evian");
    }
```

(The stub answers whatever it is told to; what this test locks is that the water line reaches the model
with both candidates and that the prompt file this service loads still parses. The real rule is the
prompt text, asserted in the next step's test.)

Add a second, sharper test that the prompt itself carries the rules the live failure needed:

```java
    /** Task 72: the rules the ₴1034 hangover cart needed are in the prompt the matcher actually loads. */
    @Test
    void theMatcherPromptNamesTheCheapStaplesAndTheBrandOverride() {
        CLAUDE.respondWithText("{\"choices\":[]}");

        productMatchingService.choose(List.of(new ProductMatchRequest(
                "регідрон", BigDecimal.ONE, "шт", List.of(packaged("Регідрон", "32", "18.9г", "5")))));

        String system = CLAUDE.requests().getFirst().path("system").toString();
        assertThat(system)
                .contains("регідрон")
                .contains("активоване вугілля")
                .contains("найдешевший");
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*ProductMatchingIntegrationTest'`
Expected: FAIL — the prompt has no «активоване вугілля» and no «найдешевший» rule.

- [ ] **Step 3: Implement**

In `product-match-system.txt`, under «Типові пастки», replace the isotonic bullet with one that names the
cheap staples, and add a water bullet:

```
- «Регідрон», «Ізотонік», «електроліти» — це засіб для регідратації. Звичайний аптечний регідрон
  (порошок у пакетиках, 20–60 грн) — саме те, що треба; імпортний преміальний напій з електролітами
  (Elekta Mix та подібні, 250–350 грн за пачку) — те саме за вдесятеро дорожче, тож бери дешевший.
  Енергетик (Burn, Red Bull, Monster, Non Stop), солодка газована вода чи «вітамінна вода» — не заміна:
  людині з похмілля енергетик шкодить, тож якщо серед кандидатів лише вони — відповідь -1.
- «Активоване вугілля», «сорбент» — класичний дешевий сорбент (вугілля в таблетках, 20–50 грн).
  Ентеросорбент-гель (Атоксіл, Ентеросгель) підходить теж, але якщо поруч є вугілля — бери вугілля.
- «Вода мінеральна», «вода» без уточнення — звичайна вітчизняна вода (Моршинська, Трускавецька,
  Миргородська, «Знаменівська», власна марка). Імпортна преміальна (Evian, Perrier, San Pellegrino)
  коштує вчетверо більше за ту саму воду — бери її лише тоді, коли людина назвала її сама.
```

And in «Як обирати серед тих, що підходять», make the price rule the first one, stated as a rule rather
than as an aside:

```
- серед придатних кандидатів бери НАЙДЕШЕВШИЙ, якщо людина не назвала бренд, сорт, розмір чи «преміум».
  Кандидати вже відсортовані за ціною — перший придатний зазвичай і є відповіддю. Дорожчий варіант
  виправданий лише тоді, коли дешевші не підходять (не той продукт, не той розмір, мало на складі);
```

In `search-terms-system.txt`, add the pharmacy staples to the examples so a branch with no регідрон and
no вугілля still gets a second pass:

```
- «Регідрон» → «електроліти», «ізотонік»;
- «Активоване вугілля» → «сорбент», «ентеросорбент».
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*ProductMatchingIntegrationTest'`
Expected: PASS.

- [ ] **Step 5: Commit** (folded into Task 5's single commit)

---

### Task 3: A hangover kit made of cheap staples

**Files:**
- Modify: `src/main/java/com/silporestockai/service/AdHocOrderService.java` (`HANGOVER_RELIEF_LINES`)
- Test: `src/test/java/com/silporestockai/integration/AdHocOrderIntegrationTest.java:365`

**Interfaces:**
- Consumes: nothing.
- Produces: the searched terms `вода мінеральна`, `регідрон`, `активоване вугілля`.

- [ ] **Step 1: Change the failing assertion into the one we want**

`hangoverReliefSearchesOneTermPerNeedWithSensibleQuantities` currently asserts
`containsExactly("вода мінеральна", "ізотонік", "сорбент")`. Make it:

```java
        assertThat(searchedTerms()).containsExactly("вода мінеральна", "регідрон", "активоване вугілля");
```

and update the two fixtures that answer «ізотонік»/«сорбент» (lines ~215 and the unfound-line test at
~405) to the new terms, keeping one of them empty so the "honest about what was not found" test still
tests that.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*AdHocOrderIntegrationTest'`
Expected: FAIL — «ізотонік» is still searched.

- [ ] **Step 3: Implement**

```java
    /**
     * Task 32: one line per thing a hangover kit needs, not one per word for it. Task 72: named by the
     * cheap staple rather than by the category.
     *
     * <p>«Ізотонік» and «сорбент» are category words, and the catalog answers a category word with its
     * dearest members: a live cart came back with Elekta Mix at ₴309 a pack (twice), Atoxil gel and
     * Evian — ₴1034 for a hangover. Регідрон is the same rehydration at ₴32 and activated charcoal the
     * same sorbent at ₴30, and both are what a person actually asks a pharmacy for. A branch that
     * stocks neither is covered by the second search pass, which knows these two by their category
     * words (see search-terms-system.txt).
     */
    private static final List<HangoverLine> HANGOVER_RELIEF_LINES = List.of(
            new HangoverLine("вода мінеральна", new BigDecimal("2")),
            new HangoverLine("регідрон", BigDecimal.ONE),
            new HangoverLine("активоване вугілля", BigDecimal.ONE));
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*AdHocOrderIntegrationTest' --tests '*IntentRouterIntegrationTest'`
Expected: PASS.

- [ ] **Step 5: Commit** (folded into Task 5's single commit)

---

### Task 4: A brand the person named beats the cheap default

**Files:**
- Modify: `src/main/java/com/silporestockai/model/ProductMatchRequest.java` (new `personsWords` field +
  compatibility constructors)
- Modify: `src/main/java/com/silporestockai/service/ProductMatchingService.java` (`describe`)
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java` (`matchRequests`, `resolve`,
  `build`, `buildCart` — one new overload each, carrying the sentence)
- Modify: `src/main/java/com/silporestockai/service/CartConfirmationService.java` (`present` overload)
- Modify: `src/main/java/com/silporestockai/service/AdHocOrderService.java`
  (`buildHangoverReliefOrder(User, String, OrderTrigger)`)
- Modify: `src/main/java/com/silporestockai/service/IntentRouterService.java:234`
- Modify: `src/main/resources/prompts/product-match-system.txt`
- Test: `src/test/java/com/silporestockai/integration/AdHocOrderIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3's kit lines.
- Produces:
  - `ProductMatchRequest(String requestedName, BigDecimal quantity, String unit,
    List<ProductCandidate> candidates, boolean preferDiscounted, boolean preferUaProducer,
    String personsWords)` — the widest shape; every existing constructor stays and passes `null`.
  - `CartBuildingService.buildCart(UUID userId, List<ShoppingListItem> items, boolean preferDiscounted,
    String personsWords)`
  - `CartConfirmationService.present(User user, List<ShoppingListItem> items, OrderType type,
    boolean preferDiscounted, OrderTrigger trigger, String personsWords)`
  - `AdHocOrderService.buildHangoverReliefOrder(User user, String personsWords, OrderTrigger trigger)` —
    the two-argument form is replaced, not kept.

- [ ] **Step 1: Write the failing test**

```java
    /**
     * Task 72: the cheap default is a default, not a rule. «Голова розвалюється, привези Evian і вугілля»
     * names a brand, and the sentence has to reach the matcher for it to be able to honour it — the kit's
     * own line is «вода мінеральна» and carries no brand of its own.
     */
    @Test
    void carriesThePersonsOwnSentenceToTheMatcherSoANamedBrandCanWin() {
        User user = hangoverUser();

        adHocOrderService.buildHangoverReliefOrder(
                user, "голова розвалюється, привези Evian і вугілля", OrderTrigger.of("HANGOVER_RELIEF", Instant.now()));

        String prompt = CLAUDE.requests().stream().map(Object::toString).collect(java.util.stream.Collectors.joining());
        assertThat(prompt).contains("привези Evian і вугілля");
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*AdHocOrderIntegrationTest.carriesThePersonsOwnSentence*'`
Expected: FAIL to compile — `buildHangoverReliefOrder` takes two arguments.

- [ ] **Step 3: Implement**

`ProductMatchRequest` gains the field and keeps every existing constructor delegating with `null`:

```java
/**
 * @param personsWords the sentence that asked for this order, when there was one — «привези Evian»
 *     names a brand the line itself does not carry, and the cheap default has to yield to it. Null for
 *     lines nobody wrote a sentence for (a weekly plan, a baseline reorder).
 */
public record ProductMatchRequest(
        String requestedName,
        BigDecimal quantity,
        String unit,
        List<ProductCandidate> candidates,
        boolean preferDiscounted,
        boolean preferUaProducer,
        String personsWords) {
```

`ProductMatchingService.describe` appends it once per line, right after the discount/UA markers:

```java
                    .append(
                            request.personsWords() == null || request.personsWords().isBlank()
                                    ? ""
                                    : " — людина написала: «" + request.personsWords()
                                            + "»; якщо вона назвала конкретний товар чи бренд для цього"
                                            + " рядка — бери саме його")
```

and `product-match-system.txt` gains the matching rule under «Як обирати»:

```
- якщо в примітці до рядка наведено слова людини і вона назвала конкретний бренд («хочу саме Evian»,
  «привези Моршинську») — бери саме його, навіть якщо він дорожчий за інших придатних. Правило
  «найдешевший» — це поведінка за замовчуванням, а не заборона на вибір людини;
```

The sentence travels `IntentRouterService` → `AdHocOrderService` → `CartConfirmationService.present` →
`CartBuildingService.buildCart` → `resolve` → `matchRequests`, one added parameter at each step with the
previous signature kept as a delegating overload (the codebase's own idiom — see the `getVerifiedCart`
family). `IntentRouterService:234` becomes:

```java
            case HANGOVER_RELIEF -> adHocOrderService.buildHangoverReliefOrder(user, text, trigger);
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*AdHocOrder*' --tests '*CartBuilding*' --tests '*CartConfirmation*'
--tests '*ProductMatching*' --tests '*IntentRouter*'`
Expected: PASS.

- [ ] **Step 5: Commit** (folded into Task 5's single commit)

---

### Task 5: Verify live, then commit the whole task

**Files:**
- Modify: `docs/RUNBOOK.md` (a `### Task 72:` live-check section)
- Modify: `docs/OVERNIGHT_SUMMARY.md` (this session's section)

- [ ] **Step 1: Whole suite and format**

Run: `make format && ./gradlew test`
Expected: BUILD SUCCESSFUL. (`make run` must be stopped; restart it afterwards.)

- [ ] **Step 2: Drive the live repro**

With the app running (`make run`) and the household connected, POST the synthetic webhook that task 32's
live run used:

```bash
set -a && . ./.env && set +a
curl -sS -X POST http://localhost:8080/telegram/webhook \
  -H "Content-Type: application/json" \
  -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
  -d '{"update_id":972001,"message":{"message_id":72001,"date":1757500000,
       "chat":{"id":<CHAT_ID>,"type":"private"},"from":{"id":<CHAT_ID>,"is_bot":false,"first_name":"K"},
       "text":"Голова після вчорашнього, привезіть мінералку і щось від інтоксикації якнайшвидше"}}'
```

Read `logs/app.log` for `«регідрон» -> …`, `«активоване вугілля» -> …`, `«вода мінеральна» -> …` and for
the cart total in the outbound message. Record in the runbook **what the catalog actually answered and at
what price** — the task requires the cheap staples to be confirmed live, not assumed. If регідрон or
вугілля come back unresolved, note the second-pass terms that rescued them (or that the branch stocks
neither, which is an honest «Не знайшов» line, not a premium substitute).

Expected: a cart well under ₴1000 for the three lines; no Evian, no Elekta Mix.

- [ ] **Step 3: Write the docs**

`docs/RUNBOOK.md` gets a `### Task 72: hangover cost sanity` section with the command above, the live
prices observed, and the one-line check («no line over ~₴120; total under ₴500»).
`docs/OVERNIGHT_SUMMARY.md` gets the session entry.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Buy the hangover kit a pharmacy would sell, not the imported one

A live hangover order came back at 1034 UAH for three items: Evian twice,
Atoxil, and two packs of Elekta Mix at 309 each. Every one of them was a
real answer to a real search — the kit asked for «ізотонік» and «сорбент»,
category words the catalog answers with its dearest members, and the matcher
read the candidates in Silpo's own order, which ranks by relevance and puts
the imported drink above the 32 UAH sachet.

So the kit asks for what a person asks a pharmacy for, the matcher reads its
candidates cheapest-first, and the prompt names the staples and the water
rule instead of leaving «prefer the ordinary one» as an aside. The ordering
and the prompt are shared, so #24, #36 and #19 get the same floor. A brand
the person names still wins: the sentence now travels to the matcher, which
is the only place that can tell «привези Evian» from «привези води».

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-Review

- **Spec coverage:** cheap staples in the pool → Task 3; cheap-first ranking + prompt rules (the shared
  fix the task demands over patching #32) → Tasks 1–2; explicit brand wins → Task 4; live verification of
  the staples and their prices → Task 5 Step 2; audit of #24/#36/#19 → they share `resolve()` and
  `ProductMatchingService`, so Tasks 1–2 are the shared fix, recorded in the commit message.
- **Placeholders:** none; `<CHAT_ID>` in Step 2 is a real value read from the `users` table at run time.
- **Type consistency:** `cheapestFirst` is used in `plausibleFor` and the second pass;
  `MAX_CANDIDATES_SHOWN` is referenced across both services; `personsWords` is the same name in the
  record, the prompt builder and every overload.
