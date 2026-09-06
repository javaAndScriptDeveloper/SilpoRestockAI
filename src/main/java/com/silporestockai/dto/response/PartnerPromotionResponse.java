package com.silporestockai.dto.response;

import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.model.PartnerPromotionStatus;
import java.util.UUID;

/** What the internal endpoint answers once a placement exists (task 46): the real product it will feature. */
public record PartnerPromotionResponse(
        UUID id,
        String partnerName,
        String categoryOrQuery,
        String silpoProductId,
        String productName,
        int priorityWeight,
        PartnerPromotionStatus status) {

    public static PartnerPromotionResponse of(PartnerPromotion promotion) {
        return new PartnerPromotionResponse(
                promotion.getId(),
                promotion.getPartnerName(),
                promotion.getCategoryOrQuery(),
                promotion.getSilpoProductId(),
                promotion.getProductName(),
                promotion.getPriorityWeight(),
                promotion.getStatus());
    }
}
