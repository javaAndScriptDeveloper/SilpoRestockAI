package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.PastOrderLine;
import com.silporestockai.model.PastOrderSummary;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.ShoppingListSourceType;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.service.telegram.TelegramOutboundService;
import com.silporestockai.utils.McpResponses;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * «Зроби список як минулого разу» (task 35): the household's real Silpo orders, one tap, and the picked order's
 * lines become the live list — product ids, quantities and prices copied, nothing generated, nothing searched.
 *
 * <p>This is strictly better than any receipt photo: the ids are Silpo's own, so {@code CartBuildingService}
 * skips {@code silpo_find_products_batch} entirely (task 22's pre-resolved path) and the cart is the order,
 * not a best-effort match of it. The list then goes through the ordinary Замовити → cart → confirm steps and
 * becomes the baseline like any first order — this class only changes where the list came from.
 *
 * <p>The person always picks; the newest order is never assumed. Response shapes are read with the same
 * key-guessing helpers the rest of the app uses, and an order the tools return without line items is said to
 * be exactly that rather than padded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PastOrderSeedService {

    public static final String CALLBACK_PREFIX = "past:";
    public static final String CALLBACK_CANCEL = "past:cancel";

    private static final String STEP_AWAITING_PICK = "AWAITING_PICK";
    private static final String KEY_ORDERS = "orders";
    private static final int MAX_OFFERED = 5;

    /** Both history tools, each tolerated on its own — an in-store shopper may have no online orders at all. */
    private static final List<String> ORDER_TOOLS =
            List.of("silpo_get_my_online_orders", "silpo_get_my_offline_orders");

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("d MMM", new Locale("uk"));

    private final SilpoMcpClient silpoMcpClient;
    private final CartBuildingService cartBuildingService;
    private final SilpoAuthService silpoAuthService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final ConversationStateService conversationStateService;
    private final TelegramOutboundService telegramOutboundService;

    /** Lists recent orders as buttons, newest first, and waits for a pick. */
    public void offer(User user) {
        long chatId = user.getTelegramChatId();
        if (!silpoAuthService.isConnected(user.getId())) {
            telegramOutboundService.sendMessage(
                    chatId, "Спершу під'єднай акаунт «Сільпо» — без нього я не бачу твоїх замовлень.");
            return;
        }
        List<PastOrderSummary> orders = recentOrders(user.getId());
        if (orders.isEmpty()) {
            telegramOutboundService.sendMessage(
                    chatId,
                    "Не бачу минулих замовлень в акаунті «Сільпо». Можу скласти список сам — натисни «Список».");
            return;
        }
        List<TelegramButton> buttons = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            buttons.add(TelegramButton.callback(label(orders.get(i)), CALLBACK_PREFIX + i));
        }
        buttons.add(TelegramButton.callback("Скасувати", CALLBACK_CANCEL));
        conversationStateService.save(
                chatId,
                ConversationFlow.PAST_ORDER_PICK,
                STEP_AWAITING_PICK,
                Map.of(
                        KEY_ORDERS,
                        orders.stream().map(PastOrderSeedService::asMap).toList()));
        telegramOutboundService.sendMessageWithButtons(
                chatId, "Ось твої останні замовлення в «Сільпо». Яке взяти за основу?", buttons);
    }

    /** Everything a chat sitting in {@link ConversationFlow#PAST_ORDER_PICK} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = user.getTelegramChatId();
        if (!(incoming instanceof TelegramIncomingUpdate.ButtonTap tap)) {
            telegramOutboundService.sendMessage(chatId, "Обери замовлення кнопкою вище або натисни «Скасувати».");
            return;
        }
        telegramOutboundService.answerCallback(tap.callbackQueryId());
        if (CALLBACK_CANCEL.equals(tap.data())) {
            conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
            telegramOutboundService.sendMessageWithMainMenu(chatId, "Гаразд, список лишаю як є.");
            return;
        }
        if (!tap.data().startsWith(CALLBACK_PREFIX)) {
            log.debug("ignoring callback {} while awaiting a past-order pick in chat {}", tap.data(), chatId);
            return;
        }
        ConversationState state = conversationStateService.load(chatId);
        List<PastOrderSummary> orders = ordersOf(state);
        int index;
        try {
            index = Integer.parseInt(tap.data().substring(CALLBACK_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        if (index < 0 || index >= orders.size()) {
            return;
        }
        seed(user, orders.get(index));
    }

    private void seed(User user, PastOrderSummary order) {
        long chatId = user.getTelegramChatId();
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        if (order.lines() == null || order.lines().isEmpty()) {
            // The listing tool answered with summaries only. Saying so beats a search by name that would turn a
            // known order into a guessed one.
            telegramOutboundService.sendMessage(
                    chatId,
                    "«Сільпо» віддало це замовлення без переліку позицій, тож зібрати з нього список не можу. "
                            + "Спробуй інше або натисни «Список».");
            log.warn("past order {} for user {} carried no line items", order.orderId(), user.getId());
            return;
        }
        List<PlannedIngredient> ingredients = order.lines().stream()
                .map(line -> new PlannedIngredient(
                        line.name(), line.quantity(), line.unit(), null, line.productId(), unitPrice(line)))
                .toList();
        shoppingListService.keepOnly(user.getId(), List.of());
        List<ShoppingListItem> items =
                shoppingListService.createAdHocList(user.getId(), ingredients, ShoppingListSourceType.PAST_ORDER);
        telegramOutboundService.sendMessage(
                chatId,
                "Взяв за основу замовлення " + label(order) + " — " + items.size() + " " + positions(items.size())
                        + ", ті самі товари, що й тоді.");
        shoppingListBuilderService.present(user, items);
        log.info("seeded a {}-line list for user {} from past order {}", items.size(), user.getId(), order.orderId());
    }

    /** The list stores a unit price (task 39); an order line carries what the line cost. */
    private static BigDecimal unitPrice(PastOrderLine line) {
        if (line.price() == null) {
            return null;
        }
        if (line.quantity() == null || line.quantity().signum() <= 0) {
            return line.price();
        }
        return line.price().divide(line.quantity(), 2, RoundingMode.HALF_UP);
    }

    private List<PastOrderSummary> recentOrders(UUID userId) {
        List<PastOrderSummary> orders = new ArrayList<>();
        for (String tool : ORDER_TOOLS) {
            try {
                McpToolResponse response = silpoMcpClient.callTool(tool, argumentsFor(tool, userId), userId);
                if (response.isError()) {
                    log.info("Silpo tool {} reported an error for user {}; skipping it", tool, userId);
                    continue;
                }
                orders.addAll(parse(McpResponses.tree(response), tool.contains("offline") ? "offline" : "online"));
            } catch (RuntimeException e) {
                // A 403 means this guest has not granted the tool; the other one may still answer.
                log.info("Silpo tool {} unavailable for user {}: {}", tool, userId, e.getMessage());
            }
        }
        // Newest first when dates parse; otherwise Silpo's own order, which is newest-first in practice.
        orders.sort((a, b) -> parseDate(b.dateLabel()).compareTo(parseDate(a.dateLabel())));
        return orders.size() > MAX_OFFERED ? orders.subList(0, MAX_OFFERED) : orders;
    }

    /**
     * The offline-orders tool wants the cart's branch, delivery type and time slot like the catalog tools do
     * (its own schema says so); the online one takes nothing. Called with no arguments, the offline tool
     * answered «Invalid arguments» on every live run and in-store history was never seen.
     */
    private Map<String, Object> argumentsFor(String tool, UUID userId) {
        if (!tool.contains("offline")) {
            return Map.of();
        }
        CartContext context = cartBuildingService.getOrCreateCartContext(userId);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("branchId", context.branchId() == null ? "" : context.branchId());
        arguments.put("deliveryType", context.deliveryType() == null ? "" : context.deliveryType());
        arguments.put("timeslotStart", context.timeslotStart() == null ? "" : context.timeslotStart());
        arguments.put("timeslotEnd", context.timeslotEnd() == null ? "" : context.timeslotEnd());
        return arguments;
    }

    static List<PastOrderSummary> parse(JsonNode root, String source) {
        if (root == null) {
            return List.of();
        }
        // The tool may answer with the array itself or wrap it under one of the usual keys.
        List<JsonNode> orderNodes = root.isArray() ? toList(root) : McpResponses.findArray(root, McpResponses.ORDERS);
        List<PastOrderSummary> orders = new ArrayList<>();
        for (JsonNode node : orderNodes) {
            List<PastOrderLine> lines = new ArrayList<>();
            for (JsonNode item : McpResponses.findArray(node, McpResponses.ITEMS)) {
                String productId =
                        McpResponses.findString(item, McpResponses.PRODUCT_ID).orElse(null);
                String name = McpResponses.findString(item, McpResponses.NAME).orElse(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                lines.add(new PastOrderLine(
                        productId,
                        name,
                        McpResponses.findNumber(item, McpResponses.QUANTITY).orElse(BigDecimal.ONE),
                        McpResponses.findString(item, McpResponses.UNIT).orElse("шт"),
                        McpResponses.findNumber(item, McpResponses.PRICE).orElse(null)));
            }
            orders.add(new PastOrderSummary(
                    McpResponses.findString(node, McpResponses.ORDER_ID).orElse(null),
                    source,
                    McpResponses.findString(node, McpResponses.ORDER_DATE).orElse(""),
                    McpResponses.findNumber(node, McpResponses.TOTAL).orElse(null),
                    lines));
        }
        return orders;
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> nodes = new ArrayList<>();
        array.forEach(nodes::add);
        return nodes;
    }

    private static OffsetDateTime parseDate(String label) {
        try {
            return OffsetDateTime.parse(label);
        } catch (RuntimeException e) {
            try {
                return java.time.LocalDate.parse(label.length() >= 10 ? label.substring(0, 10) : label)
                        .atStartOfDay()
                        .atOffset(java.time.ZoneOffset.UTC);
            } catch (RuntimeException ignored) {
                return OffsetDateTime.MIN;
            }
        }
    }

    /** «12 серп · 23 поз. · 1234.50 грн», with whatever parts the response actually had. */
    static String label(PastOrderSummary order) {
        List<String> parts = new ArrayList<>();
        OffsetDateTime date = parseDate(order.dateLabel());
        if (!date.equals(OffsetDateTime.MIN)) {
            parts.add(DATE_LABEL.format(date));
        } else if (order.dateLabel() != null && !order.dateLabel().isBlank()) {
            parts.add(order.dateLabel());
        } else if (order.orderId() != null) {
            parts.add("№" + order.orderId());
        }
        if (order.itemCount() > 0) {
            parts.add(order.itemCount() + " " + positions(order.itemCount()));
        }
        if (order.total() != null) {
            parts.add(order.total().setScale(2, RoundingMode.HALF_UP).toPlainString() + " грн");
        }
        if ("offline".equals(order.source())) {
            parts.add("магазин");
        }
        return parts.isEmpty() ? "Замовлення" : String.join(" · ", parts);
    }

    private static String positions(int count) {
        int lastTwo = count % 100;
        int last = count % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return "позицій";
        }
        if (last == 1) {
            return "позиція";
        }
        if (last >= 2 && last <= 4) {
            return "позиції";
        }
        return "позицій";
    }

    private static List<PastOrderSummary> ordersOf(ConversationState state) {
        Object raw = state.getContext().get(KEY_ORDERS);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(node -> MAPPER.convertValue(node, PastOrderSummary.class))
                .toList();
    }

    // convertValue to a raw Map rather than a TypeReference: an anonymous TypeReference subclass is a class in
    // this package, and ArchUnit requires every one of those to be named ...Service.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return MAPPER.convertValue(value, Map.class);
    }
}
