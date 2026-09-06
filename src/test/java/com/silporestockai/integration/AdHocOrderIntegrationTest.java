package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.AdHocOrderService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.telegram.CartMessageService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("an ad-hoc order is a small discounted-snacks cart that never touches the weekly cycle")
class AdHocOrderIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "2020:stub-bot-token";
    private static final long CHAT_ID = 13701L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdHocOrderService adHocOrderService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products",
                    "silpo_get_promotions"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    /** The theme as the model turns it into shop lines, then the matcher's choice for each line that had a hit. */
    private static final String CHEESE_AND_WINE = """
            {"items":[{"name":"Сир твердий","quantity":300,"unit":"г","category":"Молочні продукти"},\
            {"name":"Вино червоне сухе","quantity":1,"unit":"шт","category":"Напої"}]}""";

    private static final String MATCH_BOTH = """
            {"choices":[{"lineIndex":0,"candidateIndex":1,"reason":"акційний твердий сир"},\
            {"lineIndex":1,"candidateIndex":0,"reason":"сухе червоне"}]}""";

    private static final String MATCH_WATER_AND_ISOTONIC = """
            {"choices":[{"lineIndex":0,"candidateIndex":0,"reason":"вода"},{"lineIndex":1,"candidateIndex":0,"reason":"ізотонік"}]}""";

    private static List<String> searchedTerms() {
        List<String> terms = new ArrayList<>();
        MCP.callArguments("silpo_find_products_batch")
                .getLast()
                .path("products")
                .forEach(term -> terms.add(term.asText()));
        return terms;
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        CLAUDE.reset();
        MCP.reset();
        baselineBasketRepository.deleteAll();
        customerOrderRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();

        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(new BasketItem(
                        "00000000-0000-4000-8000-000000000001",
                        "Молоко",
                        "л",
                        new BigDecimal("2"),
                        new BigDecimal("38"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
        scriptSilpo();
    }

    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-a\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-a","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"00000000-0000-4000-8000-00000000005a","name":"Сир Пирятин","quantity":2,"price":88.9,"weighted":false},\
                {"productId":"00000000-0000-4000-8000-00000000005b","name":"Вино Los Cardos","quantity":1,"price":329,"weighted":false}],\
                "calculation":{"total":506.8,"productsTotal":506.8,"subDiscount":40},\
                "validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-a",\
                "checkoutMobileLink":"silpo://checkout/cart-a"}""");
        // Two cheeses, the second on promotion; one wine. The hangover terms find water and an isotonic drink.
        MCP.respondToTool("silpo_find_products_batch", """
                {"queries":[\
                {"query":"сир твердий","products":[\
                {"name":"Сир Плай Бердо","productId":"00000000-0000-4000-8000-000000000059","step":1,"displayRatio":"150г","price":149},\
                {"name":"Сир Пирятин","productId":"00000000-0000-4000-8000-00000000005a","step":1,"displayRatio":"150г","price":88.9,"oldPrice":108.9}]},\
                {"query":"вино червоне сухе","products":[\
                {"name":"Вино Los Cardos","productId":"00000000-0000-4000-8000-00000000005b","step":1,"displayRatio":"750мл","price":329}]},\
                {"query":"вода мінеральна","products":[{"name":"Моршинська","productId":"00000000-0000-4000-8000-000000000046",\
                "step":1,"displayRatio":"1.5л"}]},\
                {"query":"ізотонік","products":[{"name":"Oshee ізотонік","productId":"00000000-0000-4000-8000-000000000047",\
                "step":1,"displayRatio":"750мл"}]},\
                {"query":"сорбент","products":[]}]}""");
    }

    private void tapButton(int updateId, String data) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"callback_query":{"id":"cb-%d","chat_instance":"ci",\
                                "from":{"id":5,"is_bot":false,"first_name":"Тест"},"data":"%s",\
                                "message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}}}}""".formatted(updateId, updateId, data, updateId, CHAT_ID)))
                .andExpect(status().isOk());
    }

    /**
     * The theme drives what is searched: «сир та вино» becomes a cheese and a wine, each resolved through the
     * ordinary pipeline. The first version searched Silpo's promotions for snack keywords whatever the theme said,
     * and would have answered this request with chips.
     */
    @Test
    void theThemeBecomesShopLinesAndTheOrdinaryCartPipelineResolvesThem() {
        CLAUDE.respondWithTexts(CHEESE_AND_WINE, MATCH_BOTH);

        adHocOrderService.buildAdHocOrder(user, "сир та вино по знижці до п'ятниці", Instant.now());

        assertThat(searchedTerms()).containsExactly("Сир твердий", "Вино червоне сухе");
        List<String> addedProductIds = new ArrayList<>();
        MCP.callArguments("silpo_add_or_update_cart_products")
                .getFirst()
                .path("products")
                .forEach(p -> addedProductIds.add(p.path("productId").asText()));
        assertThat(addedProductIds)
                .containsExactly("00000000-0000-4000-8000-00000000005a", "00000000-0000-4000-8000-00000000005b");
        CustomerOrder draft = customerOrderRepository
                .findByUserIdAndStatus(user.getId(), OrderStatus.DRAFT)
                .getFirst();
        assertThat(draft.getType()).isEqualTo(OrderType.AD_HOC);
        // 300 г of a 150 г cheese is two packs.
        assertThat(MCP.callArguments("silpo_add_or_update_cart_products")
                        .getFirst()
                        .path("products")
                        .get(0)
                        .path("quantity")
                        .asInt())
                .isEqualTo(2);
    }

    /** «По знижці» reaches the matcher as a preference, with the promoted candidate marked as such. */
    @Test
    void askingForADiscountTellsTheMatcherToPreferPromotedCandidates() {
        CLAUDE.respondWithTexts(CHEESE_AND_WINE, MATCH_BOTH);

        adHocOrderService.buildAdHocOrder(user, "сир та вино по знижці до п'ятниці", Instant.now());

        String matcherLines =
                CLAUDE.requests().get(1).path("messages").get(0).path("content").asText();
        assertThat(matcherLines).contains("ПО ЗНИЖЦІ").contains("АКЦІЯ, було 108.9");
    }

    @Test
    void aThemeWithoutADiscountWordDoesNotAskForOne() {
        CLAUDE.respondWithTexts(CHEESE_AND_WINE, MATCH_BOTH);

        adHocOrderService.buildAdHocOrder(user, "сир та вино на вечір", Instant.now());

        // The rule lives in the system prompt either way; the line itself carries the note only when asked.
        assertThat(CLAUDE.requests()
                        .get(1)
                        .path("messages")
                        .get(0)
                        .path("content")
                        .asText())
                .doesNotContain("ПО ЗНИЖЦІ");
    }

    /** The preface names the theme and the lines; the cart names Silpo's own saving. */
    @Test
    void thePrefaceNamesTheLinesAndTheCartNamesTheSavings() {
        CLAUDE.respondWithTexts(CHEESE_AND_WINE, MATCH_BOTH);

        adHocOrderService.buildAdHocOrder(user, "сир та вино по знижці", Instant.now());

        assertThat(TELEGRAM.sentMessages())
                .anySatisfy(message -> assertThat(message.path("text").asText())
                        .contains("На «сир та вино по знижці» беру")
                        .contains("сир твердий 300 г")
                        .contains("акційне"));
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Економія за акціями: 40.00 грн");
    }

    @Test
    void confirmingLeavesTheBaselineAndCheckinCycleUntouched() throws Exception {
        UUID baselineBefore = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow()
                .getId();
        CLAUDE.respondWithTexts(CHEESE_AND_WINE, MATCH_BOTH);

        adHocOrderService.buildAdHocOrder(user, "сир та вино", Instant.now());
        tapButton(1, CartMessageService.CALLBACK_CONFIRM);

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CONFIRMED))
                .singleElement()
                .satisfies(order -> assertThat(order.getType()).isEqualTo(OrderType.AD_HOC));
        assertThat(baselineBasketRepository.findByUserIdOrderByConfirmedAtDesc(user.getId()))
                .hasSize(1);
        assertThat(baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(user.getId())
                        .orElseThrow()
                        .getId())
                .isEqualTo(baselineBefore);
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("https://silpo.ua/checkout/cart-a");
    }

    /** A theme the model cannot turn into lines is said so, not answered with a cart of something else. */
    @Test
    void aThemeThatYieldsNoLinesGetsAClearMessageAndNoOrder() {
        CLAUDE.respondWithText("{\"items\":[]}");

        adHocOrderService.buildAdHocOrder(user, "щось", Instant.now());

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.DRAFT))
                .isEmpty();
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Не зрозумів, що саме купити");
    }

    /**
     * One line per thing a hangover needs, not one per word for it: seven overlapping terms once put the same ₴329
     * electrolyte drink in a live cart twice and two sorbent gels beside a ₴464 charcoal — ₴1514 for a hangover.
     */
    @Test
    void hangoverReliefSearchesOneTermPerNeedWithSensibleQuantities() {
        CLAUDE.respondWithText(MATCH_WATER_AND_ISOTONIC);
        adHocOrderService.buildHangoverReliefOrder(user);

        assertThat(searchedTerms()).containsExactly("вода мінеральна", "ізотонік", "сорбент");
        var added = MCP.callArguments("silpo_add_or_update_cart_products")
                .getFirst()
                .path("products");
        assertThat(added).hasSize(2);
        assertThat(added.get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(added.get(1).path("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void hangoverReliefIsHonestAboutWhatWasNotFoundRatherThanFailingSilently() {
        CLAUDE.respondWithText(MATCH_WATER_AND_ISOTONIC);
        adHocOrderService.buildHangoverReliefOrder(user);

        CustomerOrder draft = customerOrderRepository
                .findByUserIdAndStatus(user.getId(), OrderStatus.DRAFT)
                .getFirst();
        assertThat(draft.getType()).isEqualTo(OrderType.AD_HOC);
        // Only water and the isotonic drink resolved to a real product in the stub above; the sorbent comes back
        // empty, and the shared cart-building pipeline reports it honestly rather than silently dropping it.
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText())
                .contains("Не знайшов")
                .contains("сорбент");
    }
}
