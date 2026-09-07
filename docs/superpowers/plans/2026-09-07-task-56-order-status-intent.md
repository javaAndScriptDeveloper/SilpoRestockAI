# Task 56 — «Де моє замовлення» Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `WHERE_IS_MY_ORDER` intent that answers «де моє замовлення» from the household's real Silpo
order history over MCP, read-only.

**Architecture:** The MCP-history read that `PastOrderSeedService` (task 35) already owns moves into a thin
`OrderHistoryService`; the seeding service keeps seeding and calls it. The new intent asks the same service
for the newest order and presents its status, sum and delivery time. Nothing is written anywhere.

**Tech Stack:** Spring Boot 4, Java 21 records, the Silpo MCP client, Claude structured output for
classification, JUnit 5 + MockMvc + `StubMcpServer`/`StubAnthropicServer`/`StubTelegramServer`.

**Spec:** Notion «56. Order status check: «де моє замовлення» — read-only intent через MCP order history»
(`https://app.notion.com/p/3d47227def1c810b91e2ef4b4c17c55a`).

## Global Constraints

- Read-only: no new tables, no Liquibase changeset, no write to `conversation_state`.
- One place calls the MCP history tools; a second caller is a bug (task 57 reuses this one).
- Ukrainian «ти» register, honest copy: never state a status the response did not carry.
- ArchUnit: constructor injection, `...Service` suffix for `@Service` beans, Telegram SDK stays in
  `service.telegram`.
- Spotless palantir: `make format` before committing.

---

### Task 1: `OrderHistoryService` — the one MCP-history reader

**Files:**
- Create: `src/main/java/com/silporestockai/service/OrderHistoryService.java`
- Modify: `src/main/java/com/silporestockai/service/PastOrderSeedService.java`
- Modify: `src/main/java/com/silporestockai/model/PastOrderSummary.java`
- Modify: `src/main/java/com/silporestockai/utils/McpResponses.java`
- Test: `src/test/java/com/silporestockai/integration/PastOrderSeedIntegrationTest.java` (must stay green)

**Interfaces:**
- Produces:
  - `List<PastOrderSummary> OrderHistoryService.recentOrders(UUID userId)` — newest first, at most 5,
    both tools, each tolerated on its own.
  - `static String OrderHistoryService.label(PastOrderSummary order)` — «12 серп · 23 поз. · 1234.50 грн».
  - `static String OrderHistoryService.positions(int count)` — Ukrainian plural of «позиція».
  - `PastOrderSummary(String orderId, String source, String dateLabel, BigDecimal total, String status,
    String deliveryLabel, List<PastOrderLine> lines)`.
  - `McpResponses.ORDER_STATUS`, `McpResponses.DELIVERY_SLOT`.

- [ ] **Step 1: Extend the response-key block and the summary record**

`McpResponses`:

```java
    /** An order's own state and promised delivery time (task 56) — read-only, best effort. */
    public static final String[] ORDER_STATUS = {"status", "orderStatus", "state", "statusName"};

    public static final String[] DELIVERY_SLOT = {"deliveryTime", "deliveryDate", "timeslot", "timeSlot"};
```

`PastOrderSummary` gains two components between `total` and `lines`, with javadoc lines:

```java
public record PastOrderSummary(
        String orderId,
        String source,
        String dateLabel,
        BigDecimal total,
        String status,
        String deliveryLabel,
        List<PastOrderLine> lines) {
```

- [ ] **Step 2: Move the history read into `OrderHistoryService`**

Cut `ORDER_TOOLS`, `MAX_OFFERED` (rename `MAX_RECENT`), `recentOrders`, `argumentsFor`, `parse`, `toList`,
`parseDate`, `label`, `positions` and `DATE_LABEL` out of `PastOrderSeedService` into the new class; make
`recentOrders`, `label` and `positions` public. In `parse`, read the two new fields off the order node:

```java
            orders.add(new PastOrderSummary(
                    McpResponses.findString(node, McpResponses.ORDER_ID).orElse(null),
                    source,
                    McpResponses.findString(node, McpResponses.ORDER_DATE).orElse(""),
                    McpResponses.findNumber(node, McpResponses.TOTAL).orElse(null),
                    McpResponses.findString(node, McpResponses.ORDER_STATUS).orElse(null),
                    deliveryLabel(node),
                    lines));
```

- [ ] **Step 3: Point `PastOrderSeedService` at it**

Replace its `silpoMcpClient` / `cartBuildingService` fields with `orderHistoryService`, and every
`recentOrders(...)` / `label(...)` / `positions(...)` call with the delegated one.

- [ ] **Step 4: Run the existing task-35 suite — it must be untouched behaviour**

