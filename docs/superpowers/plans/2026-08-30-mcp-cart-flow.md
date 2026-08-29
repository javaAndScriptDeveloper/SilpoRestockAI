# MCP Cart Session Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A list of shopping items becomes a populated, verified Silpo cart through the six documented MCP tool calls, with unresolved items reported rather than dropped and an INFO log a demo can be recorded from.

**Architecture:** `CartBuildingService` owns one public method per documented step plus `buildCart` that runs all six in order. Responses are read through `utils/McpResponses`, which searches the JSON tree for known key names rather than binding to a schema nobody here has seen. Nothing is persisted and nothing reaches Telegram — task 10 owns both.

**Tech Stack:** Java 25, Spring Boot 4.1.0, `SilpoMcpClient` (task 02), Jackson, Testcontainers PostgreSQL, `StubMcpServer` over `com.sun.net.httpserver`, logback `ListAppender`, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-30-mcp-cart-flow-design.md`

## Global Constraints

- Base package `com.silporestockai`. The service goes in `service`, the records in `model`, the extraction helper in `utils`, the exception in `exception`.
- **ArchUnit is enforced.** Classes under `..service..` end with `Service`, including anything nested or anonymous — no anonymous `TypeReference`, no inner records. Constructor injection only.
- **No schema change.** This task persists nothing.
- **Spotless (palantir)** — `make format` before every commit.
- Every MCP call logs at INFO through the service. The Silpo client's own DEBUG logging stays as it is.
- User-facing copy: none in this task. Log messages are English, like the rest of the codebase.

---

### Task 1: Read a response whose shape nobody has seen

**Files:**
- Create: `src/main/java/com/silporestockai/utils/McpResponses.java`
- Test: `src/test/java/com/silporestockai/utils/McpResponsesTest.java`

**Interfaces:**
- Consumes: `McpToolResponse(String text, Object structuredContent, boolean isError)` from task 02.
- Produces:
  - `McpResponses.tree(McpToolResponse) -> JsonNode`
  - `McpResponses.findString(JsonNode, String...) -> Optional<String>`
  - `McpResponses.findNumber(JsonNode, String...) -> Optional<BigDecimal>`
  - `McpResponses.findNode(JsonNode, String...) -> Optional<JsonNode>`
  - `McpResponses.findArray(JsonNode, String...) -> List<JsonNode>`
  - Key-name constants: `CART_ID`, `BRANCH_ID`, `COMPANY_ID`, `PRODUCT_ID`, `DELIVERY_TYPE`, `TIMESLOT`, `TIME_SLOTS`, `PRODUCTS`, `ITEMS`, `NAME`, `QUANTITY`, `UNIT`, `PRICE`, `TOTAL`, `VALIDATIONS`, `LOYALTY`, `BONUS_AVAILABLE`, `BONUS_REQUESTED`, `LOYALTY_ENABLED`, `CHECKOUT_WEB`, `CHECKOUT_MOBILE`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/utils/McpResponsesTest.java`:

```java
package com.silporestockai.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MCP responses are read by key name, wherever the server nested it")
class McpResponsesTest {

    @Test
    void readsAValueFromStructuredContent() {
        McpToolResponse response = new McpToolResponse(null, Map.of("cartId", "c-1"), false);

        assertThat(McpResponses.findString(McpResponses.tree(response), McpResponses.CART_ID))
                .contains("c-1");
    }

    @Test
    void fallsBackToTheTextBlockWhenThereIsNoStructuredContent() {
        McpToolResponse response = new McpToolResponse("{\"data\":{\"cartId\":\"c-2\"}}", null, false);

        assertThat(McpResponses.findString(McpResponses.tree(response), McpResponses.CART_ID))
                .contains("c-2");
    }

    @Test
    void findsAKeyNestedAnyDepthDown() {
        McpToolResponse response =
                new McpToolResponse(null, Map.of("result", Map.of("cart", Map.of("branchId", "b-9"))), false);

        assertThat(McpResponses.findString(McpResponses.tree(response), McpResponses.BRANCH_ID))
                .contains("b-9");
    }

    @Test
    void acceptsAnyOfSeveralKeyNamesInOrder() {
        JsonNode tree = McpResponses.tree(new McpToolResponse(null, Map.of("id", "c-3"), false));

        // cartId is preferred; id is the documented fallback for a response that only carries one identifier.
        assertThat(McpResponses.findString(tree, McpResponses.CART_ID)).contains("c-3");
    }

    @Test
    void readsNumbersAndArrays() {
        McpToolResponse response = new McpToolResponse(
                null,
                Map.of(
                        "loyalty", Map.of("bonusAvailable", 120.5),
                        "products", List.of(Map.of("productId", "p-1"), Map.of("productId", "p-2"))),
                false);
        JsonNode tree = McpResponses.tree(response);

        assertThat(McpResponses.findNumber(tree, McpResponses.BONUS_AVAILABLE)).contains(new java.math.BigDecimal("120.5"));
        assertThat(McpResponses.findArray(tree, McpResponses.PRODUCTS)).hasSize(2);
    }

    @Test
    void answersEmptyRatherThanThrowingForNonsense() {
        JsonNode tree = McpResponses.tree(new McpToolResponse("not json at all", null, false));

        assertThat(McpResponses.findString(tree, McpResponses.CART_ID)).isEmpty();
        assertThat(McpResponses.findArray(tree, McpResponses.PRODUCTS)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*McpResponsesTest*'`
