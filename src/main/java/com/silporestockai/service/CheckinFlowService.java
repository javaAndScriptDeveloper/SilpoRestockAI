package com.silporestockai.service;

import com.silporestockai.entity.User;
import com.silporestockai.model.CheckinResult;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.service.telegram.CheckinMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.util.List;
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
    private final IntentRouterService intentRouterService;

    /** Everything a chat sitting in {@link ConversationFlow#CHECK_IN} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = incoming.chatId();
        switch (incoming) {
            case TelegramIncomingUpdate.Text text -> {
                CheckinResult result = checkinParsingService.parseText(user.getId(), text.text());
                if (result.needsClarification() && wasARequestInstead(user, chatId, text.text())) {
                    return;
                }
                respond(user, chatId, result);
            }
            case TelegramIncomingUpdate.Voice voice -> handleVoice(user, chatId, voice);
            case TelegramIncomingUpdate.Photo photo -> handlePhoto(user, chatId, photo);
            case TelegramIncomingUpdate.ButtonTap tap -> {
                // Nothing in a check-in has buttons; this is a leftover keyboard from another flow.
                telegramOutboundService.answerCallback(tap.callbackQueryId());
                log.debug("ignoring button tap {} during a check-in in chat {}", tap.data(), chatId);
            }
            case TelegramIncomingUpdate.WebAppData ignored ->
                // A stray onboarding-form submission arriving while a check-in is in progress; nothing here
                // reads it.
                log.debug("ignoring web_app_data during a check-in in chat {}", chatId);
            // The group-chat shapes (task 68) never reach a household flow; the router splits them off first.
            default -> log.debug("ignoring a group update in a household flow for chat {}", incoming.chatId());
        }
    }

    /**
     * A sentence the check-in parser made nothing of may not be an answer at all. On a live account «замов сир з
     * вином» typed while a prompt was open came back as «Не розібрав. Скажи коротко по цих: Хек Norven…» — the
     * open question had swallowed a request. So the sentence is offered to the intent router first; if it is a
     * request, the check-in steps aside (the next sweep asks again) and the request is carried out. Only a
     * sentence nobody recognises gets the clarification.
     */
    private boolean wasARequestInstead(User user, long chatId, String text) {
        // Step aside first: the dispatched flow may own conversation_state from here on, and a check-in state
        // written back over it afterwards would leave that flow's buttons dead.
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        if (intentRouterService.tryRoute(user, text)) {
            // The parser already stored the sentence as an unanswered check-in. It was never one: leaving the row
            // counts a hangover order as a check-in reply in the pitch metrics and as a cycle in the trend.
            checkinParsingService.discardNotAnAnswer(user.getId(), text);
            log.info("check-in for user {} stepped aside for a request typed over it", user.getId());
            return true;
        }
        conversationStateService.save(
                chatId, ConversationFlow.CHECK_IN, CheckinPromptService.STEP_AWAITING_REPORT, Map.of());
        return false;
    }

    private void handleVoice(User user, long chatId, TelegramIncomingUpdate.Voice voice) {
        if (!checkinParsingService.voiceSupported()) {
            telegramOutboundService.sendMessage(chatId, checkinMessageService.voiceUnsupportedText());
            return;
        }
        try {
            byte[] audio = telegramOutboundService.downloadFile(voice.fileId());
            respond(user, chatId, checkinParsingService.parseVoice(user.getId(), audio));
        } catch (RuntimeException e) {
            // Transcription is the one step with no partial result to keep: without text there is nothing to store.
            log.error("could not handle a voice check-in from user {}", user.getId(), e);
            telegramOutboundService.sendMessage(chatId, checkinMessageService.voiceUnsupportedText());
        }
    }

    /** A fridge photo is a third way to answer the same question; everything after the model call is shared. */
    private void handlePhoto(User user, long chatId, TelegramIncomingUpdate.Photo photo) {
        try {
            byte[] image = telegramOutboundService.downloadFile(photo.fileId());
            respond(user, chatId, checkinParsingService.parsePhoto(user.getId(), image, photo.mediaType()), true);
        } catch (RuntimeException e) {
            log.error("could not read a fridge photo from user {}", user.getId(), e);
            telegramOutboundService.sendMessage(chatId, checkinMessageService.clarificationText(List.of()));
        }
    }

    /**
     * Acknowledge and close, or ask once more.
     *
     * <p>A check-in that could not be understood keeps the chat in {@link ConversationFlow#CHECK_IN}: the next message
     * is still an answer to the same question, and the alternative is treating silence as "nothing changed".
     */
    private void respond(User user, long chatId, CheckinResult result) {
        respond(user, chatId, result, false);
    }

    private void respond(User user, long chatId, CheckinResult result, boolean fromPhoto) {
        if (result.needsClarification()) {
            telegramOutboundService.sendMessage(
                    chatId,
                    checkinMessageService.clarificationText(checkinParsingService.baselineItemNames(user.getId())));
            return;
        }
        String acknowledgement = checkinMessageService.acknowledgementText(result.delta());
        telegramOutboundService.sendMessage(
                chatId,
                fromPhoto ? acknowledgement + "\n" + checkinMessageService.photoDisclaimerText() : acknowledgement);
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        log.info("check-in recorded for user {}", user.getId());
    }
}
