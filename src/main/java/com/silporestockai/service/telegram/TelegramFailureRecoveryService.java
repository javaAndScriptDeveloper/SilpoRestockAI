package com.silporestockai.service.telegram;

import com.silporestockai.exception.CartBuildException;
import com.silporestockai.exception.ClaudeApiException;
import com.silporestockai.exception.SilpoMcpException;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.service.ConversationStateService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The last-resort safety net for anything that escapes {@link TelegramRoutingService}'s dispatch (task 26)
 * — an exhausted-retry {@link SilpoMcpException}/{@link ClaudeApiException}, or any other unhandled
 * exception a specific flow didn't already catch locally (several already do, e.g.
 * {@code CartConfirmationService.present}, {@code MealPlanHandoffService.generateFirstPlan} — those keep
 * their own more specific messages; this only covers what nothing else caught).
 *
 * <p>{@code route()} is {@code @Async}, so without this an exception thrown deep in a flow never reaches
 * {@code TelegramWebhookController}'s own try/catch at all — Spring's default async-uncaught-exception
 * handling just logs it and the user sees nothing. Silence is the one outcome this exists to rule out.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramFailureRecoveryService {

    private final ConversationStateService conversationStateService;
    private final TelegramOutboundService telegramOutboundService;

    /**
     * Logs the full exception for debugging, resets the chat's conversation state so the next message is
     * never a dead end, and tells the user — in friendly Ukrainian, never a stack trace — what happened.
     */
    public void recover(long chatId, RuntimeException e) {
        log.error("unhandled failure while processing an update for chat {}", chatId, e);
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        telegramOutboundService.sendMessage(chatId, messageFor(e));
    }

    private static String messageFor(RuntimeException e) {
        // CartBuildException (and NoSilpoDeliveryAddressException, which extends it) is not a SilpoMcpException
        // itself — CartBuildingService raises it directly when a Silpo tool answers with its own error payload
        // rather than a transport failure — but it is exactly as much "Сільпо" as a network-level one is.
        if (e instanceof SilpoMcpException || e instanceof CartBuildException) {
            return "Сільпо тимчасово не відповідає, спробуйте за хвилину.";
        }
        if (e instanceof ClaudeApiException) {
            return "Не вдалось згенерувати відповідь, спробуйте ще раз.";
        }
        return "Щось пішло не так. Спробуйте ще раз або напишіть /start.";
    }
}
