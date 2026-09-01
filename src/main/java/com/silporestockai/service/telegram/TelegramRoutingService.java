package com.silporestockai.service.telegram;

import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.service.CartConfirmationService;
import com.silporestockai.service.CheckinFlowService;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.GoogleAuthService;
import com.silporestockai.service.ReorderConfirmationService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.onboarding.OnboardingFlowService;
import java.util.List;
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
    private final CheckinFlowService checkinFlowService;
    private final ReorderConfirmationService reorderConfirmationService;
    private final GoogleAuthService googleAuthService;
    private final TelegramOutboundService telegramOutboundService;

    public void route(Update update) {
        toIncoming(update).ifPresentOrElse(this::handle, () -> log.debug("ignoring unsupported Telegram update"));
    }

    /** Opt-in, and only ever opt-in: a calendar nobody connected is never touched. */
    private void offerCalendar(User user, long chatId) {
        if (!googleAuthService.configured()) {
            telegramOutboundService.sendMessage(chatId, "Календар зараз не налаштований на сервері.");
            return;
        }
        if (googleAuthService.isConnected(user.getId())) {
            telegramOutboundService.sendMessage(chatId, "Календар уже підключено — додаю туди слоти доставки.");
            return;
        }
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                "Підключи Google Календар — і я вноситиму туди вікна доставки.",
                List.of(TelegramButton.link(
                        "Підключити календар", googleAuthService.buildAuthorizationUrl(user.getId()))));
    }

    private Optional<TelegramIncomingUpdate> toIncoming(Update update) {
        if (update.hasMessage()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            long userId = message.getFrom() == null ? 0L : message.getFrom().getId();
            if (message.hasText()) {
                return Optional.of(new TelegramIncomingUpdate.Text(chatId, userId, message.getText()));
            }
            if (message.hasPhoto()) {
                // Telegram sends the same picture in several sizes, smallest first. The model wants the pixels.
                var largest = message.getPhoto().getLast();
                return Optional.of(new TelegramIncomingUpdate.Photo(chatId, userId, largest.getFileId(), "image/jpeg"));
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
        if (flow == ConversationFlow.CHECK_IN) {
            checkinFlowService.handle(user, incoming);
            return;
        }
        if (flow == ConversationFlow.REORDER_CONFIRMATION) {
            reorderConfirmationService.handle(user, incoming);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text text
                && text.text().strip().startsWith("/calendar")) {
            offerCalendar(user, incoming.chatId());
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
        telegramOutboundService.sendMessage(
                incoming.chatId(), "Профіль уже є. Регулярні чек-іни та перезамовлення додам далі.");
    }
}