Expected: FAIL — `McpResponses` does not exist.

- [ ] **Step 3: Write the helper**

Create `src/main/java/com/silporestockai/utils/McpResponses.java`:

```java
package com.silporestockai.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.silporestockai.client.mcp.McpToolResponse;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads values out of a Silpo MCP tool response without binding to a schema.
 *
 * <p>No real guest account exists in this repository, so the exact shape these tools answer with has never been
 * observed. Rather than guess a nesting and fail as a silent null in front of a jury, every lookup is a breadth-first
 * search for a key name, and every key name this application relies on is in the block below — the one place to fix
 * when the live server disagrees.
 */
@Slf4j
public final class McpResponses {

    public static final String[] CART_ID = {"cartId", "shoppingCartId", "id"};
    public static final String[] BRANCH_ID = {"branchId", "filialId"};
    public static final String[] COMPANY_ID = {"companyId"};
    public static final String[] PRODUCT_ID = {"productId", "id"};
    public static final String[] DELIVERY_TYPE = {"deliveryType", "type"};
    public static final String[] TIMESLOT = {"timeslot", "timeSlot", "slot"};
    public static final String[] TIME_SLOTS = {"timeSlots", "timeslots", "slots"};
    public static final String[] PRODUCTS = {"products", "items", "results"};
    public static final String[] ITEMS = {"items", "products", "lines"};
    public static final String[] NAME = {"name", "title", "query", "requestedName"};
    public static final String[] QUANTITY = {"quantity", "amount", "count"};
    public static final String[] UNIT = {"unit", "measure"};
    public static final String[] PRICE = {"price", "sum", "amount"};
    public static final String[] TOTAL = {"total", "totalSum", "sum"};
    public static final String[] VALIDATIONS = {"validations", "errors", "warnings"};
    public static final String[] LOYALTY = {"loyalty"};
    public static final String[] BONUS_AVAILABLE = {"bonusAvailable"};
    public static final String[] BONUS_REQUESTED = {"bonusRequested"};
    public static final String[] LOYALTY_ENABLED = {"isEnabled", "enabled"};
    public static final String[] CHECKOUT_WEB = {"checkoutWebLink", "webLink"};
    public static final String[] CHECKOUT_MOBILE = {"checkoutMobileLink", "mobileLink"};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpResponses() {}

    /** The response as a tree: structured content when the server sent it, otherwise the text block parsed as JSON. */
    public static JsonNode tree(McpToolResponse response) {
        if (response == null) {
            return MissingNode.getInstance();
        }
        if (response.structuredContent() != null) {
            return MAPPER.valueToTree(response.structuredContent());
        }
        if (response.text() == null || response.text().isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return MAPPER.readTree(response.text());
        } catch (Exception e) {
            log.debug("MCP response was not JSON: {}", e.getMessage());
            return MissingNode.getInstance();
        }
    }

    public static Optional<JsonNode> findNode(JsonNode root, String... keys) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(root == null ? MissingNode.getInstance() : root);
        while (!queue.isEmpty()) {
            JsonNode node = queue.poll();
            if (node.isObject()) {
                for (String key : keys) {
                    JsonNode found = node.get(key);
                    if (found != null && !found.isNull()) {
                        return Optional.of(found);
                    }
                }
            }
            node.forEach(queue::add);
        }
        return Optional.empty();
    }

    public static Optional<String> findString(JsonNode root, String... keys) {
        return findNode(root, keys).filter(JsonNode::isValueNode).map(JsonNode::asText);
    }

    public static Optional<BigDecimal> findNumber(JsonNode root, String... keys) {
        return findNode(root, keys).filter(JsonNode::isNumber).map(JsonNode::decimalValue);
    }

    /** The first array found under any of the key names, or empty — never null, never a partial list. */
    public static List<JsonNode> findArray(JsonNode root, String... keys) {
        return findNode(root, keys)
                .filter(JsonNode::isArray)
                .map(array -> {
                    List<JsonNode> items = new ArrayList<>();
                    array.forEach(items::add);
                    return items;
                })
                .orElseGet(List::of);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests '*McpResponsesTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Format and commit**

```bash
make format
./gradlew test --tests '*McpResponsesTest*'
git add src/main/java/com/silporestockai/utils/McpResponses.java \
        src/test/java/com/silporestockai/utils/McpResponsesTest.java
