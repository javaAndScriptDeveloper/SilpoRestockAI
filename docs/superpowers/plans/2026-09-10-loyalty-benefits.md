# Лояльність та акції at checkout — implementation plan (tasks 78 + 79)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply every «Лояльність та акції» benefit the live Silpo MCP actually supports — балабонуси, подарункові сертифікати, промокоди — with the household's consent at cart confirmation, and surface the rest (купони, персональні промо, Premium) honestly as information, because the live schema has no way to apply them.

**Architecture:** One new `LoyaltyBenefitsService` owns all seven loyalty tools behind a defensive facade (any failure → «no offer»). `CartConfirmationService` reads it once at cart presentation, `CartMessageService` renders it, one extra confirm button applies everything the household agreed to. A new `MY_BENEFITS` intent answers «які в мене знижки?» from the same service.

**Tech Stack:** Java 25, Spring Boot 4, Lombok, Jackson (`McpResponses` helpers), JUnit 5 + AssertJ + Mockito, Spotless (palantir).

**Spec:** `docs/superpowers/specs/2026-09-10-loyalty-benefits-design.md`

## Global Constraints

- Tool names exactly as the live server advertises: `silpo_get_loyalty_info`, `silpo_get_my_coupons`, `silpo_get_coupon_details`, `silpo_get_my_promos`, `silpo_get_promo_codes`, `silpo_get_my_certificates`, `silpo_get_my_premium_subscription`, `silpo_add_or_update_certificates`, `silpo_update_shopping_cart`.
- `silpo_update_shopping_cart` is not a patch: `shoppingCartId`, `deliveryType`, `timeslot`, `address`, `shipments` go on **every** call. Only `CartBuildingService.updateCart` builds those; new mutations go through it.
- No benefit is ever applied without a tap. No button is ever drawn for something the live API cannot do.
- Any loyalty call failing must not block or degrade cart confirmation, list building, or any other flow.
- Package rules (CLAUDE.md): services in `service`, records in `model` (framework-free), Telegram wording in `service.telegram`, constructor injection only, `@Slf4j`, class names ending in `Service`.
- `make format` before every commit; ArchUnit and the full suite must pass (`./gradlew test` with the app stopped — shared `build/classes`).
- Ukrainian user-facing copy, «ти» form, matching the existing cart messages.

---

### Task 1: `applyPromoCode` and a cart-total re-read on `CartBuildingService`

