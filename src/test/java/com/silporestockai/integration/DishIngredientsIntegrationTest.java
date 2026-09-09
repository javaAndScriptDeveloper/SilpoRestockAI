package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.ScheduledAdHocTaskKind;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("ordering the ingredients for a dish (task 36)")
class DishIngredientsIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "3636:stub-bot-token";
    private static final long CHAT_ID = 3601L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    private static final String INGREDIENTS = """
            {"dishName":"карбонара","servings":2,"items":[
              {"name":"спагеті","quantity":500,"unit":"г","category":"Крупи і бакалія","productId":null,"price":null},
              {"name":"панчета","quantity":200,"unit":"г","category":"М'ясо і птиця","productId":null,"price":null},
              {"name":"яйця","quantity":4,"unit":"шт","category":"Молочні продукти","productId":null,"price":null}]}""";

    private static final String IDENTIFIED = "{\"dishName\":\"карбонара\",\"confidence\":0.88}";

    /**
     * The matcher's answer for the one line with a candidate. Since the matcher stopped degrading silently to
     * Silpo's own ranking, a cart build needs a real answer here — a canned prose reply would fail the cart.
     */
    private static final String MATCH = "{\"choices\":[{\"lineIndex\":0,\"candidateIndex\":0,\"reason\":\"паста\"}]}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

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
                    "silpo_add_or_update_cart_products"));
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
        MCP.reset();
        CLAUDE.reset();
        scheduledAdHocTaskRepository.deleteAll();
        customerOrderRepository.deleteAll();
        baselineBasketRepository.deleteAll();
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
        scriptSilpo();
    }

    /** Task 09's ordinary cart pipeline: one ingredient resolves, two do not — the cart says so. */
    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-d\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool(
                "silpo_find_products_batch",
                "{\"queries\":[{\"query\":\"спагеті\",\"products\":[{\"name\":\"Спагеті Barilla 500г\","
                        + "\"productId\":\"p-spag\",\"branchId\":\"branch-7\"}]},"
                        + "{\"query\":\"панчета\",\"products\":[]},{\"query\":\"яйця\",\"products\":[]}]}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-d","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-spag","name":"Спагеті Barilla 500г","unit":"шт","quantity":1,"price":64}],\
                "total":64,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-d",\
                "checkoutMobileLink":"silpo://checkout/cart-d"}""");
    }

    private void sendText(int updateId, String text) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "text":"%s"}}""".formatted(updateId, updateId, CHAT_ID, text)))
                .andExpect(status().isOk());
    }

    private void sendPhotoWithCaption(int updateId, String caption) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "caption":"%s",\
                                "photo":[{"file_id":"photo-1","file_unique_id":"p1","width":90,"height":90}]}}""".formatted(updateId, updateId, CHAT_ID, caption)))
                .andExpect(status().isOk());
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

    private static String classified(String dish) {
        return "{\"intent\":\"DISH_INGREDIENTS_ORDER\",\"confidence\":0.9,\"themeDescription\":%s,\"targetDateTimeIso\":null}"
                .formatted(dish == null ? "null" : "\"" + dish + "\"");
    }

    private List<String> sentTexts() {
        return TELEGRAM.sentMessages().stream()
                .map(m -> m.path("text").asText())
                .toList();
    }

    private void assertDishWasOrdered(String dish) {
        List<ScheduledAdHocTask> tasks = scheduledAdHocTaskRepository.findAll();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getKind()).isEqualTo(ScheduledAdHocTaskKind.DISH_INGREDIENTS);
        assertThat(tasks.getFirst().getStatus()).isEqualTo(ScheduledAdHocTaskStatus.FIRED);
        assertThat(tasks.getFirst().getThemeDescription()).isEqualTo(dish);

        List<CustomerOrder> orders = customerOrderRepository.findAll();
        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst().getType()).isEqualTo(OrderType.AD_HOC);
        assertThat(orders.getFirst().getStatus()).isEqualTo(OrderStatus.DRAFT);
        // Task 75: whichever message started this — the sentence, or the «Так, замовляй» tap — is the request.
        assertThat(orders.getFirst().getTriggerIntent()).isEqualTo("DISH_INGREDIENTS_ORDER");
        assertThat(orders.getFirst().getRequestedAt()).isNotNull();
        // Task 09's path: resolved by name search, unresolved lines named, nothing invented.
        assertThat(MCP.calledTools()).contains("silpo_find_products_batch");
        assertThat(sentTexts())
                .anyMatch(text -> text.contains("Зібрав кошик:") && text.contains("Не знайшов: панчета, яйця"));
        assertThat(baselineBasketRepository.count()).isZero();
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow())
                .isEqualTo(ConversationFlow.CART_CONFIRMATION);
    }

    @Test
    void aNamedDishBecomesAnImmediateScheduledTaskAndAnAdHocCart() throws Exception {
        CLAUDE.respondWithTexts(classified("карбонара"), INGREDIENTS, MATCH);

        sendText(1, "замов усе для карбонари");

        assertThat(sentTexts())
                .anyMatch(text -> text.contains("Зберу все для «карбонара»"))
                .anyMatch(text -> text.contains("Інгредієнти для «карбонара» на 2 порції"));
        assertDishWasOrdered("карбонара");
        // The ingredient prompt is scoped to one dish and this household's size, not a weekly plan. Not the
        // last request any more: choosing which catalogue product each ingredient means is a call of its own.
        assertThat(CLAUDE.requests())
                .anyMatch(request -> request.toString().contains("Страва: карбонара")
                        && request.toString().contains("Порцій: 2"));
    }

    @Test
    void aPhotoWithACaptionIsIdentifiedAndConfirmedBeforeAnythingIsGenerated() throws Exception {
        CLAUDE.respondWithTexts(classified(null), IDENTIFIED, INGREDIENTS, MATCH);

        sendPhotoWithCaption(1, "замов все для цього");

        var question = TELEGRAM.sentMessages().getLast();
        assertThat(question.path("text").asText()).contains("Схоже на «карбонара»");
        assertThat(question.path("reply_markup").path("inline_keyboard").findValues("callback_data").stream()
                        .map(n -> n.asText()))
                .containsExactly("dish:yes", "dish:no");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.DISH_CONFIRM);
        assertThat(scheduledAdHocTaskRepository.count()).isZero();

        tapButton(2, "dish:yes");

        assertDishWasOrdered("карбонара");
    }

    @Test
    void aRequestWithoutADishNameAsksForOne() throws Exception {
        CLAUDE.respondWithTexts(classified(null), INGREDIENTS, MATCH);

        sendText(1, "хочу щось приготувати, замов інгредієнти");
        assertThat(sentTexts().getLast()).contains("Яку страву готуємо?");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.DISH_CONFIRM);

        sendText(2, "борщ");

        assertDishWasOrdered("борщ");
    }

    @Test
    void rejectingTheIdentifiedDishLetsThePersonNameItInstead() throws Exception {
        CLAUDE.respondWithTexts(classified(null), IDENTIFIED, INGREDIENTS, MATCH);

        sendPhotoWithCaption(1, "замов інгредієнти для цього");
        tapButton(2, "dish:no");
        assertThat(sentTexts().getLast()).contains("Яку страву готуємо?");

        sendText(3, "лазанья");

        assertDishWasOrdered("лазанья");
        assertThat(CLAUDE.requests()).anyMatch(request -> request.toString().contains("Страва: лазанья"));
    }
}
