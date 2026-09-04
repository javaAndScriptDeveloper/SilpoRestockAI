# Guarantee Real Checkout Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every order-building flow (task 10, 15, 19) reliably ends with a valid, tappable checkout
button pointing at Silpo's real checkout — a verified cart missing either checkout link fails loudly
instead of silently reaching a broken confirmation message.

**Architecture:** `CartBuildingService.getVerifiedCart` becomes the single point that guarantees both
`checkoutWebLink`/`checkoutMobileLink` are present, throwing `CartBuildException` otherwise.
`CartMessageService` becomes the single source of truth for how checkout is presented (mobile link as
the tappable button, web link as a text fallback); `ReorderConfirmationService`/`ReorderMessageService`
stop duplicating that logic and delegate to it instead — the same "one flow's component, reused" pattern
task 19 (blackout) already uses for its whole confirmation step.

**Tech Stack:** Spring Boot / Java, JUnit 5 + AssertJ + Mockito, Testcontainers + `StubMcpServer` for
`*IntegrationTest`.

**Spec:** `docs/superpowers/specs/2026-09-04-checkout-link-guarantee-design.md`

## Global Constraints

- Constructor injection only (`@RequiredArgsConstructor`), no field `@Autowired` — ArchUnit-enforced.
- `@Slf4j` for logging, never `LoggerFactory` directly.
- No changes to cart-building logic itself beyond the link-presence assertion (explicitly out of scope
  per the Notion task).
- No custom payment/checkout UI — Silpo's own checkout page handles payment.
- Task 24 (ad-hoc order) does not exist yet and is not built by this plan.
- Every task ends green on `./gradlew test` before moving to the next.

---

## Task 1: `CartBuildingService.getVerifiedCart` asserts both checkout links

**Files:**
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java` (the `getVerifiedCart`
  method, currently around lines 580-627 — re-read the file fresh, line numbers drift)
- Test: `src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java`

**Interfaces:**
- Produces: `getVerifiedCart` now throws `com.silporestockai.exception.CartBuildException` (existing
  type, already thrown elsewhere in this class) when either checkout link is missing/blank — no
  signature change, same return type `CartSummary` on success.

- [ ] **Step 1: Write the failing test**

Add to `CartBuildingIntegrationTest.java`, near the other `buildCart` tests (e.g. after
`runsAllSixCallsInTheDocumentedOrder`):

```java
    /**
     * The verified cart is the one authoritative read for whether checkout can actually happen — a cart
     * with no usable checkout link is not something any consuming flow (task 10, 15, 19) can present to
     * a user, so this must fail loudly here rather than let a confirmation message reach the user with a
     * missing button.
     */
    @Test
    void treatsAMissingCheckoutLinkAsAFatalError() {
        UUID userId = connectedUser(8423L);
        scriptCartTools();
        scriptProductTools();

        assertThatThrownBy(() -> cartBuildingService.buildCart(userId, List.of(item("цибуля", "0.5", "кг"))))
                .isInstanceOf(CartBuildException.class)
                .hasMessageContaining("checkout");

        // The add still ran — this is a failure of the verification read, not a reason to skip step 5.
        assertThat(MCP.calledTools()).contains("silpo_add_or_update_cart_products");
    }
```

`scriptCartTools()`'s existing `silpo_get_shopping_cart_by_id` stub already has no `checkoutWebLink`/
`checkoutMobileLink` fields, and it's the same stub reused for both the context read and the later
verification read — no new stubbing needed. `assertThatThrownBy` and `CartBuildException` are already
imported in this file.

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew test --tests "com.silporestockai.integration.CartBuildingIntegrationTest.treatsAMissingCheckoutLinkAsAFatalError"
```

Expected: FAIL — no exception thrown today, `buildCart` returns a `CartSummary` with null links instead.

- [ ] **Step 3: Implement the assertion**

In `CartBuildingService.java`, find `getVerifiedCart` and replace the two inline lookups inside the
`CartSummary` constructor call with extracted, guarded local variables. The method currently reads:

