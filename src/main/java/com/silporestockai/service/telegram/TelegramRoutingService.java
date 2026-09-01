package com.silporestockai.service.telegram;

import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.service.CartConfirmationService;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.onboarding.OnboardingFlowService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Turns a Telegram update into one of the internal {@link TelegramIncomingUpdate} shapes and dispatches it.
 *
 * <p>This class and {@code TelegramWebhookController} are the only places that see the Telegram SDK. Everything
 * downstream receives records that carry no SDK types.
 *
 * <p>Dispatch is deliberately thin: the user row is resolved here, and everything else is decided by whether that
 * user has a profile yet. Tasks 10 to 12 add the cart and check-in flows next to the onboarding branch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramRoutingService {

    private final UserAccountService userAccountService;
    private final OnboardingFlowService onboardingFlowService;
    private final ConversationStateService conversationStateService;
    private final CartConfirmationService cartConfirmationService;
    private final TelegramOutboundService telegramOutboundService;

    public void route(Update update) {
        toIncoming(update).ifPresentOrElse(this::handle, () -> log.debug("ignoring unsupported Telegram update"));
    }

    private Optional<TelegramIncomingUpdate> toIncoming(Update update) {
        if (update.hasMessage()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            long userId = message.getFrom() == null ? 0L : message.getFrom().getId();
            if (message.hasText()) {
                return Optional.of(new TelegramIncomingUpdate.Text(chatId, userId, message.getText()));
            }
            if (message.hasVoice()) {
                var voice = message.getVoice();
                int duration = voice.getDuration() == null ? 0 : voice.getDuration();
                return Optional.of(new TelegramIncomingUpdate.Voice(chatId, userId, voice.getFileId(), duration));
            }
            return Optional.empty();
        }
        if (update.hasCallbackQuery()) {
            CallbackQuery callback = update.getCallbackQuery();
            if (callback.getMessage() == null) {
                return Optional.empty();
            }
            long userId = callback.getFrom() == null ? 0L : callback.getFrom().getId();
            return Optional.of(new TelegramIncomingUpdate.ButtonTap(
                    callback.getMessage().getChatId(), userId, callback.getId(), callback.getData()));
        }
        return Optional.empty();
    }

    private void handle(TelegramIncomingUpdate incoming) {
        User user = userAccountService.findOrCreate(incoming.chatId());
        if (!onboardingFlowService.isOnboarded(user.getId())) {
            onboardingFlowService.handle(user, incoming);
            return;
        }
        ConversationFlow flow = conversationStateService.load(incoming.chatId()).getCurrentFlow();
        if (flow == ConversationFlow.CART_CONFIRMATION) {
            cartConfirmationService.handle(user, incoming);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.ButtonTap tap) {
            // A keyboard left over from a conversation that has already ended — a second tap on confirm, most
            // often. Acknowledge it so Telegram stops spinning and say nothing: answering a button nobody is
            // waiting on with small talk is worse than silence.
            telegramOutboundService.answerCallback(tap.callbackQueryId());
            log.debug("ignoring stale button tap {} in chat {}", tap.data(), tap.chatId());
            return;
        }
        // TODO(#11): scheduled check-ins and the reorder cycle answer here.
        telegramOutboundService.sendMessage(
                incoming.chatId(), "Профіль уже є. Регулярні чек-іни та перезамовлення додам далі.");
    }
}
