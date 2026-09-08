package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.Feedback;
import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.FeedbackSource;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.FeedbackRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The «Фідбек» button (task 47): ask for one message, store it raw, put the chat back where it was.
 *
 * <p>The "back where it was" part is the only design in here. Tapping the button in the middle of a cart
 * confirmation or a list edit must not strand that flow, so the previous {@code conversation_state} — flow, step
 * and context — is snapshotted into the feedback flow's own context and written back once the message arrives
 * (or the prompt is cancelled, or a persistent-menu button interrupts it). Nothing is kept in memory; two
 * webhook calls may land on two instances.
 *
 * <p>Reachable before onboarding finishes on purpose: a tester stuck on the first screen is exactly who should
 * be able to say so.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    public static final String CALLBACK_CANCEL = "fb:cancel";

    private static final String STEP_AWAITING_TEXT = "AWAITING_TEXT";
    private static final String KEY_PREVIOUS_FLOW = "previousFlow";
    private static final String KEY_PREVIOUS_STEP = "previousStep";
    private static final String KEY_PREVIOUS_CONTEXT = "previousContext";

    /** Own mapper, as elsewhere in the app: Boot 4 carries both Jackson 2 and Jackson 3. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final FeedbackRepository feedbackRepository;
    private final ConversationStateService conversationStateService;
    private final TelegramOutboundService telegramOutboundService;

    /** Snapshots whatever the chat was doing and asks the one question. A second tap does not nest snapshots. */
    public void prompt(User user) {
        long chatId = user.getTelegramChatId();
        ConversationState state = conversationStateService.load(chatId);
        Map<String, Object> snapshot = state.getCurrentFlow() == ConversationFlow.FEEDBACK
                ? new LinkedHashMap<>(state.getContext())
                : snapshotOf(state);
        conversationStateService.save(chatId, ConversationFlow.FEEDBACK, STEP_AWAITING_TEXT, snapshot);
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                "Що не так або що покращити? Напиши одним повідомленням.",
                List.of(TelegramButton.callback("Скасувати", CALLBACK_CANCEL)));
    }

    /** Everything a chat sitting in {@link ConversationFlow#FEEDBACK} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = user.getTelegramChatId();
        ConversationState state = conversationStateService.load(chatId);
        switch (incoming) {
            case TelegramIncomingUpdate.ButtonTap tap -> {
                telegramOutboundService.answerCallback(tap.callbackQueryId());
                if (CALLBACK_CANCEL.equals(tap.data())) {
                    restore(chatId, state);
                    telegramOutboundService.sendMessage(chatId, "Гаразд, без фідбеку.");
                } else {
                    log.debug("ignoring callback {} while awaiting feedback in chat {}", tap.data(), chatId);
                }
            }
            case TelegramIncomingUpdate.Text text -> {
                submit(user, text.text(), FeedbackSource.BUTTON);
                restore(chatId, state);
                telegramOutboundService.sendMessage(chatId, "Дякую, врахуємо.");
            }
            // Raw text is the whole feature; a transcription or an image would be a different, lossier record.
            case TelegramIncomingUpdate.Voice ignored ->
                telegramOutboundService.sendMessage(chatId, "Напиши, будь ласка, текстом.");
            case TelegramIncomingUpdate.Photo ignored ->
                telegramOutboundService.sendMessage(chatId, "Напиши, будь ласка, текстом.");
            case TelegramIncomingUpdate.WebAppData ignored ->
                telegramOutboundService.sendMessage(chatId, "Напиши, будь ласка, текстом.");
            // The group-chat shapes (task 68) never reach a household flow; the router splits them off first.
            default -> log.debug("ignoring a group update in a household flow for chat {}", incoming.chatId());
        }
    }

    /**
     * A persistent-menu tap while the prompt is open means "never mind": the previous state comes back and the
     * button does what it always does. Without this, the next sentence typed after «Список» would have been
     * filed as feedback and the list edit lost.
     */
    public boolean abandonIfPending(long chatId) {
        ConversationState state = conversationStateService.load(chatId);
        if (state.getCurrentFlow() != ConversationFlow.FEEDBACK) {
            return false;
        }
        restore(chatId, state);
        return true;
    }

    public Feedback submit(User user, String rawText, FeedbackSource source) {
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .id(UUID.randomUUID())
                .userId(user == null ? null : user.getId())
                .telegramChatId(user == null ? null : user.getTelegramChatId())
                .rawText(rawText.strip())
                .source(source)
                .createdAt(Instant.now())
                .build());
        log.info("stored feedback {} from chat {}", feedback.getId(), feedback.getTelegramChatId());
        return feedback;
    }

    private static Map<String, Object> snapshotOf(ConversationState state) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put(KEY_PREVIOUS_FLOW, state.getCurrentFlow().name());
        snapshot.put(KEY_PREVIOUS_STEP, state.getCurrentStep());
        snapshot.put(KEY_PREVIOUS_CONTEXT, new LinkedHashMap<>(state.getContext()));
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private void restore(long chatId, ConversationState state) {
        Map<String, Object> snapshot = state.getContext();
        Object flowName = snapshot.get(KEY_PREVIOUS_FLOW);
        ConversationFlow flow =
                flowName == null ? ConversationFlow.NONE : ConversationFlow.valueOf(flowName.toString());
        Object step = snapshot.get(KEY_PREVIOUS_STEP);
        Object context = snapshot.get(KEY_PREVIOUS_CONTEXT);
        Map<String, Object> previous =
                context instanceof Map<?, ?> map ? MAPPER.convertValue(map, Map.class) : new LinkedHashMap<>();
        conversationStateService.save(chatId, flow, step == null ? null : step.toString(), previous);
    }
}
