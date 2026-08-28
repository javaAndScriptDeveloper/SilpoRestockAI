package com.silporestockai.service;

import com.silporestockai.entity.ConversationState;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.ConversationStateRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the conversation state a Telegram chat resumes from.
 *
 * <p>{@link #load(long)} never returns {@code null}: an unknown chat gets a transient {@link ConversationFlow#NONE}
 * state that is not persisted until something calls {@link #save}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationStateService {

    private final ConversationStateRepository repository;

    @Transactional(readOnly = true)
    public ConversationState load(long telegramChatId) {
        return repository
                .findById(telegramChatId)
                .orElseGet(() -> ConversationState.builder()
                        .telegramChatId(telegramChatId)
                        .currentFlow(ConversationFlow.NONE)
                        .context(new LinkedHashMap<>())
                        .build());
    }

    @Transactional
    public ConversationState save(
            long telegramChatId, ConversationFlow flow, String step, Map<String, Object> context) {
        Instant now = Instant.now();
        ConversationState state = repository
                .findById(telegramChatId)
                .orElseGet(() -> ConversationState.builder()
                        .telegramChatId(telegramChatId)
                        .createdAt(now)
                        .build());
        state.setCurrentFlow(flow == null ? ConversationFlow.NONE : flow);
        state.setCurrentStep(step);
        state.setContext(context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context));
        state.setUpdatedAt(now);
        if (state.getCreatedAt() == null) {
            state.setCreatedAt(now);
        }
        return repository.save(state);
    }

    @Transactional
    public void clear(long telegramChatId) {
        repository.deleteById(telegramChatId);
        log.debug("cleared conversation state for chat {}", telegramChatId);
    }
}
