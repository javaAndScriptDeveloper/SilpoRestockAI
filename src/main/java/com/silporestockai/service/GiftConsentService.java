package com.silporestockai.service;

import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Leaving — or taking back — the address friends may send gifts to (task 81).
 *
 * <p>The Анкета offers this once, at the end of onboarding. This is the other way in, for the person who reads
 * about the feature later and wants it, and the only way out for the person who changes their mind. Both
 * directions are one intent, like the Ukrainian-producer filter: the sentence says which.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftConsentService {

    static final String STEP_AWAITING_ADDRESS = "AWAITING_GIFT_CONSENT_ADDRESS";

    /** The ways a person asks to be taken off the list. Anything else under this intent is asking to go on it. */
    private static final Pattern REVOKES =
            Pattern.compile("більше не|не хочу|прибери|видали|забудь|скасуй|вимкни|заборон", Pattern.CASE_INSENSITIVE);

    private final UserProfileRepository userProfileRepository;
    private final ConversationStateService conversationStateService;
    private final TelegramOutboundService telegramOutboundService;

    /** Task 31's entry point: a sentence classified as GIFT_ADDRESS_CONSENT. */
    public void handleRequest(User user, String text) {
        if (text != null && REVOKES.matcher(text).find()) {
            revoke(user);
            return;
        }
        offer(user);
    }

    /** Asks for the address and the phone in one message — both, because a courier needs both. */
    public void offer(User user) {
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.GIFT_CONSENT, STEP_AWAITING_ADDRESS, Map.of());
        telegramOutboundService.sendMessage(user.getTelegramChatId(), """
                🎁 Гаразд. Напиши одним повідомленням адресу, квартиру й телефон — наприклад: \
                «Київ, вулиця Хрещатик 22, кв. 42, +380671234567».

                Далі друзі зможуть замовити тобі подарунок у «Сільпо» просто за твоїм ніком, і я привезу його \
                сюди. Самої адреси ніхто з них не побачить. Передумаєш — скажи «більше не хочу подарунки».""");
    }

    /** Clears all three columns at once: a stored address nobody may use is worth nothing and leaks something. */
    public void revoke(User user) {
        userProfileRepository.findByUserId(user.getId()).ifPresent(profile -> {
            profile.setGiftDeliveryAddress(null);
            profile.setGiftDeliveryPhone(null);
            profile.setGiftAddressShareable(false);
            userProfileRepository.save(profile);
        });
        conversationStateService.save(user.getTelegramChatId(), ConversationFlow.NONE, null, Map.of());
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                "Прибрав адресу для подарунків. Друзі більше не зможуть надіслати тобі щось за ніком.");
        log.info("user {} withdrew their gift address", user.getId());
    }

    /** The reply to {@link #offer}. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        if (!(incoming instanceof TelegramIncomingUpdate.Text text)) {
            telegramOutboundService.sendMessage(
                    incoming.chatId(), "Напиши адресу текстом, будь ласка — або «не треба», щоб пропустити.");
            return;
        }
        String answer = text.text().strip();
        conversationStateService.save(incoming.chatId(), ConversationFlow.NONE, null, Map.of());
        if (REVOKES.matcher(answer).find() || answer.equalsIgnoreCase("не треба")) {
            telegramOutboundService.sendMessage(incoming.chatId(), "Добре, нічого не зберігаю.");
            return;
        }
        String street = GiftOrderService.withoutContactDetails(answer);
        if (street.isBlank()) {
            telegramOutboundService.sendMessage(
                    incoming.chatId(), "Не побачив адреси. Напиши місто, вулицю й будинок — і телефон для кур'єра.");
            offer(user);
            return;
        }
        UserProfile profile = userProfileRepository
                .findByUserId(user.getId())
                .orElseGet(() -> UserProfile.builder()
                        .id(UUID.randomUUID())
                        .userId(user.getId())
                        .build());
        String flat = GiftOrderService.flatIn(answer);
        profile.setGiftDeliveryAddress(flat == null ? street : street + ", кв. " + flat);
        profile.setGiftDeliveryPhone(GiftOrderService.phoneIn(answer));
        profile.setGiftAddressShareable(true);
        userProfileRepository.save(profile);
        telegramOutboundService.sendMessage(
                incoming.chatId(),
                profile.getGiftDeliveryPhone() == null
                        ? "Записав адресу. Телефон не побачив — додай його потім, кур'єру буде куди дзвонити."
                        : "Записав. Тепер друзі можуть надіслати тобі подарунок просто за ніком.");
        log.info("user {} left a gift address", user.getId());
    }
}