**Files:**
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java` (next to `applyBonuses`, line ~740)
- Modify: `src/main/java/com/silporestockai/utils/McpResponses.java` (add `CERTIFICATES_TOTAL`)
- Test: `src/test/java/com/silporestockai/unit/CartBenefitApplyTest.java`

**Interfaces:**
- Consumes: existing private `updateCart(UUID, String, Map<String,Object>)`, `call(UUID, String, Map)`.
- Produces:
  - `public boolean applyPromoCode(UUID userId, String cartId, String promoCode)`
  - `public java.util.Optional<BigDecimal> readCartTotal(UUID userId, String cartId)`

- [ ] **Step 1: Write the failing test**

```java
@Test
void sendsThePromoCodeWithTheCartsOwnRequiredFields() {
    // silpo_get_shopping_cart_by_id answers first, then the update
    when(client.callTool(eq("silpo_get_shopping_cart_by_id"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse(CART_JSON, null, false));
    when(client.callTool(eq("silpo_update_shopping_cart"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse("{\"success\":true}", null, false));

    assertThat(service.applyPromoCode(USER_ID, "cart-1", "SUMMER10")).isTrue();

    ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
    verify(client).callTool(eq("silpo_update_shopping_cart"), args.capture(), eq(USER_ID));
    assertThat(args.getValue()).containsEntry("promoCode", "SUMMER10").containsKeys("address", "shipments", "timeslot");
}

@Test
void reportsFalseWhenSilpoRefusesThePromoCode() {
    when(client.callTool(eq("silpo_get_shopping_cart_by_id"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse(CART_JSON, null, false));
    when(client.callTool(eq("silpo_update_shopping_cart"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse("promo code not found", null, true));

    assertThat(service.applyPromoCode(USER_ID, "cart-1", "NOPE")).isFalse();
}

@Test
void readsTheCartTotalBackAndIsEmptyWhenTheCartCannotBeRead() {
    when(client.callTool(eq("silpo_get_shopping_cart_by_id"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse(CART_JSON, null, false));
    assertThat(service.readCartTotal(USER_ID, "cart-1")).contains(new BigDecimal("911.92"));

    when(client.callTool(eq("silpo_get_shopping_cart_by_id"), any(), eq(USER_ID)))
            .thenThrow(new IllegalStateException("silpo is down"));
    assertThat(service.readCartTotal(USER_ID, "cart-1")).isEmpty();
}
```

`CART_JSON` is the live shape trimmed to what these methods read:
`{"success":true,"cart":{"id":"cart-1","deliveryType":"DeliveryHome","timeslot":{"start":"2026-09-10T13:30:00+00:00","end":"2026-09-10T15:00:00+00:00"},"address":{"addressType":"flat","latitude":"50.4","longitude":"30.6"},"shipments":[{"companyId":"c-1","branchId":"b-1"}],"promoCode":null,"certificates":[],"calculation":{"total":911.92,"productsTotal":812.92,"certificatesTotal":0}}}`

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*CartBenefitApplyTest*'`
Expected: FAIL — `applyPromoCode` / `readCartTotal` do not exist.

- [ ] **Step 3: Implement**

```java
/**
 * Puts a promo code on the cart. The live {@code silpo_update_shopping_cart} schema carries
 * {@code promoCode} beside {@code bonusRequested}, so this is a real application, not a display.
 */
public boolean applyPromoCode(UUID userId, String cartId, String promoCode) {
    return updateCart(userId, cartId, java.util.Collections.singletonMap("promoCode", promoCode));
}

/** What the cart costs right now — read back after a benefit was applied, so the number said out loud is Silpo's. */
public java.util.Optional<BigDecimal> readCartTotal(UUID userId, String cartId) {
    try {
        JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("shoppingCartId", cartId));
        return McpResponses.findNumber(cart, McpResponses.TOTAL);
    } catch (RuntimeException e) {
        log.warn("could not re-read cart {} after applying benefits: {}", cartId, e.getMessage());
        return java.util.Optional.empty();
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*CartBenefitApplyTest*'` → PASS.

- [ ] **Step 5: Commit**

```bash
make format
git add -A && git commit -m "Let the cart carry a promo code, and read its total back"
```

---

### Task 2: `LoyaltyBenefitsService` — the reads that feed the cart

**Files:**
- Create: `src/main/java/com/silporestockai/model/GiftCertificate.java`
- Create: `src/main/java/com/silporestockai/model/LoyaltyCoupon.java`
- Create: `src/main/java/com/silporestockai/model/CartBenefits.java`
- Create: `src/main/java/com/silporestockai/service/LoyaltyBenefitsService.java`
- Test: `src/test/java/com/silporestockai/unit/LoyaltyBenefitsServiceTest.java`

**Interfaces:**
- Consumes: `SilpoMcpClient.callTool`, `McpResponses.findArray/findString/findNumber`.
- Produces:
  - `record GiftCertificate(String barcode, String pincode, BigDecimal value, String expiresOn)`
  - `record LoyaltyCoupon(long id, String title, String rewardText, String endDate, boolean active, Boolean canBeApplied, String limitText, String progressText)`
  - `record CartBenefits(List<GiftCertificate> certificates, String promoCode, List<LoyaltyCoupon> coupons)` with `boolean isEmpty()` and `static CartBenefits none()`
  - `CartBenefits LoyaltyBenefitsService.cartBenefits(UUID userId)`

- [ ] **Step 1: Write the failing test**

```java
@Test
void certificatesFailingWithA500LeavesEveryOtherBenefitIntact() {
    when(client.callTool(eq("silpo_get_my_certificates"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse("Error in get-my-certificates: API returned 500 Internal Server Error.", null, true));
    when(client.callTool(eq("silpo_get_promo_codes"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse("{\"promoCodes\":[{\"code\":\"SUMMER10\"}]}", null, false));
    when(client.callTool(eq("silpo_get_my_coupons"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse("{\"coupons\":[]}", null, false));

    CartBenefits benefits = service.cartBenefits(USER_ID);

    assertThat(benefits.certificates()).isEmpty();
    assertThat(benefits.promoCode()).isEqualTo("SUMMER10");
}

@Test
void anExceptionAnywhereDegradesToNoOfferRatherThanFailingTheCart() {
    when(client.callTool(any(), any(), eq(USER_ID))).thenThrow(new IllegalStateException("silpo is down"));
    assertThat(service.cartBenefits(USER_ID).isEmpty()).isTrue();
}

@Test
void readsCertificatesAndOnlyKeepsCouponsAPersonCanStillUse() {
    when(client.callTool(eq("silpo_get_my_certificates"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse(
                    "{\"certificates\":[{\"barcode\":\"9001\",\"pincode\":\"1234\",\"value\":500,\"expireDate\":\"2026-12-31\"}]}",
                    null, false));
    when(client.callTool(eq("silpo_get_promo_codes"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse("{\"promoCodes\":[]}", null, false));
    when(client.callTool(eq("silpo_get_my_coupons"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse(
                    "{\"coupons\":[{\"id\":1,\"active\":true,\"description\":\"на покупку\",\"rewardText\":\"-15%\",\"endDate\":\"2026-10-03\"},"
                            + "{\"id\":2,\"active\":false,\"description\":\"старий\",\"rewardText\":\"-5%\",\"endDate\":\"2026-10-03\"}]}",
                    null, false));

    CartBenefits benefits = service.cartBenefits(USER_ID);

    assertThat(benefits.certificates()).singleElement()
            .satisfies(c -> assertThat(c.barcode()).isEqualTo("9001"));
    assertThat(benefits.coupons()).singleElement()
            .satisfies(c -> assertThat(c.rewardText()).isEqualTo("-15%"));
    assertThat(benefits.promoCode()).isNull();
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*LoyaltyBenefitsServiceTest*'` → FAIL, class does not exist.

- [ ] **Step 3: Implement the three records and the service reads**

Each record is a plain framework-free record in `model`. The service:

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyBenefitsService {

    static final String TOOL_CERTIFICATES = "silpo_get_my_certificates";
    static final String TOOL_PROMO_CODES = "silpo_get_promo_codes";
    static final String TOOL_COUPONS = "silpo_get_my_coupons";
    // …loyalty info, coupon details, promos, premium, add_or_update_certificates in later tasks

    private final SilpoMcpClient silpoMcpClient;

    /** Everything that could go on the cart in front of the household, plus the coupons worth mentioning. */
    public CartBenefits cartBenefits(UUID userId) {
        List<GiftCertificate> certificates = read(userId, TOOL_CERTIFICATES, Map.of())
                .map(LoyaltyBenefitsService::certificatesOf).orElse(List.of());
        String promoCode = read(userId, TOOL_PROMO_CODES, Map.of())
                .flatMap(LoyaltyBenefitsService::firstPromoCode).orElse(null);
        List<LoyaltyCoupon> coupons = read(userId, TOOL_COUPONS, Map.of())
                .map(LoyaltyBenefitsService::couponsOf).orElse(List.of())
                .stream().filter(LoyaltyCoupon::active).toList();
        return new CartBenefits(certificates, promoCode, coupons);
    }

    /**
     * One read, with every failure flattened to «nothing to offer».
     *
     * <p>{@code silpo_get_my_certificates} answered 500 on every live call during this task's own verification.
     * A lost offer is worth far less than a lost order, so nothing here ever throws at a caller.
     */
    private java.util.Optional<JsonNode> read(UUID userId, String tool, Map<String, Object> arguments) {
        try {
            McpToolResponse response = silpoMcpClient.callTool(tool, arguments, userId);
            if (response.isError()) {
                log.info("{} answered with an error, skipping that benefit: {}", tool, response.text());
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(McpResponses.parse(response));
        } catch (RuntimeException e) {
            log.warn("could not read {} for user {}: {}", tool, userId, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
```

Use whatever `McpResponses` parse helper `OrderHistoryService` uses for the same job; do not add a
second JSON entry point.

- [ ] **Step 4: Run the tests** → PASS.

- [ ] **Step 5: Commit**

```bash
make format
git add -A && git commit -m "Read the household's certificates, promo codes and coupons"
```

---

### Task 3: applying certificates

**Files:**
- Modify: `src/main/java/com/silporestockai/service/LoyaltyBenefitsService.java`
- Create: `src/main/java/com/silporestockai/model/AppliedBenefits.java`
- Test: `src/test/java/com/silporestockai/unit/LoyaltyBenefitsServiceTest.java` (extend)

**Interfaces:**
- Produces:
  - `record AppliedBenefits(boolean bonuses, List<String> certificates, String promoCode, List<String> refusals, BigDecimal newTotal)` with `boolean nothingApplied()`
  - `List<String> LoyaltyBenefitsService.applyCertificates(UUID userId, String cartId, List<GiftCertificate> certificates)` — returns the barcodes Silpo accepted; a certificate whose `added[].validations` is non-empty is not among them.

- [ ] **Step 1: Write the failing test**

```java
@Test
void appliesCertificatesAndReportsTheOnesSilpoRefused() {
    when(client.callTool(eq("silpo_add_or_update_certificates"), any(), eq(USER_ID)))
            .thenReturn(new McpToolResponse(
                    "{\"added\":[{\"barcode\":\"9001\",\"validations\":[]},"
                            + "{\"barcode\":\"9002\",\"validations\":[\"Сертифікат вже використано\"]}]}",
                    null, false));

    List<String> applied = service.applyCertificates(USER_ID, "cart-1",
            List.of(new GiftCertificate("9001", "1234", new BigDecimal("500"), null),
                    new GiftCertificate("9002", "4321", new BigDecimal("300"), null)));

    assertThat(applied).containsExactly("9001");
    ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
    verify(client).callTool(eq("silpo_add_or_update_certificates"), args.capture(), eq(USER_ID));
    assertThat(args.getValue()).containsEntry("shoppingCartId", "cart-1");
}

@Test
void aFailedCertificateCallAppliesNothingAndThrowsNothing() {
    when(client.callTool(eq("silpo_add_or_update_certificates"), any(), eq(USER_ID)))
            .thenThrow(new IllegalStateException("silpo is down"));
    assertThat(service.applyCertificates(USER_ID, "cart-1",
            List.of(new GiftCertificate("9001", "1234", new BigDecimal("500"), null)))).isEmpty();
}
```

- [ ] **Step 2: Run it and watch it fail.**
- [ ] **Step 3: Implement** — build `certificatesToAdd` as `[{barcode, pincode}]` (pincode omitted when null), call the tool, treat an empty/absent `validations` on an `added` entry as accepted, and — per the tool's own MANDATORY note — leave the cart re-read to the caller (`CartBuildingService.readCartTotal`).
- [ ] **Step 4: Run the tests** → PASS.
- [ ] **Step 5: Commit**

```bash
make format
git add -A && git commit -m "Apply gift certificates to the cart the household is confirming"
```

---

### Task 4: the consent button at cart confirmation

**Files:**
- Modify: `src/main/java/com/silporestockai/service/telegram/CartMessageService.java`
- Modify: `src/main/java/com/silporestockai/service/CartConfirmationService.java`
- Test: `src/test/java/com/silporestockai/unit/CartBenefitsMessageTest.java`
- Test: `src/test/java/com/silporestockai/service/telegram/` — extend the existing cart-message test if one covers `cartButtons`.

**Interfaces:**
- Consumes: `CartBenefits`, `AppliedBenefits`, `LoyaltyBenefitsService.cartBenefits/applyCertificates`, `CartBuildingService.applyPromoCode/readCartTotal/applyBonuses`.
- Produces:
  - `CartMessageService.CALLBACK_CONFIRM_BENEFITS = "cart:confirm-benefits"` (the old `cart:confirm-bonus` constant stays and keeps working — stale keyboards exist in real chats)
  - `String cartText(CartSummary, OfferedSlot, OrderType, CartBenefits)` and `List<TelegramButton> cartButtons(CartSummary, boolean, CartBenefits)` — the existing 3-arg/2-arg forms delegate with `CartBenefits.none()`
  - `String appliedBenefitsText(AppliedBenefits)`

- [ ] **Step 1: Write the failing test**

```java
@Test
void offersOneExtraButtonThatCoversEveryAvailableBenefit() {
    CartBenefits benefits = new CartBenefits(
            List.of(new GiftCertificate("…4321", "1", new BigDecimal("500"), null)), "SUMMER10", List.of());
    CartSummary summary = summaryWithBonuses(new BigDecimal("250"));

    assertThat(service.cartButtons(summary, true, benefits))
            .extracting(TelegramButton::text)
            .containsExactly("Підтвердити", "Підтвердити + вигоди", "Інший час", "Скасувати");
    assertThat(service.cartText(summary, SLOT, OrderType.INITIAL, benefits))
            .contains("Твої вигоди")
            .contains("250")
            .contains("Сертифікат")
            .contains("SUMMER10");
}

@Test
void keepsTodaysWordingWhenBonusesAreTheOnlyBenefit() {
    assertThat(service.cartButtons(summaryWithBonuses(new BigDecimal("250")), false, CartBenefits.none()))
            .extracting(TelegramButton::text)
            .containsExactly("Підтвердити", "Підтвердити + 250 бонусів", "Скасувати");
}

@Test
void saysNothingAtAllWhenThereIsNoBenefitToOffer() {
    CartSummary noBonuses = summaryWithBonuses(BigDecimal.ZERO);
    assertThat(service.cartButtons(noBonuses, false, CartBenefits.none()))
            .extracting(TelegramButton::text).containsExactly("Підтвердити", "Скасувати");
    assertThat(service.cartText(noBonuses, SLOT, OrderType.INITIAL, CartBenefits.none()))
            .doesNotContain("вигоди");
}

@Test
void mentionsAnActiveCouponWithoutOfferingToApplyIt() {
    CartBenefits benefits = new CartBenefits(List.of(), null,
            List.of(new LoyaltyCoupon(1, "на покупку", "-15%", "2026-10-03", true, true, null, null)));
    String text = service.cartText(summaryWithBonuses(BigDecimal.ZERO), SLOT, OrderType.INITIAL, benefits);

    assertThat(text).contains("-15%").contains("«Сільпо»");
    assertThat(service.cartButtons(summaryWithBonuses(BigDecimal.ZERO), false, benefits))
            .extracting(TelegramButton::text).containsExactly("Підтвердити", "Скасувати");
}
```

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Implement**

In `CartMessageService`: a `💳 Твої вигоди:` block listing bonuses (when `bonusDecisionPending`),
each certificate with its value and a masked barcode, and the promo code; then, separately, one line
per active coupon saying it applies itself at Silpo's checkout. `cartButtons` adds
`Підтвердити + вигоди` when more than one mechanism is available and keeps
`Підтвердити + N бонусів` when bonuses stand alone.

In `CartConfirmationService`:
1. In `present(...)`, after the summary is built: `CartBenefits benefits = loyaltyBenefitsService.cartBenefits(user.getId());` and store it in the conversation-state context under `"benefits"` beside `KEY_SUMMARY` (webhooks are stateless — nothing may live in a field).
2. `handle(...)`: route `CALLBACK_CONFIRM_BENEFITS` to `confirm(user, order, state, summary, true)`; keep `CALLBACK_CONFIRM_BONUS` mapped to the same call so old keyboards still work.
3. `confirm(..., boolean spendBenefits)` replaces the `spendBonuses` flag: apply bonuses (unchanged), then certificates, then the promo code, collecting an `AppliedBenefits`; re-read the total with `readCartTotal`; send `appliedBenefitsText` before the existing confirmation message. A refusal is named, never fatal.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*CartBenefit*' --tests '*CartConfirmation*'` → PASS.

- [ ] **Step 5: Commit**

```bash
make format
git add -A && git commit -m "Ask once, apply every benefit the cart is entitled to"
```

---

### Task 5: the informational overview

**Files:**
- Create: `src/main/java/com/silporestockai/model/BenefitsOverview.java`
- Modify: `src/main/java/com/silporestockai/service/LoyaltyBenefitsService.java`
- Create: `src/main/java/com/silporestockai/service/telegram/BenefitsMessageService.java`
- Test: `src/test/java/com/silporestockai/unit/BenefitsOverviewTest.java`

**Interfaces:**
- Produces:
  - `record BenefitsOverview(BigDecimal bonusBalance, List<GiftCertificate> certificates, List<String> promoCodes, List<LoyaltyCoupon> coupons, List<String> promos, String premiumSummary, List<String> premiumLinks, boolean anythingRead)`
  - `BenefitsOverview LoyaltyBenefitsService.overview(UUID userId)` — calls `silpo_get_loyalty_info`, `silpo_get_my_coupons` (+ `silpo_get_coupon_details` for at most the first 5 coupons), `silpo_get_my_promos`, `silpo_get_promo_codes`, `silpo_get_my_certificates`, `silpo_get_my_premium_subscription`, each defensively
  - `String BenefitsMessageService.overviewText(BenefitsOverview)`

- [ ] **Step 1: Write the failing test**

```java
@Test
void separatesWhatKomoraAppliesFromWhatOnlyTheSilpoAppCan() {
    BenefitsOverview overview = new BenefitsOverview(
            new BigDecimal("250"),
            List.of(new GiftCertificate("9001", null, new BigDecimal("500"), "2026-12-31")),
            List.of("SUMMER10"),
            List.of(new LoyaltyCoupon(1, "на покупку", "-15%", "2026-10-03", true, true,
                    "Максимальна сума знижки — 150 грн", null)),
            List.of(),
            "Немає активної підписки «Плюхс»",
            List.of("https://silpo.ua/subscription"),
            true);

    String text = message.overviewText(overview);

    assertThat(text).contains("250").contains("500").contains("SUMMER10").contains("-15%");
    assertThat(text).contains("застосую сам");            // bonuses, certificates, promo codes
    assertThat(text).contains("у застосунку «Сільпо»");   // coupons and Premium
}

@Test
void saysSoWhenSilpoAnsweredNothingAtAll() {
    BenefitsOverview nothing = new BenefitsOverview(null, List.of(), List.of(), List.of(), List.of(), null, List.of(), false);
    assertThat(message.overviewText(nothing)).contains("не відповіло");
}

@Test
void anEmptyAccountReadsAsEmptyRatherThanBroken() {
    BenefitsOverview empty = new BenefitsOverview(BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of(),
            "Немає активної підписки «Плюхс»", List.of(), true);
    assertThat(message.overviewText(empty)).contains("0").doesNotContain("не відповіло");
}
```

- [ ] **Step 2: Run it and watch it fail.**
- [ ] **Step 3: Implement** `overview`, the record and the message. Coupon details are best-effort: a
  failed `silpo_get_coupon_details` leaves `canBeApplied`/`progressText` null and the coupon is still listed.
- [ ] **Step 4: Run the tests** → PASS.
- [ ] **Step 5: Commit**

```bash
make format
git add -A && git commit -m "Answer «які в мене вигоди» with what Silpo actually holds"
```

---

### Task 6: the `MY_BENEFITS` intent

**Files:**
- Modify: `src/main/resources/prompts/intent-router-system.txt`
- Modify: `src/main/java/com/silporestockai/service/IntentRouterService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/HelpContent.java`
- Test: `src/test/java/com/silporestockai/unit/HelpContentTest.java` (extend if it asserts on the intent list)

**Interfaces:**
- Consumes: `LoyaltyBenefitsService.overview`, `BenefitsMessageService.overviewText`.
- Produces: a new `IntentType.MY_BENEFITS` dispatched to a `showBenefits(User)` on the service that owns the surface.

- [ ] **Step 1:** Add the intent to the prompt with real examples: «які в мене купони», «скільки в мене бонусів», «є якісь знижки для мене», «покажи мої сертифікати», «що там із Плюхс». Note explicitly that it is read-only and must not be confused with `LIST_VIEW` or `WHERE_IS_MY_ORDER`.
- [ ] **Step 2:** Add `MY_BENEFITS` to the enum and the `switch` in `dispatch`.
- [ ] **Step 3:** Add one line to `HelpContent`.
- [ ] **Step 4:** Run `./gradlew test` in full → PASS.
- [ ] **Step 5: Commit**

```bash
make format
git add -A && git commit -m "Let a sentence ask what the household is entitled to"
```

---

### Task 7: live verification, documentation, Notion

**Files:**
- Modify: `docs/RUNBOOK.md` (a `### Task 78/79:` section)
- Modify: `docs/OVERNIGHT_SUMMARY.md` (session section)
- Modify: `docs/OVERNIGHT_QUESTIONS.md` if a product deviation was taken

- [ ] **Step 1:** Stop the app, run the whole suite (`./gradlew test`), restart it.
- [ ] **Step 2:** Drive a live cart through a synthetic webhook (see the memory note on driving without Telegram) and confirm with the benefits button. Read `logs/app.log` and `mcp_tool_call` to prove each new tool actually fired.
- [ ] **Step 3:** Exercise `silpo_add_or_update_certificates` live with a fabricated barcode to prove the call shape and that validations come back; record the exact answer.
- [ ] **Step 4:** Count the distinct tools used before and after (`select tool_name, count(*) from mcp_tool_call group by 1`) and write the numbers down.
- [ ] **Step 5:** Write the RUNBOOK checklist, update the two Notion tasks to «In review» with what was verified live versus structurally, and commit.
