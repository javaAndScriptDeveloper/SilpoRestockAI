package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.OrderType;
import com.silporestockai.service.telegram.TelegramOutboundService;
import com.silporestockai.utils.McpResponses;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "Закажи що-небудь смачне на вечір п'ятниці зі знижками" — a one-off request outside the weekly cycle.
 *
 * <p>Not a second ordering pipeline: building a cart is task 09's job and confirming one is task 10's. The only thing
 * different here is what gets searched for — a small, snack/treat cart made entirely of items that are actually on
 * promotion right now, same spirit as {@link BlackoutModeService}. Classifying free text into a call to {@link
 * #buildAdHocOrder} is task 31's job; this assumes the request already arrived pre-classified.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdHocOrderService {

    private static final String TOOL_PROMOTIONS = "silpo_get_promotions";

    /** A cap on how many promoted snacks go in one ad-hoc cart — this is a treat, not a restock. */
    private static final int MAX_ITEMS = 12;

    /**
     * Substrings a promoted product's name is checked against. Silpo's catalogue carries no "is a snack" flag, so
     * this is written down rather than inferred — the same reasoning {@link BlackoutModeService} uses for its own
     * curated list.
     */
    private static final List<String> SNACK_KEYWORDS = List.of(
            "чіпси",
            "чипси",
            "сухарики",
            "попкорн",
            "цукерк",
            "шоколад",
            "печиво",
            "снек",
            "морозиво",
            "горішк",
            "напій",
            "напої",
            "сік",
            "лимонад",
            "вафл",
            "крекер",
            "пастил",
            "зефір",
            "цукерки");

    private final CartBuildingService cartBuildingService;
    private final CartConfirmationService cartConfirmationService;
    private final TelegramOutboundService telegramOutboundService;
    private final SilpoMcpClient silpoMcpClient;

    /**
     * Builds a small cart from whatever snacks/treats are currently on promotion, and hands it to the usual
     * confirmation flow. {@code targetDateTime} is accepted for task 31's forward contract but does not bias
     * delivery-slot selection today — no acceptance criterion asks for that yet.
     */
    public void buildAdHocOrder(User user, String themeDescription, Instant targetDateTime) {
        UUID userId = user.getId();
        long chatId = user.getTelegramChatId();
        CartContext context = cartBuildingService.getOrCreateCartContext(userId);
        List<JsonNode> promos = discountedSnacks(userId, context);
        if (promos.isEmpty()) {
            log.info("no discounted snacks found for user {} at branch {}", userId, context.branchId());
            telegramOutboundService.sendMessage(chatId, "Зараз немає активних знижок на снеки — спробую пізніше.");
            return;
        }

        List<ShoppingListItem> items = new ArrayList<>();
        BigDecimal totalSavings = BigDecimal.ZERO;
        for (JsonNode promo : promos) {
            items.add(itemOf(userId, promo));
            totalSavings = totalSavings.add(savingOn(promo));
        }

        telegramOutboundService.sendMessage(
                chatId,
                "Ось що знайшов на «%s» зі знижками (економія ~%s грн):"
                        .formatted(
                                themeDescription,
                                totalSavings.stripTrailingZeros().toPlainString()));
        cartConfirmationService.present(user, items, OrderType.AD_HOC);
        log.info("presented an ad-hoc snack cart of {} items to user {}", items.size(), userId);
    }

    /** Active promotions at this branch, filtered down to the curated snack/treat keyword list. */
    private List<JsonNode> discountedSnacks(UUID userId, CartContext context) {
        List<JsonNode> matches = new ArrayList<>();
        JsonNode response = call(userId, TOOL_PROMOTIONS, Map.of("branchId", nullSafe(context.branchId())));
        if (response == null) {
            return matches;
        }
        for (JsonNode promo : McpResponses.findArray(response, McpResponses.PROMOTIONS)) {
            String name = McpResponses.findString(promo, McpResponses.NAME).orElse("");
            if (isSnack(name) && savingOn(promo).signum() > 0) {
                matches.add(promo);
            }
            if (matches.size() >= MAX_ITEMS) {
                break;
            }
        }
        log.info("MCP <- {} discounted snacks at branch {}", matches.size(), context.branchId());
        return matches;
    }

    private static boolean isSnack(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SNACK_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /** Silpo's own product id and current price, pre-resolved — skips a redundant search (same as task 22's fix). */
    private static ShoppingListItem itemOf(UUID userId, JsonNode promo) {
        String name = McpResponses.findString(promo, McpResponses.NAME).orElse("");
        String productId =
                McpResponses.findString(promo, McpResponses.PRODUCT_ID).orElse(null);
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .name(name)
                .quantity(BigDecimal.ONE)
                .unit("шт")
                .silpoProductId(productId)
                .build();
    }

    /** Rough money saved on one line — what Silpo says it used to cost against what it costs now. */
    private static BigDecimal savingOn(JsonNode promo) {
        BigDecimal now = McpResponses.findNumber(promo, McpResponses.PRICE).orElse(null);
        BigDecimal before =
                McpResponses.findNumber(promo, McpResponses.OLD_PRICE).orElse(null);
        if (now == null || before == null || before.compareTo(now) <= 0) {
            return BigDecimal.ZERO;
        }
        return before.subtract(now);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private JsonNode call(UUID userId, String tool, Map<String, Object> arguments) {
        log.info("MCP -> {} {}", tool, arguments);
        try {
            McpToolResponse response = silpoMcpClient.callTool(tool, arguments, userId);
            if (response.isError()) {
                log.warn("Silpo tool {} reported an error; continuing without it", tool);
                return null;
            }
            return McpResponses.tree(response);
        } catch (RuntimeException e) {
            log.warn("Silpo tool {} failed: {}; continuing without it", tool, e.getMessage());
            return null;
        }
    }
}
