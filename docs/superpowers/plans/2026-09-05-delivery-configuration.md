# Actively Configure Delivery Before Checkout (task 34) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before a household taps "Підтвердити" on their first-order cart, show them which delivery window it will arrive in and let them pick a different one — instead of silently keeping whatever slot `CartBuildingService` auto-picked when the cart was first created.

**Architecture:** Port `ReorderConfirmationService`'s already-shipped, already-tested slot-menu interaction (task 15) into `CartConfirmationService` verbatim: fetch offered slots once at presentation time, store them in `conversation_state`, let the user browse and pick without touching Silpo, and only call `silpo_update_shopping_cart` to actually book the chosen slot at the moment of final confirmation. No new MCP capability is invented — `silpo_update_shopping_cart` accepting `{cartId, timeslot}` is already proven by `ReorderConfirmationService.bookSlot`.

**Tech Stack:** Spring Boot service classes, existing `ConversationStateService`/`conversation_state` persistence, the existing stub-Telegram/stub-MCP integration test harness.

**Spec:** Task 34 (`Комора — Development Plan`, Notion, page `3d27227d-ef1c-81d6-8beb-f05b1bae6974`) — **this page is completely empty** (title and dependencies only, no Context/Goal/acceptance criteria). Scope here was derived entirely from reading `CartBuildingService`, `CartConfirmationService`, `CartMessageService`, and the proven precedent in `ReorderConfirmationService`/`ReorderMessageService` (task 15) — see `docs/OVERNIGHT_QUESTIONS.md`'s "Task 34" entry for the full reasoning and what was deliberately left out.

## Global Constraints

- Do not build delivery-*type* switching (home delivery vs self-pickup) — no MCP call anywhere in this codebase demonstrates changing an existing cart's delivery type, unlike the slot case. Only `silpo_create_shopping_cart` resolves a delivery type, and that is a brand-new-cart operation, not an update.
- Do not surface the full street address in the Telegram message — Silpo's own checkout page already shows it, and there is no way to change it without re-running `createCart`'s whole address-resolution path.
- A slot picked from the menu is **not** booked with Silpo until the order is actually confirmed — mirror `ReorderConfirmationService`'s lazy-booking design exactly. A test in Task 2 exists specifically to catch a regression into eager booking.
- A failure to book the newly-picked slot at confirm time must not block the order — mirror how a failed bonus-application call already degrades gracefully in this same method.
- `make format` and the full `make test` suite must stay green after every task.

---

### Task 1: Show the delivery slot in the cart message, with a menu to change it (no booking yet)

**Files:**
- Modify: `src/main/java/com/silporestockai/service/telegram/CartMessageService.java`
- Modify: `src/main/java/com/silporestockai/service/CartConfirmationService.java`
- Test: `src/test/java/com/silporestockai/integration/CartConfirmationIntegrationTest.java`

**Interfaces:**
- Consumes: `CartBuildingService.getOrCreateCartContext(UUID)` (existing), `CartBuildingService.offeredTimeSlots(UUID, CartContext)` (existing, returns `List<OfferedSlot>`), `OfferedSlot(String id, String label, Instant startsAt, String end)` (existing record).
- Produces: `CartMessageService.cartText(CartSummary, OfferedSlot)`, `CartMessageService.cartButtons(CartSummary, boolean)`, `CartMessageService.slotMenuText()`, `CartMessageService.slotButtons(List<OfferedSlot>)`, `CartMessageService.CALLBACK_SLOT_MENU`, `CartMessageService.CALLBACK_SLOT_PREFIX` — all consumed by Task 2's `pickSlot`/`confirm` and by this task's own `present`.

**Step 1: Write the failing test**

The shared MCP stub in `CartConfirmationIntegrationTest.scriptSilpo()` currently offers exactly one time slot (`slot-1`, starting `2026-09-03T18:00:00Z`). Add a second, later slot so a test can prove the menu actually offers a choice without disturbing every other test's assumption that the *default* pick is still `slot-1` (earliest wins — see `CartBuildingService.firstDeliverableSlot`, unchanged):

```java
        MCP.respondToTool(
                "silpo_get_time_slots",
                "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"2026-09-03T18:00:00Z\"},"
                        + "{\"id\":\"slot-2\",\"from\":\"2026-09-04T20:00:00Z\"}]}");
```

