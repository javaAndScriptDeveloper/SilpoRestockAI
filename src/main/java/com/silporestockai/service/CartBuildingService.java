package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.exception.NoSilpoDeliveryAddressException;
import com.silporestockai.exception.SilpoMcpException;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.ProductCandidate;
import com.silporestockai.model.ProductMatchRequest;
import com.silporestockai.model.ProductResolution;
import com.silporestockai.model.ResolvedProduct;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.utils.McpResponses;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Drives the documented Silpo cart sequence: cart, branch, clear, slots, products, add, verify.
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
    private static final String TOOL_CLEAR_CART = "silpo_clear_shopping_cart";
    private static final String TOOL_MY_ADDRESSES = "silpo_get_my_delivery_addresses";
    private static final String TOOL_DELIVERY_TYPES = "silpo_get_available_delivery_types";
    private static final String TOOL_LIST_BRANCHES = "silpo_list_branches";
    private static final String TOOL_CREATE_CART = "silpo_create_shopping_cart";

    /** {@code silpo_get_available_delivery_types} hands back a branch directly for these; the rest need resolving. */
    private static final Set<String> DELIVERY_TYPES_WITH_A_BRANCH_ALREADY =
            Set.of("DeliveryHome", "WideAssortDelivery", "B2B");

    /** Slot times without a zone are the household's, and the household is in Kyiv. */
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    /** The documented per-call limit of {@code silpo_find_products_batch}. */
    private static final int SEARCH_BATCH_SIZE = 30;

    private final SilpoMcpClient silpoMcpClient;
    private final UserProfileRepository userProfileRepository;
    private final PartnerPromotionService partnerPromotionService;
    private final ProductMatchingService productMatchingService;
    private final BaselineBasketRepository baselineBasketRepository;

    /**
     * Candidates Silpo says it can actually sell right now. {@code available: false} is not a judgement call, so it
     * is settled here rather than spent on the model's attention — and a product the branch cannot sell is not a
     * better answer than none.
     */
    private static List<JsonNode> availableOnly(List<JsonNode> candidates) {
        return candidates.stream()
                .filter(candidate -> McpResponses.findNode(candidate, McpResponses.AVAILABLE)
                        .map(node -> node.asBoolean(true))
                        .orElse(true))
                .toList();
    }

    /**
     * Name fragments that mark a catalog hit as not groceries for people at all: pet food, baby food, toys,
     * kitchenware. A search for «Яловичина» returns cat food and dog treats, «Спагеті» a serving spoon, «Банан»
     * an anti-stress toy, and «Вівсянка» a Gerber infant porridge — which the fast matcher picked once, at ₴388.
     *
     * <p>Not a substitute for the matcher's judgement (that list would never end) — a floor under it, for the
     * classes where no judgement is needed. A line that itself asks for one of these («корм для кота») keeps
     * them.
     */
    private static final List<String> NOT_GROCERIES_FOR_PEOPLE = List.of(
            "корм для",
            "для котів",
            "для кішок",
            "для собак",
            "ласощі для",
            "іграшк",
            "антистрес",
            "дитяче харчування",
            "gerber",
            "nutrilon",
            "hipp",
            "milupa",
            "nestlé nan",
            "nestle nan",
            "nutricia",
            "ложка",
            "виделка",
            "лопатка",
            "терка",
            "каструл",
            "сковорід");

    /** {@link #availableOnly}, minus the hits that are obviously not food for people — see the list above. */
    private static List<JsonNode> plausibleFor(String requestedName, List<JsonNode> candidates) {
        String asked = requestedName == null ? "" : requestedName.toLowerCase(Locale.ROOT);
        List<String> markers = NOT_GROCERIES_FOR_PEOPLE.stream()
                .filter(marker -> !asked.contains(marker))
                .toList();
        List<JsonNode> kept = new ArrayList<>();
        for (JsonNode candidate : availableOnly(candidates)) {
            String name = McpResponses.findString(candidate, McpResponses.NAME)
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
            if (markers.stream().anyMatch(name::contains)) {
                log.debug("dropping «{}» as a candidate for «{}»: not groceries for people", name, requestedName);
                continue;
            }
            kept.add(candidate);
        }
        return kept;
    }

    /** The shelf tags of each line's candidates, in Silpo's own order, as the matcher wants them. */
    private static List<ProductMatchRequest> matchRequests(
            List<ShoppingListItem> items, List<List<JsonNode>> candidatesFor) {
        List<ProductMatchRequest> requests = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ShoppingListItem item = items.get(i);
            List<ProductCandidate> candidates = candidatesFor.get(i).stream()
                    .map(node -> new ProductCandidate(
                            McpResponses.findString(node, McpResponses.NAME).orElse(""),
                            McpResponses.findNumber(node, McpResponses.PRICE).orElse(null),
                            McpResponses.findString(node, McpResponses.DISPLAY_RATIO)
                                    .orElse(null),
                            McpResponses.findNode(node, McpResponses.WEIGHTED)
                                    .map(weighted -> weighted.asBoolean(false))
                                    .orElse(false),
                            McpResponses.findNumber(node, McpResponses.STOCK).orElse(null)))
                    .toList();
            requests.add(new ProductMatchRequest(item.getName(), quantityOf(item), item.getUnit(), candidates));
        }
        return requests;
    }

    /** Steps 1 to 6, in the documented order. Unresolved items are reported, not fatal. */
    public CartSummary buildCart(UUID userId, List<ShoppingListItem> items) {
        CartContext context = getOrCreateCartContext(userId);
        clearCart(userId, context);
        OfferedSlot deliverySlot = firstDeliverableSlot(userId, context);
        // The cart's own stored timeslot can be stale (or Silpo-rejected outright — see the cart's own
        // validations) by the time somebody actually confirms it. Searching against that stale window, rather
        // than the fresh slot just picked, silently returned zero matches for every real product name.
        context = new CartContext(
                context.cartId(),
                context.branchId(),
                context.companyId(),
                context.deliveryType(),
                deliverySlot.label(),
                deliverySlot.end());
        ProductResolution resolution = resolve(userId, context, items);
        List<ResolvedProduct> resolved = resolution.resolved();
        List<String> skippedNames = resolution.skipped().stream()
                .map(line -> line.substring(0, line.indexOf(" — ")))
                .toList();
        List<String> unresolved = unresolvedNames(items, resolved).stream()
                .filter(name -> !skippedNames.contains(name))
                .toList();
        if (!unresolved.isEmpty()) {
            log.info("Silpo matched no product for {} of {} items: {}", unresolved.size(), items.size(), unresolved);
        }
        addProductsToCart(userId, context, resolved);
        List<String> promoted = resolved.stream()
                .filter(ResolvedProduct::promoted)
                .map(ResolvedProduct::productId)
                .distinct()
                .toList();
        try {
            return getVerifiedCart(userId, context, deliverySlot, unresolved, promoted, resolution.skipped());
        } catch (CartBuildException refused) {
            if (!refused.belowMinimumOrder()) {
                throw refused;
            }
            List<ResolvedProduct> topUp =
                    topUpFromBaseline(userId, context, resolved, refused.getTotal(), refused.getMinimumOrder());
            if (topUp.isEmpty()) {
                throw refused;
            }
            addProductsToCart(userId, context, topUp);
            return getVerifiedCart(
                    userId,
                    context,
                    deliverySlot,
                    unresolved,
                    promoted,
                    resolution.skipped(),
                    topUp.stream().map(CartBuildingService::describeTopUp).toList());
        }
    }

    /**
     * Lines a small cart is short of Silpo's minimum delivery order, taken from the household's own baseline.
     *
     * <p>A dish's ingredients, a blackout lunch or a Friday-night snack cart comes to a few hundred hryvnia, and
     * Silpo's home delivery starts at ₴799 (its own {@code order.cost.min}). Rather than a dead end, the shortfall
     * is filled with what this household buys every week anyway — their confirmed baseline, cheapest lines first,
     * each carrying the product id and price of a real past order — and every added line is named in the cart
     * message with the right to take it out. Nothing is added for a household with no baseline yet; that case is
     * explained instead.
     */
    private List<ResolvedProduct> topUpFromBaseline(
            UUID userId,
            CartContext context,
            List<ResolvedProduct> alreadyInCart,
            BigDecimal total,
            BigDecimal minimum) {
        List<BasketItem> baseline =
                baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(userId)
                        .map(BaselineBasket::getItems)
                        .orElseGet(List::of)
                        .stream()
                        .filter(item -> item.silpoProductId() != null
                                && item.price() != null
                                && item.price().signum() > 0)
                        .filter(item -> alreadyInCart.stream()
                                .noneMatch(p -> p.productId().equals(item.silpoProductId())))
                        .sorted(java.util.Comparator.comparing(item ->
                                item.price().multiply(item.quantity() == null ? BigDecimal.ONE : item.quantity())))
                        .toList();
        if (baseline.isEmpty()) {
            log.info(
                    "cart {} is {} short of the {} minimum and there is no baseline to top it up from",
                    context.cartId(),
                    minimum.subtract(total == null ? BigDecimal.ZERO : total),
                    minimum);
            return List.of();
        }
        BigDecimal running = total == null ? BigDecimal.ZERO : total;
        // Baseline prices are what the household paid last time; today's may be a little lower after a discount,
        // and landing a few hryvnia short means another refused cart. Aim a little past the line.
        BigDecimal target = minimum.multiply(new BigDecimal("1.05"));
        List<ResolvedProduct> topUp = new ArrayList<>();
        for (BasketItem item : baseline) {
            if (running.compareTo(target) >= 0) {
                break;
            }
            BigDecimal quantity =
                    item.quantity() == null || item.quantity().signum() <= 0 ? BigDecimal.ONE : item.quantity();
            topUp.add(new ResolvedProduct(
                    item.name(),
                    item.silpoProductId(),
                    context.companyId(),
                    context.branchId(),
                    quantity,
                    item.unit(),
                    null,
                    item.name(),
                    item.price(),
                    false));
            running = running.add(item.price().multiply(quantity));
        }
        if (running.compareTo(minimum) < 0) {
            log.info(
                    "the whole baseline ({} lines) still leaves cart {} at {} against a {} minimum",
                    baseline.size(),
                    context.cartId(),
                    running,
                    minimum);
        }
        log.info(
                "topping cart {} up from {} to about {} with {} baseline lines to clear the {} minimum",
                context.cartId(),
                total,
                running,
                topUp.size(),
                minimum);
        return topUp;
    }

    /** «Молоко «Премія» 2,5% — 1 шт, 45.99 грн», for the cart message. */
    private static String describeTopUp(ResolvedProduct line) {
        // A baseline stored before units were kept has none; a fractional quantity of it can only be kilograms.
        String unit = line.unit() != null
                ? line.unit()
                : line.quantity().stripTrailingZeros().scale() > 0 ? "кг" : "шт";
        return "%s — %s %s, %s грн"
                .formatted(
                        line.catalogName(),
                        plain(line.quantity()),
                        unit,
                        plain(line.unitPrice().multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP)));
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

        JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("shoppingCartId", cartId));
        CartContext context = new CartContext(
                cartId,
                McpResponses.findString(cart, McpResponses.BRANCH_ID).orElse(null),
                McpResponses.findString(cart, McpResponses.COMPANY_ID).orElse(null),
                McpResponses.findString(cart, McpResponses.DELIVERY_TYPE).orElse(null),
                McpResponses.findString(cart, McpResponses.SLOT_START).orElse(null),
                McpResponses.findString(cart, McpResponses.SLOT_END).orElse(null));
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
        String addressType =
                McpResponses.findString(address, McpResponses.ADDRESS_TYPE).orElse("house");

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
                .orElseThrow(
                        () -> new CartBuildException("delivery type option had no deliveryType for user " + userId));
        String branchId =
                McpResponses.findString(chosen, McpResponses.BRANCH_ID).orElseGet(() -> resolvePickupBranch(userId));

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
            log.error(
                    "silpo_create_shopping_cart answered but no cart id for user {}. Raw response: {}",
                    userId,
                    created);
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
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("branchId", nullSafe(context.branchId()));
        if (context.deliveryType() != null && !context.deliveryType().isBlank()) {
            // deliveryTypes, plural, an array — sending the wrong enum value is worse than sending none at all.
            arguments.put("deliveryTypes", List.of(context.deliveryType()));
        }
        JsonNode slots = call(userId, TOOL_TIME_SLOTS, arguments);
        List<OfferedSlot> offered = new ArrayList<>();
        for (JsonNode slot : McpResponses.findArray(slots, McpResponses.TIME_SLOTS)) {
            boolean available = McpResponses.findNode(slot, McpResponses.SLOT_AVAILABLE)
                    .map(JsonNode::asBoolean)
                    .orElse(true);
            if (!available) {
                continue;
            }
            // Real slots carry no id of their own — only start/end/available — so start doubles as this
            // application's own handle for "which slot was picked", falling back to a documented id field only
            // if one is ever present.
            String start =
                    McpResponses.findString(slot, McpResponses.SLOT_START).orElse(null);
            String end = McpResponses.findString(slot, McpResponses.SLOT_END).orElse(null);
            String id = McpResponses.findString(slot, McpResponses.SLOT_ID).orElse(start);
            if (id == null) {
                log.debug("ignoring a time slot with no start and no identifier");
                continue;
            }
            offered.add(new OfferedSlot(id, start == null ? id : start, parseStart(start), end));
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

    /**
     * Whether a stored id is one Silpo will actually accept as a {@code productId}.
     *
     * <p>Silpo's product ids are UUIDs, and {@code silpo_add_or_update_cart_products} rejects the *whole* call —
     * every line, not just the offending one — with {@code Invalid UUID} when any entry is not one. A model that
     * helpfully fills the field in with "1", "2", "3" therefore does not cost one wrong product, it costs the entire
     * order; and because the value is persisted on the list, every retry fails the same way until somebody edits the
     * database. That is what a live account hit: 32 of 32 lines "pre-resolved" to 1..32, no search performed, cart
     * refused, «Кошик зібрати не вдалось» forever.
     *
     * <p>Generation strips these where it knows to ({@code MealPlanService.withoutProductIds}), but every producer of
     * a {@code PlannedIngredient} can set the field and this is the one place that talks to Silpo. Anything that is
     * not a UUID is treated as "not resolved yet" and goes through the name search like any other line, so a
     * fabricated id costs one search instead of the order.
     */
    private static boolean carriesASilpoProductId(ShoppingListItem item) {
        String id = item.getSilpoProductId();
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(id.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Step 4. Chunked at the documented batch limit; an unmatched item is normal, not an error.
     *
     * <p>One search term per requested item, not a name-plus-quantity object: {@code silpo_find_products_batch}'s
     * {@code products} field is an array of plain search strings, and the answer comes back as one {@code queries[]}
     * entry per term — each carrying its own {@code query} text and its own {@code products[]} matches — not a single
     * flat product list. The best match for a term is whichever product its own query entry lists first.
     */
    public List<ResolvedProduct> resolveProducts(UUID userId, CartContext context, List<ShoppingListItem> items) {
        return resolve(userId, context, items).resolved();
    }

    /**
     * {@link #resolveProducts}, keeping the lines the sanity check held back — the cart message names them.
     *
     * <p>Two passes. The first searches every line by its own name. Lines that come back with nothing acceptable
     * — no candidates at all, or candidates the matcher refused — get a second search under the other names the
     * product might carry on a shelf («Вівсянка» → «Вівсяні пластівці», «Яйця курячі» → «Яйця»): Silpo's search
     * is a plain text match, and on a live account it returned nothing for eggs and only sauerkraut for cabbage.
     */
    public ProductResolution resolve(UUID userId, CartContext context, List<ShoppingListItem> items) {
        List<String> skipped = new ArrayList<>();
        List<ResolvedProduct> resolved = new ArrayList<>();
        List<ShoppingListItem> preResolved = items.stream()
                .filter(CartBuildingService::carriesASilpoProductId)
                .toList();
        // READY_MEALS_ONLY lines already carry a real productId, resolved during generation (task 22) — adding
        // them straight to the cart, not searching for them again, is the whole point of that fix.
        for (ShoppingListItem item : preResolved) {
            resolved.add(new ResolvedProduct(
                    item.getName(),
                    item.getSilpoProductId(),
                    context.companyId(),
                    context.branchId(),
                    quantityOf(item),
                    item.getUnit()));
        }

        List<ShoppingListItem> needsSearch =
                items.stream().filter(item -> !carriesASilpoProductId(item)).toList();
        List<String> fabricated = needsSearch.stream()
                .filter(item -> item.getSilpoProductId() != null
                        && !item.getSilpoProductId().isBlank())
                .map(item -> item.getName() + "=" + item.getSilpoProductId())
                .toList();
        if (!fabricated.isEmpty()) {
            log.warn(
                    "{} shopping list lines carry a stored id that is not a Silpo product id; searching by name "
                            + "instead: {}",
                    fabricated.size(),
                    fabricated);
        }
        UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
        boolean onlyUaProducer = profile != null && Boolean.TRUE.equals(profile.getOnlyUaProducer());

        // Task 46: a partner placement may answer a line the household already asked for. Decided per line
        // before any search — restrictions are checked inside match(), so a conflicting promotion never gets
        // this far — and verified live below by searching the promoted product's own catalog name in the same
        // batch: the placement is used only if Silpo returns that exact product id for this branch and slot.
        List<PartnerPromotion> promotions = partnerPromotionService.activePromotions();
        Map<ShoppingListItem, PartnerPromotion> promotionFor = new IdentityHashMap<>();
        for (ShoppingListItem item : needsSearch) {
            partnerPromotionService
                    .match(promotions, item.getName(), profile)
                    .ifPresent(promotion -> promotionFor.put(item, promotion));
        }
        int chunkSize = promotionFor.isEmpty() ? SEARCH_BATCH_SIZE : SEARCH_BATCH_SIZE / 2;

        for (int start = 0; start < needsSearch.size(); start += chunkSize) {
            List<ShoppingListItem> chunk = needsSearch.subList(start, Math.min(needsSearch.size(), start + chunkSize));
            List<String> terms = new ArrayList<>();
            for (ShoppingListItem item : chunk) {
                addTerm(terms, biasedSearchTerm(item.getName(), onlyUaProducer));
                PartnerPromotion promotion = promotionFor.get(item);
                if (promotion != null) {
                    addTerm(terms, promotion.getProductName());
                }
            }
            JsonNode found = call(
                    userId,
                    TOOL_FIND_PRODUCTS,
                    Map.of(
                            "branchId", nullSafe(context.branchId()),
                            "deliveryType", nullSafe(context.deliveryType()),
                            "timeslotStart", nullSafe(context.timeslotStart()),
                            "timeslotEnd", nullSafe(context.timeslotEnd()),
                            "products", terms));
            Map<String, List<JsonNode>> productsByQuery = new LinkedHashMap<>();
            for (JsonNode query : McpResponses.findArray(found, McpResponses.QUERIES)) {
                McpResponses.findString(query, McpResponses.NAME)
                        .ifPresent(text -> productsByQuery.putIfAbsent(
                                text.toLowerCase(Locale.ROOT), McpResponses.findArray(query, McpResponses.PRODUCTS)));
            }
            // Every line, decided in one call rather than by taking whatever Silpo ranked first — see
            // ProductMatchingService for what that ranking actually returns. A line a partner placement claims is
            // matched here too, deliberately: the placement is only used if it comes back live (task 46), and
            // without an ordinary match behind it a placement that does not would leave the line unresolved.
            List<ShoppingListItem> toMatch = chunk;
            List<List<JsonNode>> candidatesFor = toMatch.stream()
                    .map(item -> plausibleFor(
                            item.getName(),
                            productsByQuery.getOrDefault(
                                    biasedSearchTerm(item.getName(), onlyUaProducer)
                                            .toLowerCase(Locale.ROOT),
                                    List.of())))
                    .toList();
            List<Integer> picked = productMatchingService.choose(matchRequests(toMatch, candidatesFor));
            Map<ShoppingListItem, JsonNode> matched = new IdentityHashMap<>();
            for (int i = 0; i < toMatch.size(); i++) {
                int index = picked.get(i);
                if (index != ProductMatchingService.NONE) {
                    matched.put(toMatch.get(i), candidatesFor.get(i).get(index));
                }
            }

            for (ShoppingListItem item : chunk) {
                ResolvedProduct chosen = null;
                PartnerPromotion promotion = promotionFor.get(item);
                if (promotion != null) {
                    JsonNode live =
                            productsByQuery
                                    .getOrDefault(promotion.getProductName().toLowerCase(Locale.ROOT), List.of())
                                    .stream()
                                    .filter(product -> promotion
                                            .getSilpoProductId()
                                            .equals(McpResponses.findString(product, McpResponses.PRODUCT_ID)
                                                    .orElse(null)))
                                    .findFirst()
                                    .orElse(null);
                    if (live != null) {
                        chosen = resolvedFrom(item, live, context, promotion.getId());
                        partnerPromotionService.recordImpression(promotion, userId);
                        log.info(
                                "partner placement {} answered «{}» with product {}",
                                promotion.getId(),
                                item.getName(),
                                promotion.getSilpoProductId());
                    } else {
                        log.info(
                                "partner placement {} not returned live for «{}»; using the ordinary match",
                                promotion.getId(),
                                item.getName());
                    }
                }
                if (chosen == null) {
                    JsonNode match = matched.get(item);
                    if (match != null) {
                        chosen = resolvedFrom(item, match, context, null);
                    }
                }
                accept(item, chosen, resolved, skipped);
            }
        }

        secondPass(userId, context, needsSearch, resolved, skipped);

        log.info(
                "MCP <- resolved {} of {} shopping list lines ({} pre-resolved, {} searched, {} partner placements,"
                        + " {} held back)",
                resolved.size(),
                items.size(),
                preResolved.size(),
                needsSearch.size(),
                resolved.stream().filter(ResolvedProduct::promoted).count(),
                skipped.size());
        return new ProductResolution(resolved, skipped);
    }

    /** A resolved line goes in unless the sanity check names a problem with it, in which case the problem does. */
    private static void accept(
            ShoppingListItem item, ResolvedProduct chosen, List<ResolvedProduct> resolved, List<String> skipped) {
        if (chosen == null) {
            return;
        }
        Optional<String> problem = sanityProblem(item, chosen);
        if (problem.isPresent()) {
            log.warn("holding back «{}»: {}", item.getName(), problem.get());
            skipped.add(item.getName() + " — " + problem.get());
            return;
        }
        resolved.add(chosen);
    }

    /**
     * The search again, under other names, for every line the first pass left with nothing.
     *
     * <p>One model call for the alternative phrasings, one Silpo search for all of them, one matcher call over
     * the union of what came back. A line the second pass cannot help stays unresolved and is reported as before;
     * a failure anywhere in this pass costs the lines it would have found, never the cart.
     */
    private void secondPass(
            UUID userId,
            CartContext context,
            List<ShoppingListItem> searched,
            List<ResolvedProduct> resolved,
            List<String> skipped) {
        List<ShoppingListItem> stillMissing = searched.stream()
                .filter(item ->
                        resolved.stream().noneMatch(p -> p.requestedName().equals(item.getName())))
                .filter(item -> skipped.stream().noneMatch(line -> line.startsWith(item.getName() + " — ")))
                .toList();
        if (stillMissing.isEmpty()) {
            return;
        }
        Map<Integer, List<String>> alternatives;
        try {
            alternatives = productMatchingService.alternativeTerms(stillMissing.stream()
                    .map(item -> new ProductMatchRequest(item.getName(), quantityOf(item), item.getUnit(), List.of()))
                    .toList());
        } catch (RuntimeException e) {
            log.warn("second search pass skipped: {}", e.getMessage());
            return;
        }
        if (alternatives.isEmpty()) {
            return;
        }
        List<String> terms = new ArrayList<>();
        alternatives.values().forEach(list -> list.forEach(term -> addTerm(terms, term)));
        JsonNode found;
        try {
            found = call(
                    userId,
                    TOOL_FIND_PRODUCTS,
                    Map.of(
                            "branchId", nullSafe(context.branchId()),
                            "deliveryType", nullSafe(context.deliveryType()),
                            "timeslotStart", nullSafe(context.timeslotStart()),
                            "timeslotEnd", nullSafe(context.timeslotEnd()),
                            "products", terms));
        } catch (RuntimeException e) {
            log.warn("second search pass failed: {}", e.getMessage());
            return;
        }
        Map<String, List<JsonNode>> productsByQuery = new LinkedHashMap<>();
        for (JsonNode query : McpResponses.findArray(found, McpResponses.QUERIES)) {
            McpResponses.findString(query, McpResponses.NAME)
                    .ifPresent(text -> productsByQuery.putIfAbsent(
                            text.toLowerCase(Locale.ROOT), McpResponses.findArray(query, McpResponses.PRODUCTS)));
        }
        List<ShoppingListItem> toMatch = new ArrayList<>();
        List<List<JsonNode>> candidatesFor = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : alternatives.entrySet()) {
            ShoppingListItem item = stillMissing.get(entry.getKey());
            List<JsonNode> union = new ArrayList<>();
            for (String term : entry.getValue()) {
                for (JsonNode product : plausibleFor(
                        item.getName(), productsByQuery.getOrDefault(term.toLowerCase(Locale.ROOT), List.of()))) {
                    String id = McpResponses.findString(product, McpResponses.PRODUCT_ID)
                            .orElse(null);
                    boolean seen = union.stream()
                            .anyMatch(known -> McpResponses.findString(known, McpResponses.PRODUCT_ID)
                                    .orElse("")
                                    .equals(id));
                    if (!seen) {
                        union.add(product);
                    }
                }
            }
            if (!union.isEmpty()) {
                toMatch.add(item);
                candidatesFor.add(union);
            }
        }
        if (toMatch.isEmpty()) {
            log.info("second search pass found nothing for {} lines", stillMissing.size());
            return;
        }
        List<Integer> picked;
        try {
            picked = productMatchingService.choose(matchRequests(toMatch, candidatesFor));
        } catch (RuntimeException e) {
            log.warn("second search pass could not match: {}", e.getMessage());
            return;
        }
        int recovered = 0;
        for (int i = 0; i < toMatch.size(); i++) {
            int index = picked.get(i);
            if (index == ProductMatchingService.NONE) {
                continue;
            }
            ResolvedProduct chosen =
                    resolvedFrom(toMatch.get(i), candidatesFor.get(i).get(index), context, null);
            int before = resolved.size();
            accept(toMatch.get(i), chosen, resolved, skipped);
            recovered += resolved.size() - before;
        }
        log.info("second search pass recovered {} of {} lines", recovered, stillMissing.size());
    }

    private static void addTerm(List<String> terms, String term) {
        if (term != null && !term.isBlank() && terms.stream().noneMatch(term::equalsIgnoreCase)) {
            terms.add(term);
        }
    }

    /** One catalog hit turned into a cart line, or null when the hit carries no product id. */
    private static ResolvedProduct resolvedFrom(
            ShoppingListItem item, JsonNode product, CartContext context, UUID promotionId) {
        String productId =
                McpResponses.findString(product, McpResponses.PRODUCT_ID).orElse(null);
        if (productId == null) {
            return null;
        }
        boolean weighted = McpResponses.findNode(product, McpResponses.WEIGHTED)
                .map(node -> node.asBoolean(false))
                .orElse(false);
        return new ResolvedProduct(
                item.getName(),
                productId,
                McpResponses.findString(product, McpResponses.COMPANY_ID).orElse(context.companyId()),
                McpResponses.findString(product, McpResponses.BRANCH_ID).orElse(context.branchId()),
                cartQuantity(item, product),
                item.getUnit(),
                promotionId,
                McpResponses.findString(product, McpResponses.NAME).orElse(null),
                McpResponses.findNumber(product, McpResponses.PRICE).orElse(null),
                weighted);
    }

    /** More than this for one line of a household's shopping is a wrong product or a wrong quantity, not a purchase. */
    static final BigDecimal MAX_LINE_COST = new BigDecimal("1500");

    /** Twenty of anything is a crate. */
    static final BigDecimal MAX_LINE_UNITS = new BigDecimal("20");

    /** Six kilograms of one loose product is a sack. */
    static final BigDecimal MAX_LINE_KILOGRAMS = new BigDecimal("6");

    /**
     * The one check that is arithmetic rather than judgement: what this line would cost, and how much of it there
     * would be. A weekly cart once carried «Яловичина 850 г» as thirty-four 25 g packets of jerky at ₴3246, and a
     * dish order two kilograms of DOP cheese at ₴2798. The matcher and the quantity conversion have both been fixed
     * since, but the household is the one who pays when either slips, so a line past these limits is held back and
     * named in the cart message rather than put in the basket with a «Підтвердити» under it.
     */
    static Optional<String> sanityProblem(ShoppingListItem item, ResolvedProduct product) {
        BigDecimal quantity = product.quantity() == null ? BigDecimal.ONE : product.quantity();
        String catalogName = product.catalogName() == null ? product.productId() : product.catalogName();
        if (product.weighted() && quantity.compareTo(MAX_LINE_KILOGRAMS) > 0) {
            return Optional.of("%s кг «%s» — забагато для одного замовлення, перевір кількість"
                    .formatted(plain(quantity), catalogName));
        }
        if (!product.weighted() && quantity.compareTo(MAX_LINE_UNITS) > 0) {
            return Optional.of("%s шт «%s» — схоже, не той розмір упаковки, перевір позицію"
                    .formatted(plain(quantity), catalogName));
        }
        if (product.unitPrice() != null) {
            BigDecimal cost = product.unitPrice().multiply(quantity);
            if (cost.compareTo(MAX_LINE_COST) > 0) {
                return Optional.of("«%s» вийшло б %s грн (%s %s по %s грн) — перевір, чи це той товар"
                        .formatted(
                                catalogName,
                                plain(cost.setScale(0, RoundingMode.HALF_UP)),
                                plain(quantity),
                                product.weighted() ? "кг" : "шт",
                                plain(product.unitPrice())));
            }
        }
        return Optional.empty();
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }

    /** A weight (grams), a volume (millilitres) or a count — the three kinds {@code displayRatio} comes in. */
    private enum UnitKind {
        WEIGHT,
        VOLUME,
        COUNT
    }

    private record UnitAmount(BigDecimal amount, UnitKind kind) {}

    /**
     * {@code displayRatio}, e.g. {@code "5*70г"}, {@code "1л"}, {@code "100шт"}, or bare {@code "шт"} with no
     * number at all.
     */
    private static final Pattern DISPLAY_RATIO_PATTERN =
            Pattern.compile("^(?:(\\d+)\\s*[*×]\\s*)?(\\d+(?:[.,]\\d+)?)\\s*(кг|г|мл|л|шт)?");

    /**
     * What one piece of loose produce weighs, when a list says «4 шт» and Silpo sells the thing by the kilogram.
     * Cucumbers, peppers, lemons and avocados are the cases the planner is told to count in pieces, and 150 g is a
     * fair middle for all of them. A guess, said so in the log — but the previous answer, the minimum step, sent
     * 100 g of cucumber for a week and was silently wrong by a factor of six.
     */
    private static final BigDecimal PIECE_WEIGHT_ESTIMATE_G = new BigDecimal("150");

    private static Optional<UnitAmount> parseDisplayRatio(String raw) {
        String trimmed = raw.strip();
        if ("шт".equalsIgnoreCase(trimmed)) {
            return Optional.of(new UnitAmount(BigDecimal.ONE, UnitKind.COUNT));
        }
        Matcher matcher = DISPLAY_RATIO_PATTERN.matcher(trimmed);
        if (!matcher.find()) {
            return Optional.empty();
        }
        BigDecimal multiplier = matcher.group(1) == null ? BigDecimal.ONE : new BigDecimal(matcher.group(1));
        BigDecimal value = new BigDecimal(matcher.group(2).replace(',', '.'));
        String unit = matcher.group(3);
        BigDecimal amount = multiplier.multiply(value);
        return switch (unit == null ? "" : unit) {
            case "г" -> Optional.of(new UnitAmount(amount, UnitKind.WEIGHT));
            case "кг" -> Optional.of(new UnitAmount(amount.multiply(BigDecimal.valueOf(1000)), UnitKind.WEIGHT));
            case "мл" -> Optional.of(new UnitAmount(amount, UnitKind.VOLUME));
            case "л" -> Optional.of(new UnitAmount(amount.multiply(BigDecimal.valueOf(1000)), UnitKind.VOLUME));
            case "шт" -> Optional.of(new UnitAmount(amount, UnitKind.COUNT));
            // A bare number with no unit letter, e.g. "1" or "12" — always a piece count in what has been seen.
            default -> Optional.of(new UnitAmount(amount, UnitKind.COUNT));
        };
    }

    private static Optional<UnitAmount> shoppingListAmount(ShoppingListItem item) {
        BigDecimal quantity = quantityOf(item);
        String unit = item.getUnit() == null ? "" : item.getUnit().strip().toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "г", "грам", "грами" -> Optional.of(new UnitAmount(quantity, UnitKind.WEIGHT));
            case "кг" -> Optional.of(new UnitAmount(quantity.multiply(BigDecimal.valueOf(1000)), UnitKind.WEIGHT));
            case "мл" -> Optional.of(new UnitAmount(quantity, UnitKind.VOLUME));
            case "л" -> Optional.of(new UnitAmount(quantity.multiply(BigDecimal.valueOf(1000)), UnitKind.VOLUME));
            case "шт", "упаковка", "уп" -> Optional.of(new UnitAmount(quantity, UnitKind.COUNT));
            default -> Optional.empty();
        };
    }

    /**
     * Whether two unit kinds can be divided into each other. Grams and millilitres are the same number for every
     * liquid a grocery sells — a «900г» milk pack is 900 ml, and a list that asks for «2 л» of it wants two packs,
     * not the one pack the "different kinds of unit" refusal used to send.
     */
    private static boolean comparable(UnitKind packageKind, UnitKind wantedKind) {
        if (packageKind == wantedKind) {
            return true;
        }
        return packageKind != UnitKind.COUNT && wantedKind != UnitKind.COUNT;
    }

    /** Never less than one step, and always a whole multiple of it — Silpo rejects anything else. */
    private static BigDecimal roundToStep(BigDecimal amount, BigDecimal step) {
        BigDecimal safeStep = step == null || step.signum() <= 0 ? BigDecimal.ONE : step;
        BigDecimal multiples = amount.divide(safeStep, 0, RoundingMode.HALF_UP);
        BigDecimal rounded = multiples.multiply(safeStep);
        return rounded.signum() > 0 ? rounded : safeStep;
    }

    /**
     * How much to actually send as {@code quantity}: a count of {@code displayRatio}-sized units, not the household's
     * own grams or millilitres. Sending "800" as the unit count for a product whose own {@code displayRatio} is
     * "50г" does not ask for 800 grams — it asks for eight hundred 50g packages. Silpo's own documentation says as
     * much ("use displayRatio together with step to compute how many units to add"); this is the computation.
     *
     * <p>When the shopping list's unit and {@code displayRatio}'s unit are different kinds of measurement — a plan
     * asking for "2 шт" of something Silpo prices as "100г" — there is no safe conversion to guess at, so this falls
     * back to the smallest valid amount, {@code step}, rather than a number that might silently order far more or
     * far less than intended.
     *
     * <p><b>A weighted product is the exception, and getting it wrong is expensive.</b> For {@code weighted: true}
     * lines Silpo's {@code quantity} is the weight itself in kilograms, not a count of anything: its {@code price}
     * is the price per kilogram and {@code quantity * price} is exactly the {@code subTotal} it answers with. Its
     * {@code displayRatio} ("100г") is a pricing-display hint and has nothing to do with the unit of
     * {@code quantity}. Dividing by it anyway made every weighted line ten times too large on a live cart —
     * «Картопля 2000 г» was ordered as 20 kg against a branch holding 7, «Фарш свинячий 550 г» as 5.5 kg, chicken
     * as 15.4 kg at ₴4996 — which is what put a perfectly ordinary week's groceries at ~58 kg and over Silpo's
     * 40 kg order cap. The correct weight was ~5.8 kg.
     */
    private static BigDecimal cartQuantity(ShoppingListItem item, JsonNode product) {
        BigDecimal step = McpResponses.findNumber(product, McpResponses.STEP).orElse(BigDecimal.ONE);
        Optional<String> displayRatio = McpResponses.findString(product, McpResponses.DISPLAY_RATIO);
        Optional<UnitAmount> wanted = shoppingListAmount(item);
        boolean weighted = McpResponses.findNode(product, McpResponses.WEIGHTED)
                .map(node -> node.asBoolean(false))
                .orElse(false);

        if (weighted) {
            // Kilograms (or litres) of the thing itself. Nothing to divide by; displayRatio is not its unit.
            if (wanted.isEmpty()) {
                log.warn(
                        "\"{}\" {} has no unit this cart understands, and Silpo sells the product by weight — "
                                + "sending the minimum step",
                        item.getQuantity(),
                        item.getUnit());
                return step;
            }
            if (wanted.get().kind() == UnitKind.COUNT) {
                // «4 шт» of something sold loose by weight. An estimate, and said so — see the constant.
                BigDecimal grams = wanted.get().amount().multiply(PIECE_WEIGHT_ESTIMATE_G);
                log.info(
                        "\"{}\" {} is a count, but Silpo sells «{}» by weight — estimating {} g a piece, {} g",
                        item.getQuantity(),
                        item.getUnit(),
                        item.getName(),
                        PIECE_WEIGHT_ESTIMATE_G,
                        grams);
                return roundToStep(grams.divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP), step);
            }
            BigDecimal base = wanted.get().amount().divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
            return roundToStep(base, step);
        }

        if (displayRatio.isEmpty()) {
            return roundToStep(quantityOf(item), step);
        }
        Optional<UnitAmount> packageAmount = parseDisplayRatio(displayRatio.get());
        if (wanted.isPresent()
                && wanted.get().kind() == UnitKind.COUNT
                && packageAmount.isPresent()
                && packageAmount.get().kind() != UnitKind.COUNT) {
            // «2 шт» of a product packaged by weight or volume is two packages — two loaves of a 600 г bread,
            // not the minimum step because "шт" and "600г" are different kinds of unit. A count against a
            // count-labelled package («10 шт» of eggs sold as «10шт») still divides, below.
            return roundToStep(wanted.get().amount(), step);
        }
        if (packageAmount.isEmpty()
                || wanted.isEmpty()
                || !comparable(packageAmount.get().kind(), wanted.get().kind())
                || packageAmount.get().amount().signum() <= 0) {
            log.warn(
                    "could not relate \"{}\" {} to Silpo's displayRatio \"{}\" for product match — sending the "
                            + "minimum step instead of guessing",
                    item.getQuantity(),
                    item.getUnit(),
                    displayRatio.get());
            return step;
        }
        BigDecimal unitsWanted =
                wanted.get().amount().divide(packageAmount.get().amount(), 4, RoundingMode.HALF_UP);
        return roundToStep(unitsWanted, step);
    }

    /**
     * Step 5. Adds and updates our own lines; whatever the guest already had stays untouched.
     *
     * <p>Two different requested names can both match the same physical product — "Курка (ціла)" and "Курка
     * (гомілка)" landing on the same catalogue entry is a fuzzy-search collision, not a mistake anywhere in this
     * list. Silpo's own API refuses a request that lists one {@code productId} twice, so lines sharing one are merged
     * here, quantities summed, rather than sent as two competing lines for the same product.
     */
    /**
     * Empties the cart before {@link #buildCart} re-adds the current shopping list to it.
     *
     * <p>{@code silpo_add_or_update_cart_products} only ever adds or updates by {@code productId} — it never
     * removes a line. A rebuild that re-searches the catalogue can land on a different product for the same
     * shopping list line than a previous attempt did (fuzzy name search is not guaranteed deterministic run to
     * run), which adds a second line instead of replacing the first. Across enough retries the cart accumulates
     * lines that have nothing to do with the current list, inflating its weight and price until Silpo's own
     * validations (stock, the 40kg order cap) refuse checkout — with no indication in the failure that the cart
     * itself, not the list, is the problem. Only {@link #buildCart} calls this: {@link ReorderService} and
     * {@link AdHocOrderService} reuse {@link #getOrCreateCartContext} and {@link #addProductsToCart} to add to
     * whatever is already in the cart on purpose, and must not have it cleared out from under them.
     */
    private void clearCart(UUID userId, CartContext context) {
        call(userId, TOOL_CLEAR_CART, Map.of("shoppingCartId", context.cartId()));
    }

    public void addProductsToCart(UUID userId, CartContext context, List<ResolvedProduct> products) {
        if (products.isEmpty()) {
            log.info("nothing resolved, so nothing to add to cart {}", context.cartId());
            return;
        }
        Map<String, ResolvedProduct> merged = new LinkedHashMap<>();
        for (ResolvedProduct product : products) {
            merged.merge(
                    product.productId(),
                    product,
                    (existing, incoming) -> new ResolvedProduct(
                            existing.requestedName() + " + " + incoming.requestedName(),
                            existing.productId(),
                            existing.companyId(),
                            existing.branchId(),
                            existing.quantity().add(incoming.quantity()),
                            existing.unit()));
        }
        if (merged.size() < products.size()) {
            log.info(
                    "{} requested lines matched the same Silpo product as another; quantities merged",
                    products.size() - merged.size());
        }
        Map<String, Object> arguments = Map.of(
                "shoppingCartId",
                context.cartId(),
                "products",
                merged.values().stream()
                        .map(product -> Map.of(
                                "productId", product.productId(),
                                "companyId", nullSafe(product.companyId()),
                                "branchId", nullSafe(product.branchId()),
                                "quantity", product.quantity()))
                        .toList());
        try {
            call(userId, TOOL_ADD_PRODUCTS, arguments);
        } catch (SilpoMcpException e) {
            // The one call in the sequence that carries the whole week at once, and the one that timed out on a
            // live account. It adds or updates by product id, so sending the same lines twice is harmless — and
            // a second attempt is cheaper than telling the household to start over.
            log.warn(
                    "adding {} lines to cart {} failed once ({}); trying again",
                    merged.size(),
                    context.cartId(),
                    e.getMessage());
            call(userId, TOOL_ADD_PRODUCTS, arguments);
        }
        // Task 46: the partner's second funnel step — the placement is now in a cart Silpo accepted.
        products.stream()
                .filter(ResolvedProduct::promoted)
                .forEach(product -> partnerPromotionService.recordAddedToCart(product.promotionId(), userId));
    }

    /** Step 6: read the cart back rather than trusting the write. */
    public CartSummary getVerifiedCart(
            UUID userId, CartContext context, OfferedSlot deliverySlot, List<String> unresolved) {
        return getVerifiedCart(userId, context, deliverySlot, unresolved, List.of());
    }

    /** Same, naming the product ids a partner placement put in the cart (task 46) so the message can mark them. */
    public CartSummary getVerifiedCart(
            UUID userId,
            CartContext context,
            OfferedSlot deliverySlot,
            List<String> unresolved,
            List<String> promotedProductIds) {
        return getVerifiedCart(userId, context, deliverySlot, unresolved, promotedProductIds, List.of());
    }

    /** Same, with the lines the sanity check held back, so the message can say so. */
    public CartSummary getVerifiedCart(
            UUID userId,
            CartContext context,
            OfferedSlot deliverySlot,
            List<String> unresolved,
            List<String> promotedProductIds,
            List<String> skipped) {
        return getVerifiedCart(userId, context, deliverySlot, unresolved, promotedProductIds, skipped, List.of());
    }

    /** Same, with the baseline lines a too-small cart was topped up with, so the message can name them. */
    public CartSummary getVerifiedCart(
            UUID userId,
            CartContext context,
            OfferedSlot deliverySlot,
            List<String> unresolved,
            List<String> promotedProductIds,
            List<String> skipped,
            List<String> toppedUp) {
        JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("shoppingCartId", context.cartId()));

        List<BasketItem> items = McpResponses.findArray(cart, McpResponses.ITEMS).stream()
                .map(node -> new BasketItem(
                        McpResponses.findString(node, McpResponses.PRODUCT_ID).orElse(null),
                        McpResponses.findString(node, McpResponses.NAME).orElse(null),
                        // Silpo's cart lines carry no unit of their own; «weighted» says whether the quantity
                        // is kilograms or packages, which is the only thing the unit needs to say.
                        McpResponses.findString(node, McpResponses.UNIT)
                                .orElseGet(() -> McpResponses.findNode(node, McpResponses.WEIGHTED)
                                                .map(weighted -> weighted.asBoolean(false))
                                                .orElse(false)
                                        ? "кг"
                                        : "шт"),
                        McpResponses.findNumber(node, McpResponses.QUANTITY).orElse(null),
                        McpResponses.findNumber(node, McpResponses.PRICE).orElse(null)))
                .toList();

        Map<String, String> nameByProductId = new LinkedHashMap<>();
        items.forEach(item -> {
            if (item.silpoProductId() != null && item.name() != null) {
                nameByProductId.put(item.silpoProductId(), item.name());
            }
        });
        // Only what blocks checkout. Silpo also sends level "info" notes — «BNPL is not available for this total»
        // — which reached the household as a raw machine code next to the real reason.
        List<JsonNode> blocking = McpResponses.findArray(cart, McpResponses.VALIDATIONS).stream()
                .filter(node -> !"info".equalsIgnoreCase(node.path("level").asText("error"))
                        && !"warning".equalsIgnoreCase(node.path("level").asText("error")))
                .toList();
        List<String> validations = blocking.stream()
                .map(node -> describeValidation(node, nameByProductId))
                .toList();
        BigDecimal minimumOrder = blocking.stream()
                .filter(node -> "order.cost.min".equals(node.path("message").asText()))
                .map(node -> McpResponses.findNumber(node.path("context"), "orderCostMin")
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        BigDecimal total = McpResponses.findNumber(cart, McpResponses.TOTAL).orElse(BigDecimal.ZERO);
        // The minimum is measured against the goods, not the goods plus delivery.
        BigDecimal goodsTotal =
                McpResponses.findNumber(cart, McpResponses.PRODUCTS_TOTAL).orElse(total);

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

        String checkoutWebLink =
                McpResponses.findString(cart, McpResponses.CHECKOUT_WEB).orElse(null);
        String checkoutMobileLink =
                McpResponses.findString(cart, McpResponses.CHECKOUT_MOBILE).orElse(null);
        if (isBlank(checkoutWebLink) || isBlank(checkoutMobileLink)) {
            log.error(
                    "verified cart {} has no usable checkout link — checkoutWebLink={}, checkoutMobileLink={}, "
                            + "validations={}. Raw response: {}",
                    context.cartId(),
                    checkoutWebLink,
                    checkoutMobileLink,
                    validations,
                    cart);
            throw new CartBuildException(
                    "Silpo gave no checkout link for cart " + context.cartId(), validations, goodsTotal, minimumOrder);
        }

        CartSummary summary = new CartSummary(
                context.cartId(),
                deliverySlot == null ? null : deliverySlot.id(),
                deliverySlot == null ? null : deliverySlot.startsAt(),
                items,
                total,
                validations,
                bonusAvailable,
                bonusDecisionPending,
                checkoutWebLink,
                checkoutMobileLink,
                unresolved,
                promotedProductIds == null ? List.of() : promotedProductIds,
                skipped == null ? List.of() : skipped,
                toppedUp == null ? List.of() : toppedUp);
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

    /**
     * A cart-level validation is an object ({@code level}, {@code type}, {@code message}, {@code context}), not the
     * plain string {@link CartSummary#validations()} used to hold — so this turns the known codes into the sentence
     * a person reads, and falls back to the raw {@code message} for one this app has never seen.
     */
    private static String describeValidation(JsonNode validation, Map<String, String> nameByProductId) {
        String message = McpResponses.findString(validation, "message").orElse("");
        JsonNode context = validation.get("context");
        return switch (message) {
            case "timeslot.not_available" -> "обраний час доставки більше недоступний";
            case "product.offer.stock.max" -> {
                String productId = context == null
                        ? null
                        : McpResponses.findString(context, "productId").orElse(null);
                String name = productId == null ? null : nameByProductId.get(productId);
                String stock = context == null
                        ? ""
                        : McpResponses.findString(context, "stock").orElse("");
                yield (name == null ? "товар" : name) + ": на складі лишилось " + stock
                        + ", а в кошику замовлено більше";
            }
            // Both seen on a live weekly cart, and both left the household reading a machine code with no idea
            // what to do next. Neither is fixable from here: age confirmation happens on Silpo's own checkout page,
            // and the weight cap is a hard limit a full week's groceries for a family genuinely exceeds — so the
            // sentence has to say which line to drop, not just that something is wrong.
            case "order.adult.is_not_confirmed" ->
                "у кошику є алкоголь — «Сільпо» просить підтвердити вік на своїй сторінці оплати, "
                        + "або прибери цю позицію зі списку";
            // Says the number, because "too heavy" without one leaves nothing to act on. Silpo names the cap in
            // context when it sends one; 40 кг is the documented limit and the fallback when it does not.
            case "order.weight.max" -> {
                String limit = context == null
                        ? ""
                        : McpResponses.findString(context, "weightMax", "maxWeight", "weight")
                                .orElse("");
                yield "у кошику більше ніж %s — «Сільпо» стільки за раз не везе, прибери щось зі списку"
                        .formatted(limit.isBlank() ? "40 кг" : limit + " кг");
            }
            case "order.cost.min" -> {
                String minimum = context == null
                        ? ""
                        : McpResponses.findString(context, "orderCostMin").orElse("");
                yield "замовлення менше мінімальної суми доставки"
                        + (minimum.isBlank() ? "" : " (" + minimum + " грн)");
            }
            case "" -> "невідома причина";
            default -> message;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Best-effort UA-producer preference: no producer/country field exists anywhere in this app's observed MCP
     * product data (never exercised against a live server — see task 25's design doc), so this biases Silpo's own
     * search ranking via the query text instead of filtering results client-side. Not a guaranteed filter.
     */
    static String biasedSearchTerm(String itemName, boolean onlyUaProducer) {
        return onlyUaProducer ? itemName + " українського виробництва" : itemName;
    }

    private static BigDecimal quantityOf(ShoppingListItem item) {
        return item.getQuantity() == null ? BigDecimal.ONE : item.getQuantity();
    }
}
