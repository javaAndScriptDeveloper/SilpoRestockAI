package com.silporestockai.repository;

import com.silporestockai.entity.PartnerPromotionEvent;
import com.silporestockai.model.PartnerPromotionEventType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerPromotionEventRepository extends JpaRepository<PartnerPromotionEvent, UUID> {

    long countByPromotionIdAndEventType(UUID promotionId, PartnerPromotionEventType eventType);

    List<PartnerPromotionEvent> findByPromotionId(UUID promotionId);
}