```java
        CartSummary summary = new CartSummary(
                context.cartId(),
                deliverySlot == null ? null : deliverySlot.id(),
                deliverySlot == null ? null : deliverySlot.startsAt(),
                items,
                McpResponses.findNumber(cart, McpResponses.TOTAL).orElse(BigDecimal.ZERO),
                McpResponses.findArray(cart, McpResponses.VALIDATIONS).stream()
                        .map(JsonNode::asText)
                        .toList(),
                bonusAvailable,
                bonusDecisionPending,
                McpResponses.findString(cart, McpResponses.CHECKOUT_WEB).orElse(null),
                McpResponses.findString(cart, McpResponses.CHECKOUT_MOBILE).orElse(null),
                unresolved);
```

Replace with:

```java
        String checkoutWebLink =
                McpResponses.findString(cart, McpResponses.CHECKOUT_WEB).orElse(null);
        String checkoutMobileLink =
                McpResponses.findString(cart, McpResponses.CHECKOUT_MOBILE).orElse(null);
        if (isBlank(checkoutWebLink) || isBlank(checkoutMobileLink)) {
            log.error(
                    "verified cart {} has no usable checkout link — checkoutWebLink={}, checkoutMobileLink={}. "
                            + "Raw response: {}",
                    context.cartId(),
                    checkoutWebLink,
                    checkoutMobileLink,
                    cart);
            throw new CartBuildException("Silpo gave no checkout link for cart " + context.cartId());
        }

        CartSummary summary = new CartSummary(
                context.cartId(),
                deliverySlot == null ? null : deliverySlot.id(),
                deliverySlot == null ? null : deliverySlot.startsAt(),
                items,
                McpResponses.findNumber(cart, McpResponses.TOTAL).orElse(BigDecimal.ZERO),
                McpResponses.findArray(cart, McpResponses.VALIDATIONS).stream()
                        .map(JsonNode::asText)
                        .toList(),
                bonusAvailable,
                bonusDecisionPending,
                checkoutWebLink,
                checkoutMobileLink,
                unresolved);
```

Add the small `isBlank` helper next to the other private static helpers in this class (e.g. near
`nullSafe`):

```java
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

(Add it as a new private static method, not replacing `nullSafe` — both stay, they serve different
call sites.)

- [ ] **Step 4: Run the test again**

```bash
./gradlew test --tests "com.silporestockai.integration.CartBuildingIntegrationTest"
```

Expected: PASS, all tests in the file — including the new one and every pre-existing test (they all
script `checkoutWebLink`/`checkoutMobileLink` on their verified-cart responses already, per
`scriptVerifiedCart()`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/CartBuildingService.java \
        src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java
git commit -m "$(cat <<'EOF'
Treat a missing checkout link as a fatal cart-build error

Per task 28: every consuming flow needs a real checkoutWebLink and
checkoutMobileLink to present a working checkout button. Silently
passing a null link through to CartSummary meant a broken or absent
button could reach a real confirmation message. getVerifiedCart now
asserts both are present, the same way this class already treats
every other structurally-broken cart state.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `CartMessageService` — mobile link as the button, web link as fallback text

**Files:**
- Modify: `src/main/java/com/silporestockai/service/telegram/CartMessageService.java`
- Test: `src/test/java/com/silporestockai/service/telegram/CartMessageServiceTest.java`

**Interfaces:**
- Produces: `checkoutButtons(CartSummary)` now targets `checkoutMobileLink` (was `checkoutWebLink`),
  label `"Перейти до оплати"`, and never returns an empty list (unreachable after Task 1 — a
  `CartSummary` only ever exists with both links present).
- Produces: new public method `checkoutFallbackLine(CartSummary)` → `String`, e.g.
  `"\nАбо в браузері: https://..."` — consumed by Task 3.

- [ ] **Step 1: Write the failing tests**

Replace the existing `theClosingMessageSaysWhereToPayAndWhetherBonusesWereSpent` test in
`CartMessageServiceTest.java` with:

```java
    @Test
    void theClosingMessageSaysWhereToPayAndWhetherBonusesWereSpent() {
        CartSummary cart = summary(twoItems().items(), new BigDecimal("73.5"), new BigDecimal("120"), true, List.of());

        assertThat(service.confirmedText(cart, true)).contains("120").contains("https://silpo.ua/checkout/cart-1");
        assertThat(service.confirmedText(cart, false)).doesNotContain("Списав бонусів");
    }

    @Test
    void theCheckoutButtonOpensTheMobileDeepLinkNotTheWebPage() {
        CartSummary cart = summary(twoItems().items(), new BigDecimal("73.5"), BigDecimal.ZERO, false, List.of());

        assertThat(service.checkoutButtons(cart))
                .extracting(TelegramButton::url)
                .containsExactly("silpo://checkout/cart-1");
        assertThat(service.checkoutButtons(cart).getFirst().label()).isEqualTo("Перейти до оплати");
    }

    @Test
    void theFallbackLineMentionsTheWebLinkNotTheMobileOne() {
        CartSummary cart = summary(twoItems().items(), new BigDecimal("73.5"), BigDecimal.ZERO, false, List.of());

        assertThat(service.checkoutFallbackLine(cart))
                .contains("https://silpo.ua/checkout/cart-1")
                .doesNotContain("silpo://checkout/cart-1");
    }
