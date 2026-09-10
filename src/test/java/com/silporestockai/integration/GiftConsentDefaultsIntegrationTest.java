package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("a profile shares no gift address until somebody says so")
class GiftConsentDefaultsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void aFreshProfileIsNullNullFalse() {
        var user = userAccountService.findOrCreate(9201L);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .onlyUaProducer(false)
                .build());

        UserProfile stored = userProfileRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(stored.getGiftDeliveryAddress()).isNull();
        assertThat(stored.getGiftDeliveryPhone()).isNull();
        assertThat(stored.getGiftAddressShareable()).isFalse();
        assertThat(stored.acceptsGifts()).isFalse();
    }

    @Test
    void acceptsGiftsOnlyWithBothAnAddressAndTheFlag() {
        var user = userAccountService.findOrCreate(9202L);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .onlyUaProducer(false)
                .giftAddressShareable(true)
                .build());

        assertThat(userProfileRepository
                        .findByUserId(user.getId())
                        .orElseThrow()
                        .acceptsGifts())
                .isFalse();
    }
}
