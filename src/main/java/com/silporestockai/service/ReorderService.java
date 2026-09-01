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
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.ReplacementOption;
import com.silporestockai.model.ReplacementSuggestion;
import com.silporestockai.model.ResolvedProduct;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.utils.McpResponses;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Builds the smallest cart that fixes the fridge.
 *
 * <p>The first order was a week of shopping. A reorder is a delta: what the last check-in said ran out, minus what
 * this household demonstrably never eats, with substitutes offered for whatever Silpo cannot supply and promotions
 * taken where they exist.
 *
 * <p>The MCP sequence is not reimplemented here. {@link CartBuildingService} owns it step by step, and this composes
 * those steps with two calls of its own in between.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReorderService {

    private static final String TOOL_PROMOTIONS = "silpo_get_promotions";
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
        Map<String, JsonNode> promotions = promotions(userId, context);

        List<ResolvedProduct> resolved = cartBuildingService.resolveProducts(userId, context, items);
        BigDecimal savings = BigDecimal.ZERO;
        List<ResolvedProduct> toAdd = new ArrayList<>();
        for (ResolvedProduct product : resolved) {
            JsonNode promo = promotions.get(normalise(product.requestedName()));
            toAdd.add(promo == null ? product : promoted(product, promo));
            savings = savings.add(savingOn(product, promo));
        }

        cartBuildingService.addProductsToCart(userId, context, toAdd);
        // No time slot: choosing one is task 15's job, and asking for one here would pick it twice.
        OfferedSlot slotChosenLater = null;
        CartSummary cart = cartBuildingService.getVerifiedCart(
                userId, context, slotChosenLater, cartBuildingService.unresolvedNames(items, resolved));

        // Matching on product id, not on name: the cart comes back with Silpo's own names, and "Молоко 2.5% 900мл"
        // is the same line as the "молоко" that was asked for only if the id says so.
        Map<String, String> addedIds = new LinkedHashMap<>();
        toAdd.forEach(product -> addedIds.putIfAbsent(normalise(product.requestedName()), product.productId()));
        List<String> cartProductIds = cart.items().stream()
                .map(BasketItem::silpoProductId)
                .filter(Objects::nonNull)
                .toList();

        List<String> reordered = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String need : needs) {
            // A name can fail to arrive two ways — no search hit, or a hit the cart did not end up containing.
            // Both are the same thing to the person reading the message, so both are asked about once.
            String productId = addedIds.get(normalise(need));
            if (productId != null && cartProductIds.contains(productId)) {
                reordered.add(need);
            } else {
                missing.add(need);
            }
        }

        List<ReplacementSuggestion> suggestions = replacementsFor(userId, context, missing, resolved);
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

    /** Active promotions at this branch, keyed by product name so a needed item can be looked up directly. */
    private Map<String, JsonNode> promotions(UUID userId, CartContext context) {
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        JsonNode response = call(userId, TOOL_PROMOTIONS, Map.of("branchId", nullSafe(context.branchId())));
        if (response == null) {
            return byName;
        }
        for (JsonNode promo : McpResponses.findArray(response, McpResponses.PROMOTIONS)) {
            McpResponses.findString(promo, McpResponses.NAME)
                    .ifPresent(name -> byName.putIfAbsent(normalise(name), promo));
        }
        log.info("MCP <- {} active promotions at branch {}", byName.size(), context.branchId());
        return byName;
    }

    /** The promoted variant of a product: same line, Silpo's promo product id. */
    private static ResolvedProduct promoted(ResolvedProduct product, JsonNode promo) {
        return McpResponses.findString(promo, McpResponses.PRODUCT_ID)
                .map(productId -> new ResolvedProduct(
                        product.requestedName(),
                        productId,
                        product.companyId(),
                        product.branchId(),
                        product.quantity(),
                        product.unit()))
                .orElse(product);
    }

    /**
     * Rough money saved on one line.
     *
     * <p>Deliberately rough: it is what Silpo says the item used to cost against what it costs now, times the amount
     * being bought. A number to show someone, not an accounting figure.
     */
    private static BigDecimal savingOn(ResolvedProduct product, JsonNode promo) {
        if (promo == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal now = McpResponses.findNumber(promo, McpResponses.PRICE).orElse(null);
        BigDecimal before =
                McpResponses.findNumber(promo, McpResponses.OLD_PRICE).orElse(null);
        if (now == null || before == null || before.compareTo(now) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal quantity = product.quantity() == null ? BigDecimal.ONE : product.quantity();
        return before.subtract(now).multiply(quantity);
    }

    /** Asks Silpo what it would offer instead, for each item that did not make it into the cart. */
    private List<ReplacementSuggestion> replacementsFor(
            UUID userId, CartContext context, List<String> missing, List<ResolvedProduct> resolved) {
        List<ReplacementSuggestion> suggestions = new ArrayList<>();
        for (String name : missing.stream().limit(MAX_REPLACEMENT_LOOKUPS).toList()) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("branchId", nullSafe(context.branchId()));
            arguments.put("name", name);
            // A product id when the search found one and the cart still refused it; the name alone otherwise.
            resolved.stream()
                    .filter(product -> product.requestedName().equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresent(product -> arguments.put("productId", product.productId()));

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
     * A failed promotion or replacement lookup is not a failed reorder.
     *
     * <p>Both calls are enrichment: without them the cart is still correct, only less clever. The cart calls
     * themselves stay in {@link CartBuildingService}, where a failure is fatal on purpose.
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
