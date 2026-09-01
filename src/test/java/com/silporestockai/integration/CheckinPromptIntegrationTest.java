package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.job.CheckinScheduler;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CheckinPromptService;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("the agent opens a check-in on its own, once per window, and only with households that have a baseline")
class CheckinPromptIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "666:stub-bot-token";
    private static final long CHAT_ID = 8801L;
    private static final StubTelegramServer TELEGRAM = startTelegram();

    /** Matches {@code komora.checkin.interval} in {@code application-test.yml}. */
    private static final Duration INTERVAL = Duration.ofDays(3);

    @Autowired
    private CheckinPromptService checkinPromptService;

    @Autowired
    private CheckinScheduler checkinScheduler;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CheckinRepository checkinRepository;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @DynamicPropertySource
    static void telegram(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        TELEGRAM.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        checkinRepository.deleteAll();
        baselineBasketRepository.deleteAll();
        userProfileRepository.deleteAll();
        customerOrderRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userRepository.deleteAll();
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    /** A household past its first order: a confirmed order and the baseline that order produced. */
    private User household(int orderConfirmedDaysAgo) {
        User user = userAccountService.findOrCreate(CHAT_ID);
        Instant confirmedAt = daysAgo(orderConfirmedDaysAgo);
        customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(OrderType.INITIAL)
                .items(List.of(new BasketItem("p-1", "Гречка", "кг", BigDecimal.ONE, new BigDecimal("48"))))
                .status(OrderStatus.CONFIRMED)
                .silpoCartId("cart-1")
                .createdAt(confirmedAt)
                .confirmedAt(confirmedAt)
                .build());
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(new BasketItem("p-1", "Гречка", "кг", BigDecimal.ONE, new BigDecimal("48"))))
                .confirmedAt(confirmedAt)
                .isCurrent(true)
                .build());
        return user;
    }

    private static List<String> promptsSent() {
        return TELEGRAM.sentMessages().stream()
                .map(message -> message.path("text").asText())
                .toList();
    }

    /** Onboarded, so the router hands their messages to the check-in flow rather than to onboarding. */
    private void withProfile(User user) {
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
    }

    @Test
    void theScheduledSweepIsTheSameSweep() {
        household(4);

        checkinScheduler.sweepForDueCheckins();

        assertThat(promptsSent()).hasSize(1);
    }

    @Test
    void aVoiceCheckinAsksForTextWhenNoTranscriptionIsConfigured() throws Exception {
        // This context leaves stt.api-key blank, which is a supported configuration: voice degrades to typing.
        User user = household(4);
        withProfile(user);
        checkinPromptService.sweep();
        TELEGRAM.reset();

        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":1,"message":{"message_id":1,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "voice":{"file_id":"voice-1","file_unique_id":"u1","duration":3,\
                                "mime_type":"audio/ogg"}}}""".formatted(CHAT_ID)))
                .andExpect(status().isOk());

        assertThat(promptsSent()).singleElement().asString().contains("текстом");
    }

    @Test
    void asksAHouseholdThatHasNotBeenHeardFromForAWholeInterval() {
        household(4);

        int prompted = checkinPromptService.sweep();

        assertThat(prompted).isEqualTo(1);
        assertThat(promptsSent()).singleElement().asString().contains("закінчилось");
    }

    @Test
    void sweepingTwiceInARowSendsOneMessage() {
        household(4);

        checkinPromptService.sweep();
        int secondSweep = checkinPromptService.sweep();

        assertThat(secondSweep).isZero();
        assertThat(promptsSent()).hasSize(1);
    }

    @Test
    void neverPromptsSomeoneWhoHasNoBaselineYet() {
        userAccountService.findOrCreate(CHAT_ID);

        assertThat(checkinPromptService.sweep()).isZero();
        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }

    @Test
    void doesNotAskWhatIsLeftTheDayAfterAnOrder() {
        household(1);

        assertThat(checkinPromptService.sweep()).isZero();
        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }

    @Test
    void aRecentCheckinCountsAsContact() {
        User user = household(10);
        checkinRepository.save(Checkin.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .rawInputText("гречка ще є")
                .receivedAt(daysAgo(1))
                .build());

        assertThat(checkinPromptService.sweep()).isZero();
    }

    @Test
    void leavesTheFlagTaskTwelveReadsAndStampsTheUser() {
        household(4);

        checkinPromptService.sweep();

        var state = conversationStateService.load(CHAT_ID);
        assertThat(state.getCurrentFlow()).isEqualTo(ConversationFlow.CHECK_IN);
        assertThat(state.getCurrentStep()).isEqualTo(CheckinPromptService.STEP_AWAITING_REPORT);
        assertThat(userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getLastCheckinPromptSentAt())
                .isNotNull();
    }

    @Test
    void doesNotInterruptAUserWhoOwesAnAnswerToAnotherFlow() {
        household(4);
        conversationStateService.save(CHAT_ID, ConversationFlow.CART_CONFIRMATION, "AWAITING_DECISION", Map.of());

        assertThat(checkinPromptService.sweep()).isZero();
        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }

    @Test
    void asksAgainWhenAWholeIntervalPassesWithNoAnswer() {
        User user = household(10);
        user.setLastCheckinPromptSentAt(Instant.now().minus(INTERVAL).minus(Duration.ofHours(1)));
        userRepository.save(user);
        conversationStateService.save(
                CHAT_ID, ConversationFlow.CHECK_IN, CheckinPromptService.STEP_AWAITING_REPORT, Map.of());

        assertThat(checkinPromptService.sweep()).isEqualTo(1);
        assertThat(promptsSent()).hasSize(1);
    }
}
