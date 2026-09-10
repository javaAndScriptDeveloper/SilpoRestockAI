package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.service.CartBuildingService;
import com.silporestockai.service.CategoryResolutionLogService;
import com.silporestockai.service.ObservabilityService;
import com.silporestockai.service.PartnerPromotionService;
import com.silporestockai.service.ProductMatchingService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The two cart mutations the loyalty work added (tasks 78/79): a promo code goes on the cart the same way a
 * booked slot and spent bonuses do, and the total is read back afterwards so the number said out loud is Silpo's
 * own rather than the one from before the benefit.
 */
class CartBenefitApplyTest {

    private static final UUID USER_ID = UUID.randomUUID();

    /** The live cart shape, trimmed to what {@code updateCart} copies and {@code readCartTotal} reads. */
    private static final String CART_JSON = """
            {"success":true,"cart":{"id":"cart-1","deliveryType":"DeliveryHome",
            "timeslot":{"start":"2026-09-10T13:30:00+00:00","end":"2026-09-10T15:00:00+00:00"},
            "address":{"addressType":"flat","latitude":"50.4","longitude":"30.6"},
            "shipments":[{"companyId":"c-1","branchId":"b-1"}],
            "promoCode":null,"certificates":[],
            "calculation":{"total":911.92,"productsTotal":812.92,"certificatesTotal":0}}}
            """;

    private SilpoMcpClient silpoMcpClient;
    private CartBuildingService service;

    @BeforeEach
    void setUp() {
        silpoMcpClient = mock(SilpoMcpClient.class);
        service = new CartBuildingService(
                silpoMcpClient,
                mock(com.silporestockai.repository.UserProfileRepository.class),
                mock(PartnerPromotionService.class),
                mock(CategoryResolutionLogService.class),
                mock(ProductMatchingService.class),
                mock(com.silporestockai.repository.BaselineBasketRepository.class),
                mock(ObservabilityService.class));
        when(silpoMcpClient.callTool(eq("silpo_get_shopping_cart_by_id"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse(CART_JSON, null, false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsThePromoCodeWithTheCartsOwnRequiredFields() {
        when(silpoMcpClient.callTool(eq("silpo_update_shopping_cart"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("{\"success\":true}", null, false));

        assertThat(service.applyPromoCode(USER_ID, "cart-1", "SUMMER10")).isTrue();

        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(silpoMcpClient).callTool(eq("silpo_update_shopping_cart"), arguments.capture(), eq(USER_ID));
        assertThat(arguments.getValue())
                .containsEntry("promoCode", "SUMMER10")
                .containsEntry("shoppingCartId", "cart-1")
                .containsKeys("deliveryType", "timeslot", "address", "shipments");
    }

    @Test
    void reportsFalseWhenSilpoRefusesThePromoCode() {
        when(silpoMcpClient.callTool(eq("silpo_update_shopping_cart"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("promo code not found", null, true));

        assertThat(service.applyPromoCode(USER_ID, "cart-1", "NOPE")).isFalse();
    }

    @Test
    void readsTheCartTotalBack() {
        assertThat(service.readCartTotal(USER_ID, "cart-1")).contains(new BigDecimal("911.92"));
    }

    @Test
    void isEmptyRatherThanFatalWhenTheCartCannotBeReadBack() {
        when(silpoMcpClient.callTool(eq("silpo_get_shopping_cart_by_id"), any(), eq(USER_ID)))
                .thenThrow(new IllegalStateException("silpo is down"));

        assertThat(service.readCartTotal(USER_ID, "cart-1")).isEmpty();
    }
}
