# Task 57 — «📦 Замовлення» menu button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sixth persistent-menu button that shows the current order status, driven by the same
`OrderHistoryService` read task 56 built.

**Architecture:** The button is global navigation like «Список» and «Заплановані» — routed in
`TelegramRoutingService` above every flow, calling `orderHistoryService.showStatus(user, true)`. `true` is the
only difference from the chat intent: a management view lists the earlier orders too. The keyboard grows from
2×2 + 1 to an even 3×2.

**Tech Stack:** Spring Boot 4, the Telegram SDK behind `service.telegram`, JUnit 5 + MockMvc + stubs.

**Spec:** Notion «57. Active orders button: persistent menu entry showing current order status»
(`https://app.notion.com/p/3d47227def1c814da9b6cfaf6beff7bf`).

## Global Constraints

- No second MCP-history caller: the button calls `OrderHistoryService.showStatus`, nothing else.
- One history read per interaction — a button tap must not cost two Silpo calls.
- Telegram SDK types stay inside `service.telegram` (ArchUnit).
- Ukrainian «ти» register; «Інструкція» lists every button and every intent example.
- Spotless palantir: `make format` before committing.

---

### Task 1: The button, its route and the help text

**Files:**
- Modify: `src/main/java/com/silporestockai/service/telegram/MainMenuKeyboard.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Modify: `src/main/java/com/silporestockai/service/IntentRouterService.java` (the `HELP_TEXT` block)
- Test: `src/test/java/com/silporestockai/integration/OrderStatusIntegrationTest.java`

**Interfaces:**
- Consumes: `OrderHistoryService.showStatus(User user, boolean detailed)` from task 56.
- Produces: `MainMenuKeyboard.ORDERS` — the label `"📦 Замовлення"`, part of `LABELS` so
  `MainMenuKeyboard.isButton` recognises it as navigation rather than free text.

- [ ] **Step 1: Write the failing tests**

Append to `OrderStatusIntegrationTest`:

```java
    @Test
    void theButtonAnswersTheSameFactsInMoreDetail() throws Exception {
        MCP.respondToTool("silpo_get_my_online_orders", ONLINE_ORDERS);
        MCP.respondToTool("silpo_get_my_offline_orders", "{\"orders\":[]}");

        sendText(1, "📦 Замовлення");

        String text = lastMessageText();
        assertThat(text).contains("1234.56 грн").contains("Готується").contains("7 вересня, 10:00–12:00");
        assertThat(text).contains("20 серп").contains("310.50 грн").contains("Доставлено");
        assertThat(CLAUDE.callCount()).isZero();
        assertThat(MCP.callCount("silpo_get_my_online_orders")).isEqualTo(1);
    }

    @Test
    void theButtonSaysTheSameThingAsTheIntentWhenTheHistoryIsEmpty() throws Exception {
        MCP.respondToTool("silpo_get_my_online_orders", "{\"orders\":[]}");
        MCP.respondToTool("silpo_get_my_offline_orders", "{\"orders\":[]}");

        sendText(1, "📦 Замовлення");

        assertThat(lastMessageText()).contains("Не бачу замовлень");
    }
```

`MCP.callCount` counts JSON-RPC methods, not tool names, so if it does not answer per tool, assert on
`MCP.calledTools().stream().filter("silpo_get_my_online_orders"::equals).count()` instead.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew test --tests '*OrderStatusIntegrationTest'`
Expected: FAIL — the label is free text, so it reaches the classifier and comes back unclassified.

- [ ] **Step 3: Add the label and the 3×2 layout**

```java
    public static final String ORDERS = "📦 Замовлення";
    ...
    private static final List<String> LABELS = List.of(LIST, ORDERS, SCHEDULED, FORM, HELP, FEEDBACK);

    public static ReplyKeyboardMarkup markup() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(LIST, ORDERS))
                .keyboardRow(new KeyboardRow(SCHEDULED, FORM))
                .keyboardRow(new KeyboardRow(HELP, FEEDBACK))
                .resizeKeyboard(true)
                .build();
    }
```

Update the class javadoc: six labels, three rows of two, and why «Фідбек» now shares a row with «Інструкція»
(both are about the bot rather than about groceries).

- [ ] **Step 4: Route it**

Next to the `/list` branch in `TelegramRoutingService.handle`, add the field
`private final OrderHistoryService orderHistoryService;` and

```java
        if (incoming instanceof TelegramIncomingUpdate.Text orders
                && matches(orders.text(), "/orders", MainMenuKeyboard.ORDERS)) {
            orderHistoryService.showStatus(user, true);
            return;
        }
```

- [ ] **Step 5: Add both to «Інструкція»**

In `IntentRouterService.HELP_TEXT`: a button line
`📦 Замовлення — статус останнього замовлення: що з ним і коли приїде.` next to the other buttons, and an
intent example `— «Де моє замовлення?» — статус і час доставки з «Сільпо».`

- [ ] **Step 6: Run the new tests and every neighbour that touches the menu**

Run: `./gradlew test --tests '*OrderStatusIntegrationTest' --tests '*FeedbackIntegrationTest' --tests '*TelegramWebhookIntegrationTest' --tests '*ProfileReeditIntegrationTest'`
Expected: PASS.

- [ ] **Step 7: Full suite, then commit**

Run: `make test` with `bootRun` stopped first (the app and the tests share `build/classes`).

```bash
make format && git add -A && git commit
```