```

Leave `survivesACartWhoseLinesCarryNoPriceOrQuantity` as-is — it still passes unchanged.

- [ ] **Step 2: Run to verify the new/changed tests fail**

```bash
./gradlew test --tests "com.silporestockai.service.telegram.CartMessageServiceTest"
```

Expected: FAIL — `checkoutFallbackLine` doesn't exist yet; `checkoutButtons` still returns the web link.

- [ ] **Step 3: Implement the change**

In `CartMessageService.java`, replace `confirmedText` and `checkoutButtons`:

```java
    /** Said once the order is stored. Payment is Silpo's page, not ours — there is no MCP payment tool. */
    public String confirmedText(CartSummary summary, boolean bonusesApplied) {
        StringBuilder text = new StringBuilder("Підтвердив. Зберіг цей кошик як еталонний набір — далі буду ")
                .append("порівнювати з ним, коли питатиму, що закінчилось.");
        if (bonusesApplied) {
            text.append("\nСписав бонусів: ")
                    .append(amount(summary.bonusAvailable()))
                    .append('.');
        }
        text.append("\n\nОплата — на боці «Сільпо».");
        text.append(checkoutFallbackLine(summary));
        return text.toString();
    }

    /**
     * A link button straight to checkout — the mobile deep link, which opens the Silpo app directly
     * rather than a browser, so it never hits the in-app-browser session friction a web link opened
     * inside Telegram's own browser can. Shared by every order-confirmation flow (task 10, 15, 19).
     */
    public List<TelegramButton> checkoutButtons(CartSummary summary) {
        return List.of(TelegramButton.link("Перейти до оплати", summary.checkoutMobileLink()));
    }

    /**
     * The web link, mentioned as a text fallback — the button above already covers the mobile case.
     * Pulled out on its own so every confirmation message (task 10, 15) uses identical wording instead
     * of each spelling it out separately.
     */
    public String checkoutFallbackLine(CartSummary summary) {
        return "\nАбо в браузері: " + summary.checkoutWebLink();
    }
