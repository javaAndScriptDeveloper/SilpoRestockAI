package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.GiftResolution;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.GiftOrderService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("a friend who never answers is not left as silence in the sender's chat")
class GiftExpiryIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "7373:stub-bot-token";
    private static final long SENDER_CHAT = 9801L;
    private static final long RECIPIENT_CHAT = 9802L;
    private static final StubTelegramServer TELEGRAM = startTelegram();

    @Autowired
    private GiftOrderService giftOrderService;

    @Autowired
    private GiftOrderRepository giftOrderRepository;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

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
        giftOrderRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void unansweredRequest(Instant expiresAt) {
        var sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");
        userAccountService.findOrCreate(RECIPIENT_CHAT, "olena");
        conversationStateService.save(RECIPIENT_CHAT, ConversationFlow.GIFT_ADDRESS_REQUEST, null, Map.of());
        giftOrderRepository.save(GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername("olena")
                .recipientChatId(RECIPIENT_CHAT)
                .status(GiftOrderStatus.AWAITING_ADDRESS)
                .resolution(GiftResolution.ASKED)
                .theme("щось до кави")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .expiresAt(expiresAt)
                .build());
    }

    @Test
    void tellsTheSenderAndReleasesTheFriendsChat() {
        unansweredRequest(Instant.now().minusSeconds(60));

        giftOrderService.expireUnanswered();

        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getStatus)
                .isEqualTo(GiftOrderStatus.EXPIRED);
        assertThat(TELEGRAM.sentMessages().getLast().path("chat_id").asLong()).isEqualTo(SENDER_CHAT);
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("поки не відповів");
        assertThat(conversationStateService.load(RECIPIENT_CHAT).getCurrentFlow())
                .isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void leavesARequestThatIsStillInsideItsWindowAlone() {
        unansweredRequest(Instant.now().plusSeconds(3600));

        giftOrderService.expireUnanswered();

        assertThat(TELEGRAM.sentMessages()).isEmpty();
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getStatus)
                .isEqualTo(GiftOrderStatus.AWAITING_ADDRESS);
    }
}
