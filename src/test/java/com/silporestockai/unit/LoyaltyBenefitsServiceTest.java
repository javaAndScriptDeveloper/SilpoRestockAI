package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.model.CartBenefits;
import com.silporestockai.model.GiftCertificate;
import com.silporestockai.service.LoyaltyBenefitsService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The loyalty facade, against the shapes the live server actually answered with on 2026-09-10 — including the
 * HTTP 500 {@code silpo_get_my_certificates} returned on every call that day.
 */
class LoyaltyBenefitsServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private static final String CERTIFICATES_500 =
            "Error in get-my-certificates: API returned 500 Internal Server Error.";

    /** Verbatim from the live account, trimmed to two coupons. */
    private static final String COUPONS_JSON = """
            {"success":true,"summary":"Found 2 coupons","coupons":[
              {"id":573714784,"active":false,"useWay":"Електронний","beginDate":"2026-09-03","endDate":"2026-09-10",
               "description":"на покупку","limitText":"Не діє на тютюнові вироби","warningText":"Максимальна сума знижки - 150 гривень.",
               "promoId":289599,"rewardText":"-15%","rewardValue":15,"rewardUnit":"%","rewardSign":"-","rewardLimit":1350},
              {"id":571703209,"active":true,"useWay":"Електронний","beginDate":"2026-09-03","endDate":"2026-10-03",
               "description":"Безкоштовний мобільний зв'язок Yezzz!","promoId":289600,"rewardText":"Yezzz!"}]}
            """;

    private SilpoMcpClient silpoMcpClient;
    private LoyaltyBenefitsService service;

    @BeforeEach
    void setUp() {
        silpoMcpClient = mock(SilpoMcpClient.class);
        service = new LoyaltyBenefitsService(silpoMcpClient);
        answers("silpo_get_my_certificates", "{\"certificates\":[]}");
        answers("silpo_get_promo_codes", "{\"success\":true,\"promoCodes\":[],\"meta\":{\"total\":0}}");
        answers("silpo_get_my_coupons", "{\"success\":true,\"coupons\":[]}");
    }

    private void answers(String tool, String json) {
        when(silpoMcpClient.callTool(eq(tool), any(), eq(USER_ID))).thenReturn(new McpToolResponse(json, null, false));
    }

    private void fails(String tool, String text) {
        when(silpoMcpClient.callTool(eq(tool), any(), eq(USER_ID))).thenReturn(new McpToolResponse(text, null, true));
    }

    @Test
    void certificatesFailingWithA500LeavesEveryOtherBenefitIntact() {
        fails("silpo_get_my_certificates", CERTIFICATES_500);
        answers("silpo_get_promo_codes", "{\"promoCodes\":[{\"code\":\"SUMMER10\"}]}");

        CartBenefits benefits = service.cartBenefits(USER_ID);

        assertThat(benefits.certificateList()).isEmpty();
        assertThat(benefits.promoCode()).isEqualTo("SUMMER10");
    }

    @Test
    void anExceptionAnywhereDegradesToNoOfferRatherThanFailingTheCart() {
        when(silpoMcpClient.callTool(any(), any(), eq(USER_ID))).thenThrow(new IllegalStateException("silpo is down"));

        assertThat(service.cartBenefits(USER_ID).nothingToOffer()).isTrue();
    }

    @Test
    void theLiveEmptyAccountOffersNothingAtAll() {
        assertThat(service.cartBenefits(USER_ID).nothingToOffer()).isTrue();
    }

    @Test
    void readsCertificatesWithTheirBarcodeAndPin() {
        answers(
                "silpo_get_my_certificates",
                "{\"certificates\":[{\"barcode\":\"9001234567\",\"pincode\":\"1234\",\"value\":500,"
                        + "\"expireDate\":\"2026-12-31T00:00:00\"}]}");

        assertThat(service.cartBenefits(USER_ID).certificateList())
                .singleElement()
                .satisfies(certificate -> {
                    assertThat(certificate.barcode()).isEqualTo("9001234567");
                    assertThat(certificate.pincode()).isEqualTo("1234");
                    assertThat(certificate.value()).isEqualByComparingTo("500");
                    assertThat(certificate.maskedBarcode()).isEqualTo("…4567");
                });
    }

    @Test
    void mentionsOnlyCouponsTheHouseholdHasActuallySwitchedOn() {
        answers("silpo_get_my_coupons", COUPONS_JSON);

        assertThat(service.cartBenefits(USER_ID).couponList()).singleElement().satisfies(coupon -> {
            assertThat(coupon.id()).isEqualTo(571703209L);
            assertThat(coupon.title()).isEqualTo("Безкоштовний мобільний зв'язок Yezzz!");
            assertThat(coupon.endDate()).isEqualTo("2026-10-03");
        });
    }

    @Test
    void takesAPromoCodeWhetherSilpoSendsAStringOrAnObject() {
        answers("silpo_get_promo_codes", "{\"promoCodes\":[\"WINTER5\"]}");
        assertThat(service.cartBenefits(USER_ID).promoCode()).isEqualTo("WINTER5");

        answers("silpo_get_promo_codes", "{\"promoCodes\":[{\"promoCode\":\"SPRING7\",\"title\":\"…\"}]}");
        assertThat(service.cartBenefits(USER_ID).promoCode()).isEqualTo("SPRING7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesCertificatesAndReportsTheOnesSilpoRefused() {
        answers(
                "silpo_add_or_update_certificates",
                "{\"added\":[{\"barcode\":\"9001\",\"validations\":[]},"
                        + "{\"barcode\":\"9002\",\"validations\":[\"Сертифікат уже використано\"]}]}");

        List<String> applied = service.applyCertificates(
                USER_ID,
                "cart-1",
                List.of(
                        new GiftCertificate("9001", "1234", new BigDecimal("500"), null),
                        new GiftCertificate("9002", null, new BigDecimal("300"), null)));

        assertThat(applied).containsExactly("9001");
        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(silpoMcpClient).callTool(eq("silpo_add_or_update_certificates"), arguments.capture(), eq(USER_ID));
        assertThat(arguments.getValue()).containsEntry("shoppingCartId", "cart-1");
        List<Map<String, Object>> toAdd =
                (List<Map<String, Object>>) arguments.getValue().get("certificatesToAdd");
        assertThat(toAdd).hasSize(2);
        assertThat(toAdd.getFirst()).containsEntry("barcode", "9001").containsEntry("pincode", "1234");
        assertThat(toAdd.get(1)).containsEntry("barcode", "9002").doesNotContainKey("pincode");
    }

    @Test
    void aFailedCertificateCallAppliesNothingAndThrowsNothing() {
        when(silpoMcpClient.callTool(eq("silpo_add_or_update_certificates"), any(), eq(USER_ID)))
                .thenThrow(new IllegalStateException("silpo is down"));

        assertThat(service.applyCertificates(
                        USER_ID, "cart-1", List.of(new GiftCertificate("9001", "1234", new BigDecimal("500"), null))))
                .isEmpty();
    }

    @Test
    void nothingToApplyMeansNoCallAtAll() {
        assertThat(service.applyCertificates(USER_ID, "cart-1", List.of())).isEmpty();
        verify(silpoMcpClient, never()).callTool(eq("silpo_add_or_update_certificates"), any(), any());
    }
}