```

- [ ] **Step 4: Run the tests again**

```bash
./gradlew test --tests "com.silporestockai.service.telegram.CartMessageServiceTest"
```

Expected: PASS, all tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/telegram/CartMessageService.java \
        src/test/java/com/silporestockai/service/telegram/CartMessageServiceTest.java
git commit -m "$(cat <<'EOF'
Make the checkout button open the mobile deep link, not the web page

Per task 28: checkoutMobileLink opens the Silpo app directly, which
sidesteps the session friction a web link can hit inside Telegram's
own in-app browser — no server-side way exists to force a URL button
to open in the system browser instead, so routing around the problem
via the mobile deep link is the fix. checkoutWebLink moves to a text
fallback line, extracted once so task 15's confirmation message can
reuse the same wording instead of duplicating it.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `ReorderConfirmationService`/`ReorderMessageService` delegate instead of duplicating

**Files:**
- Modify: `src/main/java/com/silporestockai/service/ReorderConfirmationService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/ReorderMessageService.java`
- Test: `src/test/java/com/silporestockai/integration/ReorderConfirmationIntegrationTest.java`

**Interfaces:**
- Consumes: `CartMessageService.checkoutButtons(CartSummary)` and
  `CartMessageService.checkoutFallbackLine(CartSummary)` from Task 2.

- [ ] **Step 1: Write the failing test**

Add to `ReorderConfirmationIntegrationTest.java`, right after `confirmingWithNoEditsLeavesTheBaselineAloneAndCountsTowardsTrust`
(same arrange/act pattern — `needs`, `present`, `tapButton` — copied from that test):

```java
    @Test
    void theConfirmedMessageCarriesATappableCheckoutButtonOnTheMobileLink() throws Exception {
        needs(List.of("Молоко"));
        present();

        tapButton(1, ReorderMessageService.CALLBACK_CONFIRM);

        JsonNode confirmed = TELEGRAM.sentMessages().getLast();
        JsonNode buttons = confirmed.path("reply_markup").path("inline_keyboard").get(0);
        JsonNode checkoutButton = buttons.get(buttons.size() - 1);
        assertThat(checkoutButton.path("url").asText()).isEqualTo("silpo://checkout/cart-9");
        assertThat(checkoutButton.path("text").asText()).isEqualTo("Перейти до оплати");
        assertThat(confirmed.path("text").asText()).contains("https://silpo.ua/checkout/cart-9");
    }
```

`scriptSilpo()`'s cart response (`cart-9`) already carries both `checkoutWebLink`/`checkoutMobileLink`.
If `com.fasterxml.jackson.databind.JsonNode` isn't already imported in this file, add
`import com.fasterxml.jackson.databind.JsonNode;`.

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew test --tests "com.silporestockai.integration.ReorderConfirmationIntegrationTest.theConfirmedMessageCarriesATappableCheckoutButtonOnTheMobileLink"
```

Expected: FAIL — today's button targets the web link, not the mobile one (or the assertion path finds
the old wording).

- [ ] **Step 3: Delegate in `ReorderConfirmationService`**

Add the import and field:

```java
import com.silporestockai.service.telegram.CartMessageService;
```

```java
    private final CartBuildingService cartBuildingService;
    private final CartMessageService cartMessageService;
    private final CustomerOrderRepository customerOrderRepository;
```

(Insert `cartMessageService` right after `cartBuildingService` in the field list — `@RequiredArgsConstructor`
generates the constructor from field order, so any test that constructs this class positionally would
need updating, but none does — this class is only ever Spring-wired in integration tests.)

Delete the private method entirely:

```java
    private List<TelegramButton> checkoutButtons(CartSummary cart) {
        return cart.checkoutWebLink() == null
                ? List.of()
                : List.of(TelegramButton.link("Оформити на silpo.ua", cart.checkoutWebLink()));
    }
```

Remove the now-unused import `com.silporestockai.model.TelegramButton;` (it was only used by the
deleted method — confirm with `grep -n "TelegramButton" src/main/java/com/silporestockai/service/ReorderConfirmationService.java`
that no other line references it before removing the import).

Update the one call site:

```java
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                reorderMessageService.confirmedText(cart, delta.estimatedSavings(), slotFixed, edited),
                cartMessageService.checkoutButtons(cart));
```

- [ ] **Step 4: Delegate in `ReorderMessageService`**

Add the constructor and field (same package as `CartMessageService`, no import needed):

```java
import lombok.RequiredArgsConstructor;
```

```java
@Service
@RequiredArgsConstructor
public class ReorderMessageService {

    public static final String CALLBACK_ACCEPT_PREFIX = "re:acc:";
    ...

    private final CartMessageService cartMessageService;
```

Replace the mobile-link block in `confirmedText`:

```java
        text.append("\n\nОплата — на боці «Сільпо».");
        if (cart.checkoutMobileLink() != null) {
            text.append("\nУ застосунку: ").append(cart.checkoutMobileLink());
        }
        return text.toString();
```

with:

```java
        text.append("\n\nОплата — на боці «Сільпо».");
        text.append(cartMessageService.checkoutFallbackLine(cart));
        return text.toString();
```

