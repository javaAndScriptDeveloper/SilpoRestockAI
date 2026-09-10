package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.GiftResolution;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("a gift order survives the round trip between two chats")
class GiftOrderSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private GiftOrderRepository giftOrderRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        giftOrderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void storesTheSnapshotAndFindsTheRowByTheRecipientsChat() {
        var sender = userAccountService.findOrCreate(9301L);
        giftOrderRepository.save(GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername("olena")
                .recipientChatId(9302L)
                .status(GiftOrderStatus.AWAITING_ADDRESS)
                .resolution(GiftResolution.ASKED)
                .theme("щось до кави")
                .ownDelivery(Map.of("deliveryType", "DeliveryHome"))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86_400))
                .build());

        GiftOrder found = giftOrderRepository
                .findFirstByRecipientChatIdAndStatusOrderByCreatedAtDesc(9302L, GiftOrderStatus.AWAITING_ADDRESS)
                .orElseThrow();

        assertThat(found.getTheme()).isEqualTo("щось до кави");
        assertThat(found.getOwnDelivery()).containsEntry("deliveryType", "DeliveryHome");
    }

    @Test
    void findsWhatHasExpired() {
        var sender = userAccountService.findOrCreate(9303L);
        giftOrderRepository.save(GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername("stale")
                .status(GiftOrderStatus.AWAITING_ADDRESS)
                .resolution(GiftResolution.ASKED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .expiresAt(Instant.now().minusSeconds(60))
                .build());

        assertThat(giftOrderRepository.findAllByStatusAndExpiresAtBefore(
                        GiftOrderStatus.AWAITING_ADDRESS, Instant.now()))
                .hasSize(1);
    }

    @Test
    void aRowWithNoSnapshotIsHoldingNothing() {
        var sender = userAccountService.findOrCreate(9304L);
        GiftOrder waiting = GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .status(GiftOrderStatus.AWAITING_ADDRESS)
                .resolution(GiftResolution.ASKED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        assertThat(waiting.holdsTheCart()).isFalse();
    }
}