git commit -m "Read MCP responses by key name rather than by schema"
```

---

### Task 2: The cart context and the mandatory slot check

**Files:**
- Create: `src/main/java/com/silporestockai/model/CartContext.java`
- Create: `src/main/java/com/silporestockai/exception/CartBuildException.java`
- Create: `src/main/java/com/silporestockai/service/CartBuildingService.java`
- Modify: `src/test/java/com/silporestockai/support/StubMcpServer.java`
- Test: `src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java`

**Interfaces:**
- Consumes: `SilpoMcpClient.callTool(String, Map, UUID)`; `McpResponses`; `UserAccountService.findOrCreate`; `SilpoOAuthTokenRepository`; `TokenCipher`.
- Produces:
  - `record CartContext(String cartId, String branchId, String companyId, String deliveryType, String timeslot)`
  - `CartBuildingService.getOrCreateCartContext(UUID userId) -> CartContext`
  - `CartBuildingService.validateTimeSlot(UUID userId, CartContext context) -> String` (the chosen slot)
  - `CartBuildException extends ApplicationException` (502)

- [ ] **Step 1: Give the MCP stub per-tool responses**

`StubMcpServer` answers every `tools/call` with one canned string, which cannot express a six-step
conversation. In `src/test/java/com/silporestockai/support/StubMcpServer.java`, add:

```java
    /** Canned JSON per tool name, returned as the tool's single text block. */
    private final Map<String, String> toolResponses = new ConcurrentHashMap<>();

    /** Names of the tools called, in order — this is what a sequence test asserts on. */
    private final List<String> calledTools = Collections.synchronizedList(new ArrayList<>());

    /** Makes {@code toolName} answer with {@code json}. Tools without a scripted answer keep the old canned text. */
    public void respondToTool(String toolName, String json) {
        toolResponses.put(toolName, json);
    }

    public List<String> calledTools() {
        return List.copyOf(calledTools);
    }
```

`reset()` gains `toolResponses.clear();` and `calledTools.clear();`.

`handle` records the tool name before dispatching — inside the existing `callCounts` block, add:

```java
            if ("tools/call".equals(method)) {
                calledTools.add(request.path("params").path("name").asText());
            }
```

and `resultFor(method)` becomes `resultFor(method, request)`, with the `tools/call` branch reading the
scripted answer:

```java
            case "tools/call" -> {
                String tool = request.path("params").path("name").asText();
                String json = toolResponses.getOrDefault(tool, "stub tool result");
                yield Map.of("content", List.of(Map.of("type", "text", "text", json)), "isError", false);
            }
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.model.CartContext;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CartBuildingService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("the documented six-call cart sequence, against a stub MCP server")
class CartBuildingIntegrationTest extends AbstractIntegrationTest {

    private static final StubMcpServer MCP = startMcp();

    @Autowired
    private CartBuildingService cartBuildingService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
    }

    @AfterAll
    static void stopStub() {
        MCP.close();
    }

    @BeforeEach
    void clean() {
        MCP.reset();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    UUID connectedUser(long chatId) {
        User user = userAccountService.findOrCreate(chatId);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user.getId();
    }

    void scriptCartTools() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-1\"}");
        MCP.respondToTool(
                "silpo_get_shopping_cart_by_id",
                """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3",\
                "deliveryType":"delivery","timeslot":null,"items":[]}""");
        MCP.respondToTool(
                "silpo_get_time_slots",
                "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\",\"to\":\"20:00\"}]}");
    }

    @Test
    void readsTheCartAndItsBranchFromTheFirstTwoCalls() {
        UUID userId = connectedUser(8401L);
        scriptCartTools();

        CartContext context = cartBuildingService.getOrCreateCartContext(userId);

        assertThat(context.cartId()).isEqualTo("cart-1");
        assertThat(context.branchId()).isEqualTo("branch-7");
        assertThat(context.companyId()).isEqualTo("company-3");
        assertThat(context.deliveryType()).isEqualTo("delivery");
        assertThat(MCP.calledTools())
                .containsExactly("silpo_get_my_shopping_cart", "silpo_get_shopping_cart_by_id");
    }

    @Test
    void picksTheFirstOfferedTimeSlot() {
        UUID userId = connectedUser(8402L);
        scriptCartTools();
        CartContext context = cartBuildingService.getOrCreateCartContext(userId);

        String slot = cartBuildingService.validateTimeSlot(userId, context);

        assertThat(slot).isEqualTo("slot-1");
    }

    @Test
    void refusesToBuildACartThatCannotBeDelivered() {
        UUID userId = connectedUser(8403L);
        scriptCartTools();
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[]}");
        CartContext context = cartBuildingService.getOrCreateCartContext(userId);

        assertThatThrownBy(() -> cartBuildingService.validateTimeSlot(userId, context))
                .isInstanceOf(CartBuildException.class)
                .hasMessageContaining("time slot");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests '*CartBuildingIntegrationTest*'`
Expected: FAIL — `CartBuildingService` does not exist.

- [ ] **Step 4: Write the types**

Create `src/main/java/com/silporestockai/model/CartContext.java`:

```java
package com.silporestockai.model;

