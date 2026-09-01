package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.TrustLevel;
import com.silporestockai.entity.User;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.DeltaOrder;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.ReplacementOption;
import com.silporestockai.model.ReplacementSuggestion;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.model.TrustTier;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.TrustLevelRepository;
import com.silporestockai.service.telegram.ReorderMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Puts a delta reorder in front of the user, item by item, and keeps what they agreed to.
 *
 * <p>Same lifecycle as the first order in task 10 — a {@code DRAFT} row written at presentation time, which is what
 * makes a duplicate confirm callback cheap to recognise — but a longer conversation: substitutes are decided one at a
 * time, and only then is there something to confirm.
 *
 * <p>The rule this task exists for: an order the user edited becomes the new baseline; an order they accepted as-is
 * does not. Editing means changing what is in the basket, which here means refusing a substitute. Choosing a different
 * delivery slot changes when the food arrives, not what it is, and the baseline is a basket.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReorderConfirmationService {

    private static final String TOOL_UPDATE_CART = "silpo_update_shopping_cart";
    private static final String STEP_AWAITING_DECISION = "AWAITING_DECISION";

    private static final String KEY_ORDER_ID = "orderId";
    private static final String KEY_DELTA = "delta";
    private static final String KEY_CONTEXT = "cartContext";
    private static final String KEY_SLOTS = "slots";
    private static final String KEY_SLOT = "slot";
    private static final String KEY_DECISIONS = "decisions";

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    /**
     * Own mapper, as elsewhere in the app: Boot 4 carries both Jackson 2 and Jackson 3.
     *
     * <p>With the time module registered, because a delivery slot carries an {@link java.time.Instant} and this state
     * makes a round trip through {@code conversation_state.context_json} between two button taps.
     */
    private static final ObjectMapper MAPPER =
            JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private final CartBuildingService cartBuildingService;
    private final CustomerOrderRepository customerOrderRepository;
    private final BaselineBasketRepository baselineBasketRepository;
    private final TrustLevelRepository trustLevelRepository;
    private final ConversationStateService conversationStateService;
    private final ReorderMessageService reorderMessageService;
    private final TelegramOutboundService telegramOutboundService;
    private final SilpoMcpClient silpoMcpClient;
    private final Clock clock;

    /** Chooses a slot, writes the draft, and shows the order. */
    public void present(User user, DeltaOrder order) {
        long chatId = user.getTelegramChatId();
        if (order.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, reorderMessageService.nothingToOrderText());
            return;
        }

        CartContext context = order.context();
        List<OfferedSlot> slots = slotsFor(user.getId(), context);
        OfferedSlot slot = chooseSlot(user.getId(), slots);

        CustomerOrder draft = customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(order.type())
                .items(order.cart().items())
                .deliverySlot(slot == null ? null : slot.id())
                .status(OrderStatus.DRAFT)
                .silpoCartId(order.cart().cartId())
                .createdAt(clock.instant())
                .build());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put(KEY_ORDER_ID, draft.getId().toString());
        state.put(KEY_DELTA, asMap(order));
        state.put(KEY_CONTEXT, asMap(context));
        state.put(
                KEY_SLOTS, slots.stream().map(ReorderConfirmationService::asMap).toList());
        state.put(KEY_SLOT, slot == null ? null : slot.id());
        state.put(KEY_DECISIONS, new LinkedHashMap<String, Object>());
        conversationStateService.save(chatId, ConversationFlow.REORDER_CONFIRMATION, STEP_AWAITING_DECISION, state);

        send(chatId, order, slot, Map.of());
        log.info("presented delta order {} to user {}", draft.getId(), user.getId());
    }

    /**
     * The household's usual delivery day, or the earliest slot on offer.
     *
     * <p>Most frequent weekday among past confirmed orders, in Kyiv time. A second order has no pattern to speak of,
     * and neither does a slot whose date the server wrote in a shape nobody could parse — both land on the fallback.
     */
    public OfferedSlot chooseSlot(UUID userId, List<OfferedSlot> slots) {
        if (slots.isEmpty()) {
            return null;
        }
        Optional<DayOfWeek> habit = usualDeliveryDay(userId);
        return habit.flatMap(day -> slots.stream()
                        .filter(slot -> slot.startsAt() != null
                                && slot.startsAt().atZone(KYIV).getDayOfWeek() == day)
                        .findFirst())
                .orElseGet(() -> slots.stream()
                        .filter(slot -> slot.startsAt() != null)
                        .min(Comparator.comparing(OfferedSlot::startsAt))
                        .orElse(slots.getFirst()));
    }

    /** Everything a chat sitting in {@link ConversationFlow#REORDER_CONFIRMATION} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        if (!(incoming instanceof TelegramIncomingUpdate.ButtonTap tap)) {
            telegramOutboundService.sendMessage(
                    incoming.chatId(), "Скористайся, будь ласка, кнопками під замовленням.");
            return;
        }
        telegramOutboundService.answerCallback(tap.callbackQueryId());

        ConversationState state = conversationStateService.load(tap.chatId());
        Optional<CustomerOrder> draft = draftOf(state);
        if (draft.isEmpty()) {
            log.debug("ignoring {} for chat {}: no draft reorder in state", tap.data(), tap.chatId());
            return;
        }
        CustomerOrder order = draft.get();
        DeltaOrder delta = MAPPER.convertValue(state.getContext().get(KEY_DELTA), DeltaOrder.class);
        Map<Integer, Boolean> decisions = decisionsOf(state);

        String data = tap.data();
        if (data.startsWith(ReorderMessageService.CALLBACK_ACCEPT_PREFIX)) {
            decide(user, state, delta, decisions, indexOf(data, ReorderMessageService.CALLBACK_ACCEPT_PREFIX), true);
        } else if (data.startsWith(ReorderMessageService.CALLBACK_REJECT_PREFIX)) {
            decide(user, state, delta, decisions, indexOf(data, ReorderMessageService.CALLBACK_REJECT_PREFIX), false);
        } else if (ReorderMessageService.CALLBACK_SLOT_MENU.equals(data)) {
            telegramOutboundService.sendMessageWithButtons(
                    tap.chatId(),
                    reorderMessageService.slotMenuText(),
                    reorderMessageService.slotButtons(slotsOf(state)));
        } else if (data.startsWith(ReorderMessageService.CALLBACK_SLOT_PREFIX)) {
            pickSlot(user, state, delta, decisions, indexOf(data, ReorderMessageService.CALLBACK_SLOT_PREFIX));
        } else if (ReorderMessageService.CALLBACK_CONFIRM.equals(data)) {
            confirm(user, order, state, delta, decisions);
        } else if (ReorderMessageService.CALLBACK_CANCEL.equals(data)) {
            cancel(user, order);
        } else {
            log.debug("ignoring unknown reorder callback {} in chat {}", data, tap.chatId());
        }
    }

    private void decide(
            User user,
            ConversationState state,
            DeltaOrder delta,
            Map<Integer, Boolean> decisions,
            int index,
            boolean accepted) {
        if (index < 0 || index >= delta.pendingReplacements().size()) {
            return;
        }
        decisions.put(index, accepted);
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());
        context.put(KEY_DECISIONS, asStringKeys(decisions));
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.REORDER_CONFIRMATION, STEP_AWAITING_DECISION, context);
        send(user.getTelegramChatId(), delta, slotOf(state), decisions);
    }

    private void pickSlot(
            User user, ConversationState state, DeltaOrder delta, Map<Integer, Boolean> decisions, int index) {
        List<OfferedSlot> slots = slotsOf(state);
        if (index < 0 || index >= slots.size()) {
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());
        context.put(KEY_SLOT, slots.get(index).id());
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.REORDER_CONFIRMATION, STEP_AWAITING_DECISION, context);
        send(user.getTelegramChatId(), delta, slots.get(index), decisions);
    }

    /**
     * Adds whatever was accepted, books the slot, reads the cart back, and applies the baseline rule.
     *
     * <p>The confirmed contents come from the verified cart rather than from what we meant to add — the same "never
     * trust the write" rule the first order follows.
     */
    private void confirm(
            User user,
            CustomerOrder order,
            ConversationState state,
            DeltaOrder delta,
            Map<Integer, Boolean> decisions) {
        long chatId = user.getTelegramChatId();
        CartContext context = MAPPER.convertValue(state.getContext().get(KEY_CONTEXT), CartContext.class);
        String slotId = state.getContext().get(KEY_SLOT) == null
                ? null
                : state.getContext().get(KEY_SLOT).toString();

        addAcceptedReplacements(user.getId(), context, delta, decisions);
        boolean slotFixed = slotId != null && bookSlot(user.getId(), context.cartId(), slotId);
        CartSummary cart = cartBuildingService.getVerifiedCart(user.getId(), context, slotId, List.of());

        order.setItems(cart.items());
        order.setDeliverySlot(slotId);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setConfirmedAt(clock.instant());
        customerOrderRepository.save(order);

        // Refusing a substitute is an edit; accepting one is agreeing with the suggestion.
        boolean edited = decisions.containsValue(false);
        if (edited) {
            supersedeBaseline(user.getId(), cart);
        }
        recordTrust(user.getId(), edited);
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());

        telegramOutboundService.sendMessageWithButtons(
                chatId,
                reorderMessageService.confirmedText(cart, delta.estimatedSavings(), slotFixed, edited),
                checkoutButtons(cart));
        log.info("reorder {} confirmed for user {}, edited: {}", order.getId(), user.getId(), edited);
    }

    private void cancel(User user, CustomerOrder order) {
        order.setStatus(OrderStatus.CANCELLED);
        customerOrderRepository.save(order);
        conversationStateService.save(user.getTelegramChatId(), ConversationFlow.NONE, null, Map.of());
        telegramOutboundService.sendMessage(user.getTelegramChatId(), reorderMessageService.cancelledText());
        log.info("reorder {} cancelled by user {}", order.getId(), user.getId());
    }

    private void addAcceptedReplacements(
            UUID userId, CartContext context, DeltaOrder delta, Map<Integer, Boolean> decisions) {
        List<Map<String, Object>> products = new ArrayList<>();
        for (Map.Entry<Integer, Boolean> decision : decisions.entrySet()) {
            if (!Boolean.TRUE.equals(decision.getValue())) {
                continue;
            }
            ReplacementSuggestion suggestion = delta.pendingReplacements().get(decision.getKey());
            if (suggestion.options().isEmpty()) {
                continue;
            }
            ReplacementOption option = suggestion.options().getFirst();
            products.add(Map.of(
                    "productId", option.productId(),
                    "companyId", nullSafe(context.companyId()),
                    "branchId", nullSafe(context.branchId()),
                    "quantity", 1));
        }
        if (products.isEmpty()) {
            return;
        }
        call(userId, "silpo_add_or_update_cart_products", Map.of("cartId", context.cartId(), "products", products));
    }

    /** Books the chosen window. A refusal is reported, not fatal: checkout can still fix it. */
    private boolean bookSlot(UUID userId, String cartId, String slotId) {
        return call(userId, TOOL_UPDATE_CART, Map.of("cartId", cartId, "timeslot", slotId));
    }

    /** The edited order becomes the reference point; the previous snapshot is superseded, not deleted. */
    private void supersedeBaseline(UUID userId, CartSummary cart) {
        baselineBasketRepository.findByUserIdAndIsCurrentTrue(userId).ifPresent(previous -> {
            previous.setIsCurrent(false);
            baselineBasketRepository.saveAndFlush(previous);
        });
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .items(cart.items())
                .confirmedAt(clock.instant())
                .isCurrent(true)
                .build());
    }

    /**
     * Counts confirmations nobody edited.
     *
     * <p>Plumbing only. Nothing reads this to skip a confirmation, and nothing in this task should: auto-confirm is
     * explicitly future work.
     */
    private void recordTrust(UUID userId, boolean edited) {
        TrustLevel trust = trustLevelRepository
                .findByUserId(userId)
                .orElseGet(() -> TrustLevel.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .consecutiveUneditedConfirmations(0)
                        .currentTrustTier(TrustTier.MANUAL_CONFIRM)
                        .build());
        trust.setConsecutiveUneditedConfirmations(edited ? 0 : trust.getConsecutiveUneditedConfirmations() + 1);
        trustLevelRepository.save(trust);
    }

    private void send(long chatId, DeltaOrder order, OfferedSlot slot, Map<Integer, Boolean> decisions) {
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                reorderMessageService.orderText(order, slot, decisions),
                reorderMessageService.orderButtons(order, decisions));
    }

    private List<OfferedSlot> slotsFor(UUID userId, CartContext context) {
        try {
            return cartBuildingService.offeredTimeSlots(userId, context);
        } catch (RuntimeException e) {
            // No slots is not a reason to hide a finished order: checkout can still pick one.
            log.warn("could not read time slots for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private Optional<DayOfWeek> usualDeliveryDay(UUID userId) {
        Map<DayOfWeek, Long> byDay = new LinkedHashMap<>();
        customerOrderRepository.findByUserIdAndStatus(userId, OrderStatus.CONFIRMED).stream()
                .map(CustomerOrder::getConfirmedAt)
                .filter(Objects::nonNull)
                .map(confirmedAt -> confirmedAt.atZone(KYIV).getDayOfWeek())
                .forEach(day -> byDay.merge(day, 1L, Long::sum));
        return byDay.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
    }

    private Optional<CustomerOrder> draftOf(ConversationState state) {
        Object orderId = state.getContext().get(KEY_ORDER_ID);
        if (orderId == null) {
            return Optional.empty();
        }
        return customerOrderRepository
                .findById(UUID.fromString(orderId.toString()))
                .filter(order -> order.getStatus() == OrderStatus.DRAFT);
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

    private OfferedSlot slotOf(ConversationState state) {
        Object slotId = state.getContext().get(KEY_SLOT);
        return slotId == null
                ? null
                : slotsOf(state).stream()
                        .filter(slot -> slot.id().equals(slotId.toString()))
                        .findFirst()
                        .orElse(null);
    }

    private static Map<Integer, Boolean> decisionsOf(ConversationState state) {
        Map<Integer, Boolean> decisions = new LinkedHashMap<>();
        if (state.getContext().get(KEY_DECISIONS) instanceof Map<?, ?> stored) {
            stored.forEach((key, value) ->
                    decisions.put(Integer.parseInt(key.toString()), Boolean.parseBoolean(value.toString())));
        }
        return decisions;
    }

    private static Map<String, Object> asStringKeys(Map<Integer, Boolean> decisions) {
        Map<String, Object> stored = new LinkedHashMap<>();
        decisions.forEach((index, accepted) -> stored.put(index.toString(), accepted));
        return stored;
    }

    private static int indexOf(String data, String prefix) {
        try {
            return Integer.parseInt(data.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private List<TelegramButton> checkoutButtons(CartSummary cart) {
        return cart.checkoutWebLink() == null
                ? List.of()
                : List.of(TelegramButton.link("Оформити на silpo.ua", cart.checkoutWebLink()));
    }

    /** True when the tool answered without an error. Both calls here are best effort by design. */
    private boolean call(UUID userId, String tool, Map<String, Object> arguments) {
        log.info("MCP -> {} {}", tool, arguments);
        try {
            McpToolResponse response = silpoMcpClient.callTool(tool, arguments, userId);
            if (response.isError()) {
                log.warn("Silpo tool {} reported an error", tool);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Silpo tool {} failed: {}", tool, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        // convertValue to a raw Map rather than a TypeReference: an anonymous TypeReference subclass is a class in
        // this package, and ArchUnit requires every one of those to be named ...Service.
        return MAPPER.convertValue(value, Map.class);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
