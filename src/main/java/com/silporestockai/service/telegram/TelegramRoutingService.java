package com.silporestockai.service.telegram;

import com.silporestockai.entity.ConversationState;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.service.ConversationStateService;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>The handlers here are placeholders. Task 06 replaces them with the onboarding flow, tasks 10 to 12 add the cart
 * and check-in flows. Until then they echo, which is what proves the webhook, the router, the conversation state and
 * the outbound service are wired together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramRoutingService {

    /** Key in {@code conversation_state.context_json} the placeholder echo counts with. */
    static final String MESSAGE_COUNT = "messageCount";

    private final ConversationStateService conversationStateService;
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
        switch (incoming) {
            // TODO(#6): replace the echo with the onboarding flow.
            case TelegramIncomingUpdate.Text text ->
                telegramOutboundService.sendMessage(
                        text.chatId(),
                        "Комора: почув — «%s» (повідомлення №%d)".formatted(text.text(), countMessage(text.chatId())));
            // TODO(#12): hand the bytes to transcription instead of reporting their size.
            case TelegramIncomingUpdate.Voice voice -> {
                byte[] audio = telegramOutboundService.downloadVoiceNote(voice.fileId());
                telegramOutboundService.sendMessage(
                        voice.chatId(),
                        "Комора: голосове отримав (%d с, %d байт). Розшифровка буде пізніше."
                                .formatted(voice.durationSeconds(), audio.length));
            }
            // TODO(#10): dispatch on the callback data once cart confirmation exists.
            case TelegramIncomingUpdate.ButtonTap tap -> {
                telegramOutboundService.answerCallback(tap.callbackQueryId());
                telegramOutboundService.sendMessage(tap.chatId(), "Комора: кнопка «%s».".formatted(tap.data()));
            }
        }
    }

    /** Increments and persists the placeholder counter, proving state survives between webhook calls. */
    private long countMessage(long chatId) {
        ConversationState state = conversationStateService.load(chatId);
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());
        long count = ((Number) context.getOrDefault(MESSAGE_COUNT, 0)).longValue() + 1;
        context.put(MESSAGE_COUNT, count);
        conversationStateService.save(chatId, state.getCurrentFlow(), state.getCurrentStep(), context);
        return count;
    }
}
