package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.ShoppingListService;
import com.silporestockai.service.SpecialModeService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.telegram.ShoppingListMessageService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
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

@DisplayName("an AI-triggered regeneration shows what changed, not the full list again (task 21)")
class ShoppingListDeltaIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 17201L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SpecialModeService specialModeService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer("6060:stub-bot-token");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> "6060:stub-bot-token");
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
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
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(new BasketItem("p-1", "Гречка", "кг", BigDecimal.ONE, new BigDecimal("48"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
    }

    private static String weekJson(String breakfast, String lunchIngredient, String dinnerIngredient) {
        StringBuilder days = new StringBuilder();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            days.append("""
                    {"day":"%s","meals":[\
                    {"type":"BREAKFAST","name":"%s"},\
                    {"type":"LUNCH","name":"Обід"},\
                    {"type":"DINNER","name":"Вечеря"}]}""".formatted(day.name(), breakfast));
        }
        // Three lines that change between the two answers and two that stay: the diff is bulk, not trivial.
        return """
                {"days":[%s],"shoppingList":[\
                {"name":"%s","quantity":1,"unit":"шт","category":"Інше"},\
                {"name":"%s","quantity":0.5,"unit":"кг","category":"М'ясо і птиця"},\
                {"name":"%s","quantity":0.4,"unit":"кг","category":"Крупи і бакалія"},\
                {"name":"молоко","quantity":1,"unit":"л","category":"Молочні продукти"},\
                {"name":"хліб","quantity":1,"unit":"шт","category":"Хлібобулочні вироби"}]}""".formatted(days, breakfast, lunchIngredient, dinnerIngredient);
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

    @Test
    void firstEverPlanShowsTheFullListNotADelta() {
        // No previous active list exists yet, so this must behave exactly like the non-delta present().
        CLAUDE.respondWithText(weekJson("Вівсянка", "курка", "гречка"));

        specialModeService.triggerGastritis(user);

        String text = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(text).contains("Вівсянка").doesNotContain("Оновив раціон");
    }

    @Test
    void aBulkChangeShowsADeltaSummaryWithAShowFullListButtonInstead() throws Exception {
        CLAUDE.respondWithText(weekJson("Вівсянка", "курка", "гречка"));
        specialModeService.triggerGastritis(user);
        TELEGRAM.reset();
        // Cancelling regenerates again — this time against the gastritis plan's own active list, with every
        // breakfast/lunch/dinner ingredient different, so the diff is genuinely bulk (3 changed lines).
        CLAUDE.respondWithText(weekJson("Омлет", "індичка", "рис"));

        specialModeService.cancel(user);

        var last = TELEGRAM.sentMessages().getLast();
        assertThat(last.path("text").asText()).contains("Оновив раціон");
        assertThat(last.toString()).contains(ShoppingListMessageService.CALLBACK_SHOW_FULL);
        // The delta message itself must not carry the ordinary list-action buttons — the reader hasn't
        // seen the full new list yet.
        assertThat(last.toString()).doesNotContain(ShoppingListMessageService.CALLBACK_ORDER);

        tapButton(1, ShoppingListMessageService.CALLBACK_SHOW_FULL);

        String fullListText = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(fullListText).contains("Омлет");
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains(ShoppingListMessageService.CALLBACK_ORDER);
    }

    @Test
    void manualQuantityEditNeverProducesADeltaSummary() throws Exception {
        // adjustQuantity (the sli:inc:/sli:dec: callback path) never calls present/presentRegenerated at
        // all — this asserts the reply stays the plain "Оновив: <quantity>." confirmation, never the
        // delta-summary framing, even though the user's list just changed.
        var item = shoppingListService.addItem(user.getId(), "Молоко", BigDecimal.ONE, "л", null);
        conversationStateService.save(CHAT_ID, ConversationFlow.LIST_BUILDING, "AWAITING_APPROVAL", java.util.Map.of());

        tapButton(1, ShoppingListMessageService.CALLBACK_ITEM_INC_PREFIX + item.getId());

        String text = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(text).startsWith("Оновив:").doesNotContain("Оновив раціон");
        assertThat(TELEGRAM.sentMessages().getLast().toString())
                .doesNotContain(ShoppingListMessageService.CALLBACK_SHOW_FULL);
    }
}
