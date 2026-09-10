# Auto-reselect an expired delivery slot (task 76) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When Silpo refuses a cart because the slot chosen earlier is no longer available, pick a valid
slot, book it, and go on — telling the household which slot it now is — instead of «Кошик зібрати не
вдалось: обраний час доставки більше недоступний. Виправ список і спробуй ще раз», which sends them to
fix a list that was never the problem.

**Architecture:** The failure is already named by Silpo in the cart's own validations
(`timeslot.not_available`, and `timeslot.not_found` for the same condition — both seen live). Today
`CartBuildingService.getVerifiedCart` treats it as one more blocking validation: no checkout link, so a
`CartBuildException` carrying the sentence. It already knows how to heal a cart once and re-read it —
that is what `takeOutWhatTheBranchLacks` does for stock — so the slot recovery is the same shape: pick a
fresh slot (`offeredTimeSlots`), book it on the cart (`bookSlot`, task 34's own call), read the cart
again, and report the new slot. Once only, like the stock healing; a second refusal is honest.

The genuinely-no-slots case gets its own exception so the message can differ. It extends
`CartBuildException`, so every existing handler keeps working, and the one place that writes the
household's message catches it first.

**Tech Stack:** Java 25 / Spring Boot 4, JUnit 5 + AssertJ integration tests, `StubMcpServer`
(`respondToToolInOrder` scripts a first read that refuses and a second that does not).

**Spec:** Notion task «76. Auto-reselect expired delivery slot»
(`3d77227d-ef1c-8149-ad03-ec0d909e46ba`), acceptance criteria copied below.

## Global Constraints

- Acceptance criteria: its own distinct error type, not a generic cart-build failure; the new slot is
  picked automatically with nothing asked of the person; the success message says a new slot was picked;
  a genuine no-slots case reads honestly and never says «виправ список»; verified inside a revision loop
  («Змінити» more than once before the final confirm), where the gap between picking a slot and building
  the cart is longest.
- Out of scope: slot *preference* logic (#15) is untouched — recovery reuses the existing selection.
- `make format`; ArchUnit rules as in task 72's plan; `make run` stopped before `./gradlew test`.

---

### Task 1: Recover from a stale slot inside the verified-cart read

**Files:**
- Create: `src/main/java/com/silporestockai/exception/DeliverySlotUnavailableException.java`
- Modify: `src/main/java/com/silporestockai/model/CartSummary.java` (trailing
  `boolean deliverySlotRepicked`, plus the existing constructors delegating with `false`)
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java`
  (`getVerifiedCart`'s healing flag becomes a two-field record; the slot branch)
- Modify: `src/main/java/com/silporestockai/service/telegram/CartMessageService.java` (the «Доставка:»
  line says the slot changed)
- Modify: `src/main/java/com/silporestockai/service/CartConfirmationService.java` (its own catch and
  message)
- Test: `src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java`,
  `src/test/java/com/silporestockai/integration/CartConfirmationIntegrationTest.java`

**Interfaces:**
- Produces:
  - `DeliverySlotUnavailableException extends CartBuildException` — thrown when the cart's slot is stale
    and Silpo offers no replacement.
  - `CartSummary.deliverySlotRepicked()` — true when this cart is on a slot picked in recovery.

- [ ] **Step 1: Write the failing tests**

```java
    /**
     * Task 76: time passes between picking a slot and building the cart — a «Змінити» round-trip is minutes of
     * it — and Silpo then refuses the cart with «timeslot.not_available». The old message told the household to
     * fix the list, which had nothing to do with it. The slot is re-picked and booked instead.
     */
    @Test
    void rebooksTheCartOnAFreshSlotWhenTheOldOneIsGone() {
        UUID userId = connectedUser(8435L);
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-1\"}");
        MCP.respondToToolInOrder(
                "silpo_get_shopping_cart_by_id",
                CART_WITH_A_STALE_SLOT,   // read for the context
                CART_WITH_A_STALE_SLOT,   // the verified read: timeslot.not_available, no checkout link
                CART_ON_A_FRESH_SLOT);    // after the slot is booked
        ...
        CartSummary summary = cartBuildingService.buildCart(userId, List.of(item("гречка", "1", "кг")));

        assertThat(summary.deliverySlotRepicked()).isTrue();
        assertThat(summary.checkoutWebLink()).isNotBlank();
        assertThat(MCP.calledTools()).contains("silpo_update_shopping_cart");
    }

    /** The honest case: the slot is stale and Silpo has nothing to move it to. */
    @Test
    void saysThereAreNoSlotsRatherThanBlamingTheListWhenNoneAreOffered() { ... }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew test --tests '*CartBuildingIntegrationTest.rebooksTheCart*' --tests
'*CartBuildingIntegrationTest.saysThereAreNoSlots*'`
Expected: FAIL — `CartBuildException: Silpo gave no checkout link`.

- [ ] **Step 3: Implement**

`getVerifiedCart`'s `boolean healedOnce` becomes `Healing tried` (`record Healing(boolean stock, boolean
slot)`), and a new branch runs before the checkout-link check: if a blocking validation is
`timeslot.not_available` or `timeslot.not_found` and `!tried.slot()`, take
`offeredTimeSlots(userId, context).getFirst()` (empty ⇒ `DeliverySlotUnavailableException`),
`bookSlot(...)`, and re-read with the new slot and `tried.withSlot()`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*CartBuilding*' --tests '*CartConfirmation*' --tests '*Reorder*'`
Expected: PASS.

- [ ] **Step 5: Commit** (folded into Task 2)

---

### Task 2: Verify in a revision loop, live, then commit

- [ ] **Step 1: Whole suite and format** — `make format && ./gradlew test`.

- [ ] **Step 2: Live** — build a cart, edit a line («давай замість гречки рис»), confirm, and check the
  log for `slot … is no longer available; re-picked …` and a cart message whose «Доставка:» line says the
  slot changed. RUNBOOK gets a `## 23. Expired delivery slot` section with the commands.

- [ ] **Step 3: Commit** with a message explaining the misleading advice, not the diff.
