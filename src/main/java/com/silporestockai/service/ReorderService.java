package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.DeltaOrder;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.ReplacementOption;
import com.silporestockai.model.ReplacementSuggestion;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.utils.McpResponses;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Builds the smallest cart that fixes the fridge.
 *
 * <p>The first order was a week of shopping. A reorder is a delta: what the last check-in said ran out, minus what
 * this household demonstrably never eats, with substitutes offered for whatever Silpo cannot supply.
 *
 * <p>The cart itself is {@link CartBuildingService#buildCart}, the same pipeline every other order takes — which
 * clears the cart first, chooses products, holds back a line that looks wrong, tops a small cart up over Silpo's
 * minimum, and reads the verified total back. A reorder used to compose those steps by hand and skipped the first
 * of them: it added to whatever the Silpo cart already held, and on the live account a three-line reorder
 * carried the eleven lines of a cancelled cheese-and-wine cart underneath it. Savings are Silpo's own figure for
 * the cart; the promotions tool answers with campaign codes, not products, and is not consulted here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReorderService {

    private static final String TOOL_REPLACEMENTS = "silpo_get_replacements";

    /** A branch with nothing in stock must not turn one reorder into forty tool calls. */
    private static final int MAX_REPLACEMENT_LOOKUPS = 10;

    private final InventoryTrendService inventoryTrendService;
    private final BaselineBasketRepository baselineBasketRepository;
    private final CartBuildingService cartBuildingService;
    private final SilpoMcpClient silpoMcpClient;

    /** The reorder cycle came round. */
    public DeltaOrder buildScheduledDeltaOrder(UUID userId) {
        return build(userId, OrderType.SCHEDULED_REORDER, null);
    }

    /** One item ran out before the cycle did. Same path, plus that item whether or not the check-in named it. */
    public DeltaOrder buildTriggeredDeltaOrder(UUID userId, String triggerItem) {
        return build(userId, OrderType.AD_HOC, triggerItem);
    }

    private DeltaOrder build(UUID userId, OrderType type, String triggerItem) {
        List<String> excluded = inventoryTrendService.getRemovalCandidates(userId);
        LinkedHashSet<String> needs = new LinkedHashSet<>();
        if (triggerItem != null && !triggerItem.isBlank()) {
            needs.add(triggerItem);
        }
        inventoryTrendService.getUpcomingNeeds(userId).stream()
                .filter(name -> excluded.stream().noneMatch(name::equalsIgnoreCase))
                .forEach(needs::add);

        if (needs.isEmpty()) {
            // Not a failure: a household that has everything is the system working.
            log.info("nothing to reorder for user {}", userId);
            return new DeltaOrder(
                    userId, type, triggerItem, null, null, List.of(), List.of(), BigDecimal.ZERO, excluded);
        }
        log.info("reordering {} items for user {}, excluding {}", needs.size(), userId, excluded);

        List<ShoppingListItem> items = withBaselineQuantities(userId, needs);
        CartContext context = cartBuildingService.getOrCreateCartContext(userId);
        CartSummary cart = cartBuildingService.buildCart(userId, items);
        if (cart.belowMinimumOrder()) {
            // A reorder is the household restocking its staples; more of its staples is the natural way over
            // Silpo's minimum, and there is always a baseline here — a reorder is measured against one.
            cart = cartBuildingService.topUp(userId, cart);
        }

        List<String> reordered = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String need : needs) {
            boolean unresolved = cart.unresolved().stream().anyMatch(name -> name.equalsIgnoreCase(need));
            boolean heldBack = cart.skippedLines().stream()
                    .anyMatch(line -> line.toLowerCase(Locale.ROOT).startsWith(need.toLowerCase(Locale.ROOT) + " — "));
            if (unresolved || heldBack) {
                missing.add(need);
            } else {
                reordered.add(need);
            }
        }

        List<ReplacementSuggestion> suggestions = replacementsFor(userId, context, missing);
        BigDecimal savings = cart.savings() == null ? BigDecimal.ZERO : cart.savings();
        log.info(
                "delta order for user {}: {} reordered, {} needing a decision, saving about {}",
                userId,
                reordered.size(),
                suggestions.size(),
                savings);
        return new DeltaOrder(userId, type, triggerItem, cart, context, reordered, suggestions, savings, excluded);
    }

    /**
     * How much of each item to buy, taken from the baseline.
     *
     * <p>The baseline is what this household confirmed buying last time, which makes it the only defensible default
     * for a restock. An item with no baseline line gets one unit.
     */
    private List<ShoppingListItem> withBaselineQuantities(UUID userId, LinkedHashSet<String> needs) {
        Map<String, BasketItem> baseline = new LinkedHashMap<>();
        baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(userId)
                .map(BaselineBasket::getItems)
                .orElseGet(List::of)
                .forEach(item -> {
                    if (item.name() != null) {
                        baseline.putIfAbsent(normalise(item.name()), item);
                    }
                });
        return needs.stream()
                .map(name -> {
                    BasketItem known = baseline.get(normalise(name));
                    return ShoppingListItem.builder()
                            .id(UUID.randomUUID())
                            .userId(userId)
                            .name(name)
                            .quantity(known == null || known.quantity() == null ? BigDecimal.ONE : known.quantity())
                            .unit(known == null ? null : known.unit())
                            .build();
                })
                .toList();
    }

    /** Asks Silpo what it would offer instead, for each item that did not make it into the cart. */
    private List<ReplacementSuggestion> replacementsFor(UUID userId, CartContext context, List<String> missing) {
        List<ReplacementSuggestion> suggestions = new ArrayList<>();
        for (String name : missing.stream().limit(MAX_REPLACEMENT_LOOKUPS).toList()) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("branchId", nullSafe(context.branchId()));
            arguments.put("name", name);

            JsonNode response = call(userId, TOOL_REPLACEMENTS, arguments);
            List<ReplacementOption> options = response == null
                    ? List.of()
                    : McpResponses.findArray(response, McpResponses.REPLACEMENTS).stream()
                            .map(node -> new ReplacementOption(
                                    McpResponses.findString(node, McpResponses.PRODUCT_ID)
                                            .orElse(null),
                                    McpResponses.findString(node, McpResponses.NAME)
                                            .orElse(null),
                                    McpResponses.findNumber(node, McpResponses.PRICE)
                                            .orElse(null)))
                            .filter(option -> option.productId() != null)
                            .toList();
            suggestions.add(new ReplacementSuggestion(name, options));
        }
        if (missing.size() > MAX_REPLACEMENT_LOOKUPS) {
            log.warn(
                    "{} items are missing for user {}; asked about the first {}",
                    missing.size(),
                    userId,
                    MAX_REPLACEMENT_LOOKUPS);
        }
        return suggestions;
    }

    /**
     * A failed replacement lookup is not a failed reorder.
     *
     * <p>Enrichment: without it the cart is still correct, only less clever. The cart calls themselves stay in
     * {@link CartBuildingService}, where a failure is fatal on purpose.
     */
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

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
