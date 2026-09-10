package com.silporestockai.service;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.repository.GiftOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Custody of the household's single Silpo cart while a gift is using it (task 81).
 *
 * <p>An account has exactly one cart — {@code silpo_create_shopping_cart} is documented idempotent per user — so a
 * gift borrows the same one the weekly order uses, and has to give it back.
 *
 * <p>Not when the sender confirms, though. Checkout is a Silpo web link that reads the cart live, so restoring the
 * household's own address at that moment would deliver the gift to the sender — the one failure nobody would find
 * out about until after the money moved. It happens on the household's next ordinary build instead: late enough to
 * be safe, early enough that no weekly order ever lands on a friend's doorstep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftCartCustodyService {

    private static final List<GiftOrderStatus> HOLDING =
            List.of(GiftOrderStatus.CART_PRESENTED, GiftOrderStatus.CONFIRMED);

    private final GiftOrderRepository giftOrderRepository;
    private final CartBuildingService cartBuildingService;

    /** Marks this gift as the current holder of the cart. */
    public void hold(GiftOrder order) {
        order.setStatus(GiftOrderStatus.CART_PRESENTED);
        order.setUpdatedAt(Instant.now());
        giftOrderRepository.save(order);
    }

    /**
     * Puts the household's own delivery settings back if a gift is still holding them.
     *
     * @return whether anything was restored
     */
    public boolean releaseCartIfHeld(UUID userId) {
        Optional<GiftOrder> holder =
                giftOrderRepository.findFirstBySenderUserIdAndStatusInOrderByCreatedAtDesc(userId, HOLDING);
        if (holder.isEmpty() || !holder.get().holdsTheCart()) {
            return false;
        }
        GiftOrder order = holder.get();
        if (order.getSilpoCartId() != null) {
            cartBuildingService.restoreOwnDelivery(userId, order.getSilpoCartId(), order.getOwnDelivery());
        }
        // Closed either way. A Silpo refusal here would otherwise be retried on every build forever, and the
        // build that follows reads the cart's real state regardless of what this row believes about it.
        order.setStatus(GiftOrderStatus.CANCELLED);
        order.setUpdatedAt(Instant.now());
        giftOrderRepository.save(order);
        log.info("released the cart held by gift {} for user {}", order.getId(), userId);
        return true;
    }
}
