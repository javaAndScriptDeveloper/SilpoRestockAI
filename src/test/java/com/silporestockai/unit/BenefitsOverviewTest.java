package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.model.BenefitsOverview;
import com.silporestockai.model.GiftCertificate;
import com.silporestockai.model.LoyaltyCoupon;
import com.silporestockai.service.LoyaltyBenefitsService;
import com.silporestockai.service.telegram.BenefitsMessageService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * «Які в мене вигоди?» (task 79). The answer's whole job is to draw one line: what Комора applies by itself at
 * checkout, and what only the Silpo app can do — because for coupons, promos and Premium the live API offers no
 * apply action at all.
 */
class BenefitsOverviewTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private final BenefitsMessageService message = new BenefitsMessageService();

    private SilpoMcpClient silpoMcpClient;
    private LoyaltyBenefitsService service;

    @BeforeEach
    void setUp() {
        silpoMcpClient = mock(SilpoMcpClient.class);
        service = new LoyaltyBenefitsService(
                silpoMcpClient,
                mock(com.silporestockai.service.SilpoAuthService.class),
                new com.silporestockai.service.telegram.BenefitsMessageService(),
                mock(com.silporestockai.service.telegram.TelegramOutboundService.class));
    }

    private void answers(String tool, String json) {
        when(silpoMcpClient.callTool(eq(tool), any(), eq(USER_ID))).thenReturn(new McpToolResponse(json, null, false));
    }

    @Test
    void separatesWhatKomoraAppliesFromWhatOnlyTheSilpoAppCan() {
        BenefitsOverview overview = new BenefitsOverview(
                new BigDecimal("250"),
                List.of(new GiftCertificate("9001234321", null, new BigDecimal("500"), "2026-12-31")),
                List.of("SUMMER10"),
                List.of(new LoyaltyCoupon(
                        1L, "на покупку", "-15%", "2026-10-03", true, true, "Максимальна сума знижки — 150 грн", null)),
                List.of(),
                "Немає активної підписки «Плюхс»",
                List.of("https://silpo.ua/subscription"),
                true);

        String text = message.overviewText(overview);

        assertThat(text).contains("250").contains("500").contains("SUMMER10").contains("-15%");
        assertThat(text).contains("застосую сам");
        assertThat(text).contains("у застосунку «Сільпо»");
        assertThat(text).contains("https://silpo.ua/subscription");
    }

    @Test
    void saysSoWhenSilpoAnsweredNothingAtAll() {
        assertThat(message.overviewText(BenefitsOverview.unreadable())).contains("не відповіло");
    }

    @Test
    void anEmptyAccountReadsAsEmptyRatherThanBroken() {
        BenefitsOverview empty = new BenefitsOverview(
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Немає активної підписки «Плюхс»",
                List.of(),
                true);

        String text = message.overviewText(empty);

        assertThat(text).contains("0").doesNotContain("не відповіло");
    }

    /** The live account, verbatim: a zero balance, two coupons, no promos, no codes, certificates answering 500. */
    @Test
    void readsTheLiveAccountAsItActuallyAnswered() {
        answers(
                "silpo_get_loyalty_info",
                "{\"success\":true,\"loyalty\":{\"card\":{\"barcode\":\"0240434643842\"},"
                        + "\"balance\":{\"total\":0,\"currency\":\"UAH\"}}}");
        when(silpoMcpClient.callTool(eq("silpo_get_my_certificates"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse(
                        "Error in get-my-certificates: API returned 500 Internal Server Error.", null, true));
        answers("silpo_get_promo_codes", "{\"success\":true,\"promoCodes\":[],\"meta\":{\"total\":0}}");
        answers("silpo_get_my_promos", "{\"success\":true,\"summary\":\"No personal promos available\",\"promos\":[]}");
        answers(
                "silpo_get_my_coupons",
                "{\"success\":true,\"coupons\":[{\"id\":573714784,\"active\":false,\"description\":\"на покупку\","
                        + "\"rewardText\":\"-15%\",\"endDate\":\"2026-09-10\"}]}");
        answers("silpo_get_coupon_details", "{\"success\":true,\"coupon\":{\"canBeAppliedToOrder\":false}}");
        answers(
                "silpo_get_my_premium_subscription",
                "{\"success\":true,\"summary\":\"You don't have an active Плюхс premium subscription.\","
                        + "\"webLink\":\"https://silpo.ua/subscription\",\"mobileLink\":\"https://link.silpo.ua/w4rW\"}");

        BenefitsOverview overview = service.overview(USER_ID);

        assertThat(overview.anythingRead()).isTrue();
        assertThat(overview.bonusBalance()).isEqualByComparingTo("0");
        assertThat(overview.certificates()).isEmpty();
        assertThat(overview.promoCodes()).isEmpty();
        assertThat(overview.promos()).isEmpty();
        assertThat(overview.coupons()).singleElement().satisfies(coupon -> {
            assertThat(coupon.rewardText()).isEqualTo("-15%");
            // The details call is what decides eligibility; the toggle alone never does.
            assertThat(coupon.canBeApplied()).isFalse();
        });
        assertThat(overview.premiumLinks()).contains("https://silpo.ua/subscription");
    }

    @Test
    void aServerThatAnswersNothingIsReportedAsUnreadableNotAsAnEmptyAccount() {
        when(silpoMcpClient.callTool(any(), any(), eq(USER_ID))).thenThrow(new IllegalStateException("silpo is down"));

        assertThat(service.overview(USER_ID).anythingRead()).isFalse();
    }
}
