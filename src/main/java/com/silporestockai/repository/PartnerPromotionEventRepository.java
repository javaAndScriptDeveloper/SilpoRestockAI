package com.silporestockai.repository;

import com.silporestockai.entity.PartnerPromotionEvent;
import com.silporestockai.model.PartnerPromotionEventType;
import com.silporestockai.model.PromotionEventCount;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PartnerPromotionEventRepository extends JpaRepository<PartnerPromotionEvent, UUID> {

    long countByPromotionIdAndEventType(UUID promotionId, PartnerPromotionEventType eventType);

    List<PartnerPromotionEvent> findByPromotionId(UUID promotionId);

    /** Every event of one kind, for the money attribution that needs the {@code order_id} each one carries. */
    List<PartnerPromotionEvent> findByEventType(PartnerPromotionEventType eventType);

    /**
     * The whole placement funnel in one grouped pass, named by partner and product rather than promotion id (task 54).
     *
     * <p>{@code report()} still uses the per-promotion count above — that one answers a partner's question about one
     * placement, this one feeds a gauge that is refreshed forever and must not cost N×3 queries to do it.
     */
    @Query("""
            select new com.silporestockai.model.PromotionEventCount(
                p.partnerName, p.productName, p.categoryOrQuery, p.promotionType, e.eventType, count(e))
            from PartnerPromotionEvent e, PartnerPromotion p
            where p.id = e.promotionId
            group by p.partnerName, p.productName, p.categoryOrQuery, p.promotionType, e.eventType
            """)
    List<PromotionEventCount> funnelCounts();
}
