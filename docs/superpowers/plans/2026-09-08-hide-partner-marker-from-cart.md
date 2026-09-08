# Hide the partner marker from the customer-facing cart — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop showing the `★` marker and the partner-disclosure footer on cart/list messages, while leaving partner
resolution and the IMPRESSION / ADDED_TO_CART / CONFIRMED_ORDER funnel from task 46 untouched.

**Architecture:** Presentation-layer only. The whole customer-visible marker lives in one method,
`CartMessageService.cartText(...)`: a `" ★"` appended per promoted line and a footer paragraph appended once when any
line was promoted. Both come out; the loop's `anyPromoted` flag comes out with them. Nothing downstream of the
renderer changes — `CartSummary.promotedProductIds` stays as an internal-only signal.

**Tech Stack:** Java 25 / Spring Boot 4, JUnit 5 + AssertJ, Testcontainers for the integration test, Spotless
(palantir) formatting.

**Spec:** Notion task *62. Remove customer-facing partner marker from cart; keep event tracking internal-only* —
<https://app.notion.com/p/3d57227def1c81b39651c7e290087f60>

## Global Constraints

- Do **not** touch `PartnerPromotionService.findActivePromotion(...)`, `recordImpression`, `recordAddedToCart`, or
  `onOrderConfirmed`. The promoted product is still resolved, still preferred, still logged.
- Do **not** touch the event-logging calls in `CartBuildingService` (`:909` impression, `:1391` added-to-cart).
- `make promotions` must return byte-identical data to before the change.
- `spring.jpa.hibernate.ddl-auto: validate` — no schema change is involved here, so no Liquibase changeset.
- Run `make format` (Spotless palantir) before committing; CI runs `spotlessCheck` before `build`.

### Decision recorded during brainstorming: `promotedProductIds` stays

The task's "Out of scope" allows removing the field, but it is deliberately kept:

1. `CartSummary` round-trips through `conversation_state.context_json` between webhook calls
   (`CartConfirmationService:464` does `MAPPER.convertValue(state.getContext().get(KEY_SUMMARY), CartSummary.class)`).
   Dropping a record component would break every cart in flight at deploy time.
2. It is an internal signal, and task 64 (Grafana partner panel) is the natural consumer.
3. Removing it would ripple through four `getVerifiedCart` overloads plus `topUp` for zero customer-facing gain.

`CartSummary.isPromoted(...)` therefore stays too, and gets a comment saying it is internal-only and no longer
rendered, so the next reader does not "restore" the marker by accident.

## File Structure

| File | Responsibility after the change |
|---|---|
| `src/main/java/com/silporestockai/service/telegram/CartMessageService.java` | Renders the cart with no partner marker and no disclosure footer |
| `src/main/java/com/silporestockai/model/CartSummary.java` | Unchanged shape; `isPromoted` documented as internal-only |
| `src/test/java/com/silporestockai/service/telegram/CartMessageServiceTest.java` | Unit-level regression guard: a promoted line renders identically to a plain one |
| `src/test/java/com/silporestockai/integration/PartnerPromotionIntegrationTest.java` | Same funnel assertions as before; the rendering assertion is inverted |

---

### Task 1: The renderer stops marking partner lines

**Files:**
- Modify: `src/main/java/com/silporestockai/service/telegram/CartMessageService.java:42-71`
- Modify: `src/main/java/com/silporestockai/model/CartSummary.java:245-249`
- Test: `src/test/java/com/silporestockai/service/telegram/CartMessageServiceTest.java`
- Test: `src/test/java/com/silporestockai/integration/PartnerPromotionIntegrationTest.java:241-245`

**Interfaces:**
- Consumes: `CartMessageService.cartText(CartSummary summary, OfferedSlot slot, OrderType type)` returning `String`;
  the 12-argument `CartSummary` constructor
  `(cartId, deliverySlot, deliverySlotStartsAt, items, total, validations, bonusAvailable, bonusDecisionPending,
  checkoutWebLink, checkoutMobileLink, unresolved, promotedProductIds)`.
