package com.silporestockai.repository;

import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.model.PartnerPromotionStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerPromotionRepository extends JpaRepository<PartnerPromotion, UUID> {

    List<PartnerPromotion> findByStatus(PartnerPromotionStatus status);
}
