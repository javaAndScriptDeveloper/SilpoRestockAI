package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.exception.NoSilpoDeliveryAddressException;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.OrderConfirmedEvent;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.service.telegram.CartMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.math.RoundingMode;
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
    private static final String KEY_SLOTS = "slots";
    private static final String KEY_SLOT = "slot";

    /** Own mapper, as elsewhere in the app: Boot 4 carries both Jackson 2 and Jackson 3. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final CartBuildingService cartBuildingService;
    private final ObservabilityService observabilityService;
    private final CustomerOrderRepository customerOrderRepository;
    private final BaselineBasketRepository baselineBasketRepository;
    private final ConversationStateService conversationStateService;
    private final CartMessageService cartMessageService;
    private final TelegramOutboundService telegramOutboundService;
    private final SilpoMcpClient silpoMcpClient;
    private final ShoppingListService shoppingListService;
    private final ApplicationEventPublisher events;

    /**
     * Builds the cart from a shopping list and puts it in front of the user.
     *
     * <p>Failures end here rather than propagating: the caller is an asynchronous hand-off from meal planning, and a
     * stack trace in a log is not an answer to somebody waiting in a chat.
     */
    public boolean present(User user, List<ShoppingListItem> items) {
        return present(user, items, OrderType.INITIAL);
    }

    /**
     * The same presentation for an order that is not the household's first.
     *
     * <p>The type matters at confirmation time and nowhere else: only an {@link OrderType#INITIAL} order becomes the
     * baseline. An emergency lunch during a blackout is not evidence about what this household normally eats.
     *
     * @return whether a cart was put in front of the person — false when the failure was already reported here,
     *     so a caller that has a way to offer another go (the list's «Замовити») can add it
     */
    public boolean present(User user, List<ShoppingListItem> items, OrderType type) {
        return present(user, items, type, false);
    }

    /** Same, for a request made «по знижці» — see {@code CartBuildingService.buildCart}. */
    public boolean present(User user, List<ShoppingListItem> items, OrderType type, boolean preferDiscounted) {
        long chatId = user.getTelegramChatId();
        CartSummary summary;
        try {
            summary = cartBuildingService.buildCart(user.getId(), items, preferDiscounted);
        } catch (NoSilpoDeliveryAddressException e) {
            log.error("could not build a cart for user {}", user.getId(), e);
            observabilityService.recordFailureMessage("cart_build", "no_address");
            telegramOutboundService.sendMessage(
                    chatId,
                    "У «Сільпо» немає збереженої адреси доставки, тому я не можу створити кошик. Додай адресу "
                            + "в застосунку «Сільпо» (Профіль → Мої адреси доставки) і напиши мені ще раз.");
            return false;
        } catch (CartBuildException e) {
            log.error("could not build a cart for user {}", user.getId(), e);
            observabilityService.recordFailureMessage("cart_build", "cart_build");
            telegramOutboundService.sendMessage(chatId, cartBuildFailureMessage(e));
            return false;
        } catch (RuntimeException e) {
            // No promise of a retry nobody performs: the person is told what to do, and the list flow adds the
            // button that does it.
            log.error("could not build a cart for user {}", user.getId(), e);
            observabilityService.recordFailureMessage("cart_build", "unexpected");
            telegramOutboundService.sendMessage(chatId, CART_BUILD_FAILED_TEXT);
            return false;
        }
        if (summary.items().isEmpty()) {
            log.warn("cart {} came back empty for user {}", summary.cartId(), user.getId());
            observabilityService.recordFailureMessage("cart_build", "empty_cart");
            telegramOutboundService.sendMessage(
                    chatId, "У «Сільпо» не знайшлось жодної позиції зі списку. Спробуй описати продукти інакше.");
            return false;
        }

        List<OfferedSlot> slots = slotsFor(user.getId());
        OfferedSlot selectedSlot = slots.stream()
                .filter(slot -> slot.id().equals(summary.deliverySlot()))
                .findFirst()
                .orElse(null);

        CustomerOrder order = customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(type)
                .items(summary.items())
                .deliverySlot(summary.deliverySlot())
                .status(OrderStatus.DRAFT)
                .silpoCartId(summary.cartId())
                .unresolvedCount(
                        summary.unresolved() == null ? 0 : summary.unresolved().size())
                // Task 54: the cart's money is only ever in hand here, while the CartSummary exists. confirm()
                // flips a status on a row it re-reads from conversation_state and never asks Silpo again, so this
                // is the last point where a total can be written without an extra MCP round-trip.
                .total(summary.total())
                .goodsTotal(summary.goodsTotal())
                .savings(summary.savings())
                .toppedUpCount(summary.toppedUpLines().size())
                .createdAt(Instant.now())
                .build());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put(KEY_ORDER_ID, order.getId().toString());
        context.put(KEY_SUMMARY, asMap(summary));
        context.put(
                KEY_SLOTS, slots.stream().map(CartConfirmationService::asMap).toList());
        context.put(KEY_SLOT, summary.deliverySlot());
        conversationStateService.save(chatId, ConversationFlow.CART_CONFIRMATION, STEP_AWAITING_DECISION, context);

        if (summary.belowMinimumOrder()) {
            // Silpo's minimum order, and the household's call what to do about it. The cart is shown exactly as
            // built; topping it up from the baseline is a button, not a default — twelve lines of vegetables under
            // a carbonara was the first thing a person said «забагато лишнього» about.
            boolean hasBaseline = cartBuildingService.hasBaseline(user.getId());
            telegramOutboundService.sendMessageWithButtons(
                    chatId,
                    cartMessageService.belowMinimumText(summary, selectedSlot, type, hasBaseline),
                    cartMessageService.belowMinimumButtons(summary, hasBaseline));
            log.info(
                    "presented cart {} as draft order {} to user {}, {} short of the minimum order",
                    summary.cartId(),
                    order.getId(),
                    user.getId(),
                    summary.shortfall());
            return true;
        }
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                cartMessageService.cartText(summary, selectedSlot, type),
                cartMessageService.cartButtons(summary, !slots.isEmpty()));
        log.info("presented cart {} as draft order {} to user {}", summary.cartId(), order.getId(), user.getId());
        return true;
    }

    /**
     * The «Докласти з мого набору» tap: the baseline lines go in, the cart is read back, and the household sees it
     * again — with the confirm button this time, if the minimum is cleared.
     */
    private void topUp(User user, ConversationState state, CustomerOrder order, CartSummary summary) {
        long chatId = user.getTelegramChatId();
        if (!summary.belowMinimumOrder()) {
            log.debug("ignoring a top-up tap for cart {}: it is not below the minimum", summary.cartId());
            return;
        }
        CartSummary topped;
        try {
            topped = cartBuildingService.topUp(user.getId(), summary);
        } catch (RuntimeException e) {
            log.error("could not top cart {} up for user {}", summary.cartId(), user.getId(), e);
            observabilityService.recordFailureMessage("cart_topup", "unexpected");
            telegramOutboundService.sendMessage(chatId, CART_BUILD_FAILED_TEXT);
            return;
        }
        order.setItems(topped.items());
        order.setUnresolvedCount(
                topped.unresolved() == null ? 0 : topped.unresolved().size());
        // The basket grew, so its money did too — without these the stored total would be the pre-top-up one.
        order.setTotal(topped.total());
        order.setGoodsTotal(topped.goodsTotal());
        order.setSavings(topped.savings());
        order.setToppedUpCount(topped.toppedUpLines().size());
        customerOrderRepository.save(order);
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());
        context.put(KEY_SUMMARY, asMap(topped));
        conversationStateService.save(chatId, ConversationFlow.CART_CONFIRMATION, STEP_AWAITING_DECISION, context);

        List<OfferedSlot> slots = slotsOf(state);
        OfferedSlot selectedSlot = slots.stream()
                .filter(slot -> slot.id().equals(topped.deliverySlot()))
                .findFirst()
                .orElse(null);
        if (topped.belowMinimumOrder()) {
            // The whole baseline was not enough. Nothing more to offer from here; the Silpo app is.
            telegramOutboundService.sendMessageWithButtons(
                    chatId,
                    cartMessageService.belowMinimumText(topped, selectedSlot, order.getType(), false),
                    cartMessageService.belowMinimumButtons(topped, false));
            return;
        }
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                cartMessageService.cartText(topped, selectedSlot, order.getType()),
                cartMessageService.cartButtons(topped, !slots.isEmpty()));
        log.info(
                "topped cart {} up with {} baseline lines for user {}",
                topped.cartId(),
                topped.toppedUpLines().size(),
                user.getId());
    }

    /** No slots is not a reason to hide a finished order: checkout can still pick one. */
    private List<OfferedSlot> slotsFor(UUID userId) {
        try {
            CartContext context = cartBuildingService.getOrCreateCartContext(userId);
            return cartBuildingService.offeredTimeSlots(userId, context);
        } catch (RuntimeException e) {
            log.warn("could not read time slots for user {}: {}", userId, e.getMessage());
            return List.of();
        }
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
        String data = tap.data();
        if (CartMessageService.CALLBACK_CONFIRM.equals(data)) {
            confirm(user, order, state, summary, false);
        } else if (CartMessageService.CALLBACK_CONFIRM_BONUS.equals(data)) {
            confirm(user, order, state, summary, true);
        } else if (CartMessageService.CALLBACK_SLOT_MENU.equals(data)) {
            telegramOutboundService.sendMessageWithButtons(
                    tap.chatId(), cartMessageService.slotMenuText(), cartMessageService.slotButtons(slotsOf(state)));
        } else if (data.startsWith(CartMessageService.CALLBACK_SLOT_PREFIX)) {
            pickSlot(user, state, order, summary, data.substring(CartMessageService.CALLBACK_SLOT_PREFIX.length()));
        } else if (CartMessageService.CALLBACK_TOP_UP.equals(data)) {
            topUp(user, state, order, summary);
        } else if (CartMessageService.CALLBACK_CANCEL.equals(data)) {
            cancel(user, order);
        } else {
            log.debug("ignoring unknown callback {} for chat {}", data, tap.chatId());
        }
    }

    private void pickSlot(
            User user, ConversationState state, CustomerOrder order, CartSummary summary, String indexRaw) {
        List<OfferedSlot> slots = slotsOf(state);
        int index;
        try {
            index = Integer.parseInt(indexRaw);
        } catch (NumberFormatException e) {
            return;
        }
        if (index < 0 || index >= slots.size()) {
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());
        context.put(KEY_SLOT, slots.get(index).id());
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.CART_CONFIRMATION, STEP_AWAITING_DECISION, context);
        telegramOutboundService.sendMessageWithButtons(
                user.getTelegramChatId(),
                cartMessageService.cartText(summary, slots.get(index), order.getType()),
                cartMessageService.cartButtons(summary, !slots.isEmpty()));
    }

    private static List<OfferedSlot> slotsOf(ConversationState state) {
        Object slots = state.getContext().get(KEY_SLOTS);
        if (!(slots instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .map(node -> MAPPER.convertValue(node, OfferedSlot.class))
                .toList();
    }

    /** Spends the bonuses if asked, stores the order and the baseline, and hands over the checkout link. */
    private void confirm(
            User user, CustomerOrder order, ConversationState state, CartSummary summary, boolean spendBonuses) {
        long chatId = user.getTelegramChatId();
        if (summary.belowMinimumOrder()) {
            // A confirm tap on a keyboard that never had one: there is no checkout link to hand over yet.
            boolean hasBaseline = cartBuildingService.hasBaseline(user.getId());
            telegramOutboundService.sendMessageWithButtons(
                    chatId,
                    cartMessageService.belowMinimumText(summary, null, order.getType(), hasBaseline),
                    cartMessageService.belowMinimumButtons(summary, hasBaseline));
            return;
        }
        String selectedSlotId = String.valueOf(state.getContext().get(KEY_SLOT));
        if (!selectedSlotId.equals(summary.deliverySlot())) {
            bookSlot(user.getId(), summary.cartId(), selectedSlotId);
            order.setDeliverySlot(selectedSlotId);
        }
        boolean bonusesApplied = spendBonuses && applyBonuses(user.getId(), summary);

        order.setStatus(OrderStatus.CONFIRMED);
        order.setConfirmedAt(Instant.now());
        customerOrderRepository.save(order);
        // The money was written when the draft was saved: nothing here re-reads the cart from Silpo, so the stored
        // total is the one the household actually approved.
        observabilityService.recordConfirmedOrder(
                order.getType(), order.getTotal(), summary.items().size());
        shoppingListService.markOrdered(user.getId());
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
                cartMessageService.confirmedText(summary, bonusesApplied, order.getType()),
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

    /** A refusal is reported, not fatal: checkout can still fix the delivery window if this call failed. */
    private void bookSlot(UUID userId, String cartId, String slotId) {
        try {
            McpToolResponse response =
                    silpoMcpClient.callTool(TOOL_UPDATE_CART, Map.of("cartId", cartId, "timeslot", slotId), userId);
            if (response.isError()) {
                log.warn("Silpo declined to rebook cart {} onto slot {}", cartId, slotId);
            }
        } catch (RuntimeException e) {
            log.warn("could not rebook cart {} onto slot {}: {}", cartId, slotId, e.getMessage());
        }
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

    /**
     * Silpo can refuse a cart for reasons a retry won't fix on its own — an expired timeslot, a quantity above
     * stock — so when we know why, the person hears why instead of a generic "спробую пізніше".
     */
    private static String cartBuildFailureMessage(CartBuildException e) {
        if (e.belowMinimumOrder()) {
            // Silpo's rule, not ours, and nothing here could lift the cart over it (no baseline to top up from).
            // The cart itself is real and sitting in the Silpo app, which is the one place the person can add
            // to it right now.
            return ("Зібрав кошик на %s грн (без доставки), але «Сільпо» не доставляє замовлення менше %s грн. "
                            + "Кошик уже в застосунку «Сільпо» — докинь щось там, або зроби спочатку тижневе "
                            + "замовлення: тоді наступного разу я сам доповню маленький кошик твоїми звичайними "
                            + "продуктами.")
                    .formatted(
                            e.getTotal() == null
                                    ? "?"
                                    : e.getTotal()
                                            .setScale(0, RoundingMode.HALF_UP)
                                            .toPlainString(),
                            e.getMinimumOrder()
                                    .setScale(0, RoundingMode.HALF_UP)
                                    .toPlainString());
        }
        if (e.getValidations().isEmpty()) {
            return CART_BUILD_FAILED_TEXT;
        }
        return "Кошик зібрати не вдалось:\n- " + String.join("\n- ", e.getValidations())
                + "\nВиправ список і спробуй ще раз.";
    }

    /** Said when a build fails for a reason a person cannot act on — a slow server, a failed model call. */
    static final String CART_BUILD_FAILED_TEXT =
            "Кошик зібрати не вдалось — «Сільпо» або каталог не відповіли " + "вчасно. Спробуй ще раз за хвилину.";

    private static CartSummary summaryOf(ConversationState state) {
        return MAPPER.convertValue(state.getContext().get(KEY_SUMMARY), CartSummary.class);
    }

    // convertValue to a raw Map rather than a TypeReference: an anonymous TypeReference subclass is a class in
    // this package, and ArchUnit requires every one of those to be named ...Service.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return MAPPER.convertValue(value, Map.class);
    }
}
