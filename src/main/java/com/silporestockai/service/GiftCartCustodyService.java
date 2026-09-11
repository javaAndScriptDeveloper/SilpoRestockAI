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

    /**
     * Every state a repointed cart can be sitting in. {@code RESOLVED} belongs here because a build can fail
     * after the address has moved — Silpo's ₴799 minimum refuses small carts outright — and that gift is holding
     * the cart just as firmly as one waiting on a tap.
     */
    private static final List<GiftOrderStatus> HOLDING =
            List.of(GiftOrderStatus.RESOLVED, GiftOrderStatus.CART_PRESENTED, GiftOrderStatus.CONFIRMED);

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
        if (order.getSilpoCartId() == null) {
            // Cannot happen since the cart id and the snapshot are written in the same save. If it ever does,
            // it means a household's cart is pointed at a friend and this is the only code that would have
            // noticed — so it is an error, not a quiet skip.
            log.error(
                    "gift {} holds a delivery snapshot but names no cart; user {}'s cart may still be pointed "
                            + "at a gift address",
                    order.getId(),
                    userId);
        } else {
            cartBuildingService.restoreOwnDelivery(userId, order.getSilpoCartId(), order.getOwnDelivery());
        }
        // Closed either way. A Silpo refusal here would otherwise be retried on every build forever, and the
        // build that follows reads the cart's real state regardless of what this row believes about it.
        //
        // A gift the sender confirmed stays CONFIRMED: the order was placed and the friend is getting it, this is
        // only the household taking its cart back. Marking it CANCELLED (session 25) made every confirmed gift
        // vanish from «Подарунків підтверджено» the moment the household ordered again. The snapshot is what says
        // «holding the cart», so it is cleared once it has been given back.
        if (order.getStatus() == GiftOrderStatus.CONFIRMED) {
            order.setOwnDelivery(null);
        } else {
            order.setStatus(GiftOrderStatus.CANCELLED);
        }
        order.setUpdatedAt(Instant.now());
        giftOrderRepository.save(order);
        log.info("released the cart held by gift {} for user {}", order.getId(), userId);
        return true;
    }
}