- [ ] **Step 5: Run the test again**

```bash
./gradlew test --tests "com.silporestockai.integration.ReorderConfirmationIntegrationTest"
```

Expected: PASS, all tests in the file.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/silporestockai/service/ReorderConfirmationService.java \
        src/main/java/com/silporestockai/service/telegram/ReorderMessageService.java \
        src/test/java/com/silporestockai/integration/ReorderConfirmationIntegrationTest.java
git commit -m "$(cat <<'EOF'
Delegate reorder checkout presentation to CartMessageService

Task 15 duplicated task 10's checkoutButtons() and its raw-mobile-
link-as-text line independently — both reimplementations pointed the
button at the wrong link. Removes the duplicate; both flows now use
the same CartMessageService methods task 28 fixed, so the wording and
the target link can never drift apart between them again.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Full regression, docs, and the manual verification checklist

**Files:**
- Modify: `docs/RUNBOOK.md` (new manual-verification section)
- No source changes — this task is verification plus the human-executable checklist the spec calls for.

- [ ] **Step 1: Run the full test suite**

```bash
./gradlew test
```

Expected: every test green except the two known pre-existing, unrelated failures already tracked before
this task started (`ArchitectureTest.servicesAreNamedProperly` — pre-existing nested-class naming
violations in `CartBuildingService`/`OnboardingFlowService`/`MainMenuKeyboard`, none introduced by this
plan; and any transient `OutOfMemoryError` context-load failures caused by concurrent local processes,
not this code — rerun once alone if anything else fails to rule out that cause before treating it as a
real regression).

- [ ] **Step 2: Format**

```bash
make format
git status --porcelain
```

If `make format` changes anything, `git add` and commit it separately as a plain formatting commit
(matches the convention used earlier this session).

- [ ] **Step 3: Add the manual verification section to `docs/RUNBOOK.md`**

Find the existing "## 7. Confirm, and the baseline" section (search for `«Підтвердити».`) and add,
immediately after its `**Verify:**` SQL block and before the next `---`:

```markdown
### Task 28: verify the checkout link actually completes a real purchase

This is the one step in this runbook that cannot be automated — it needs a real Silpo guest account, a
phone with Telegram installed, and ends in an actual payment. Do this once per significant change to
`CartMessageService`/`CartBuildingService`'s checkout-link handling, and definitely once before any
hackathon jury demo.

1. Run the full flow above (steps 1-7) with a real, OAuth-connected Silpo guest account through to the
   confirmed-order message.
2. **Confirm the button, not the text.** The confirmation message must show one tappable inline button
   labeled "Перейти до оплати" — not raw URL text. Tap it.
3. Record your screen from this tap onward (any screen recorder — this becomes hackathon demo evidence,
   referenced from the "Сценарій демо-запису" Notion page).
4. Confirm, while recording:
   - The tap opens the Silpo app directly (or its checkout page) — not a dead link, not a 404.
   - The cart shown on Silpo's side has the same items and total the bot's own confirmation message had.
   - No re-login/re-auth prompt appears — the guest's session carries through. If one does appear, this
     is exactly the friction the Notion task calls out; document it in the recording's description and
     decide with the team whether it's fixable or an inherent platform constraint (see the design doc's
     already-established finding: Telegram's Bot API has no server-side control over in-app vs. system
     browser, so `checkoutMobileLink` is the intended workaround — if friction still appears even via the
     mobile link, that's new information worth escalating, not something to silently route around again).
   - Complete a real payment for a small real order.
5. Save the recording somewhere durable and link it from the "Сценарій демо-запису" Notion page and from
   Notion task 28 itself, then mark task 28's acceptance criteria checked off there.
```

- [ ] **Step 4: Commit the doc addition**

```bash
git add docs/RUNBOOK.md
git commit -m "$(cat <<'EOF'
Document the task 28 manual checkout-completion verification steps

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Hand off**

Nothing further for the coding agent. Report back that steps 1-4 above are ready for a human to run
with a real Silpo account, and that Notion task 28's status/acceptance criteria and the "Сценарій
демо-запису" page update are the human's to do once the recording exists.
