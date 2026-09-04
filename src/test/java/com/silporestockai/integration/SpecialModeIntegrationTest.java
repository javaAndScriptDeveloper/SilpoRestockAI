package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.config.SpecialModeProperties;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.SpecialModeService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("SpecialModeService switches and cancels a special mode without touching the baseline")
class SpecialModeIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 9101L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private SpecialModeService specialModeService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpecialModeProperties specialModeProperties;

    @Autowired
    private TokenCipher tokenCipher;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer("9101:stub-bot-token");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> "9101:stub-bot-token");
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        MCP.reset();
        CLAUDE.reset();
        baselineBasketRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();

        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(new BasketItem("p-1", "Гречка", "кг", BigDecimal.ONE, new BigDecimal("48"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
        scriptSilpo();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());
    }

    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-s\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool(
                "silpo_find_products_batch",
                "{\"queries\":[{\"query\":\"вівсяні пластівці\",\"products\":[{\"name\":\"вівсяні пластівці\","
                        + "\"productId\":\"p-90\",\"branchId\":\"branch-9\"}]}]}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-s","branchId":"branch-9","companyId":"company-1","deliveryType":"delivery",\
                "items":[{"productId":"p-90","name":"Вівсянка","unit":"шт","quantity":1,"price":45}],\
                "total":45,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-s"}""");
    }

    @Test
    void triggeringGastritisSetsTheModeAndGeneratesAMedicalPlan() {
        specialModeService.triggerGastritis(user);

        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getSpecialMode()).isEqualTo(SpecialMode.MEDICAL_GASTRITIS_ACUTE);
        assertThat(profile.getSpecialModeStartedAt()).isNotNull();
        assertThat(profile.getSpecialModeExpiresAt()).isNotNull();
        assertThat(CLAUDE.requests().getFirst().toString()).contains("гострим гастритом");
    }

    @Test
    void triggeringGastritisLeavesTheBaselineExactlyAsItWas() {
        UUID baselineBefore = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow()
                .getId();

        specialModeService.triggerGastritis(user);

        assertThat(baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(user.getId())
                        .orElseThrow()
                        .getId())
                .isEqualTo(baselineBefore);
        assertThat(baselineBasketRepository.findByUserIdOrderByConfirmedAtDesc(user.getId()))
                .hasSize(1);
    }

    @Test
    void refusesToTriggerAgainWhileAModeIsAlreadyActive() {
        specialModeService.triggerGastritis(user);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        specialModeService.triggerGastritis(user);

        assertThat(CLAUDE.callCount()).isZero();
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("вже активний");
    }

    @Test
    void cancelRevertsToNormalAndRegeneratesANormalPlan() {
        specialModeService.triggerGastritis(user);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        specialModeService.cancel(user);

        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getSpecialMode()).isEqualTo(SpecialMode.NONE);
        assertThat(profile.getSpecialModeExpiresAt()).isNull();
        assertThat(CLAUDE.requests().getFirst().toString()).doesNotContain("гострим гастритом");
    }

    @Test
    void cancelWhenNothingIsActiveJustSaysSo() {
        specialModeService.cancel(user);

        assertThat(CLAUDE.callCount()).isZero();
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("Звичайний режим і так активний");
    }

    @Test
    void toggleUaOnlyFlipsIndependentlyOfSpecialMode() {
        specialModeService.toggleUaOnly(user);
        assertThat(userProfileRepository
                        .findByUserId(user.getId())
                        .orElseThrow()
                        .getOnlyUaProducer())
                .isTrue();

        specialModeService.toggleUaOnly(user);
        assertThat(userProfileRepository
                        .findByUserId(user.getId())
                        .orElseThrow()
                        .getOnlyUaProducer())
                .isFalse();
    }

    @Test
    void sweepTransitionsAcuteToDietTable5WhenTheAcuteDurationHasPassed() {
        specialModeService.triggerGastritis(user);
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        // Fast-forward: matches the "backdate the timestamp" convention CheckinPromptIntegrationTest uses instead
        // of an injected fake Clock. Truncated to microseconds because that is what the DB column round-trips to;
        // otherwise the exact-value assertion below would compare a nanosecond-precision Instant against the
        // microsecond-precision one read back from Postgres.
        Instant startedAt = Instant.now().minusSeconds(1_000_000).truncatedTo(ChronoUnit.MICROS);
        profile.setSpecialModeStartedAt(startedAt);
        profile.setSpecialModeExpiresAt(Instant.now().minusSeconds(1));
        userProfileRepository.save(profile);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        int swept = specialModeService.sweepExpired();

        assertThat(swept).isEqualTo(1);
        UserProfile after = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(after.getSpecialMode()).isEqualTo(SpecialMode.MEDICAL_DIET_TABLE_5);
        // Pinned to the exact recomputed expiry (startedAt + acute duration + diet5 duration), not just
        // "is after now" — the arithmetic in stepDownToDietTable5 is what this test exists to lock in.
        assertThat(after.getSpecialModeExpiresAt())
                .isEqualTo(startedAt
                        .plus(specialModeProperties.gastritisAcuteDuration())
                        .plus(specialModeProperties.gastritisDiet5Duration()));
        assertThat(CLAUDE.requests().getFirst().toString()).contains("столу №5").doesNotContain("гострим гастритом");
        // Not getLast(): regenerateAndPresent sends the new shopping list as the final message, after this one.
        assertThat(TELEGRAM.sentMessages()).anyMatch(m -> m.toString().contains("дієтичного столу №5"));
    }

    @Test
    void sweepRevertsToNormalWhenDietTable5HasAlsoExpired() {
        specialModeService.triggerGastritis(user);
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        profile.setSpecialMode(SpecialMode.MEDICAL_DIET_TABLE_5);
        profile.setSpecialModeExpiresAt(Instant.now().minusSeconds(1));
        userProfileRepository.save(profile);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        int swept = specialModeService.sweepExpired();

        assertThat(swept).isEqualTo(1);
        UserProfile after = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(after.getSpecialMode()).isEqualTo(SpecialMode.NONE);
        assertThat(after.getSpecialModeExpiresAt()).isNull();
        // Not getLast(): regenerateAndPresent sends the new shopping list as the final message, after this one.
        assertThat(TELEGRAM.sentMessages())
                .anyMatch(m -> m.toString()
                        .contains("Два тижні дієтичного харчування завершено, повертаємось до звичайного раціону"));
    }

    @Test
    void sweepingTwiceInARowDoesNothingTheSecondTime() {
        specialModeService.triggerGastritis(user);
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        profile.setSpecialModeExpiresAt(Instant.now().minusSeconds(1));
        userProfileRepository.save(profile);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        specialModeService.sweepExpired();
        CLAUDE.reset();
        int secondSweep = specialModeService.sweepExpired();

        assertThat(secondSweep).isZero();
        assertThat(CLAUDE.callCount()).isZero();
    }
}
