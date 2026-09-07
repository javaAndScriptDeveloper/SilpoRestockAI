package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.User;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.OrderHistory;
import com.silporestockai.model.PastOrderLine;
import com.silporestockai.model.PastOrderSummary;
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
 * The one place that reads the household's Silpo order history. Both history tools are read-only, so
 * everything here is a pull on request: the MCP server has no push, no webhook and no subscription.
 *
 * <p>Task 35 (seed a list from a past order) and task 56 («де моє замовлення») are two questions about the
 * same two tool calls, and a second caller would mean two response shapes to keep in step with a server
 * whose payloads we cannot see from here. {@link PastOrderSeedService} and the {@code WHERE_IS_MY_ORDER}
 * intent both come through this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderHistoryService {

    /** Both history tools, each tolerated on its own — an in-store shopper may have no online orders at all. */
    private static final List<String> ORDER_TOOLS =
            List.of("silpo_get_my_online_orders", "silpo_get_my_offline_orders");

    private static final int MAX_RECENT = 5;

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("d MMM", new Locale("uk"));

    private final SilpoMcpClient silpoMcpClient;
    private final CartBuildingService cartBuildingService;
    private final SilpoAuthService silpoAuthService;
    private final TelegramOutboundService telegramOutboundService;

    /**
     * «Де моє замовлення» (task 56) and the «📦 Замовлення» button (task 57), one read of the history each.
     *
     * <p>The chat answer is the newest order alone, because that is the question that was asked; the button
     * is a management view like «Список», so it also lists what came before. Both say only what Silpo
     * returned: an order with no status in the response gets no status line rather than a guessed one, and a
     * history that could not be read is said to be unreadable rather than empty.
     *
     * @param detailed whether to add the earlier orders and the "this is a pull, not a subscription" note
     */
    public void showStatus(User user, boolean detailed) {
        long chatId = user.getTelegramChatId();
        if (!silpoAuthService.isConnected(user.getId())) {
            telegramOutboundService.sendMessage(
                    chatId, "Спершу під'єднай акаунт «Сільпо» — без нього я не бачу твоїх замовлень.");
            return;
        }
        OrderHistory history = read(user.getId());
        if (!history.reachable()) {
            telegramOutboundService.sendMessage(
                    chatId, "Не зміг дістати статус із «Сільпо» — спробуй ще раз за кілька хвилин.");
            log.info("neither order-history tool answered for user {}", user.getId());
            return;
        }
        if (history.isEmpty()) {
            telegramOutboundService.sendMessage(
                    chatId, "Не бачу замовлень в акаунті «Сільпо» — ні активних, ні минулих.");
            return;
        }
        telegramOutboundService.sendMessage(chatId, statusMessage(history, detailed));
    }

    private static String statusMessage(OrderHistory history, boolean detailed) {
        List<PastOrderSummary> orders = history.orders();
        PastOrderSummary newest = orders.getFirst();
        StringBuilder message = new StringBuilder();
        if (detailed) {
            message.append("📦 Твої замовлення в «Сільпо»\n\nОстаннє: ");
        } else {
            message.append("Останнє замовлення: ");
        }
        message.append(label(newest));
        if (showable(newest.status())) {
            message.append("\nСтатус: ").append(humanStatus(newest.status()));
        }
        if (newest.deliveryLabel() != null && !newest.deliveryLabel().isBlank()) {
            message.append("\nДоставка: ").append(newest.deliveryLabel());
        }
        if (!detailed) {
            return message.toString();
        }
        if (orders.size() > 1) {
            message.append("\n\nРаніше:");
            for (PastOrderSummary order : orders.subList(1, orders.size())) {
                message.append("\n• ").append(label(order));
                if (showable(order.status())) {
                    message.append(" — ").append(humanStatus(order.status()));
                }
            }
        }
        // Said out loud because it is the honest shape of the integration: Silpo's MCP server has no push and
        // no webhook, so this is a pull on request. Promising notifications we cannot send would be worse.
        message.append("\n\nСтатус тягну з «Сільпо» на запит — сповіщення про зміну не приходять.");
        return message.toString();
    }

    /** Whether a status is worth showing at all: a bare status code says nothing to the person reading it. */
    private static boolean showable(String status) {
        return status != null && !status.isBlank() && !status.strip().chars().allMatch(Character::isDigit);
    }

    /**
     * Silpo's own status string, in Ukrainian where we have seen the wording and verbatim where we have not.
     * A status we do not recognise is shown as it came: inventing a translation for a state nobody here has
     * observed is exactly the kind of confident guess this app must not make about someone's groceries.
     */
    private static String humanStatus(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "new", "created", "accepted" -> "Прийнято";
            case "paid" -> "Оплачено";
            case "processing", "inprogress", "in_progress", "collecting", "picking" -> "Готується";
            case "shipped", "delivering", "intransit", "in_transit", "ondelivery" -> "В дорозі";
            case "delivered", "completed", "done", "finished" -> "Доставлено";
            case "cancelled", "canceled", "rejected" -> "Скасовано";
            default -> raw.trim();
        };
    }

    /**
     * One read of the history: recent orders across both tools, newest first, at most {@value #MAX_RECENT},
     * plus whether anything answered at all.
     *
     * <p>A tool that errors or throws is skipped rather than fatal — a 403 means this guest has not granted
     * that one, and the other may still answer. Reachability rides along on the same two calls rather than
     * being a second probe: every caller asks once per interaction, and a status check that hit Silpo twice
     * for one message would be the duplicate this class exists to prevent.
     */
    public OrderHistory read(UUID userId) {
        List<PastOrderSummary> orders = new ArrayList<>();
        boolean reachable = false;
        for (String tool : ORDER_TOOLS) {
            try {
                McpToolResponse response = silpoMcpClient.callTool(tool, argumentsFor(tool, userId), userId);
                if (response.isError()) {
                    log.info("Silpo tool {} reported an error for user {}; skipping it", tool, userId);
                    continue;
                }
                reachable = true;
                orders.addAll(parse(McpResponses.tree(response), tool.contains("offline") ? "offline" : "online"));
            } catch (RuntimeException e) {
                // A 403 means this guest has not granted the tool; the other one may still answer.
                log.info("Silpo tool {} unavailable for user {}: {}", tool, userId, e.getMessage());
            }
        }
        // Newest first when dates parse; otherwise Silpo's own order, which is newest-first in practice.
        orders.sort((a, b) -> parseDate(b.dateLabel()).compareTo(parseDate(a.dateLabel())));
        return new OrderHistory(orders.size() > MAX_RECENT ? orders.subList(0, MAX_RECENT) : orders, reachable);
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
                    McpResponses.findString(node, McpResponses.ORDER_STATUS).orElse(null),
                    deliveryLabel(node),
                    lines));
        }
        return orders;
    }

    /**
     * The promised delivery time as text: «7 вересня, 10:00–12:00» when the response carried a slot with two
     * ends, one moment when it carried one, and null when it carried neither. Nothing is inferred from the
     * order date — a person asking «коли приїде» must not be answered with when they ordered.
     */
    private static String deliveryLabel(JsonNode node) {
        JsonNode slot = McpResponses.findNode(node, McpResponses.DELIVERY_SLOT).orElse(null);
        if (slot == null) {
            return null;
        }
        OffsetDateTime from = slot.isValueNode()
                ? parseMoment(slot.asText())
                : McpResponses.findString(slot, McpResponses.DELIVERY_AT)
                        .map(OrderHistoryService::parseMoment)
                        .orElse(null);
        OffsetDateTime to = slot.isValueNode()
                ? null
                : McpResponses.findString(slot, McpResponses.SLOT_END)
                        .map(OrderHistoryService::parseMoment)
                        .orElse(null);
        if (from == null) {
            // A slot we could not parse is still worth showing verbatim rather than dropping on the floor.
            String raw = slot.isValueNode() ? slot.asText() : null;
            return raw == null || raw.isBlank() ? null : raw;
        }
        // The tool documents its timestamps as UTC and says to convert before displaying: a delivery at
        // 09:00+00:00 is noon in Kyiv, and showing 09:00 would be a wrong answer to "коли приїде".
        java.time.ZonedDateTime localFrom = from.atZoneSameInstant(KYIV);
        String day = DAY_LABEL.format(localFrom);
        String start = TIME_LABEL.format(localFrom);
        return to == null
                ? day + ", " + start
                : day + ", " + start + "–" + TIME_LABEL.format(to.atZoneSameInstant(KYIV));
    }

    private static final java.time.ZoneId KYIV = java.time.ZoneId.of("Europe/Kyiv");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("d MMMM", new Locale("uk"));
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

    private static OffsetDateTime parseMoment(String text) {
        try {
            return OffsetDateTime.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
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
    public static String label(PastOrderSummary order) {
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

    public static String positions(int count) {
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
}
