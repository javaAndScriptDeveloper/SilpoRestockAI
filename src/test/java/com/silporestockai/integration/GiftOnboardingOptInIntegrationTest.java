package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OnboardingStep;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.onboarding.OnboardingFlowService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The one optional section of the Анкета: an address friends may send gifts to, or nothing at all. */
@DisplayName("the gift address is offered at the end of onboarding and skipping stores nothing")
class GiftOnboardingOptInIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "9191:stub-bot-token";
    private static final long CHAT_ID = 9701L;
    private static final StubTelegramServer TELEGRAM = startTelegram();

    @Autowired
    private OnboardingFlowService onboardingFlowService;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
        user = userAccountService.findOrCreate(CHAT_ID);
    }

    /** Parks the conversation on the budget question, which is the step the gift offer follows. */
    private void atTheBudgetQuestion() {
        conversationStateService.save(
                CHAT_ID,
                ConversationFlow.ONBOARDING,
                OnboardingStep.ASK_BUDGET.name(),
                Map.of("householdSize", 2, "cookingTimePreference", "COOKS_DAILY"));
    }

    private String lastText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    private UserProfile storedProfile() {
        return userProfileRepository.findByUserId(user.getId()).orElseThrow();
    }

    @Test
    void theBudgetAnswerIsFollowedByTheOfferRatherThanTheEndOfTheForm() {
        atTheBudgetQuestion();

        onboardingFlowService.handle(user, new TelegramIncomingUpdate.Text(CHAT_ID, CHAT_ID, "2500"));

        assertThat(lastText()).contains("Подарунки від друзів").contains("за бажанням");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_GIFT_OPT_IN.name());
    }

    @Test
    void skippingLeavesTheProfileSharingNothing() {
        atTheBudgetQuestion();
        onboardingFlowService.handle(user, new TelegramIncomingUpdate.Text(CHAT_ID, CHAT_ID, "2500"));

        onboardingFlowService.handle(
                user,
                new TelegramIncomingUpdate.ButtonTap(CHAT_ID, CHAT_ID, "q1", OnboardingFlowService.CALLBACK_GIFT_SKIP));

        UserProfile stored = storedProfile();
        assertThat(stored.getGiftDeliveryAddress()).isNull();
        assertThat(stored.getGiftDeliveryPhone()).isNull();
        assertThat(stored.getGiftAddressShareable()).isFalse();
        assertThat(stored.acceptsGifts()).isFalse();
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void leavingAnAddressAndAPhoneTurnsSharingOn() {
        atTheBudgetQuestion();
        onboardingFlowService.handle(user, new TelegramIncomingUpdate.Text(CHAT_ID, CHAT_ID, "2500"));
        onboardingFlowService.handle(
                user,
                new TelegramIncomingUpdate.ButtonTap(CHAT_ID, CHAT_ID, "q2", OnboardingFlowService.CALLBACK_GIFT_YES));

        onboardingFlowService.handle(
                user,
                new TelegramIncomingUpdate.Text(CHAT_ID, CHAT_ID, "Київ, вулиця Хрещатик 22, кв. 42, +380671234567"));

        UserProfile stored = storedProfile();
        assertThat(stored.getGiftDeliveryAddress()).contains("Хрещатик").contains("кв. 42");
        assertThat(stored.getGiftDeliveryPhone()).isEqualTo("+380671234567");
        assertThat(stored.getGiftAddressShareable()).isTrue();
        assertThat(stored.acceptsGifts()).isTrue();
    }
}