Add these tests to `CartConfirmationIntegrationTest`:

```java
    @Test
    void theCartMessageShowsTheDeliverySlotAndOffersAnotherOne() {
        presentedCart();

        assertThat(lastMessageText()).contains("Доставка:");
        var buttons = TELEGRAM.sentMessages().getLast().path("reply_markup").path("inline_keyboard").get(0);
        boolean hasSlotMenuButton = false;
        for (JsonNode button : buttons) {
            if (CartMessageService.CALLBACK_SLOT_MENU.equals(button.path("callback_data").asText())) {
                hasSlotMenuButton = true;
            }
        }
        assertThat(hasSlotMenuButton).isTrue();
    }

    @Test
    void pickingADifferentSlotUpdatesTheMessageWithoutBookingItYet() throws Exception {
        presentedCart();

        tapButton(1, CartMessageService.CALLBACK_SLOT_MENU);
        var slotMenuButtons =
                TELEGRAM.sentMessages().getLast().path("reply_markup").path("inline_keyboard").get(0);
        assertThat(slotMenuButtons.size()).isEqualTo(2);

        tapButton(2, CartMessageService.CALLBACK_SLOT_PREFIX + "1");

        assertThat(MCP.calledTools()).doesNotContain("silpo_update_shopping_cart");
        CustomerOrder order = customerOrderRepository
                .findByUserIdOrderByCreatedAtDesc(
                        userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId())
                .getFirst();
        assertThat(order.getDeliverySlot()).isEqualTo("slot-1"); // unchanged until confirm — this is the point
    }
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.silporestockai.integration.CartConfirmationIntegrationTest"`
Expected: `theCartMessageShowsTheDeliverySlotAndOffersAnotherOne` FAILs (`cartText` never writes "Доставка:", no such button exists yet). `pickingADifferentSlotUpdatesTheMessageWithoutBookingItYet` FAILs (`cart:slotmenu` and `cart:slot:1` callbacks are unhandled — `CartConfirmationService.handle`'s `switch` falls to `default -> log.debug(...)`, sends no second message, so `TELEGRAM.sentMessages().getLast()` still reads the original cart message with only one button row and no slot buttons).

**Step 3: Write the minimal implementation**

`CartMessageService.java` — add the import, constants, and new/changed methods:

```java
import com.silporestockai.model.OfferedSlot;
```

```java
    public static final String CALLBACK_CONFIRM = "cart:confirm";
    public static final String CALLBACK_CONFIRM_BONUS = "cart:confirm-bonus";
    public static final String CALLBACK_SLOT_MENU = "cart:slotmenu";
    public static final String CALLBACK_SLOT_PREFIX = "cart:slot:";
    public static final String CALLBACK_CANCEL = "cart:cancel";

    /** The cart itself: what is in it, what could not be found, what Silpo warned about, what it costs. */
    public String cartText(CartSummary summary, OfferedSlot slot) {
        StringBuilder text = new StringBuilder("Зібрав кошик на тиждень:\n");
        for (BasketItem item : summary.items()) {
            text.append("\n— ").append(item.name());
            if (item.quantity() != null) {
                text.append(" — ").append(amount(item.quantity()));
                if (item.unit() != null) {
                    text.append(' ').append(item.unit());
                }
            }
            if (item.price() != null) {
                text.append(" — ").append(money(item.price())).append(" грн");
            }
        }
        if (!summary.unresolved().isEmpty()) {
            text.append("\n\nНе знайшов: ")
                    .append(String.join(", ", summary.unresolved()))
                    .append(" — можете додати вручну пізніше.");
        }
        for (String validation : summary.validations()) {
            text.append("\n⚠ ").append(validation);
        }
        text.append("\n\nРазом: ").append(money(summary.total())).append(" грн");
        text.append("\n\nДоставка: ").append(slot == null ? "слот ще не обрано" : slot.label());
        if (summary.bonusDecisionPending()) {
            text.append("\nНа рахунку ")
                    .append(amount(summary.bonusAvailable()))
                    .append(" бонусів — можу списати їх на це замовлення.");
        }
        return text.toString();
    }

    public List<TelegramButton> cartButtons(CartSummary summary, boolean hasAlternativeSlots) {
        List<TelegramButton> buttons = new ArrayList<>();
        buttons.add(TelegramButton.callback("Підтвердити", CALLBACK_CONFIRM));
        if (summary.bonusDecisionPending()) {
            buttons.add(TelegramButton.callback(
                    "Підтвердити + %s бонусів".formatted(amount(summary.bonusAvailable())), CALLBACK_CONFIRM_BONUS));
        }
        if (hasAlternativeSlots) {
            buttons.add(TelegramButton.callback("Інший час", CALLBACK_SLOT_MENU));
        }
        buttons.add(TelegramButton.callback("Скасувати", CALLBACK_CANCEL));
        return buttons;
    }

    public String slotMenuText() {
        return "Коли зручно прийняти доставку?";
    }

    public List<TelegramButton> slotButtons(List<OfferedSlot> slots) {
        List<TelegramButton> buttons = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            buttons.add(TelegramButton.callback(slots.get(i).label(), CALLBACK_SLOT_PREFIX + i));
        }
        return buttons;
    }
```

(replace the old `cartText(CartSummary summary)` and `cartButtons(CartSummary summary)` methods entirely — there is only one caller of each, in `CartConfirmationService`, updated in the same step below)

`CartConfirmationService.java` — add imports:

```java
import com.silporestockai.model.CartContext;
import com.silporestockai.model.OfferedSlot;
```

Add the two new context keys next to the existing ones:

```java
    private static final String KEY_ORDER_ID = "orderId";
    private static final String KEY_SUMMARY = "summary";
    private static final String KEY_SLOTS = "slots";
    private static final String KEY_SLOT = "slot";
```

Replace the body of `present(User user, List<ShoppingListItem> items, OrderType type)` from the point `summary` is known onward:

```java
        List<OfferedSlot> slots = slotsFor(user.getId());
        OfferedSlot selectedSlot = slots.stream()
                .filter(slot -> slot.id().equals(summary.deliverySlot()))
                .findFirst()
                .orElse(null);

        CustomerOrder order = customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(type)
                .items(summary.items())
                .deliverySlot(summary.deliverySlot())
                .status(OrderStatus.DRAFT)
                .silpoCartId(summary.cartId())
                .createdAt(Instant.now())
                .build());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put(KEY_ORDER_ID, order.getId().toString());
        context.put(KEY_SUMMARY, asMap(summary));
        context.put(KEY_SLOTS, slots.stream().map(CartConfirmationService::asMap).toList());
        context.put(KEY_SLOT, summary.deliverySlot());
        conversationStateService.save(chatId, ConversationFlow.CART_CONFIRMATION, STEP_AWAITING_DECISION, context);

        telegramOutboundService.sendMessageWithButtons(
                chatId,
                cartMessageService.cartText(summary, selectedSlot),
                cartMessageService.cartButtons(summary, !slots.isEmpty()));
        log.info("presented cart {} as draft order {} to user {}", summary.cartId(), order.getId(), user.getId());
```

(the `CustomerOrder.builder()...deliverySlot(summary.deliverySlot())` line is unchanged from before — still the id the cart was actually built with; Task 2 changes it if the user picks something different)

Add the slot-fetching helper (mirrors `ReorderConfirmationService.slotsFor` exactly, adapted to fetch its own `CartContext` since `present` has none in scope here):

```java
    /** No slots is not a reason to hide a finished order: checkout can still pick one. */
    private List<OfferedSlot> slotsFor(UUID userId) {
        try {
            CartContext context = cartBuildingService.getOrCreateCartContext(userId);
            return cartBuildingService.offeredTimeSlots(userId, context);
        } catch (RuntimeException e) {
            log.warn("could not read time slots for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }
```

Restructure `handle`'s dispatch from a `switch` to an `if`/`else if` chain so a `startsWith` arm fits naturally (mirrors `ReorderConfirmationService.handle`'s exact shape):

