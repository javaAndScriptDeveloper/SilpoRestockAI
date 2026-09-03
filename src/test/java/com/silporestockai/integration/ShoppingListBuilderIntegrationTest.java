package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.telegram.ShoppingListMessageService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.math.BigDecimal;
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

@DisplayName("the list is built from what the person says, shown, and only then ordered")
class ShoppingListBuilderIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "1212:stub-bot-token";
    private static final long CHAT_ID = 12501L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private ShoppingListItemRepository shoppingListItemRepository;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MealPlanRepository mealPlanRepository;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Claude stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("claude.api-key", () -> "stub-anthropic-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        CLAUDE.reset();
        shoppingListItemRepository.deleteAll();
        mealPlanRepository.deleteAll();
        baselineBasketRepository.deleteAll();
        customerOrderRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();

        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                // The sentence that once produced eighty-four bananas, stored exactly as it was typed.
                .dietaryRestrictions(List.of("є алергія на молочку"))
                .dislikedFoods(List.of("все окрім молочки та бананів"))
                .weeklyBudget(new BigDecimal("3000"))
                .build());
    }

    private static String list(String json) {
        return "{\"items\":[" + json + "]}";
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

    private void tapButton(int updateId, String data) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"callback_query":{"id":"cb-%d","chat_instance":"ci",\
                                "from":{"id":5,"is_bot":false,"first_name":"Тест"},"data":"%s",\
                                "message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}}}}""".formatted(updateId, updateId, data, updateId, CHAT_ID)))
                .andExpect(status().isOk());
    }

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void theCommandAsksForAPhotoAReceiptOrADescription() throws Exception {
        sendText(1, "/list");

        assertThat(lastMessageText()).contains("фото").contains("чека").contains("напиши");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.LIST_BUILDING);
    }

    /** Tapping the main-menu button does exactly what typing the slash command does — same text, same route. */
    @Test
    void theMainMenuButtonDoesTheSameThingAsTheSlashCommand() throws Exception {
        sendText(1, com.silporestockai.service.telegram.MainMenuKeyboard.LIST);

        assertThat(lastMessageText()).contains("фото").contains("чека").contains("напиши");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.LIST_BUILDING);
    }

    @Test
    void aDescriptionBecomesAListShownForApproval() throws Exception {
        sendText(1, "/list");
        CLAUDE.respondWithText(list("""
                {"name":"Гречка","quantity":1,"unit":"кг"},\
                {"name":"Куряче філе","quantity":1.5,"unit":"кг"},\
                {"name":"Хліб житній","quantity":2,"unit":"шт"}"""));

        sendText(2, "звичайна їжа на тиждень, без молочки");

        assertThat(lastMessageText()).contains("Гречка").contains("Куряче філе").contains("3 позиції");
        assertThat(shoppingListItemRepository.findByUserIdAndStatus(
                        user.getId(), com.silporestockai.model.ShoppingListStatus.ACTIVE))
                .hasSize(3);
        // Nothing is ordered yet — that is the entire point of this step.
        assertThat(customerOrderRepository.findAll()).isEmpty();
    }

    @Test
    void thePersonsOwnWordsGoToTheModelUnsplit() throws Exception {
        sendText(1, "/list");
        CLAUDE.respondWithText(list("{\"name\":\"Гречка\",\"quantity\":1,\"unit\":\"кг\"}"));

        sendText(2, "на тиждень");

        // The sentence travels whole, quoted, so the model can read the negation in it. Split on commas it says
        // the opposite: that the household eats nothing but dairy and bananas.
        String request = CLAUDE.requests().getLast().toString();
        assertThat(request).contains("все окрім молочки та бананів").contains("є алергія на молочку");
    }

    @Test
    void anEditReplacesTheListRatherThanAddingToIt() throws Exception {
        sendText(1, "/list");
        CLAUDE.respondWithText(list("{\"name\":\"Банани\",\"quantity\":84,\"unit\":\"шт\"}"));
        sendText(2, "щось на тиждень");
        assertThat(shoppingListItemRepository.findByUserIdAndStatus(
                        user.getId(), com.silporestockai.model.ShoppingListStatus.ACTIVE))
                .hasSize(1);

        tapButton(3, ShoppingListMessageService.CALLBACK_EDIT);
        CLAUDE.respondWithText(list("""
                {"name":"Гречка","quantity":1,"unit":"кг"},{"name":"Яйця С1","quantity":10,"unit":"шт"}"""));
        sendText(4, "прибери банани, додай гречку і яйця");

        List<ShoppingListItem> items = shoppingListItemRepository.findByUserIdAndStatus(
                user.getId(), com.silporestockai.model.ShoppingListStatus.ACTIVE);
        assertThat(items).extracting(ShoppingListItem::getName).containsExactlyInAnyOrder("Гречка", "Яйця С1");
    }

    @Test
    void typingInsteadOfTappingIsTreatedAsAnEdit() throws Exception {
        sendText(1, "/list");
        CLAUDE.respondWithText(list("{\"name\":\"Банани\",\"quantity\":84,\"unit\":\"шт\"}"));
        sendText(2, "щось на тиждень");

        CLAUDE.respondWithText(list("{\"name\":\"Гречка\",\"quantity\":1,\"unit\":\"кг\"}"));
        sendText(3, "без бананів, будь ласка");

        assertThat(CLAUDE.requests().getLast().toString()).contains("змінити");
        assertThat(shoppingListItemRepository.findByUserIdAndStatus(
                        user.getId(), com.silporestockai.model.ShoppingListStatus.ACTIVE))
                .extracting(ShoppingListItem::getName)
                .containsExactly("Гречка");
    }

    @Test
    void cancellingOrdersNothingAndLeavesTheFlow() throws Exception {
        sendText(1, "/list");
        CLAUDE.respondWithText(list("{\"name\":\"Гречка\",\"quantity\":1,\"unit\":\"кг\"}"));
        sendText(2, "на тиждень");

        tapButton(3, ShoppingListMessageService.CALLBACK_CANCEL);

        assertThat(customerOrderRepository.findAll()).isEmpty();
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    /**
     * The exact failure a live account hit: a weekly meal plan hands its list to the same approval screen via
     * {@code present()}, but those lines carry a {@code mealPlanId} — {@code order()} looked only at ad-hoc lines
     * ({@code mealPlanId IS NULL}), found none, and told the person their list could not be built even though it was
     * sitting right there on screen.
     */
    @Test
    void tappingOrderWorksOnAListHandedOverFromAMealPlanNotJustAnAdHocOne() throws Exception {
        MealPlan plan = mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(java.time.LocalDate.now())
                .createdAt(java.time.Instant.now())
                .build());
        ShoppingListItem fromPlan = ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .mealPlanId(plan.getId())
                .name("Гречка")
                .quantity(new BigDecimal("1"))
                .unit("кг")
                .build();
        shoppingListItemRepository.save(fromPlan);
        conversationStateService.save(CHAT_ID, ConversationFlow.LIST_BUILDING, "AWAITING_APPROVAL", java.util.Map.of());

        tapButton(1, ShoppingListMessageService.CALLBACK_ORDER);

        assertThat(lastMessageText()).doesNotContain("Не вдалось скласти список");
    }

    /**
     * The exact failure a live account hit next: a weekly plan's list and a later {@code /list} answer both stayed
     * in {@code shopping_list_item} — one tagged with a {@code mealPlanId}, one not — invisible until an order
     * merged both, sent the same product to Silpo twice in one call, and the whole cart came back a bare 400.
     */
    @Test
    void showingANewListDeletesWhateverAnEarlierFlowLeftBehind() throws Exception {
        MealPlan plan = mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(java.time.LocalDate.now())
                .createdAt(java.time.Instant.now())
                .build());
        shoppingListItemRepository.save(ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .mealPlanId(plan.getId())
                .name("Гречка")
                .quantity(new BigDecimal("1"))
                .unit("кг")
                .build());

        sendText(1, "/list");
        CLAUDE.respondWithText(list("{\"name\":\"Молоко\",\"quantity\":1,\"unit\":\"л\"}"));
        sendText(2, "молоко на тиждень");

        assertThat(shoppingListItemRepository.findByUserIdAndStatus(
                        user.getId(), com.silporestockai.model.ShoppingListStatus.ACTIVE))
                .extracting(ShoppingListItem::getName)
                .containsExactly("Молоко");
    }

    @Test
    void anUnreadableAnswerAsksAgainRatherThanOrderingSomething() throws Exception {
        sendText(1, "/list");
        CLAUDE.respondWithText("вибачте, не зрозумів");

        sendText(2, "на тиждень");

        assertThat(lastMessageText()).contains("Не вдалось скласти список");
        assertThat(shoppingListItemRepository.findAll()).isEmpty();
        assertThat(customerOrderRepository.findAll()).isEmpty();
    }
}