- Produces: no signature changes. `cartText` keeps its signature; `CartSummary.isPromoted(String)` keeps its
  signature and stays public.

- [ ] **Step 1: Write the failing unit test**

Add to `src/test/java/com/silporestockai/service/telegram/CartMessageServiceTest.java`:

```java
    /**
     * Task 62: a partner placement is an internal matter. The line the household reads must be indistinguishable
     * from any other line — no marker on it, no disclosure paragraph under the list.
     */
    @Test
    void readsIdenticallyWhetherOrNotALineCameFromAPartnerPlacement() {
        List<BasketItem> items = List.of(
                new BasketItem("p-1", "Молоко «Яготинське» 2,6% п/е", "шт", BigDecimal.ONE, new BigDecimal("42.90")),
                new BasketItem("p-2", "Гречка", "кг", BigDecimal.ONE, new BigDecimal("48")));
        CartSummary plain = new CartSummary(
                "cart-1",
                "slot-1",
                Instant.parse("2026-09-03T15:00:00Z"),
                items,
                new BigDecimal("90.90"),
                List.of(),
                BigDecimal.ZERO,
                false,
                "https://silpo.ua/checkout/cart-1",
                "silpo://checkout/cart-1",
                List.of(),
                List.of());
        CartSummary promoted = new CartSummary(
                "cart-1",
                "slot-1",
                Instant.parse("2026-09-03T15:00:00Z"),
                items,
                new BigDecimal("90.90"),
                List.of(),
                BigDecimal.ZERO,
                false,
                "https://silpo.ua/checkout/cart-1",
                "silpo://checkout/cart-1",
                List.of(),
                List.of("p-1"));

        String text = service.cartText(promoted, SLOT, OrderType.INITIAL);

        assertThat(text).isEqualTo(service.cartText(plain, SLOT, OrderType.INITIAL));
        assertThat(text).doesNotContain("★").doesNotContain("партнер");
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'com.silporestockai.service.telegram.CartMessageServiceTest'`
Expected: FAIL — the promoted rendering carries `Молоко «Яготинське» 2,6% п/е ★` and the
`★ — партнерська пропозиція…` paragraph, so it is not equal to the plain rendering.

- [ ] **Step 3: Take the marker and the footer out of the renderer**

In `CartMessageService.cartText(...)`, delete the `anyPromoted` flag, the `" ★"` append, and the whole footer block.
The loop head becomes:

```java
        StringBuilder text =
                new StringBuilder(type == OrderType.AD_HOC ? "Зібрав кошик:\n" : "Зібрав кошик на тиждень:\n");
        for (BasketItem item : summary.items()) {
            text.append("\n— ").append(item.name());
            if (item.quantity() != null) {
```

and the block between the loop and the `summary.unresolved()` check becomes nothing — i.e. these lines go away
entirely:

```java
        if (anyPromoted) {
            // Task 46, said plainly: a partner chose the brand, the household chose the category. Editing is the
            // same right as for any other line.
            text.append("\n\n★ — партнерська пропозиція: бренд від партнера «Сільпо» в категорії, яку ти й так")
                    .append(" замовляєш. Не подобається — скажи, заміню.");
        }
```

Then extend the method's javadoc with the reason, so the next reader does not put it back:

```java
     * <p>A line a partner placement won is rendered like any other line (task 62). The household asked for the
     * category and got the category; which brand answers it is our business, and a mid-cart «this one is paid for»
     * buys doubt about every other line rather than trust. The placement is still resolved, preferred and counted —
     * see {@code PartnerPromotionService} — just not announced.
```

- [ ] **Step 4: Mark `isPromoted` as internal-only**

In `src/main/java/com/silporestockai/model/CartSummary.java`, replace the `isPromoted` declaration with:

