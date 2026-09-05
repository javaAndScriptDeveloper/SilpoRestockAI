package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.AdHocScheduleService;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

@DisplayName("the Заплановані view over one-off scheduled ad-hoc purchases")
class ScheduledTaskManagementIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "777:stub-bot-token";
    private static final long CHAT_ID = 9201L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private AdHocScheduleService adHocScheduleService;

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
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
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

    private UUID onboardedUser() {
        UUID userId = userAccountService.findOrCreate(CHAT_ID).getId();
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .householdSize(2)
                .onlyUaProducer(false)
                .build());
        return userId;
    }

    private ScheduledAdHocTask pendingTask(UUID userId, String theme, Instant triggerAt) {
        return scheduledAdHocTaskRepository.save(ScheduledAdHocTask.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .themeDescription(theme)
                .triggerAt(triggerAt)
                .status(ScheduledAdHocTaskStatus.PENDING)
                .createdAt(Instant.now())
                .build());
    }

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void repositoryListsOnlyThatUsersPendingTasksOldestFirst() {
        UUID userId = onboardedUser();
        UUID otherUserId = UUID.randomUUID();
        Instant now = Instant.now();
        ScheduledAdHocTask later = pendingTask(userId, "сир на вечір", now.plus(2, ChronoUnit.DAYS));
        ScheduledAdHocTask sooner = pendingTask(userId, "вино на п'ятницю", now.plus(1, ChronoUnit.DAYS));
        ScheduledAdHocTask fired = pendingTask(userId, "вже вистрелило", now.minusSeconds(60));
        fired.setStatus(ScheduledAdHocTaskStatus.FIRED);
        scheduledAdHocTaskRepository.save(fired);
        pendingTask(otherUserId, "не мій", now.plus(1, ChronoUnit.DAYS));

        List<ScheduledAdHocTask> pending = scheduledAdHocTaskRepository.findByUserIdAndStatusOrderByTriggerAtAsc(
                userId, ScheduledAdHocTaskStatus.PENDING);

        assertThat(pending).extracting(ScheduledAdHocTask::getId).containsExactly(sooner.getId(), later.getId());
    }

    @Test
    void emptyStateIsAClearMessageNotAnEmptyList() throws Exception {
        onboardedUser();

        sendText(1, "🗓 Заплановані");

        assertThat(lastMessageText()).contains("Немає запланованих замовлень");
    }

    @Test
    void pendingTasksRenderWithThemeTimeAndButtons() throws Exception {
        UUID userId = onboardedUser();
        ScheduledAdHocTask task = pendingTask(userId, "вино та сир зі знижкою", Instant.parse("2026-09-11T18:00:00Z"));

        sendText(1, "🗓 Заплановані");

        var sent = TELEGRAM.sentMessages().getLast();
        assertThat(sent.path("text").asText()).contains("вино та сир зі знижкою").contains("вересня");
        var row = sent.path("reply_markup").path("inline_keyboard").get(0);
        assertThat(row.get(0).path("text").asText()).isEqualTo("Редагувати");
        assertThat(row.get(0).path("callback_data").asText()).isEqualTo("sched:edit:" + task.getId());
        assertThat(row.get(1).path("text").asText()).isEqualTo("Скасувати");
        assertThat(row.get(1).path("callback_data").asText()).isEqualTo("sched:cancel:" + task.getId());
    }
}
