package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.ConversationState;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.service.ConversationStateService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("conversation_state survives separate transactions the way separate webhook calls need it to")
class ConversationStateIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 4242L;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @BeforeEach
    void clean() {
        conversationStateRepository.deleteAll();
    }

    @Test
    void returnsATransientEmptyStateForAnUnknownChat() {
        ConversationState state = conversationStateService.load(CHAT_ID);

        assertThat(state.getTelegramChatId()).isEqualTo(CHAT_ID);
        assertThat(state.getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
        assertThat(state.getCurrentStep()).isNull();
        assertThat(state.getContext()).isEmpty();
        assertThat(conversationStateRepository.count()).isZero();
    }

    @Test
    void savesAndReadsBackFlowStepAndJsonContext() {
        conversationStateService.save(
                CHAT_ID, ConversationFlow.ONBOARDING, "ask-household-size", Map.of("messageCount", 1));

        ConversationState reloaded = conversationStateService.load(CHAT_ID);

        assertThat(reloaded.getCurrentFlow()).isEqualTo(ConversationFlow.ONBOARDING);
        assertThat(reloaded.getCurrentStep()).isEqualTo("ask-household-size");
        assertThat(reloaded.getContext()).containsEntry("messageCount", 1);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void overwritesTheStateForAChatInsteadOfAppendingRows() {
        conversationStateService.save(CHAT_ID, ConversationFlow.ONBOARDING, "step-1", Map.of("messageCount", 1));
        conversationStateService.save(CHAT_ID, ConversationFlow.CHECK_IN, "step-2", Map.of("messageCount", 2));

        assertThat(conversationStateRepository.count()).isEqualTo(1);
        ConversationState reloaded = conversationStateService.load(CHAT_ID);
        assertThat(reloaded.getCurrentFlow()).isEqualTo(ConversationFlow.CHECK_IN);
        assertThat(reloaded.getContext()).containsEntry("messageCount", 2);
    }

    @Test
    void clearRemovesTheRow() {
        conversationStateService.save(CHAT_ID, ConversationFlow.ONBOARDING, "step-1", Map.of());

        conversationStateService.clear(CHAT_ID);

        assertThat(conversationStateRepository.count()).isZero();
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }
}