```java
        String data = tap.data();
        if (CartMessageService.CALLBACK_CONFIRM.equals(data)) {
            confirm(user, order, state, summary, false);
        } else if (CartMessageService.CALLBACK_CONFIRM_BONUS.equals(data)) {
            confirm(user, order, state, summary, true);
        } else if (CartMessageService.CALLBACK_SLOT_MENU.equals(data)) {
            telegramOutboundService.sendMessageWithButtons(
                    tap.chatId(), cartMessageService.slotMenuText(), cartMessageService.slotButtons(slotsOf(state)));
        } else if (data.startsWith(CartMessageService.CALLBACK_SLOT_PREFIX)) {
            pickSlot(user, state, order, summary, data.substring(CartMessageService.CALLBACK_SLOT_PREFIX.length()));
        } else if (CartMessageService.CALLBACK_CANCEL.equals(data)) {
            cancel(user, order);
        } else {
            log.debug("ignoring unknown callback {} for chat {}", data, tap.chatId());
        }
```

(this changes `confirm`'s signature to take `state` — Task 2 needs it there to read `KEY_SLOT`; add the parameter now even though this task's `confirm` body does not yet use it, so the signature only changes once)

Add `pickSlot` and `slotsOf`, mirroring `ReorderConfirmationService.pickSlot`/`slotsOf` exactly:

