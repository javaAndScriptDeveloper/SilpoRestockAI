package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.exception.NoSilpoDeliveryAddressException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String TOOL_MY_ADDRESSES = "silpo_get_my_delivery_addresses";
    private static final String TOOL_DELIVERY_TYPES = "silpo_get_available_delivery_types";
    private static final String TOOL_LIST_BRANCHES = "silpo_list_branches";
    private static final String TOOL_CREATE_CART = "silpo_create_shopping_cart";

    /** {@code silpo_get_available_delivery_types} hands back a branch directly for these; the rest need resolving. */
    private static final Set<String> DELIVERY_TYPES_WITH_A_BRANCH_ALREADY = Set.of("DeliveryHome", "WideAssortDelivery", "B2B");

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

    /** Steps 1 and 2: which cart, and which branch it is bound to. Creates one first if this guest has none yet. */
    public CartContext getOrCreateCartContext(UUID userId) {
        JsonNode myCart = call(userId, TOOL_MY_CART, Map.of());
        String cartId = McpResponses.findString(myCart, McpResponses.CART_ID).orElseGet(() -> {
            boolean serverSaysNoCartYet = McpResponses.findNode(myCart, McpResponses.CART_EXISTS)
                    .map(node -> !node.asBoolean(true))
                    .orElse(false);
            if (serverSaysNoCartYet) {
                // Silpo's own answer, not a field-name mismatch: exists=false for a guest that has never had a
                // cart. silpo_create_shopping_cart is documented to create one from the guest's own saved
                // delivery address, resolving a branch and a time slot along the way.
                return createCart(userId);
            }
            // None of the key names in McpResponses.CART_ID matched — the live server disagrees with the
            // documented shape. Logging the raw answer is what turns this from a dead end into a one-line
            // fix: add whatever field name shows up here to CART_ID.
            log.error(
                    "silpo_get_my_shopping_cart answered but no field named {} was found. Raw response: {}",
                    String.join("/", McpResponses.CART_ID),
                    myCart);
            throw new CartBuildException("Silpo returned no cart id for user " + userId);
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
     * {@code silpo_create_shopping_cart}'s own documented workflow, for a guest {@code silpo_get_my_shopping_cart}
     * reports {@code exists=false} for: a saved address gives coordinates, coordinates resolve a delivery type and
     * branch, the branch's time slots give a window, and only then can a cart exist at all.
     *
     * <p>Scoped to home delivery and self-pickup — the two shapes a household grocery order actually takes. Nova
     * Poshta and the rest need settlement/office resolution nothing here has a use for; a guest offered only one of
     * those fails loudly instead of being handled by chance.
     */
    private String createCart(UUID userId) {
        JsonNode addressesResponse = call(userId, TOOL_MY_ADDRESSES, Map.of());
        JsonNode address = McpResponses.findArray(addressesResponse, McpResponses.ADDRESSES).stream()
                .findFirst()
                .orElseThrow(() -> {
                    log.error(
                            "user {} has no saved Silpo delivery address to create a cart from. Raw response: {}",
                            userId,
                            addressesResponse);
                    return new NoSilpoDeliveryAddressException(
                            "User " + userId + " has no Silpo cart and no saved delivery address to create one from");
                });
        BigDecimal latitude = requireNumber(address, McpResponses.LATITUDE, userId, "a saved address had no latitude");
        BigDecimal longitude =
                requireNumber(address, McpResponses.LONGITUDE, userId, "a saved address had no longitude");
        String addressType = McpResponses.findString(address, McpResponses.ADDRESS_TYPE).orElse("house");

        JsonNode deliveryTypesResponse =
                call(userId, TOOL_DELIVERY_TYPES, Map.of("latitude", latitude, "longitude", longitude));
        List<JsonNode> options = McpResponses.findArray(deliveryTypesResponse, McpResponses.DELIVERY_TYPE_OPTIONS);
        JsonNode chosen = options.stream()
                .filter(option -> McpResponses.findString(option, McpResponses.DELIVERY_TYPE)
                        .map(type -> DELIVERY_TYPES_WITH_A_BRANCH_ALREADY.contains(type) || "SelfPickup".equals(type))
                        .orElse(false))
                .findFirst()
                .orElseThrow(() -> {
                    log.error(
                            "no home-delivery or self-pickup option for user {} at {},{}. Raw response: {}",
                            userId,
                            latitude,
                            longitude,
                            deliveryTypesResponse);
                    return new CartBuildException(
                            "Silpo offered no home-delivery or self-pickup option for user " + userId);
                });
        String deliveryType = McpResponses.findString(chosen, McpResponses.DELIVERY_TYPE)
                .orElseThrow(() -> new CartBuildException("delivery type option had no deliveryType for user " + userId));
        String branchId = McpResponses.findString(chosen, McpResponses.BRANCH_ID)
                .orElseGet(() -> resolvePickupBranch(userId));

        JsonNode timeSlotsResponse =
                call(userId, TOOL_TIME_SLOTS, Map.of("branchId", branchId, "deliveryTypes", List.of(deliveryType)));
        JsonNode slot = McpResponses.findArray(timeSlotsResponse, McpResponses.TIME_SLOTS).stream()
                .filter(candidate -> McpResponses.findNode(candidate, McpResponses.SLOT_AVAILABLE)
                        .map(JsonNode::asBoolean)
                        .orElse(true))
                .findFirst()
                .orElseThrow(() -> {
                    log.error(
                            "no available time slot for branch {} to create a cart for user {}. Raw response: {}",
                            branchId,
                            userId,
                            timeSlotsResponse);
                    return new CartBuildException("Silpo offered no delivery time slot for branch " + branchId);
                });
        String start = McpResponses.findString(slot, McpResponses.SLOT_START)
                .orElseThrow(() -> new CartBuildException("chosen time slot had no start for user " + userId));
        String end = McpResponses.findString(slot, McpResponses.SLOT_END)
                .orElseThrow(() -> new CartBuildException("chosen time slot had no end for user " + userId));

        Map<String, Object> createArgs = new LinkedHashMap<>();
        createArgs.put("addressType", addressType);
        createArgs.put("latitude", latitude);
        createArgs.put("longitude", longitude);
        McpResponses.findString(address, McpResponses.CITY).ifPresent(v -> createArgs.put("city", v));
        McpResponses.findString(address, McpResponses.STREET).ifPresent(v -> createArgs.put("street", v));
        McpResponses.findString(address, McpResponses.HOUSE).ifPresent(v -> createArgs.put("house", v));
        McpResponses.findString(address, McpResponses.DISTRICT).ifPresent(v -> createArgs.put("district", v));
        createArgs.put("deliveryType", deliveryType);
        createArgs.put("branchId", branchId);
        createArgs.put("timeslot", Map.of("start", start, "end", end));

        JsonNode created = call(userId, TOOL_CREATE_CART, createArgs);
        String cartId = McpResponses.findString(created, McpResponses.CART_ID).orElseThrow(() -> {
            log.error("silpo_create_shopping_cart answered but no cart id for user {}. Raw response: {}", userId, created);
            return new CartBuildException("Silpo created no cart id for user " + userId);
        });
        log.info("MCP <- created cart {} for user {} at branch {}", cartId, userId, branchId);
        return cartId;
    }

    /** {@code SelfPickup} comes back with no branch of its own — the guest has to be resolved one from the list. */
    private String resolvePickupBranch(UUID userId) {
        JsonNode branchesResponse = call(userId, TOOL_LIST_BRANCHES, Map.of("hasPickup", true));
        return McpResponses.findArray(branchesResponse, McpResponses.BRANCHES).stream()
                .findFirst()
                .flatMap(branch -> McpResponses.findString(branch, McpResponses.BRANCH_ID))
                .orElseThrow(() -> {
                    log.error(
                            "silpo_list_branches(hasPickup=true) returned no pickup branch for user {}. Raw response: {}",
                            userId,
                            branchesResponse);
                    return new CartBuildException("Silpo offered no self-pickup branch for user " + userId);
                });
    }

    private BigDecimal requireNumber(JsonNode node, String[] keys, UUID userId, String problem) {
        return McpResponses.findNumber(node, keys).orElseThrow(() -> {
            log.error("{} for user {}. Raw response: {}", problem, userId, node);
            return new CartBuildException(problem + " for user " + userId);
        });
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
