package com.silporestockai.service;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.exception.GiftDeliveryUnavailableException;
import com.silporestockai.model.GiftAddress;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.OrderTrigger;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.service.telegram.GiftMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Turns a gift whose destination is known into a cart in front of the sender (task 81).
 *
 * <p>The address goes on the cart first and the products second, which is not a style choice: the branch changes
 * with the address, and every product added before the move is invalidated by it — probed live on 2026-09-10.
 * Doing it this way round also means the search runs against the shelf the friend's order is picked from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftCartBuildingService {

    private final CartBuildingService cartBuildingService;
    private final CartConfirmationService cartConfirmationService;
    private final AdHocOrderService adHocOrderService;
    private final GiftOrderRepository giftOrderRepository;
    private final GiftCartCustodyService giftCartCustodyService;
    private final GiftMessageService giftMessageService;
    private final TelegramOutboundService telegramOutboundService;

    public void build(User sender, GiftOrder order, OrderTrigger trigger) {
        long chatId = sender.getTelegramChatId();
        if (order.getGiftAddressText() == null || order.getGiftAddressText().isBlank()) {
            log.warn("gift {} has no address to build against", order.getId());
            return;
        }
        // A previous gift may still be holding this cart. Give that one back before borrowing it again.
        giftCartCustodyService.releaseCartIfHeld(sender.getId());

        GiftAddress destination =
                new GiftAddress(order.getGiftAddressText(), order.getGiftFlat(), null, null, order.getGiftPhone());
        CartBuildingService.RepointedCart moved;
        try {
            moved = cartBuildingService.repointCartTo(sender.getId(), destination);
        } catch (GiftDeliveryUnavailableException e) {
            log.warn("cannot deliver a gift for user {}: {}", sender.getId(), e.getMessage());
            cancel(order);
            telegramOutboundService.sendMessage(chatId, giftMessageService.deliveryUnavailable());
            return;
        } catch (RuntimeException e) {
            log.error("could not point the cart at a gift address for user {}", sender.getId(), e);
            cancel(order);
            telegramOutboundService.sendMessage(chatId, giftMessageService.addressNotFound());
            return;
        }
        // Both, together, and before anything else can fail: a snapshot without the cart it belongs to cannot
        // be put back, and live that left a household's cart at a friend's address after Silpo refused a ₴423
        // gift on its minimum-order rule.
        order.setOwnDelivery(moved.ownDelivery());
        order.setSilpoCartId(moved.cartId());
        order.setUpdatedAt(Instant.now());
        giftOrderRepository.save(order);

        List<ShoppingListItem> items = adHocOrderService.giftLinesFor(sender.getId(), order.getTheme());
        if (items.isEmpty()) {
            telegramOutboundService.sendMessage(
                    chatId,
                    "Не зрозумів, що покласти в подарунок на «%s». Напиши конкретніше.".formatted(order.getTheme()));
            giftCartCustodyService.releaseCartIfHeld(sender.getId());
            return;
        }
        if (cartConfirmationService.presentGift(sender, items, order, trigger)) {
            giftCartCustodyService.hold(order);
        } else {
            // Nothing is in front of the sender, so nothing is waiting on their tap — the household's own
            // delivery settings go back now rather than sitting on a friend's address until the next order.
            giftCartCustodyService.releaseCartIfHeld(sender.getId());
        }
    }

    private void cancel(GiftOrder order) {
        order.setStatus(GiftOrderStatus.CANCELLED);
        order.setUpdatedAt(Instant.now());
        giftOrderRepository.save(order);
    }
}
