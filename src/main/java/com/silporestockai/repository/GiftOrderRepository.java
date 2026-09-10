package com.silporestockai.repository;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.model.GiftOrderStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Gifts in flight (task 81). */
public interface GiftOrderRepository extends JpaRepository<GiftOrder, UUID> {

    /** The open question in this recipient's chat, if there is one. */
    Optional<GiftOrder> findFirstByRecipientChatIdAndStatusOrderByCreatedAtDesc(
            Long recipientChatId, GiftOrderStatus status);

    /** The gift this household has in flight, in any of the given states. */
    Optional<GiftOrder> findFirstBySenderUserIdAndStatusInOrderByCreatedAtDesc(
            UUID senderUserId, Collection<GiftOrderStatus> statuses);

    /** Requests nobody answered in time. */
    List<GiftOrder> findAllByStatusAndExpiresAtBefore(GiftOrderStatus status, Instant before);

    /** How many gifts sit in one state, for the social-channel gauges (task 80). */
    long countByStatus(GiftOrderStatus status);
}