/**
 * What the Silpo cart tools say about a guest's current cart, and everything later calls in the sequence need.
 *
 * @param cartId the cart every later call addresses
 * @param branchId the store the cart is bound to; product search needs it
 * @param companyId Silpo's company identifier for that branch
 * @param deliveryType delivery or pickup, as the cart already has it
 * @param timeslot the slot the cart already carries, null when none is chosen yet
 */
public record CartContext(String cartId, String branchId, String companyId, String deliveryType, String timeslot) {}
```

Create `src/main/java/com/silporestockai/exception/CartBuildException.java`:

```java
package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Raised when a cart cannot be built at all — no cart id, or no deliverable time slot. */
public class CartBuildException extends ApplicationException {

    public CartBuildException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }
}
```

- [ ] **Step 5: Write the first three steps of the service**

Create `src/main/java/com/silporestockai/service/CartBuildingService.java`:

```java
package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.model.CartContext;
import com.silporestockai.utils.McpResponses;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Drives the documented Silpo cart sequence: cart, branch, slots, products, add, verify.
 *
 * <p>Each documented step is its own method. That is what makes the sequence legible in a log — and this log is a
 * deliverable: the hackathon asks for evidence that a real agent made real tool calls, and a console recording of it
 * is that evidence. The Silpo client logs at DEBUG, which is right for a library and too quiet for this.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartBuildingService {

    private static final String TOOL_MY_CART = "silpo_get_my_shopping_cart";
    private static final String TOOL_CART_BY_ID = "silpo_get_shopping_cart_by_id";
    private static final String TOOL_TIME_SLOTS = "silpo_get_time_slots";
    private static final String TOOL_FIND_PRODUCTS = "silpo_find_products_batch";
    private static final String TOOL_ADD_PRODUCTS = "silpo_add_or_update_cart_products";

    private final SilpoMcpClient silpoMcpClient;

    /** Steps 1 and 2: which cart, and which branch it is bound to. */
    public CartContext getOrCreateCartContext(UUID userId) {
        JsonNode myCart = call(userId, TOOL_MY_CART, Map.of());
        String cartId = McpResponses.findString(myCart, McpResponses.CART_ID)
                .orElseThrow(() -> new CartBuildException("Silpo returned no cart id for user " + userId));

        JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("cartId", cartId));
        CartContext context = new CartContext(
                cartId,
                McpResponses.findString(cart, McpResponses.BRANCH_ID).orElse(null),
                McpResponses.findString(cart, McpResponses.COMPANY_ID).orElse(null),
                McpResponses.findString(cart, McpResponses.DELIVERY_TYPE).orElse(null),
                McpResponses.findString(cart, McpResponses.TIMESLOT).orElse(null));
        log.info(
                "MCP ← cart {} branch {} company {} delivery {}",
                context.cartId(),
                context.branchId(),
                context.companyId(),
                context.deliveryType());
        return context;
    }

    /**
     * Step 3, and the one failure that is fatal. Adding products to a cart nobody can deliver moves the failure to
     * checkout, where it is someone else's problem and nobody's log line.
     */
    public String validateTimeSlot(UUID userId, CartContext context) {
        JsonNode slots = call(
                userId,
                TOOL_TIME_SLOTS,
                Map.of("branchId", nullSafe(context.branchId()), "deliveryType", nullSafe(context.deliveryType())));
        List<JsonNode> offered = McpResponses.findArray(slots, McpResponses.TIME_SLOTS);
        if (offered.isEmpty()) {
            throw new CartBuildException("Silpo offered no delivery time slot for branch " + context.branchId());
        }
        String slot = McpResponses.findString(offered.getFirst(), "id", "slotId", "code")
                .orElseThrow(() -> new CartBuildException("a time slot came back without an identifier"));
        log.info("MCP ← {} time slots, taking {}", offered.size(), slot);
        return slot;
    }

    private JsonNode call(UUID userId, String tool, Map<String, Object> arguments) {
        log.info("MCP → {} {}", tool, arguments);
        McpToolResponse response = silpoMcpClient.callTool(tool, arguments, userId);
        if (response.isError()) {
            throw new CartBuildException("Silpo tool %s reported an error".formatted(tool));
        }
        return McpResponses.tree(response);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests '*CartBuildingIntegrationTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 7: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/java/com/silporestockai/model/CartContext.java \
        src/main/java/com/silporestockai/exception/CartBuildException.java \
        src/main/java/com/silporestockai/service/CartBuildingService.java \
        src/test/java/com/silporestockai/support/StubMcpServer.java \
        src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java
git commit -m "Open a Silpo cart and refuse one nobody can deliver"
```

---

### Task 3: Products in, cart verified

**Files:**
- Create: `src/main/java/com/silporestockai/model/ResolvedProduct.java`
- Create: `src/main/java/com/silporestockai/model/CartSummary.java`
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java`
- Test: `src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java`

**Interfaces:**
- Consumes: everything from Task 2; `ShoppingListItem` (task 08); `BasketItem` (task 05).
- Produces:
  - `record ResolvedProduct(String requestedName, String productId, String companyId, String branchId, BigDecimal quantity, String unit)`
  - `record CartSummary(String cartId, List<BasketItem> items, BigDecimal total, List<String> validations, BigDecimal bonusAvailable, boolean bonusDecisionPending, String checkoutWebLink, String checkoutMobileLink, List<String> unresolved)`
  - `CartBuildingService.resolveProducts(UUID, CartContext, List<ShoppingListItem>) -> List<ResolvedProduct>`
  - `CartBuildingService.unresolvedNames()` is NOT a method — unresolved names ride in `CartSummary`; `resolveProducts` returns only what resolved, and `buildCart` computes the difference.
  - `CartBuildingService.addProductsToCart(UUID, CartContext, List<ResolvedProduct>) -> void`
  - `CartBuildingService.getVerifiedCart(UUID, CartContext, List<String> unresolved) -> CartSummary`
  - `CartBuildingService.buildCart(UUID, List<ShoppingListItem>) -> CartSummary`

- [ ] **Step 1: Write the failing tests**

Append to `CartBuildingIntegrationTest`:

```java
    private static ShoppingListItem item(String name, String quantity, String unit) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .quantity(new java.math.BigDecimal(quantity))
                .unit(unit)
                .build();
    }

    private void scriptProductTools() {
        MCP.respondToTool(
                "silpo_find_products_batch",
                """
                {"products":[\
                {"name":"цибуля","productId":"p-1","companyId":"company-3","branchId":"branch-7"},\
                {"name":"гречка","productId":"p-2","companyId":"company-3","branchId":"branch-7"}]}""");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
    }

    private void scriptVerifiedCart() {
        MCP.respondToTool(
                "silpo_get_shopping_cart_by_id",
                """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-1","name":"Цибуля","unit":"кг","quantity":0.5,"price":25.5},\
                {"productId":"p-2","name":"Гречка","unit":"кг","quantity":1,"price":48}],\
                "total":73.5,"validations":[],\
                "loyalty":{"bonusAvailable":120,"bonusRequested":null,"isEnabled":true},\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-1",\
                "checkoutMobileLink":"silpo://checkout/cart-1"}""");
    }

    @Test
    void runsAllSixCallsInTheDocumentedOrder() {
        UUID userId = connectedUser(8404L);
        scriptCartTools();
        scriptProductTools();

        CartSummary summary = cartBuildingService.buildCart(userId, List.of(item("цибуля", "0.5", "кг")));
        scriptVerifiedCart();

        assertThat(MCP.calledTools())
                .containsExactly(
                        "silpo_get_my_shopping_cart",
                        "silpo_get_shopping_cart_by_id",
                        "silpo_get_time_slots",
                        "silpo_find_products_batch",
                        "silpo_add_or_update_cart_products",
                        "silpo_get_shopping_cart_by_id");
        assertThat(summary.cartId()).isEqualTo("cart-1");
    }

    @Test
    void chunksAtThirtyItemsPerSearchCall() {
        UUID userId = connectedUser(8405L);
        scriptCartTools();
        scriptProductTools();
        List<ShoppingListItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < 45; i++) {
            items.add(item("товар-" + i, "1", "шт"));
        }

        cartBuildingService.buildCart(userId, items);

        assertThat(MCP.calledTools().stream()
                        .filter("silpo_find_products_batch"::equals)
                        .count())
                .isEqualTo(2);
    }

    @Test
    void reportsWhatSilpoCouldNotMatchInsteadOfDroppingIt() {
        UUID userId = connectedUser(8406L);
        scriptCartTools();
        scriptProductTools();
        scriptVerifiedCart();

        CartSummary summary = cartBuildingService.buildCart(
                userId, List.of(item("цибуля", "0.5", "кг"), item("трюфелі", "1", "кг")));

        assertThat(summary.unresolved()).containsExactly("трюфелі");
        assertThat(summary.items()).isNotEmpty();
    }

    @Test
    void surfacesAvailableBonusesWithoutSpendingThem() {
        UUID userId = connectedUser(8407L);
        scriptCartTools();
        scriptProductTools();
        scriptVerifiedCart();

        CartSummary summary = cartBuildingService.buildCart(userId, List.of(item("цибуля", "0.5", "кг")));

        assertThat(summary.bonusAvailable()).isEqualByComparingTo("120");
        assertThat(summary.bonusDecisionPending()).isTrue();
        assertThat(summary.total()).isEqualByComparingTo("73.5");
        assertThat(summary.checkoutWebLink()).contains("checkout");
    }

    @Test
    void addsNothingWhenThereIsNoDeliverableSlot() {
        UUID userId = connectedUser(8408L);
        scriptCartTools();
        scriptProductTools();
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[]}");

        assertThatThrownBy(() -> cartBuildingService.buildCart(userId, List.of(item("цибуля", "0.5", "кг"))))
                .isInstanceOf(CartBuildException.class);

        assertThat(MCP.calledTools()).doesNotContain("silpo_add_or_update_cart_products");
    }
```

Add the imports `com.silporestockai.entity.ShoppingListItem` and `com.silporestockai.model.CartSummary`.

Note on `runsAllSixCallsInTheDocumentedOrder`: `scriptVerifiedCart()` is called after `buildCart` only in
that one test because the second `silpo_get_shopping_cart_by_id` reuses the same scripted answer as the
first — the order assertion does not depend on the verified-cart shape. Every other test scripts it before.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*CartBuildingIntegrationTest*'`
Expected: FAIL — `buildCart` does not exist.

- [ ] **Step 3: Write the records**

Create `src/main/java/com/silporestockai/model/ResolvedProduct.java`:

```java
package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * A shopping list line that Silpo matched to a real product.
 *
 * @param requestedName the name the plan asked for, kept so an unmatched line can be named back to the user
 * @param productId Silpo's product identifier
 * @param companyId company the product belongs to
 * @param branchId branch the price and availability apply to
 * @param quantity how much to add
 * @param unit unit the quantity is counted in
 */
public record ResolvedProduct(
        String requestedName,
        String productId,
        String companyId,
        String branchId,
        BigDecimal quantity,
        String unit) {}
```

Create `src/main/java/com/silporestockai/model/CartSummary.java`:

```java
package com.silporestockai.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * The cart as Silpo confirms it, plus what could not be put in it.
 *
 * <p>{@code bonusDecisionPending} is a question, not a decision: spending someone's loyalty points is not a default
 * to pick for them, so task 10 asks and this task only reports that there is something to ask about.
 *
 * @param cartId the Silpo cart
 * @param items what is in it now
 * @param total what it costs
 * @param validations warnings Silpo attached to the cart, e.g. an item that went out of stock
 * @param bonusAvailable loyalty bonuses that could be spent, zero when there are none
 * @param bonusDecisionPending true when bonuses are available, enabled and nobody has decided yet
 * @param checkoutWebLink where a person finishes the order in a browser
 * @param checkoutMobileLink the same in the Silpo app
 * @param unresolved names from the shopping list Silpo could not match to any product
 */
public record CartSummary(
        String cartId,
        List<BasketItem> items,
        BigDecimal total,
        List<String> validations,
        BigDecimal bonusAvailable,
        boolean bonusDecisionPending,
        String checkoutWebLink,
        String checkoutMobileLink,
        List<String> unresolved) {}
```

- [ ] **Step 4: Write steps 4 to 6 and the orchestration**

Add to `CartBuildingService`:

```java
    /** The documented per-call limit of silpo_find_products_batch. */
    private static final int SEARCH_BATCH_SIZE = 30;

    /** Steps 1 to 6, in the documented order. Unresolved items are reported, not fatal. */
    public CartSummary buildCart(UUID userId, List<ShoppingListItem> items) {
        CartContext context = getOrCreateCartContext(userId);
        validateTimeSlot(userId, context);
        List<ResolvedProduct> resolved = resolveProducts(userId, context, items);
        List<String> unresolved = items.stream()
                .map(ShoppingListItem::getName)
                .filter(name -> resolved.stream().noneMatch(product -> product.requestedName().equals(name)))
                .toList();
        if (!unresolved.isEmpty()) {
            log.info("Silpo matched no product for {} of {} items: {}", unresolved.size(), items.size(), unresolved);
        }
        addProductsToCart(userId, context, resolved);
        return getVerifiedCart(userId, context, unresolved);
    }

    /** Step 4. Chunked at the documented batch limit; an unmatched item is normal, not an error. */
    public List<ResolvedProduct> resolveProducts(UUID userId, CartContext context, List<ShoppingListItem> items) {
        List<ResolvedProduct> resolved = new ArrayList<>();
        for (int start = 0; start < items.size(); start += SEARCH_BATCH_SIZE) {
            List<ShoppingListItem> chunk = items.subList(start, Math.min(items.size(), start + SEARCH_BATCH_SIZE));
            JsonNode found = call(
                    userId,
                    TOOL_FIND_PRODUCTS,
                    Map.of(
                            "branchId",
                            nullSafe(context.branchId()),
                            "items",
                            chunk.stream()
                                    .map(item -> Map.of("name", item.getName(), "quantity", quantityOf(item)))
                                    .toList()));
            for (JsonNode product : McpResponses.findArray(found, McpResponses.PRODUCTS)) {
                String name = McpResponses.findString(product, McpResponses.NAME).orElse(null);
                String productId = McpResponses.findString(product, McpResponses.PRODUCT_ID).orElse(null);
                if (name == null || productId == null) {
                    continue;
                }
                chunk.stream()
                        .filter(item -> item.getName().equalsIgnoreCase(name))
                        .findFirst()
                        .ifPresent(item -> resolved.add(new ResolvedProduct(
                                item.getName(),
                                productId,
                                McpResponses.findString(product, McpResponses.COMPANY_ID)
                                        .orElse(context.companyId()),
                                McpResponses.findString(product, McpResponses.BRANCH_ID)
                                        .orElse(context.branchId()),
                                quantityOf(item),
                                item.getUnit())));
            }
        }
        log.info("MCP ← resolved {} of {} shopping list lines", resolved.size(), items.size());
        return resolved;
    }

    /** Step 5. Adds and updates our own lines; whatever the guest already had stays untouched. */
    public void addProductsToCart(UUID userId, CartContext context, List<ResolvedProduct> products) {
        if (products.isEmpty()) {
            log.info("nothing resolved, so nothing to add to cart {}", context.cartId());
            return;
        }
        call(
                userId,
                TOOL_ADD_PRODUCTS,
                Map.of(
                        "cartId",
                        context.cartId(),
                        "products",
                        products.stream()
                                .map(product -> Map.of(
                                        "productId", product.productId(),
                                        "companyId", nullSafe(product.companyId()),
                                        "branchId", nullSafe(product.branchId()),
                                        "quantity", product.quantity()))
                                .toList()));
    }

    /** Step 6: read the cart back rather than trusting the write. */
    public CartSummary getVerifiedCart(UUID userId, CartContext context, List<String> unresolved) {
        JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("cartId", context.cartId()));

        List<BasketItem> items = McpResponses.findArray(cart, McpResponses.ITEMS).stream()
                .map(node -> new BasketItem(
                        McpResponses.findString(node, McpResponses.PRODUCT_ID).orElse(null),
                        McpResponses.findString(node, McpResponses.NAME).orElse(null),
                        McpResponses.findString(node, McpResponses.UNIT).orElse(null),
                        McpResponses.findNumber(node, McpResponses.QUANTITY).orElse(null),
                        McpResponses.findNumber(node, McpResponses.PRICE).orElse(null)))
                .toList();

        JsonNode loyalty = McpResponses.findNode(cart, McpResponses.LOYALTY).orElse(null);
        BigDecimal bonusAvailable = loyalty == null
                ? BigDecimal.ZERO
                : McpResponses.findNumber(loyalty, McpResponses.BONUS_AVAILABLE).orElse(BigDecimal.ZERO);
        boolean enabled = loyalty != null
                && McpResponses.findNode(loyalty, McpResponses.LOYALTY_ENABLED)
                        .map(JsonNode::asBoolean)
                        .orElse(false);
        boolean requested = loyalty != null
                && McpResponses.findNumber(loyalty, McpResponses.BONUS_REQUESTED).isPresent();
        boolean bonusDecisionPending = enabled && !requested && bonusAvailable.signum() > 0;

        CartSummary summary = new CartSummary(
                context.cartId(),
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
        log.info(
                "MCP ← cart {} verified: {} items, total {}, bonuses available {}, unresolved {}",
                summary.cartId(),
                summary.items().size(),
                summary.total(),
                summary.bonusAvailable(),
                summary.unresolved().size());
        return summary;
    }

    private static BigDecimal quantityOf(ShoppingListItem item) {
        return item.getQuantity() == null ? BigDecimal.ONE : item.getQuantity();
    }
```

Add imports: `com.silporestockai.entity.ShoppingListItem`, `com.silporestockai.model.BasketItem`,
`com.silporestockai.model.CartSummary`, `com.silporestockai.model.ResolvedProduct`, `java.math.BigDecimal`,
`java.util.ArrayList`.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests '*CartBuildingIntegrationTest*'`
Expected: PASS, 8 tests.

If `chunksAtThirtyItemsPerSearchCall` reports 1 call, the loop is slicing wrong; if it reports 3, the batch
size is not 30.

- [ ] **Step 6: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/java/com/silporestockai/model/ResolvedProduct.java \
        src/main/java/com/silporestockai/model/CartSummary.java \
        src/main/java/com/silporestockai/service/CartBuildingService.java \
        src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java
git commit -m "Fill a Silpo cart and read it back to verify it"
```

---

### Task 4: The log is the evidence, and the runbook to prove it live

**Files:**
- Test: `src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above. No production change expected — if the assertion fails, the logging is what changes.

- [ ] **Step 1: Assert the demo log**

Append to `CartBuildingIntegrationTest`:

```java
    @Test
    void logsEveryToolCallAtInfoSoADemoCanBeRecorded() {
        UUID userId = connectedUser(8409L);
        scriptCartTools();
        scriptProductTools();
        scriptVerifiedCart();

        Logger logger = (Logger) LoggerFactory.getLogger(CartBuildingService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            cartBuildingService.buildCart(userId, List.of(item("цибуля", "0.5", "кг")));
        } finally {
            logger.detachAppender(appender);
        }

        List<String> info = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(info)
                .anyMatch(line -> line.contains("silpo_get_my_shopping_cart"))
                .anyMatch(line -> line.contains("silpo_get_shopping_cart_by_id"))
                .anyMatch(line -> line.contains("silpo_get_time_slots"))
                .anyMatch(line -> line.contains("silpo_find_products_batch"))
                .anyMatch(line -> line.contains("silpo_add_or_update_cart_products"));
    }
```

Add imports: `ch.qos.logback.classic.Level`, `ch.qos.logback.classic.Logger`,
`ch.qos.logback.classic.spi.ILoggingEvent`, `ch.qos.logback.core.read.ListAppender`,
`org.slf4j.LoggerFactory`, `com.silporestockai.service.CartBuildingService` (already imported).

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests '*CartBuildingIntegrationTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 3: Write the runbook**

In `README.md`, after the `#### The first weekly plan` subsection, add:

```markdown
#### Building a real Silpo cart

`CartBuildingService.buildCart(userId, items)` runs the documented sequence — `silpo_get_my_shopping_cart`,
`silpo_get_shopping_cart_by_id`, `silpo_get_time_slots`, `silpo_find_products_batch` (chunked at 30),
`silpo_add_or_update_cart_products`, then `silpo_get_shopping_cart_by_id` again to verify. Every call is
logged at INFO as `MCP → tool {args}` / `MCP ← result`, which is the evidence log the hackathon asks for:
record the console during a run and the JSON-RPC conversation is visible.

Items Silpo cannot match come back in `CartSummary.unresolved` rather than disappearing. Loyalty bonuses are
reported (`bonusAvailable`, `bonusDecisionPending`) and never spent — confirming that is task 10's job.

**Smoke-testing it against the real server** (needs a real Silpo account; nothing in CI can do this):

1. `make run`, then open `http://localhost:8080/auth/silpo/login` and complete the Silpo OAuth login.
2. Finish onboarding in Telegram so a `user_profile`, a `meal_plan` and its `shopping_list_item` rows exist.
3. Call `buildCart` for that user — from a REST controller if one exists by then, or from a scratch
   `@SpringBootTest` pointed at the real endpoint.
4. Watch the log: six `MCP →` lines in the documented order, then a `verified` line with a non-zero item
   count. Open `checkoutWebLink` and the cart should be there in the Silpo web checkout.

Tool names and response keys were taken from the MCP documentation and have not been exercised against the
live server here. If a key differs, it is a one-line fix in `utils/McpResponses`, where every key name this
application depends on is declared.
```

- [ ] **Step 4: Verify everything**

```bash
make format
./gradlew test
./gradlew build
```
Expected: `BUILD SUCCESSFUL` for all three.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java README.md
git commit -m "Assert the demo log and document the live smoke test"
```

---

## Acceptance criteria mapping

| Notion criterion | Proven by |
|---|---|
| The full 6-step sequence executes against a mocked MCP server | Task 3 `runsAllSixCallsInTheDocumentedOrder` |
| Batches of >30 items are chunked (45 → 2 batches) | Task 3 `chunksAtThirtyItemsPerSearchCall` |
| Unresolved products are collected and returned | Task 3 `reportsWhatSilpoCouldNotMatchInsteadOfDroppingIt` |
| Bonus availability surfaced, not auto-applied | Task 3 `surfacesAvailableBonusesWithoutSpendingThem` |
| INFO logs show each MCP tool call | Task 4 `logsEveryToolCallAtInfoSoADemoCanBeRecorded` |
| Manual smoke test against the real MCP server | Task 4 runbook. Cannot run in CI — the Notion checkbox stays unticked until someone runs it with a real account |
