package com.silporestockai.service;

import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The conversation in front of a dish-ingredients order (task 36): get a dish name the person stands behind,
 * then schedule it.
 *
 * <p>A name in the sentence («замов усе для карбонари») needs no conversation. A sentence without one asks for
 * a name or a photo. A photo goes through vision and comes back as a question — «Схоже на «карбонара».
 * Замовляти?» — because visual dish identification is approximate and a wrong guess must be one tap to
 * correct, not a cart to cancel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DishRequestService {

    public static final String CALLBACK_YES = "dish:yes";
    public static final String CALLBACK_NO = "dish:no";

    private static final String STEP_AWAITING_DISH = "AWAITING_DISH";
    private static final String STEP_AWAITING_CONFIRM = "AWAITING_CONFIRM";
    private static final String KEY_DISH = "dishName";

    private final AdHocScheduleService adHocScheduleService;
    private final DishIngredientsService dishIngredientsService;
    private final ConversationStateService conversationStateService;
    private final TelegramOutboundService telegramOutboundService;

    /** Entry from a sentence: the dish the classifier extracted, or nothing. */
    public void start(User user, String dishName) {
        if (dishName == null || dishName.isBlank()) {
            askForDish(user.getTelegramChatId());
            return;
        }
        proceed(user, dishName.trim());
    }

    /** Entry from a photo (with a caption the router read as this intent): identify, then ask. */
    public void startFromPhoto(User user, byte[] image, String mediaType) {
        long chatId = user.getTelegramChatId();
        Optional<String> dish = dishIngredientsService.identifyDish(image, mediaType);
        if (dish.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, "Не впізнав страву на фото. Напиши її назву.");
            conversationStateService.save(chatId, ConversationFlow.DISH_CONFIRM, STEP_AWAITING_DISH, Map.of());
            return;
        }
        conversationStateService.save(
                chatId, ConversationFlow.DISH_CONFIRM, STEP_AWAITING_CONFIRM, Map.of(KEY_DISH, dish.get()));
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                "Схоже на «%s». Замовляти інгредієнти для неї?".formatted(dish.get()),
                List.of(
                        TelegramButton.callback("Так, замовляй", CALLBACK_YES),
                        TelegramButton.callback("Ні, інша страва", CALLBACK_NO)));
    }

    /** Everything a chat sitting in {@link ConversationFlow#DISH_CONFIRM} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming, byte[] photoBytes) {
        long chatId = user.getTelegramChatId();
        ConversationState state = conversationStateService.load(chatId);
        boolean awaitingConfirm = STEP_AWAITING_CONFIRM.equals(state.getCurrentStep());
        switch (incoming) {
            case TelegramIncomingUpdate.ButtonTap tap -> {
                telegramOutboundService.answerCallback(tap.callbackQueryId());
                Object remembered = state.getContext().get(KEY_DISH);
                if (awaitingConfirm && CALLBACK_YES.equals(tap.data()) && remembered != null) {
                    proceed(user, remembered.toString());
                } else if (awaitingConfirm && CALLBACK_NO.equals(tap.data())) {
                    askForDish(chatId);
                } else {
                    log.debug("ignoring callback {} while awaiting a dish in chat {}", tap.data(), chatId);
                }
            }
            // A typed name at either step is the answer — at the confirm step it is the correction.
            case TelegramIncomingUpdate.Text text -> {
                String name = text.text().trim();
                if (name.isBlank()) {
                    askForDish(chatId);
                } else {
                    proceed(user, name);
                }
            }
            case TelegramIncomingUpdate.Photo photo -> {
                if (photoBytes == null) {
                    askForDish(chatId);
                } else {
                    startFromPhoto(user, photoBytes, photo.mediaType());
                }
            }
            case TelegramIncomingUpdate.Voice ignored -> askForDish(chatId);
            case TelegramIncomingUpdate.WebAppData ignored -> askForDish(chatId);
        }
    }

    private void askForDish(long chatId) {
        conversationStateService.save(chatId, ConversationFlow.DISH_CONFIRM, STEP_AWAITING_DISH, Map.of());
        telegramOutboundService.sendMessage(
                chatId, "Яку страву готуємо? Напиши назву або надішли фото готової страви.");
    }

    /** The conversation is over; the cart confirmation that follows owns the state from here. */
    private void proceed(User user, String dishName) {
        conversationStateService.save(user.getTelegramChatId(), ConversationFlow.NONE, null, Map.of());
        adHocScheduleService.scheduleDishIngredients(user, dishName);
    }
}
