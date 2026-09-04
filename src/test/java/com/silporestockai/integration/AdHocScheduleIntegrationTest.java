package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.AdHocScheduleService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
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

@DisplayName("a scheduled ad-hoc purchase fires only once its trigger time has passed")
class AdHocScheduleIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "3030:stub-bot-token";
    private static final long CHAT_ID = 14801L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();

    @Autowired
    private AdHocScheduleService adHocScheduleService;

    @Autowired
    private ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_get_time_slots",
                    "silpo_add_or_update_cart_products",
                    "silpo_get_promotions"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        MCP.reset();
        scheduledAdHocTaskRepository.deleteAll();
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
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-s\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-s","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[],"total":0,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-s",\
                "checkoutMobileLink":"silpo://checkout/cart-s"}""");
        MCP.respondToTool("silpo_get_promotions", """
                {"promotions":[{"name":"Чіпси Lays","productId":"p-1","price":40,"oldPrice":60}]}""");
    }

    @Test
    void sweepingBeforeTheTriggerTimeDoesNothing() {
        adHocScheduleService.schedule(user, "вечір п'ятниці", Instant.now().plus(2, ChronoUnit.DAYS));

        int fired = adHocScheduleService.sweepDue();

        assertThat(fired).isZero();
        assertThat(scheduledAdHocTaskRepository.findAll().getFirst().getStatus())
                .isEqualTo(ScheduledAdHocTaskStatus.PENDING);
    }

    @Test
    void sweepingAfterTheTriggerTimeBuildsTheCartAndMarksItFired() {
        adHocScheduleService.schedule(user, "вечір п'ятниці", Instant.now().minus(1, ChronoUnit.HOURS));

        int fired = adHocScheduleService.sweepDue();

        assertThat(fired).isEqualTo(1);
        assertThat(scheduledAdHocTaskRepository.findAll().getFirst().getStatus())
                .isEqualTo(ScheduledAdHocTaskStatus.FIRED);
        assertThat(TELEGRAM.sentMessages()).isNotEmpty();
    }

    @Test
    void sweepingTwiceInARowFiresOnlyOnce() {
        adHocScheduleService.schedule(user, "вечір п'ятниці", Instant.now().minus(1, ChronoUnit.HOURS));

        adHocScheduleService.sweepDue();
        int firedAgain = adHocScheduleService.sweepDue();

        assertThat(firedAgain).isZero();
    }
}