```java
    private void pickSlot(User user, ConversationState state, CustomerOrder order, CartSummary summary, String indexRaw) {
        List<OfferedSlot> slots = slotsOf(state);
        int index;
        try {
            index = Integer.parseInt(indexRaw);
        } catch (NumberFormatException e) {
            return;
        }
        if (index < 0 || index >= slots.size()) {
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());
        context.put(KEY_SLOT, slots.get(index).id());
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.CART_CONFIRMATION, STEP_AWAITING_DECISION, context);
        telegramOutboundService.sendMessageWithButtons(
                user.getTelegramChatId(),
                cartMessageService.cartText(summary, slots.get(index)),
                cartMessageService.cartButtons(summary, !slots.isEmpty()));
    }

    private static List<OfferedSlot> slotsOf(ConversationState state) {
        Object slots = state.getContext().get(KEY_SLOTS);
        if (!(slots instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(node -> MAPPER.convertValue(node, OfferedSlot.class)).toList();
    }
```

Update `confirm`'s signature to accept `ConversationState state` as its second parameter (right after `user`, before `order`) — the body is otherwise unchanged in this task:

```java
    private void confirm(User user, CustomerOrder order, ConversationState state, CartSummary summary, boolean spendBonuses) {
```

Update the two call sites already written above (`confirm(user, order, state, summary, false)` / `confirm(user, order, state, summary, true)`) — they already pass `state` in the right position.

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.silporestockai.integration.CartConfirmationIntegrationTest"`
Expected: PASS — all tests, including the two new ones and every pre-existing one (the pre-existing ones never assert on `deliverySlot` at confirm time in a way this task's unchanged `confirm` body would break).

**Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/telegram/CartMessageService.java \
        src/main/java/com/silporestockai/service/CartConfirmationService.java \
        src/test/java/com/silporestockai/integration/CartConfirmationIntegrationTest.java
git commit -m "Show the delivery slot on the cart and let it be browsed, unbooked (task 34)"
```

---

### Task 2: Book the picked slot at confirm time

**Files:**
- Modify: `src/main/java/com/silporestockai/service/CartConfirmationService.java`
- Test: `src/test/java/com/silporestockai/integration/CartConfirmationIntegrationTest.java`

**Interfaces:**
- Consumes: `SilpoMcpClient.callTool(String, Map<String, Object>, UUID)` (existing, already imported in this class for bonus application), `TOOL_UPDATE_CART` (existing constant, same class).
- Produces: nothing new for later tasks — this closes out task 34.

**Step 1: Write the failing tests**

Add to `CartConfirmationIntegrationTest`:

```java
    @Test
    void confirmingAfterPickingADifferentSlotBooksItFirst() throws Exception {
        User user = presentedCart();

        tapButton(1, CartMessageService.CALLBACK_SLOT_MENU);
        tapButton(2, CartMessageService.CALLBACK_SLOT_PREFIX + "1");
        tapButton(3, CartMessageService.CALLBACK_CONFIRM);

        assertThat(MCP.calledTools()).contains("silpo_update_shopping_cart");
        CustomerOrder order = customerOrderRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId())
                .getFirst();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getDeliverySlot()).isEqualTo("slot-2");
    }

    @Test
    void confirmingWithoutChangingTheSlotNeverCallsUpdateForIt() throws Exception {
        presentedCart();

        tapButton(1, CartMessageService.CALLBACK_CONFIRM);

        assertThat(MCP.calledTools()).doesNotContain("silpo_update_shopping_cart");
    }

    @Test
    void aFailedSlotBookingStillLeavesAConfirmedOrder() throws Exception {
        User user = presentedCart();
        MCP.failTool("silpo_update_shopping_cart");

        tapButton(1, CartMessageService.CALLBACK_SLOT_MENU);
        tapButton(2, CartMessageService.CALLBACK_SLOT_PREFIX + "1");
        tapButton(3, CartMessageService.CALLBACK_CONFIRM);

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CONFIRMED))
                .hasSize(1);
    }
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.silporestockai.integration.CartConfirmationIntegrationTest"`
Expected: `confirmingAfterPickingADifferentSlotBooksItFirst` FAILs — `confirm` never calls `silpo_update_shopping_cart` for a slot, and `order.getDeliverySlot()` stays `"slot-1"`. The other two pass already (nothing to book yet in this code, and `MCP.failTool` has nothing to affect) — that is fine, they exist to lock in the current behavior as Task 2's code lands, not to fail first.

