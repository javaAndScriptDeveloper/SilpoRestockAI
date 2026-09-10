package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.GiftResolution;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.GiftCartCustodyService;
import com.silporestockai.service.UserAccountService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("the household gets its cart back before its next order, not before the sender has paid")
class GiftCartCustodyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private GiftCartCustodyService giftCartCustodyService;

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

    private UUID senderHolding(long chatId, GiftOrderStatus status, Map<String, Object> ownDelivery) {
        var sender = userAccountService.findOrCreate(chatId);
        giftOrderRepository.save(GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername("olena")
                .status(status)
                .resolution(GiftResolution.DIRECT)
                // No cart id: the restore call itself is CartBuildingService's, and exercised live rather than
                // against a stub here. What this test pins is which rows are considered to be holding anything.
                .ownDelivery(ownDelivery)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return sender.getId();
    }

    @Test
    void closesTheGiftRowOnceTheCartIsHandedBack() {
        UUID sender = senderHolding(9401L, GiftOrderStatus.CONFIRMED, Map.of("deliveryType", "DeliveryHome"));

        assertThat(giftCartCustodyService.releaseCartIfHeld(sender)).isTrue();
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getStatus)
                .isEqualTo(GiftOrderStatus.CANCELLED);
    }

    @Test
    void aGiftStillWaitingForAnAddressHoldsNothing() {
        UUID sender = senderHolding(9402L, GiftOrderStatus.AWAITING_ADDRESS, null);

        assertThat(giftCartCustodyService.releaseCartIfHeld(sender)).isFalse();
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getStatus)
                .isEqualTo(GiftOrderStatus.AWAITING_ADDRESS);
    }

    @Test
    void aHouseholdWithNoGiftInFlightIsUntouched() {
        var user = userAccountService.findOrCreate(9403L);

        assertThat(giftCartCustodyService.releaseCartIfHeld(user.getId())).isFalse();
    }
}
