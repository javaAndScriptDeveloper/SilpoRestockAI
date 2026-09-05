package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.AgeBracket;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.DietType;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Base64;
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

@DisplayName("reopening the Анкета after onboarding")
class ProfileReeditIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "666:stub-bot-token";
    private static final long CHAT_ID = 9101L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private MealPlanRepository mealPlanRepository;

    @Autowired
    private ShoppingListItemRepository shoppingListItemRepository;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of());
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
        registry.add("telegram.web-app-base-url", () -> "https://example.test");
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
        shoppingListItemRepository.deleteAll();
        mealPlanRepository.deleteAll();
        userProfileRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void deliver(String body) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void sendText(int updateId, String text) throws Exception {
        deliver(
                """
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "text":"%s"}}"""
                        .formatted(updateId, updateId, CHAT_ID, text));
    }

    private void tapButton(int updateId, String data) throws Exception {
        deliver(
                """
                {"update_id":%d,"callback_query":{"id":"cb-%d","chat_instance":"ci",\
                "from":{"id":5,"is_bot":false,"first_name":"Тест"},"data":"%s",\
                "message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}}}}"""
                        .formatted(updateId, updateId, data, updateId, CHAT_ID));
    }

    private void sendWebAppData(int updateId, String json) throws Exception {
        String escaped = MAPPER.writeValueAsString(json);
        deliver(
                """
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "web_app_data":{"data":%s,"button_text":"Заповнити анкету"}}}"""
                        .formatted(updateId, updateId, CHAT_ID, escaped));
    }

    private UUID onboardedUser() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .adultMaleCount(2)
                .adultFemaleCount(0)
                .childrenAgeBrackets(List.of(AgeBracket.AGE_4_7))
                .householdSize(3)
                .hasKids(true)
                .dietaryRestrictions(List.of("nuts"))
                .dietType(DietType.NONE)
                .cookingTimePreference(CookingTimePreference.COOKS_DAILY)
                .weeklyBudget(new BigDecimal("2500"))
                .onlyUaProducer(false)
                .build());
        return user.getId();
    }

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void tappingAnketaReopensTheFormPrefilledWithCurrentAnswers() throws Exception {
        onboardedUser();

        sendText(1, "🧾 Анкета");

        var sent = TELEGRAM.sentMessages().getLast();
        String webAppUrl = sent.path("reply_markup")
                .path("keyboard")
                .get(0)
                .get(0)
                .path("web_app")
                .path("url")
                .asText();
        String encoded = webAppUrl.substring(webAppUrl.indexOf("prefill=") + "prefill=".length());
        JsonNode prefill =
                MAPPER.readTree(Base64.getUrlDecoder().decode(encoded.replace('-', '+').replace('_', '/')));
        assertThat(prefill.path("adultMale").asInt()).isEqualTo(2);
        assertThat(prefill.path("adultFemale").asInt()).isEqualTo(0);
        assertThat(prefill.path("childrenAgeBrackets").get(0).asText()).isEqualTo("AGE_4_7");
        assertThat(prefill.path("restrictions").get(0).asText()).isEqualTo("nuts");
        assertThat(prefill.path("dietType").asText()).isEqualTo("NONE");
        assertThat(prefill.path("cookingTimePreference").asText()).isEqualTo("COOKS_DAILY");
        assertThat(prefill.path("weeklyBudget").asDouble()).isEqualTo(2500.0);

        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow())
                .isEqualTo(ConversationFlow.PROFILE_REEDIT);
    }
}
