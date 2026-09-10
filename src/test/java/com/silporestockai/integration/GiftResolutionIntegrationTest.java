package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.GiftRequest;
import com.silporestockai.model.GiftResolution;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.GiftOrderService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("who a gift is for, and how its address is arrived at")
class GiftResolutionIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "555:stub-bot-token";
    private static final long SENDER_CHAT = 9501L;
    private static final long RECIPIENT_CHAT = 9502L;
    private static final StubTelegramServer TELEGRAM = startTelegram();

    @Autowired
    private GiftOrderService giftOrderService;

    @Autowired
    private GiftOrderRepository giftOrderRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationStateService conversationStateService;

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
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String textSentTo(long chatId) {
        return TELEGRAM.sentMessages().stream()
                .filter(message -> message.path("chat_id").asLong() == chatId)
                .map(message -> message.path("text").asText())
                .reduce("", (all, one) -> all + "\n" + one);
    }

    @Test
    void anUnknownNicknameIsSaidOutLoudRatherThanFailingQuietly() {
        var sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");

        giftOrderService.startFrom(sender, new GiftRequest("@nobody", null, null, null, "щось до кави"), null);

        assertThat(textSentTo(SENDER_CHAT)).contains("@nobody").contains("ще не користувався");
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getStatus)
                .isEqualTo(GiftOrderStatus.UNREACHABLE);
    }

    @Test
    void aKnownNicknameWithoutConsentIsAskedInTheirOwnChat() {
        var sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");
        userAccountService.findOrCreate(RECIPIENT_CHAT, "olena");

        giftOrderService.startFrom(sender, new GiftRequest("@olena", null, null, null, "щось до кави"), null);

        assertThat(textSentTo(RECIPIENT_CHAT)).contains("подарунок").contains("Куди привезти");
        assertThat(textSentTo(SENDER_CHAT)).contains("Запитав у @olena");
        assertThat(conversationStateService.load(RECIPIENT_CHAT).getCurrentFlow())
                .isEqualTo(ConversationFlow.GIFT_ADDRESS_REQUEST);
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getStatus, GiftOrder::getResolution)
                .containsExactly(GiftOrderStatus.AWAITING_ADDRESS, GiftResolution.ASKED);
    }

    @Test
    void theRecipientsAnswerResolvesTheAddressAndTheSenderIsNeverToldIt() {
        var sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");
        var recipient = userAccountService.findOrCreate(RECIPIENT_CHAT, "olena");
        giftOrderRepository.save(GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername("olena")
                .recipientUserId(recipient.getId())
                .recipientChatId(RECIPIENT_CHAT)
                .status(GiftOrderStatus.AWAITING_ADDRESS)
                .resolution(GiftResolution.ASKED)
                .theme("щось до кави")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());

        giftOrderService.handleRecipientReply(
                recipient,
                new TelegramIncomingUpdate.Text(
                        RECIPIENT_CHAT, RECIPIENT_CHAT, "Київ, вулиця Хрещатик 22, кв. 42, +380671234567"));

        GiftOrder resolved = giftOrderRepository.findAll().getFirst();
        assertThat(resolved.getGiftAddressText()).contains("Хрещатик").doesNotContain("+380");
        assertThat(resolved.getGiftFlat()).isEqualTo("42");
        assertThat(resolved.getGiftPhone()).isEqualTo("+380671234567");
        assertThat(textSentTo(SENDER_CHAT))
                .contains("Адресу для @olena маю")
                .doesNotContain("Хрещатик")
                .doesNotContain("+380671234567");
    }

    @Test
    void aRecipientWhoSaysNoStopsTheGiftAndTheSenderIsToldPlainly() {
        var sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");
        var recipient = userAccountService.findOrCreate(RECIPIENT_CHAT, "olena");
        giftOrderRepository.save(GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername("olena")
                .recipientChatId(RECIPIENT_CHAT)
                .status(GiftOrderStatus.AWAITING_ADDRESS)
                .resolution(GiftResolution.ASKED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        giftOrderService.handleRecipientReply(
                recipient, new TelegramIncomingUpdate.Text(RECIPIENT_CHAT, RECIPIENT_CHAT, "ні, не треба"));

        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getStatus)
                .isEqualTo(GiftOrderStatus.CANCELLED);
        assertThat(textSentTo(SENDER_CHAT)).contains("не хоче отримувати подарунок");
    }

    @Test
    void anAddressWithNoPhoneAsksTheSenderForOneBeforeBuildingAnything() {
        var sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");

        giftOrderService.startFrom(
                sender, new GiftRequest(null, "Київ, вулиця Хрещатик, 22", "42", null, "щось до кави"), null);

        assertThat(textSentTo(SENDER_CHAT)).contains("Який телефон");
        assertThat(conversationStateService.load(SENDER_CHAT).getCurrentFlow())
                .isEqualTo(ConversationFlow.GIFT_SENDER_DETAIL);
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getResolution)
                .isEqualTo(GiftResolution.DIRECT);
    }

    @Test
    void aConsentingFriendIsToldAGiftIsComingAndTheSenderSeesNoAddress() {
        var sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");
        var recipient = userAccountService.findOrCreate(RECIPIENT_CHAT, "olena");
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(recipient.getId())
                .onlyUaProducer(false)
                .giftDeliveryAddress("Київ, вулиця Хрещатик, 22")
                .giftDeliveryPhone("+380671234567")
                .giftAddressShareable(true)
                .build());

        giftOrderService.startFrom(sender, new GiftRequest("@olena", null, null, null, "щось до кави"), null);

        assertThat(textSentTo(RECIPIENT_CHAT)).contains("надсилає тобі подарунок");
        assertThat(textSentTo(SENDER_CHAT))
                .contains("Адресу для @olena маю")
                .doesNotContain("Хрещатик")
                .doesNotContain("+380671234567");
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getResolution)
                .isEqualTo(GiftResolution.CONSENTED);
    }
}