**Step 3: Write the minimal implementation**

In `confirm`, right after the method's existing `long chatId = user.getTelegramChatId();` line and before `boolean bonusesApplied = ...`:

```java
        String selectedSlotId = String.valueOf(state.getContext().get(KEY_SLOT));
        if (!selectedSlotId.equals(summary.deliverySlot())) {
            bookSlot(user.getId(), summary.cartId(), selectedSlotId);
            order.setDeliverySlot(selectedSlotId);
        }
```

Add the booking helper right below `applyBonuses`, matching its exact best-effort shape:

```java
    /** A refusal is reported, not fatal: checkout can still fix the delivery window if this call failed. */
    private void bookSlot(UUID userId, String cartId, String slotId) {
        try {
            McpToolResponse response =
                    silpoMcpClient.callTool(TOOL_UPDATE_CART, Map.of("cartId", cartId, "timeslot", slotId), userId);
            if (response.isError()) {
                log.warn("Silpo declined to rebook cart {} onto slot {}", cartId, slotId);
            }
        } catch (RuntimeException e) {
            log.warn("could not rebook cart {} onto slot {}: {}", cartId, slotId, e.getMessage());
        }
    }
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.silporestockai.integration.CartConfirmationIntegrationTest"`
Expected: PASS — all tests.

**Step 5: Run the full suite**

Run: `make format && make test`
Expected: all green. This closes out task 34.

**Step 6: Commit**

```bash
git add src/main/java/com/silporestockai/service/CartConfirmationService.java \
        src/test/java/com/silporestockai/integration/CartConfirmationIntegrationTest.java
git commit -m "Book the delivery slot the household actually picked, at confirm time (task 34)"
```

---

## Self-Review Notes

- **Spec coverage:** since the Notion page carries no acceptance criteria, this is measured against the reasoning in `docs/OVERNIGHT_QUESTIONS.md`'s "Task 34" entry instead — surfacing delivery info before checkout (Task 1's cart-text change), letting it be actively changed (Task 1's slot menu), the change actually taking effect (Task 2's booking-at-confirm). Both explicitly-scoped-out pieces (delivery-type switching, address text) have no task, by design.
- **Type consistency:** `confirm`'s new `ConversationState state` parameter is threaded through both call sites in the same task that introduces it (Task 1), so Task 2 finds it already there rather than needing its own signature change.
- **Known risk called out explicitly:** the eager-vs-lazy booking distinction is easy to regress (an obvious-looking "just call bookSlot from pickSlot too, why not" edit would silently make every browsed slot get provisionally booked with Silpo). `pickingADifferentSlotUpdatesTheMessageWithoutBookingItYet` (Task 1) and `confirmingWithoutChangingTheSlotNeverCallsUpdateForIt` (Task 2) both exist specifically to catch that regression, not just to pad coverage.
