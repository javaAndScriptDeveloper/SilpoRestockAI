package com.silporestockai.service;

import com.silporestockai.entity.User;
import com.silporestockai.model.CheckinResult;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.service.telegram.CheckinMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The conversation half of a check-in: what to do with the answer, and what to say back.
 *
 * <p>Split from {@link CheckinParsingService} the same way the cart flow is split from its message service — this one
 * knows the chat is waiting, the other knows what the words meant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinFlowService {

    private final CheckinParsingService checkinParsingService;
    private final CheckinMessageService checkinMessageService;
    private final TelegramOutboundService telegramOutboundService;
    private final ConversationStateService conversationStateService;

    /** Everything a chat sitting in {@link ConversationFlow#CHECK_IN} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = incoming.chatId();
        switch (incoming) {
            case TelegramIncomingUpdate.Text text ->
                respond(user, chatId, checkinParsingService.parseText(user.getId(), text.text()));
            case TelegramIncomingUpdate.Voice voice -> handleVoice(user, chatId, voice);
            case TelegramIncomingUpdate.ButtonTap tap -> {
                // Nothing in a check-in has buttons; this is a leftover keyboard from another flow.
                telegramOutboundService.answerCallback(tap.callbackQueryId());
                log.debug("ignoring button tap {} during a check-in in chat {}", tap.data(), chatId);
            }
        }
    }

    private void handleVoice(User user, long chatId, TelegramIncomingUpdate.Voice voice) {
        if (!checkinParsingService.voiceSupported()) {
            telegramOutboundService.sendMessage(chatId, checkinMessageService.voiceUnsupportedText());
            return;
        }
        try {
            byte[] audio = telegramOutboundService.downloadVoiceNote(voice.fileId());
            respond(user, chatId, checkinParsingService.parseVoice(user.getId(), audio));
        } catch (RuntimeException e) {
            // Transcription is the one step with no partial result to keep: without text there is nothing to store.
            log.error("could not handle a voice check-in from user {}", user.getId(), e);
            telegramOutboundService.sendMessage(chatId, checkinMessageService.voiceUnsupportedText());
        }
    }

    /**
     * Acknowledge and close, or ask once more.
     *
     * <p>A check-in that could not be understood keeps the chat in {@link ConversationFlow#CHECK_IN}: the next message
     * is still an answer to the same question, and the alternative is treating silence as "nothing changed".
     */
    private void respond(User user, long chatId, CheckinResult result) {
        if (result.needsClarification()) {
            telegramOutboundService.sendMessage(
                    chatId,
                    checkinMessageService.clarificationText(checkinParsingService.baselineItemNames(user.getId())));
            return;
        }
        telegramOutboundService.sendMessage(chatId, checkinMessageService.acknowledgementText(result.delta()));
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        log.info("check-in recorded for user {}", user.getId());
    }
}
