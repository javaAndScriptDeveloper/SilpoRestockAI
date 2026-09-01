package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OrderConfirmedEvent;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.service.telegram.CartMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The last step of the first order: show the cart, take the answer, keep what was agreed.
 *
 * <p>The order row is written as a {@link OrderStatus#DRAFT} before anyone answers. That is what makes a duplicate
 * confirm callback — Telegram re-delivers them, and people do tap twice when the first tap feels slow — cheap to
 * recognise: anything that is not a draft has already been decided, and is acknowledged and dropped.
 *
 * <p>Payment is not attempted. There is no MCP payment tool, so the confirmation hands over Silpo's own checkout link
 * and stops there.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartConfirmationService {

    /** Silpo's tool for changing cart-level settings; the only one that carries the loyalty decision. */
    private static final String TOOL_UPDATE_CART = "silpo_update_shopping_cart";

    private static final String STEP_AWAITING_DECISION = "AWAITING_DECISION";
    private static final String KEY_ORDER_ID = "orderId";
    private static final String KEY_SUMMARY = "summary";

    /** Own mapper, as elsewhere in the app: Boot 4 carries both Jackson 2 and Jackson 3. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CartBuildingService cartBuildingService;
    private final CustomerOrderRepository customerOrderRepository;
    private final BaselineBasketRepository baselineBasketRepository;
    private final ConversationStateService conversationStateService;
    private final CartMessageService cartMessageService;
    private final TelegramOutboundService telegramOutboundService;
    private final SilpoMcpClient silpoMcpClient;
    private final ApplicationEventPublisher events;

    /**
     * Builds the cart from a shopping list and puts it in front of the user.
     *
     * <p>Failures end here rather than propagating: the caller is an asynchronous hand-off from meal planning, and a
     * stack trace in a log is not an answer to somebody waiting in a chat.
     */
    public void present(User user, List<ShoppingListItem> items) {
        present(user, items, OrderType.INITIAL);
    }

    /**
     * The same presentation for an order that is not the household's first.
     *
     * <p>The type matters at confirmation time and nowhere else: only an {@link OrderType#INITIAL} order becomes the
     * baseline. An emergency lunch during a blackout is not evidence about what this household normally eats.
     */
    public void present(User user, List<ShoppingListItem> items, OrderType type) {
        long chatId = user.getTelegramChatId();
        CartSummary summary;
        try {
            summary = cartBuildingService.buildCart(user.getId(), items);
        } catch (RuntimeException e) {
            log.error("could not build a cart for user {}", user.getId(), e);
            telegramOutboundService.sendMessage(chatId, "Кошик зібрати не вдалось. Спробую ще раз трохи пізніше.");
            return;
        }
        if (summary.items().isEmpty()) {
            log.warn("cart {} came back empty for user {}", summary.cartId(), user.getId());
            telegramOutboundService.sendMessage(
                    chatId, "У «Сільпо» не знайшлось жодної позиції зі списку. Спробую інакше трохи пізніше.");
            return;
        }

        CustomerOrder order = customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(type)
                .items(summary.items())
                .deliverySlot(summary.deliverySlot())
                .status(OrderStatus.DRAFT)
                .silpoCartId(summary.cartId())
                .createdAt(Instant.now())
                .build());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put(KEY_ORDER_ID, order.getId().toString());
        context.put(KEY_SUMMARY, asMap(summary));
        conversationStateService.save(chatId, ConversationFlow.CART_CONFIRMATION, STEP_AWAITING_DECISION, context);

        telegramOutboundService.sendMessageWithButtons(
                chatId, cartMessageService.cartText(summary), cartMessageService.cartButtons(summary));
        log.info("presented cart {} as draft order {} to user {}", summary.cartId(), order.getId(), user.getId());
    }

    /** Everything a chat sitting in {@link ConversationFlow#CART_CONFIRMATION} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        if (!(incoming instanceof TelegramIncomingUpdate.ButtonTap tap)) {
            telegramOutboundService.sendMessage(incoming.chatId(), "Скористайся, будь ласка, кнопками під кошиком.");
            return;
        }
        telegramOutboundService.answerCallback(tap.callbackQueryId());

        ConversationState state = conversationStateService.load(tap.chatId());
        Optional<CustomerOrder> draft = draftOf(state);
        if (draft.isEmpty()) {
            // A stale keyboard: the order is gone, already decided, or belongs to a previous week.
            log.debug("ignoring {} for chat {}: no draft order in state", tap.data(), tap.chatId());
            return;
        }

        CustomerOrder order = draft.get();
        CartSummary summary = summaryOf(state);
        switch (tap.data()) {
            case CartMessageService.CALLBACK_CONFIRM -> confirm(user, order, summary, false);
            case CartMessageService.CALLBACK_CONFIRM_BONUS -> confirm(user, order, summary, true);
            case CartMessageService.CALLBACK_CANCEL -> cancel(user, order);
            default -> log.debug("ignoring unknown callback {} for chat {}", tap.data(), tap.chatId());
        }
    }

    /** Spends the bonuses if asked, stores the order and the baseline, and hands over the checkout link. */
    private void confirm(User user, CustomerOrder order, CartSummary summary, boolean spendBonuses) {
        long chatId = user.getTelegramChatId();
        boolean bonusesApplied = spendBonuses && applyBonuses(user.getId(), summary);

        order.setStatus(OrderStatus.CONFIRMED);
        order.setConfirmedAt(Instant.now());
        customerOrderRepository.save(order);
        if (order.getType() == OrderType.INITIAL) {
            storeBaseline(user.getId(), order);
        }
        // Optional integrations listen for this; nothing here depends on any of them existing.
        events.publishEvent(new OrderConfirmedEvent(
                user.getId(),
                order.getId(),
                summary.deliverySlotStartsAt(),
                summary.deliverySlot(),
                summary.items().size()));
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());

        if (spendBonuses && !bonusesApplied) {
            telegramOutboundService.sendMessage(chatId, cartMessageService.bonusesUnavailableText());
        }
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                cartMessageService.confirmedText(summary, bonusesApplied),
                cartMessageService.checkoutButtons(summary));
        log.info("order {} confirmed for user {}, bonuses applied: {}", order.getId(), user.getId(), bonusesApplied);
    }

    private void cancel(User user, CustomerOrder order) {
        order.setStatus(OrderStatus.CANCELLED);
        customerOrderRepository.save(order);
        conversationStateService.save(user.getTelegramChatId(), ConversationFlow.NONE, null, Map.of());
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(), "Скасував. Скажи, що змінити, і зберу кошик заново.");
        log.info("order {} cancelled by user {}", order.getId(), user.getId());
    }

    /**
     * Asks Silpo to put the loyalty bonuses against this cart.
     *
     * <p>Best effort by design: a lost discount is worth less than a lost order, so a failure here is reported and the
     * confirmation continues without it.
     */
    private boolean applyBonuses(UUID userId, CartSummary summary) {
        if (summary.bonusAvailable() == null || summary.bonusAvailable().signum() <= 0) {
            return false;
        }
        try {
            McpToolResponse response = silpoMcpClient.callTool(
                    TOOL_UPDATE_CART,
                    Map.of("cartId", summary.cartId(), "bonusRequested", summary.bonusAvailable()),
                    userId);
            if (response.isError()) {
                log.warn("Silpo declined to apply bonuses to cart {}", summary.cartId());
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("could not apply bonuses to cart {}: {}", summary.cartId(), e.getMessage());
            return false;
        }
    }

    /**
     * Makes the confirmed basket the reference point every later check-in is measured against.
     *
     * <p>The previous snapshot is demoted rather than deleted, and demoted in its own transaction: a partial unique
     * index allows one current row per user, and an insert that lands before the update trips it.
     */
    private void storeBaseline(UUID userId, CustomerOrder order) {
        baselineBasketRepository.findByUserIdAndIsCurrentTrue(userId).ifPresent(previous -> {
            previous.setIsCurrent(false);
            baselineBasketRepository.saveAndFlush(previous);
        });
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .items(order.getItems())
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
    }

    /** The draft this chat is deciding on, or empty when there is nothing left to decide. */
    private Optional<CustomerOrder> draftOf(ConversationState state) {
        Object orderId = state.getContext().get(KEY_ORDER_ID);
        if (orderId == null) {
            return Optional.empty();
        }
        return customerOrderRepository
                .findById(UUID.fromString(orderId.toString()))
                .filter(order -> order.getStatus() == OrderStatus.DRAFT);
    }

    private static CartSummary summaryOf(ConversationState state) {
        return MAPPER.convertValue(state.getContext().get(KEY_SUMMARY), CartSummary.class);
    }

    // convertValue to a raw Map rather than a TypeReference: an anonymous TypeReference subclass is a class in
    // this package, and ArchUnit requires every one of those to be named ...Service.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(CartSummary summary) {
        return MAPPER.convertValue(summary, Map.class);
    }
}
