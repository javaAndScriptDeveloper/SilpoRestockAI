package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.IntentClassification;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.IntentClassificationRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
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

/**
 * Task 55: every classification leaves one row, including the ones that did not work.
 *
 * <p>The pitch artifact's intent distribution counts these rows. A distribution built from orders would be
 * silently short by the nine intents that never build one.
 */
@DisplayName("each intent classification is recorded as evidence for the pitch artifact")
class IntentClassificationIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "4055:stub-bot-token";
    private static final long CHAT_ID = 15955L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IntentClassificationRepository intentClassificationRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of("silpo_get_my_shopping_cart"));
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
        intentClassificationRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        User user = userAccountService.findOrCreate(CHAT_ID);
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

    @Test
    void aRoutedClassificationIsRecordedUnderItsOwnIntentName() throws Exception {
        CLAUDE.respondWithText("""
                {"intent":"HELP","confidence":0.95,"themeDescription":null,"targetDateTimeIso":null}""");

        sendText(1, "що ти вмієш");

        assertThat(intentClassificationRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getIntent()).isEqualTo("HELP");
            assertThat(row.getOutcome()).isEqualTo("ROUTED");
            assertThat(row.getConfidence()).isEqualTo(0.95);
            assertThat(row.getUserId()).isNotNull();
            assertThat(row.getClassifiedAt()).isNotNull();
        });
    }

    @Test
    void aClassificationBelowTheConfidenceThresholdIsRecordedAsUnclassified() throws Exception {
        CLAUDE.respondWithText("""
                {"intent":"HELP","confidence":0.2,"themeDescription":null,"targetDateTimeIso":null}""");

        sendText(2, "ммм");

        assertThat(intentClassificationRepository.findAll())
                .singleElement()
                .extracting(IntentClassification::getIntent, IntentClassification::getOutcome)
                .containsExactly("UNCLASSIFIED", "UNCLASSIFIED");
    }

    @Test
    void anIntentNameTheClassifierInventedIsRecordedAsUnclassifiedRatherThanAsItself() throws Exception {
        CLAUDE.respondWithText("""
                {"intent":"ORDER_A_PONY","confidence":0.99,"themeDescription":null,"targetDateTimeIso":null}""");

        sendText(3, "хочу поні");

        assertThat(intentClassificationRepository.findAll())
                .singleElement()
                .extracting(IntentClassification::getIntent)
                .isEqualTo("UNCLASSIFIED");
    }
}
