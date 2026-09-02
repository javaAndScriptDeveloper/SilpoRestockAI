package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.ResolvedProduct;
import com.silporestockai.utils.McpResponses;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Drives the documented Silpo cart sequence: cart, branch, slots, products, add, verify.
 *
 * <p>Each documented step is its own method. That is what makes the sequence legible in a log — and this log is a
 * deliverable: the hackathon asks for evidence that a real agent made real tool calls, and a console recording of it
 * is that evidence. The Silpo client logs at DEBUG, which is right for a library and too quiet for this.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartBuildingService {

    private static final String TOOL_MY_CART = "silpo_get_my_shopping_cart";
    private static final String TOOL_CART_BY_ID = "silpo_get_shopping_cart_by_id";
    private static final String TOOL_TIME_SLOTS = "silpo_get_time_slots";
    private static final String TOOL_FIND_PRODUCTS = "silpo_find_products_batch";
    private static final String TOOL_ADD_PRODUCTS = "silpo_add_or_update_cart_products";

    /** Slot times without a zone are the household's, and the household is in Kyiv. */
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    /** The documented per-call limit of {@code silpo_find_products_batch}. */
    private static final int SEARCH_BATCH_SIZE = 30;

    private final SilpoMcpClient silpoMcpClient;

    /** Steps 1 to 6, in the documented order. Unresolved items are reported, not fatal. */
    public CartSummary buildCart(UUID userId, List<ShoppingListItem> items) {
        CartContext context = getOrCreateCartContext(userId);
        OfferedSlot deliverySlot = firstDeliverableSlot(userId, context);
        List<ResolvedProduct> resolved = resolveProducts(userId, context, items);
        List<String> unresolved = unresolvedNames(items, resolved);
        if (!unresolved.isEmpty()) {
            log.info("Silpo matched no product for {} of {} items: {}", unresolved.size(), items.size(), unresolved);
        }
        addProductsToCart(userId, context, resolved);
        return getVerifiedCart(userId, context, deliverySlot, unresolved);
    }

    /**
     * Names Silpo matched nothing for.
     *
     * <p>Public because the reorder in task 14 composes these steps itself and has to ask the same question the same
     * way; a second copy of this stream is exactly how two answers start disagreeing.
     */
    public List<String> unresolvedNames(List<ShoppingListItem> items, List<ResolvedProduct> resolved) {
        return items.stream()
                .map(ShoppingListItem::getName)
                .filter(name -> resolved.stream()
                        .noneMatch(product -> product.requestedName().equals(name)))
                .toList();
    }

    /** Steps 1 and 2: which cart, and which branch it is bound to. */
    public CartContext getOrCreateCartContext(UUID userId) {
        JsonNode myCart = call(userId, TOOL_MY_CART, Map.of());
        String cartId = McpResponses.findString(myCart, McpResponses.CART_ID).orElseThrow(() -> {
            // None of the key names in McpResponses.CART_ID matched — the live server disagrees with the
            // documented shape. Logging the raw answer is what turns this from a dead end into a one-line
            // fix: add whatever field name shows up here to CART_ID.
            log.error(
                    "silpo_get_my_shopping_cart answered but no field named {} was found. Raw response: {}",
                    String.join("/", McpResponses.CART_ID),
                    myCart);
            return new CartBuildException("Silpo returned no cart id for user " + userId);
        });

        JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("cartId", cartId));
        CartContext context = new CartContext(
                cartId,
                McpResponses.findString(cart, McpResponses.BRANCH_ID).orElse(null),
                McpResponses.findString(cart, McpResponses.COMPANY_ID).orElse(null),
                McpResponses.findString(cart, McpResponses.DELIVERY_TYPE).orElse(null),
                McpResponses.findString(cart, McpResponses.TIMESLOT).orElse(null));
        log.info(
                "MCP <- cart {} branch {} company {} delivery {}",
                context.cartId(),
                context.branchId(),
                context.companyId(),
                context.deliveryType());
        return context;
    }

    /**
     * Step 3, and the one failure that is fatal. Adding products to a cart nobody can deliver moves the failure to
     * checkout, where it is someone else's problem and nobody's log line.
     */
    public String validateTimeSlot(UUID userId, CartContext context) {
        return firstDeliverableSlot(userId, context).id();
    }

    /** The slot itself, not just its id: a calendar event needs the instant the window starts. */
    public OfferedSlot firstDeliverableSlot(UUID userId, CartContext context) {
        List<OfferedSlot> offered = offeredTimeSlots(userId, context);
        if (offered.isEmpty()) {
            throw new CartBuildException("Silpo offered no delivery time slot for branch " + context.branchId());
        }
        OfferedSlot slot = offered.getFirst();
        log.info("MCP <- {} time slots, taking {}", offered.size(), slot.id());
        return slot;
    }

    /**
     * Every slot on offer, for a caller that has to let somebody choose.
     *
     * <p>Start times are read leniently, like every other MCP field: a slot whose date format defeats parsing keeps a
     * null {@code startsAt} rather than failing the call, and simply never matches a household's usual day.
     */
    public List<OfferedSlot> offeredTimeSlots(UUID userId, CartContext context) {
        JsonNode slots = call(
                userId,
                TOOL_TIME_SLOTS,
                Map.of("branchId", nullSafe(context.branchId()), "deliveryType", nullSafe(context.deliveryType())));
        List<OfferedSlot> offered = new ArrayList<>();
        for (JsonNode slot : McpResponses.findArray(slots, McpResponses.TIME_SLOTS)) {
            String id = McpResponses.findString(slot, McpResponses.SLOT_ID).orElse(null);
            if (id == null) {
                log.debug("ignoring a time slot with no identifier");
                continue;
            }
            String start =
                    McpResponses.findString(slot, McpResponses.SLOT_START).orElse(null);
            offered.add(new OfferedSlot(id, start == null ? id : start, parseStart(start)));
        }
        return offered;
    }

    /** Instant, local date-time, or plain date — in that order. Anything else is left unparsed, not guessed at. */
    private static Instant parseStart(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value).atZone(KYIV).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(value).atStartOfDay(KYIV).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Step 4. Chunked at the documented batch limit; an unmatched item is normal, not an error. */
    public List<ResolvedProduct> resolveProducts(UUID userId, CartContext context, List<ShoppingListItem> items) {
        List<ResolvedProduct> resolved = new ArrayList<>();
        for (int start = 0; start < items.size(); start += SEARCH_BATCH_SIZE) {
            List<ShoppingListItem> chunk = items.subList(start, Math.min(items.size(), start + SEARCH_BATCH_SIZE));
            JsonNode found = call(
                    userId,
                    TOOL_FIND_PRODUCTS,
                    Map.of(
                            "branchId",
                            nullSafe(context.branchId()),
                            "items",
                            chunk.stream()
                                    .map(item -> Map.of("name", item.getName(), "quantity", quantityOf(item)))
                                    .toList()));
            for (JsonNode product : McpResponses.findArray(found, McpResponses.PRODUCTS)) {
                String name =
                        McpResponses.findString(product, McpResponses.NAME).orElse(null);
                String productId = McpResponses.findString(product, McpResponses.PRODUCT_ID)
                        .orElse(null);
                if (name == null || productId == null) {
                    continue;
                }
                chunk.stream()
                        .filter(item -> item.getName().equalsIgnoreCase(name))
                        .findFirst()
                        .ifPresent(item -> resolved.add(new ResolvedProduct(
                                item.getName(),
                                productId,
                                McpResponses.findString(product, McpResponses.COMPANY_ID)
                                        .orElse(context.companyId()),
                                McpResponses.findString(product, McpResponses.BRANCH_ID)
                                        .orElse(context.branchId()),
                                quantityOf(item),
                                item.getUnit())));
            }
        }
        log.info("MCP <- resolved {} of {} shopping list lines", resolved.size(), items.size());
        return resolved;
    }

    /** Step 5. Adds and updates our own lines; whatever the guest already had stays untouched. */
    public void addProductsToCart(UUID userId, CartContext context, List<ResolvedProduct> products) {
        if (products.isEmpty()) {
            log.info("nothing resolved, so nothing to add to cart {}", context.cartId());
            return;
        }
        call(
                userId,
                TOOL_ADD_PRODUCTS,
                Map.of(
                        "cartId",
                        context.cartId(),
                        "products",
                        products.stream()
                                .map(product -> Map.of(
                                        "productId", product.productId(),
                                        "companyId", nullSafe(product.companyId()),
                                        "branchId", nullSafe(product.branchId()),
                                        "quantity", product.quantity()))
                                .toList()));
    }

    /** Step 6: read the cart back rather than trusting the write. */
    public CartSummary getVerifiedCart(
            UUID userId, CartContext context, OfferedSlot deliverySlot, List<String> unresolved) {
        JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("cartId", context.cartId()));

        List<BasketItem> items = McpResponses.findArray(cart, McpResponses.ITEMS).stream()
                .map(node -> new BasketItem(
                        McpResponses.findString(node, McpResponses.PRODUCT_ID).orElse(null),
                        McpResponses.findString(node, McpResponses.NAME).orElse(null),
                        McpResponses.findString(node, McpResponses.UNIT).orElse(null),
                        McpResponses.findNumber(node, McpResponses.QUANTITY).orElse(null),
                        McpResponses.findNumber(node, McpResponses.PRICE).orElse(null)))
                .toList();

        JsonNode loyalty = McpResponses.findNode(cart, McpResponses.LOYALTY).orElse(null);
        BigDecimal bonusAvailable = loyalty == null
                ? BigDecimal.ZERO
                : McpResponses.findNumber(loyalty, McpResponses.BONUS_AVAILABLE).orElse(BigDecimal.ZERO);
        boolean enabled = loyalty != null
                && McpResponses.findNode(loyalty, McpResponses.LOYALTY_ENABLED)
                        .map(JsonNode::asBoolean)
                        .orElse(false);
        boolean requested = loyalty != null
                && McpResponses.findNumber(loyalty, McpResponses.BONUS_REQUESTED)
                        .isPresent();
        boolean bonusDecisionPending = enabled && !requested && bonusAvailable.signum() > 0;

        CartSummary summary = new CartSummary(
                context.cartId(),
                deliverySlot == null ? null : deliverySlot.id(),
                deliverySlot == null ? null : deliverySlot.startsAt(),
                items,
                McpResponses.findNumber(cart, McpResponses.TOTAL).orElse(BigDecimal.ZERO),
                McpResponses.findArray(cart, McpResponses.VALIDATIONS).stream()
                        .map(JsonNode::asText)
                        .toList(),
                bonusAvailable,
                bonusDecisionPending,
                McpResponses.findString(cart, McpResponses.CHECKOUT_WEB).orElse(null),
                McpResponses.findString(cart, McpResponses.CHECKOUT_MOBILE).orElse(null),
                unresolved);
        log.info(
                "MCP <- cart {} verified: {} items, total {}, bonuses available {}, unresolved {}",
                summary.cartId(),
                summary.items().size(),
                summary.total(),
                summary.bonusAvailable(),
                summary.unresolved().size());
        return summary;
    }

    private JsonNode call(UUID userId, String tool, Map<String, Object> arguments) {
        log.info("MCP -> {} {}", tool, arguments);
        McpToolResponse response = silpoMcpClient.callTool(tool, arguments, userId);
        if (response.isError()) {
            throw new CartBuildException("Silpo tool %s reported an error".formatted(tool));
        }
        return McpResponses.tree(response);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static BigDecimal quantityOf(ShoppingListItem item) {
        return item.getQuantity() == null ? BigDecimal.ONE : item.getQuantity();
    }
}