Run: `./gradlew test --tests '*PastOrderSeedIntegrationTest' --tests '*ArchitectureTest'`
Expected: PASS, 4/4 plus the architecture rules.

- [ ] **Step 5: Commit**

```bash
make format && git add -A && git commit
```

---

### Task 2: The intent itself

**Files:**
- Modify: `src/main/java/com/silporestockai/service/OrderHistoryService.java`
- Modify: `src/main/java/com/silporestockai/service/IntentRouterService.java`
- Modify: `src/main/resources/prompts/intent-router-system.txt`
- Test: `src/test/java/com/silporestockai/integration/OrderStatusIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `OrderHistoryService.recentOrders`, `OrderHistoryService.label`.
- Produces: `void OrderHistoryService.showStatus(User user, boolean detailed)`.

- [ ] **Step 1: Write the failing test**

`OrderStatusIntegrationTest`, modelled on `PastOrderSeedIntegrationTest` (same stubs, same webhook helper):

```java
    @Test
    void answersWithTheNewestOrderFromTheRealHistory() throws Exception {
        CLAUDE.respondWithText(CLASSIFIED);
        MCP.respondToTool("silpo_get_my_online_orders", ONLINE_ORDERS);
        MCP.respondToTool("silpo_get_my_offline_orders", "{\"orders\":[]}");

        sendText(1, "де моє замовлення?");

        assertThat(lastMessageText())
                .contains("1 вер")
                .contains("Готується")
                .contains("1234.56 грн")
                .contains("7 вересня, 10:00–12:00");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void saysSoWhenTheHistoryIsEmpty() throws Exception { /* both tools "{\"orders\":[]}" */ }

    @Test
    void saysSoWhenTheHistoryToolFails() throws Exception { /* MCP.failTool(...) both */ }

    @Test
    void asksToConnectSilpoWhenThereIsNoToken() throws Exception { /* tokenRepository.deleteAll() */ }
```

with

```java
    private static final String CLASSIFIED =
            "{\"intent\":\"WHERE_IS_MY_ORDER\",\"confidence\":0.94,\"themeDescription\":null,\"targetDateTimeIso\":null}";

    private static final String ONLINE_ORDERS = """
            {"orders":[
              {"orderId":"A-100","date":"2026-08-20T10:00:00Z","total":310.5,"status":"Delivered","items":[]},
              {"orderId":"A-200","date":"2026-09-01T18:30:00Z","total":1234.56,"status":"Готується",
               "deliveryTime":{"from":"2026-09-07T10:00:00+03:00","to":"2026-09-07T12:00:00+03:00"},
               "items":[{"productId":"p-eggs","name":"Яйця С0 10шт","quantity":1,"unit":"шт","price":75.00}]}
            ]}""";
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*OrderStatusIntegrationTest'`
Expected: FAIL — the classifier answer parses to `UNKNOWN`, so the bot replies «Не зовсім зрозумів».

- [ ] **Step 3: Implement `showStatus`**

In `OrderHistoryService`: guard on `silpoAuthService.isConnected`, read `recentOrders`, and send

- not connected → «Спершу під'єднай акаунт «Сільпо» — без нього я не бачу твоїх замовлень.»
- a tool error or an exception on **every** tool → «Не зміг дістати статус із «Сільпо» — спробуй ще раз
  за кілька хвилин.» (distinguish "all calls failed" from "all calls answered empty")
- empty → «Не бачу замовлень в акаунті «Сільпо» — ні активних, ні минулих.»
- otherwise the newest order: date, status (only when the response carried one), sum, delivery slot; plus,
  when `detailed`, up to four older orders one per line.

Statuses come back as Silpo's own strings; map the English ones we have seen to Ukrainian and pass anything
else through verbatim rather than inventing a translation.

- [ ] **Step 4: Add the intent**

`IntentType.WHERE_IS_MY_ORDER`, a `case WHERE_IS_MY_ORDER -> orderHistoryService.showStatus(user, false);`
in `dispatch`, and the prompt entry, disambiguated from `PAST_ORDER_SEED`:

```
- WHERE_IS_MY_ORDER — питає про СТАТУС уже зробленого замовлення: де воно, коли приїде, що з доставкою.
  Приклади: "де моє замовлення", "коли приїде доставка", "що там із замовленням", "статус замовлення".
  Увага: "повтори моє минуле замовлення" / "зроби список як минулого разу" — це PAST_ORDER_SEED, а не цей намір.
```

- [ ] **Step 5: Run the new test and the neighbours**

Run: `./gradlew test --tests '*OrderStatusIntegrationTest' --tests '*PastOrderSeedIntegrationTest' --tests '*IntentRouterIntegrationTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
make format && git add -A && git commit
```
