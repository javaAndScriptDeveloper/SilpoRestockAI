package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.model.CartBenefits;
import com.silporestockai.model.GiftCertificate;
import com.silporestockai.model.LoyaltyCoupon;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The benefits offered with a cart cross {@code conversation_state.context_json} between the presentation webhook
 * and the confirm tap, so they have to survive a round trip through a plain map — with the same strict mapper
 * {@code CartConfirmationService} uses. An accessor named like a getter would quietly add a fourth property and
 * make the read back fail, which is how the confirm tap once stopped confirming anything at all.
 */
class CartBenefitsStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @SuppressWarnings("unchecked")
    @Test
    void survivesTheTripThroughTheConversationState() {
        CartBenefits benefits = new CartBenefits(
                List.of(new GiftCertificate("9001234321", "1111", new BigDecimal("500"), "2026-12-31")),
                "SUMMER10",
                List.of(new LoyaltyCoupon(1L, "на покупку", "-15%", "2026-10-03", true, true, "ліміт 150 грн", null)));

        Map<String, Object> stored = MAPPER.convertValue(benefits, Map.class);
        CartBenefits read = MAPPER.convertValue(stored, CartBenefits.class);

        assertThat(stored).containsOnlyKeys("certificates", "promoCode", "coupons");
        assertThat(read).isEqualTo(benefits);
    }

    @Test
    void anEmptyOfferSurvivesItToo() {
        Map<?, ?> stored = MAPPER.convertValue(CartBenefits.none(), Map.class);

        assertThat(MAPPER.convertValue(stored, CartBenefits.class).nothingToOffer())
                .isTrue();
    }
}
