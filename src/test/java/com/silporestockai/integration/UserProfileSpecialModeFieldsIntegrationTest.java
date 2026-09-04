package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("user_profile carries special-mode expiry and mass-gain targets")
class UserProfileSpecialModeFieldsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void persistsAndReloadsTheNewColumns() {
        User user = userAccountService.findOrCreate(9001L);
        Instant expiresAt = Instant.now().plusSeconds(3600);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .specialMode(SpecialMode.MEDICAL_GASTRITIS_ACUTE)
                .specialModeExpiresAt(expiresAt)
                .targetWeightKg(new BigDecimal("82.5"))
                .targetCalories(3200)
                .targetProteinG(160)
                .build());

        UserProfile reloaded = userProfileRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(reloaded.getSpecialModeExpiresAt()).isCloseTo(expiresAt, within(1, ChronoUnit.MILLIS));
        assertThat(reloaded.getTargetWeightKg()).isEqualByComparingTo("82.5");
        assertThat(reloaded.getTargetCalories()).isEqualTo(3200);
        assertThat(reloaded.getTargetProteinG()).isEqualTo(160);
    }
}