```java
    /**
     * Whether a partner placement put this product in the cart (task 46). Internal only: since task 62 no message a
     * household reads distinguishes a promoted line, and the funnel is counted from the promotion's own events, not
     * from here.
     */
    public boolean isPromoted(String productId) {
        return productId != null && promotedProductIds != null && promotedProductIds.contains(productId);
    }
```

- [ ] **Step 5: Run the unit test to verify it passes**

Run: `./gradlew test --tests 'com.silporestockai.service.telegram.CartMessageServiceTest'`
Expected: PASS, all tests in the class.

- [ ] **Step 6: Invert the rendering assertion in the integration test**

In `src/test/java/com/silporestockai/integration/PartnerPromotionIntegrationTest.java`, inside
`thePartnersProductAnswersTheCategoryTheHouseholdAskedForAndTheFunnelIsLogged`, replace these two lines:

```java
        // The household is told, plainly, on the line and once under the list.
        String text = cartMessageService.cartText(summary, null, OrderType.INITIAL);
        assertThat(text).contains(PARTNER_MILK_NAME + " ★").contains("★ — партнерська пропозиція");
```

with:

```java
        // Task 62: the placement is counted, never announced — the line reads like any other line.
        String text = cartMessageService.cartText(summary, null, OrderType.INITIAL);
        assertThat(text).contains(PARTNER_MILK_NAME).doesNotContain("★").doesNotContain("партнерськ");
```

Leave every other assertion in the class untouched: `addedProductIds()`, `summary.promotedProductIds()`, the
single-batch check, the `IMPRESSION, ADDED_TO_CART` funnel assertion, the lactose-free case and the
`CONFIRMED_ORDER` case all stay exactly as they are.

- [ ] **Step 7: Run the partner integration test**

Run: `./gradlew test --tests 'com.silporestockai.integration.PartnerPromotionIntegrationTest'`
Expected: PASS — all tests, including the untouched funnel and restriction cases. (Docker must be running;
`make run` must not be holding `build/classes`.)

- [ ] **Step 8: Run the whole suite and format**

Run: `make format && make test`
Expected: Spotless rewrites nothing surprising; BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/silporestockai/service/telegram/CartMessageService.java \
        src/main/java/com/silporestockai/model/CartSummary.java \
        src/test/java/com/silporestockai/service/telegram/CartMessageServiceTest.java \
        src/test/java/com/silporestockai/integration/PartnerPromotionIntegrationTest.java \
        docs/superpowers/plans/2026-09-08-hide-partner-marker-from-cart.md
git commit
```

---

### Task 2: Prove the funnel is untouched on the live account

**Files:** none — this is the manual verification the task's acceptance criteria call for.

**Interfaces:**
- Consumes: `make promotions` (GETs `/internal/promotions/report` with `X-Metrics-Token`), the running app, and the
  synthetic-webhook driving described in the session notes.

- [ ] **Step 1: Capture the funnel report before touching anything**

Run: `make promotions > /tmp/promotions-before.txt` against the app running on the pre-change build.
Expected: the per-promotion table with featured / in-cart / confirmed counts.

- [ ] **Step 2: Replay task 46's live scenario on the new build**

Restart the app on the post-change build and drive the weekly-list scenario that puts молоко in the cart, so the
milk placement resolves. Read the cart message the bot sends.
Expected: the milk line reads exactly like the гречка line — no `★` anywhere in the message, no
`★ — партнерська пропозиція` paragraph under the list.

- [ ] **Step 3: Capture the funnel report again and diff**

Run: `make promotions > /tmp/promotions-after.txt && diff /tmp/promotions-before.txt /tmp/promotions-after.txt`
Expected: the only differences are the counts the replay itself just added (one more IMPRESSION and one more
ADDED_TO_CART for the milk promotion). The report's shape, promotions and conversion columns are unchanged — no
column lost, no promotion missing.

- [ ] **Step 4: Record the result**

Update the Notion task's status and step 13.5 of the demo-script document with what the replay showed.
