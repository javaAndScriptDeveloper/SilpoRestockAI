package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.exception.DeliverySlotUnavailableException;
import com.silporestockai.exception.GiftDeliveryUnavailableException;
import com.silporestockai.exception.NoSilpoDeliveryAddressException;
import com.silporestockai.exception.SilpoMcpException;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.GiftAddress;
import com.silporestockai.model.MatchingHints;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.ProductCandidate;
import com.silporestockai.model.ProductMatchRequest;
import com.silporestockai.model.ProductResolution;
import com.silporestockai.model.ResolvedProduct;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.utils.CategoryWords;
import com.silporestockai.utils.McpResponses;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final String TOOL_REMOVE_PRODUCTS = "silpo_remove_cart_products";
    private static final String TOOL_MY_ADDRESSES = "silpo_get_my_delivery_addresses";
    private static final String TOOL_DELIVERY_TYPES = "silpo_get_available_delivery_types";
    private static final String TOOL_LIST_BRANCHES = "silpo_list_branches";
    private static final String TOOL_CREATE_CART = "silpo_create_shopping_cart";
    private static final String TOOL_UPDATE_CART = "silpo_update_shopping_cart";
    private static final String TOOL_FIND_ADDRESS = "silpo_find_address";

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /** {@code silpo_get_available_delivery_types} hands back a branch directly for these; the rest need resolving. */
    private static final Set<String> DELIVERY_TYPES_WITH_A_BRANCH_ALREADY =
            Set.of("DeliveryHome", "WideAssortDelivery", "B2B");

    /**
     * The one delivery type a gift can use (task 81).
     *
     * <p>Not a simplification: nothing among the live server's forty tools names a recipient. {@code SelfPickup}
     * builds its address out of branch data and {@code NovaPoshta} out of office data, and neither the create nor
     * the update tool has a field for who may collect an order — so "your friend picks it up himself" cannot be
     * expressed, and is not offered.
     */
    private static final String DELIVERY_HOME = "DeliveryHome";

    /** Slot times without a zone are the household's, and the household is in Kyiv. */
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    /** The documented per-call limit of {@code silpo_find_products_batch}. */
    private static final int SEARCH_BATCH_SIZE = 30;

    private final SilpoMcpClient silpoMcpClient;
    private final UserProfileRepository userProfileRepository;
    private final PartnerPromotionService partnerPromotionService;
    private final CategoryResolutionLogService categoryResolutionLogService;
    private final ProductMatchingService productMatchingService;
    private final BaselineBasketRepository baselineBasketRepository;
    private final ObservabilityService observabilityService;

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

    /**
     * Name fragments that mark a product as one a household with no electricity cannot keep.
     *
     * <p>Task 73's live repro: «світло вимкнули» came back with Сир Spomlek «Радамер» at ₴84.90 and Шинка Алан
     * at ₴69.99 in the bag. Both were asked for by name by the curated list of the day, and that list is fixed —
     * but a shelf-stable line reaches a chilled shelf all the same, because «паштет» has a refrigerated aisle and
     * so does «сік». A mode whose promise is that nothing in the bag needs cold cannot keep it by asking the
     * matcher nicely, so these are a floor under the pool exactly like {@link #NOT_GROCERIES_FOR_PEOPLE}: a
     * refrigerated candidate the matcher never sees is one it can never pick.
     *
     * <p>By name, because the live catalog offers nothing else to go on — a product from
     * {@code silpo_find_products_batch} carries {@code name}, {@code slug}, {@code price}, {@code oldPrice},
     * {@code stock}, {@code available}, {@code weighted}, {@code step}, {@code displayRatio} and ids, and not one
     * field about storage, category or temperature (verified live, 2026-09-10).
     *
     * <p>Matched against the <em>first word</em> of the product name, because that is where Silpo puts the
     * category: «Сир Spomlek «Радамер» нарізка», «Шинка Алан Куряча в/к», «Молоко Яготинське 2.6%». Anywhere in
     * the name is too greedy and was measured to be: a live run dropped «Хліб «Київхліб» британський світлий з
     * молоком нарізаний» — a loaf of bread — because «молоком» is in it. Stems rather than whole words, because
     * Ukrainian declines: «ковбас» covers ковбаса/ковбаси/ковбасні, and «сир» has to reach «сирок».
     */
    private static final List<String> COLD_SHELF_CATEGORIES = List.of(
            "сир",
            "шинка",
            "ковбас",
            "сосиск",
            "сардельк",
            "салямі",
            "молоко",
            "кефір",
            "йогурт",
            "ряжанк",
            "сметан",
            "вершки",
            "творог",
            "морозиво",
            "пельмен",
            "вареник",
            "напівфабрикат");

    /**
     * First words that begin like a {@link #COLD_SHELF_CATEGORIES} stem and are not that category at all. «Сироп»
     * is a bottle of syrup, not a cheese; «масло» is the ambiguous one and is decided below rather than here,
     * since «Масло вершкове Селянське» and «Масло соняшникове» differ only past the first word.
     */
    private static final List<String> NOT_A_COLD_SHELF_AFTER_ALL = List.of("сироп");

    /**
     * Storage said outright, wherever it appears in the name — «Салат Олів'є ваговий, охолоджений» leads with a
     * category that is not cold on its own, and a freezer with no power is a fridge with no power, only worse.
     */
    private static final List<String> KEPT_COLD_ANYWHERE = List.of("охолодж", "заморож");

    /**
     * Whether this product has to be kept cold, judged by its name. See {@link #COLD_SHELF_CATEGORIES}.
     *
     * <p>Public because the blackout tests read it: what the mode promises and what the pool refuses have to be
     * the same sentence, and a test that restates the list would let the two drift apart silently.
     */
    public static boolean needsAFridge(String productName) {
        String name = productName == null ? "" : productName.trim().toLowerCase(Locale.ROOT);
        if (KEPT_COLD_ANYWHERE.stream().anyMatch(name::contains)) {
            return true;
        }
        String category = name.split("[\\s,.]+", 2)[0];
        if (NOT_A_COLD_SHELF_AFTER_ALL.stream().anyMatch(category::startsWith)) {
            return false;
        }
        // The one category the first word cannot settle: butter is a fridge, sunflower oil is a shelf, and both
        // are «масло» until the second word.
        if (category.startsWith("масло")) {
            return name.contains("вершков");
        }
        return COLD_SHELF_CATEGORIES.stream().anyMatch(category::startsWith);
    }

    /**
     * {@link #availableOnly}, minus the hits that are obviously not food for people (see the list above), minus
     * the ones the branch cannot cover in the asked amount, minus — for a household with no power — everything
     * that needs a fridge.
     *
     * <p>The stock rule is deterministic on purpose: the matcher was told to avoid short stock and still took
     * «Банан» at 0.4 kg for a 1 kg line («хоча запасу мало»), and Silpo then refused the whole cart with
     * {@code product.offer.stock.max}. A candidate that would be refused is not a candidate.
     *
     * <p>The cold-chain rule has no «unless the line asked for it» escape, unlike the pet-food one: under that
     * flag there is no working fridge in the household, so a line that asks for cheese is a line that cannot be
     * filled, and saying so is the honest answer.
     */
    private static List<JsonNode> plausibleFor(ShoppingListItem item, List<JsonNode> candidates, boolean noColdChain) {
        String requestedName = item.getName();
        String asked = requestedName == null ? "" : requestedName.toLowerCase(Locale.ROOT);
        List<String> markers = NOT_GROCERIES_FOR_PEOPLE.stream()
                .filter(marker -> !asked.contains(marker))
                .toList();
        List<String> variantMarkers = variantsABareLineDoesNotMean(asked);
        List<JsonNode> kept = new ArrayList<>();
        for (JsonNode candidate : availableOnly(candidates)) {
            String name = McpResponses.findString(candidate, McpResponses.NAME)
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
            if (markers.stream().anyMatch(name::contains)) {
                log.debug("dropping «{}» as a candidate for «{}»: not groceries for people", name, requestedName);
                continue;
            }
            if (noColdChain && needsAFridge(name)) {
                log.info("dropping «{}» as a candidate for «{}»: it needs a fridge", name, requestedName);
                continue;
            }
            if (variantMarkers.stream().anyMatch(name::contains)) {
                log.info(
                        "dropping «{}» as a candidate for «{}»: not the plain variant the line means",
                        name,
                        requestedName);
                continue;
            }
            Optional<BigDecimal> stock = McpResponses.findNumber(candidate, McpResponses.STOCK);
            if (stock.isPresent() && stock.get().compareTo(cartQuantity(item, candidate)) < 0) {
                log.info(
                        "dropping «{}» as a candidate for «{}»: {} in stock, the line needs {}",
                        name,
                        requestedName,
                        stock.get().toPlainString(),
                        cartQuantity(item, candidate).toPlainString());
                continue;
            }
            kept.add(candidate);
        }
        return cheapestFirst(kept);
    }

    /**
     * The candidates the matcher reads, cheapest first.
     *
     * <p>Silpo's order is relevance, and relevance is not "the ordinary version of this thing": for «регідрон» it
     * puts a ₴309 imported electrolyte drink above the ₴32 sachet in the same answer, and for «вода мінеральна»
     * Evian above Моршинська. That ranking is what a live hangover order cost ₴1034 for three basic items (task
     * 72). The prompt has asked for the ordinary one since task 49 and the fast model still followed the order
     * often enough to matter, so the order is settled here instead: Silpo's own relevance decides which fifteen
     * are worth reading — the cap the matcher applies anyway — and price decides which of those is read first.
     *
     * <p>Not a decision about which product is right, and it takes nothing out: a line whose cheap candidates are
     * all the wrong product is still answered «none of these» by the matcher, exactly as before. A candidate with
     * no price sorts last; unknown is not cheap.
     */
    private static List<JsonNode> cheapestFirst(List<JsonNode> candidates) {
        return candidates.stream()
                .limit(ProductMatchingService.MAX_CANDIDATES_SHOWN)
                .sorted(java.util.Comparator.comparing(candidate ->
                        McpResponses.findNumber(candidate, McpResponses.PRICE).orElse(PRICELESS)))
                .toList();
    }

    /** Where a candidate Silpo quoted no price for sorts: after every candidate that has one. */
    private static final BigDecimal PRICELESS = new BigDecimal("99999999");

    /**
     * A bare staple and the words that mark a variant the line did not ask for.
     *
     * <p>Deterministic for the same reason as stock: the matcher prompt says a bare «Рис» is plain white rice and
     * anything else is -1, and the fast model still took «Рис Sacramento червоний» three times in one night and
     * «Origini Карнаролі білий класичний» at ₴449 once — the name says «білий», after all. The branch this account
     * shops in has no plain rice at all; the honest line is «Не знайшов: Рис», and a candidate that is a different
     * product is not a candidate. A line that names the variant itself («рис басматі») keeps it.
     */
    private static final Map<String, List<String>> VARIANTS_A_BARE_LINE_DOES_NOT_MEAN = Map.of(
            "рис",
            List.of(
                    "червон",
                    "чорн",
                    "рожев",
                    "бур",
                    "коричнев",
                    "дик",
                    "різот",
                    "карнарол",
                    "арборіо",
                    "басмат",
                    "жасмин",
                    "суміш",
                    "спеці",
                    "суші"),
            "локшина",
            List.of("швидкого приготування", "з соусом"),
            "макарони",
            List.of("швидкого приготування", "з соусом"),
            // Live, session 25: «Капуста — 1 шт» on a recipe list bought Kyivkraut sauerkraut at ₴159 — Silpo
            // ranks the jar above the head. A bare cabbage is the vegetable; a list that wants it pickled says so.
            "капуста",
            List.of("квашен", "маринован", "по-корейськ", "салат"));

    static List<String> variantsABareLineDoesNotMean(String asked) {
        String bare = asked.trim();
        for (Map.Entry<String, List<String>> entry : VARIANTS_A_BARE_LINE_DOES_NOT_MEAN.entrySet()) {
            String staple = entry.getKey();
            if (!(bare.equals(staple) || bare.startsWith(staple + " "))) {
                continue;
            }
            // «рис басматі» names the variant: nothing to drop. A plain name with no colour in it («Рис
            // Sacramento») is plain rice and stays — only a named variant is refused, never an unmarked one.
            if (entry.getValue().stream().anyMatch(bare::contains)) {
                return List.of();
            }
            return entry.getValue();
        }
        return List.of();
    }

    /**
     * Everything the searches under {@code names} offered for this line, worth showing, cheapest first.
     *
     * <p>One need can go by several names — «регідрон», «електроліти», «ізотонік» are one thing to buy — and the
     * whole point of searching them together is that the cheapest suitable one wins, so the answers are one pool
     * rather than one pool per word. The same product can come back under two of the names; a product id is in
     * this list once.
     */
    private static List<JsonNode> candidatesUnder(
            ShoppingListItem item,
            List<String> names,
            Map<String, List<JsonNode>> productsByQuery,
            boolean noColdChain) {
        List<JsonNode> union = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String name : names) {
            for (JsonNode product : plausibleFor(
                    item, productsByQuery.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()), noColdChain)) {
                String id = McpResponses.findString(product, McpResponses.PRODUCT_ID)
                        .orElse(null);
                if (id == null || !seen.contains(id)) {
                    union.add(product);
                    if (id != null) {
                        seen.add(id);
                    }
                }
            }
        }
        return cheapestFirst(union);
    }

    /** The shelf tags of each line's candidates, in Silpo's own order, as the matcher wants them. */
    private static List<ProductMatchRequest> matchRequests(
            List<ShoppingListItem> items,
            List<List<JsonNode>> candidatesFor,
            boolean preferDiscounted,
            boolean preferUaProducer,
            MatchingHints hints) {
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
                            McpResponses.findNumber(node, McpResponses.STOCK).orElse(null),
                            McpResponses.findNumber(node, McpResponses.OLD_PRICE)
                                    .orElse(null)))
                    .toList();
            requests.add(new ProductMatchRequest(
                    item.getName(),
                    quantityOf(item),
                    item.getUnit(),
                    candidates,
                    preferDiscounted,
                    preferUaProducer,
                    hints.personsWords()));
        }
        return requests;
    }

    /** Steps 1 to 6, in the documented order. Unresolved items are reported, not fatal. */
    public CartSummary buildCart(UUID userId, List<ShoppingListItem> items) {
        return buildCart(userId, items, false);
    }

    /**
     * Same, for a request made «по знижці»: a discounted candidate wins a tie in the product choice. Silpo prices
     * its promotions into the cart itself, so the saving reported afterwards is its own number.
     */
    public CartSummary buildCart(UUID userId, List<ShoppingListItem> items, boolean preferDiscounted) {
        return buildCart(userId, items, preferDiscounted, MatchingHints.NONE);
    }

    /**
     * The same, carrying what the product choice knows about this cart beyond its lines (task 72): the sentence
     * that asked for it, and the other shelf names a line's need goes by. {@link MatchingHints#NONE} for a weekly
     * plan or a reorder, where a line is exactly its own name and no one sentence stands behind the list.
     */
    public CartSummary buildCart(
            UUID userId, List<ShoppingListItem> items, boolean preferDiscounted, MatchingHints hints) {
        // Task 54: the timer wraps the whole pipeline, both exits, because "how long does a cart take" is the
        // question a person waiting on one actually has, and a failure that takes 90s is the worst case of all.
        long startedAt = System.nanoTime();
        try {
            CartSummary built = build(userId, items, preferDiscounted, hints);
            observabilityService.recordCartBuild(
                    built.belowMinimumOrder() ? "below_minimum" : "ok",
                    Duration.ofNanos(System.nanoTime() - startedAt));
            observabilityService.recordMinimumOrder(built.belowMinimumOrder());
            return built;
        } catch (RuntimeException e) {
            observabilityService.recordCartBuild("failed", Duration.ofNanos(System.nanoTime() - startedAt));
            throw e;
        }
    }

    private CartSummary build(
            UUID userId, List<ShoppingListItem> items, boolean preferDiscounted, MatchingHints hints) {
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
        ProductResolution resolution = resolve(userId, context, items, preferDiscounted, hints);
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
        observabilityService.recordCartLines(resolved.size(), unresolved.size());
        addProductsToCart(userId, context, resolved);
        List<String> promoted = resolved.stream()
                .filter(ResolvedProduct::promoted)
                .map(ResolvedProduct::productId)
                .distinct()
                .toList();
        // A cart under Silpo's minimum order comes back as such, with the goods total and the minimum on it, and no
        // checkout link. It is not topped up here: a carbonara that came back with twelve lines of vegetables under
        // it was the household's first sight of the top-up, and «тут забагато лишнього» was the verdict. The
        // decision — add from the baseline, or go and add something in the Silpo app — is asked, see
        // CartConfirmationService; only a reorder, which is restocking staples anyway, takes {@link #topUp} unasked.
        return getVerifiedCart(userId, context, deliverySlot, unresolved, promoted, resolution.skipped())
                .withRequestedNames(requestedNames(resolved));
    }

    /**
     * Which list line each product was resolved for, kept for the basket lines (task 39). Two lines can resolve to
     * the same product — «Курка (ціла)» and «Курка (гомілка)» did — and the first one to ask for it wins, the same
     * way the quantities are merged.
     */
    private static Map<String, String> requestedNames(List<ResolvedProduct> resolved) {
        Map<String, String> names = new LinkedHashMap<>();
        resolved.forEach(product -> {
            if (product.productId() != null && product.requestedName() != null) {
                names.putIfAbsent(product.productId(), product.requestedName());
            }
        });
        return names;
    }

    /**
     * Lifts a cart that came back under Silpo's minimum order over it with the household's own baseline, on request.
     *
     * @throws CartBuildException carrying the amount and the minimum when there is no baseline to draw on
     */
    public CartSummary topUp(UUID userId, CartSummary cart) {
        return topUp(userId, cart, java.util.Set.of());
    }

    /**
     * The same, leaving out baseline lines by name — what a check-in just said the household still has. The
     * baseline is what they buy every week; the check-in is what they have this week, and the second wins.
     */
    public CartSummary topUp(UUID userId, CartSummary cart, java.util.Set<String> leaveOut) {
        CartContext context = getOrCreateCartContext(userId);
        OfferedSlot deliverySlot = firstDeliverableSlot(userId, context);
        java.util.Set<String> alreadyInCart = cart.items().stream()
                .map(BasketItem::silpoProductId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<ResolvedProduct> topUp =
                topUpFromBaseline(userId, context, alreadyInCart, leaveOut, cart.goodsTotal(), cart.minimumOrder());
        if (topUp.isEmpty()) {
            observabilityService.recordTopUp("no_baseline");
            throw new CartBuildException(
                    "no baseline to top cart %s up from".formatted(cart.cartId()),
                    cart.validations(),
                    cart.goodsTotal(),
                    cart.minimumOrder());
        }
        // Counted here rather than in the callers: a scheduled reorder tops up unasked (ReorderService), and a
        // counter that only saw the button tap would miss half the population.
        observabilityService.recordTopUp("applied");
        addProductsToCart(userId, context, topUp);
        List<String> toppedUpLines = new ArrayList<>(
                topUp.stream().map(CartBuildingService::describeTopUp).toList());
        CartSummary verified = getVerifiedCart(
                userId,
                context,
                deliverySlot,
                cart.unresolved(),
                cart.promotedProductIds(),
                cart.skippedLines(),
                toppedUpLines);
        // A baseline line the branch has run out of is taken out by the read-back, and the cheapest baseline lines
        // are exactly the ones a small cart reaches for first — live, milk and a potato went in and came out again
        // and the cart landed ₴22 under the line with nothing but «Скасувати» under it. Reach again, past what
        // was just tried, until the line is cleared or the baseline is spent.
        for (int round = 0; round < TOP_UP_ROUNDS && verified.belowMinimumOrder(); round++) {
            java.util.Set<String> triedNames = new java.util.HashSet<>(toppedUpLines.stream()
                    .map(CartBuildingService::nameOfTopUpLine)
                    .toList());
            verified.unresolved().stream()
                    .map(CartBuildingService::nameOfTopUpLine)
                    .forEach(triedNames::add);
            java.util.Set<String> inCart = verified.items().stream()
                    .map(BasketItem::silpoProductId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            // The names already tried go into the exclusion, not onto a filter afterwards: the cheapest baseline
            // lines are exactly the ones the branch had none of, and picking them again only to drop them left
            // ₴57 on the table with nothing but «Скасувати» under the cart (live, session 25).
            java.util.Set<String> skip = new java.util.HashSet<>(leaveOut);
            skip.addAll(triedNames);
            List<ResolvedProduct> more =
                    topUpFromBaseline(userId, context, inCart, skip, verified.goodsTotal(), verified.minimumOrder());
            if (more.isEmpty()) {
                break;
            }
            addProductsToCart(userId, context, more);
            toppedUpLines = new ArrayList<>(verified.toppedUpLines());
            more.stream().map(CartBuildingService::describeTopUp).forEach(toppedUpLines::add);
            verified = getVerifiedCart(
                    userId,
                    context,
                    deliverySlot,
                    verified.unresolved(),
                    cart.promotedProductIds(),
                    cart.skippedLines(),
                    toppedUpLines);
        }
        // The cart is read back whole, so the lines that were already in it would otherwise lose the list
        // line they were bought for. The top-up lines get none: nobody asked for them by name.
        return verified.withRequestedNames(BasketItem.requestedNamesByProductId(cart.items()));
    }

    /** How many more times a top-up reaches into the baseline after a read-back took some of its lines out. */
    private static final int TOP_UP_ROUNDS = 2;

    /** The catalog name in front of «— 1 шт, 45.99 грн» or «(немає на складі)». */
    private static String nameOfTopUpLine(String line) {
        int cut = line.indexOf(" — ");
        String name = cut < 0 ? line : line.substring(0, cut);
        return name.endsWith(OUT_OF_STOCK_SUFFIX)
                ? name.substring(0, name.length() - OUT_OF_STOCK_SUFFIX.length())
                : name;
    }

    /** Appended to a line's name under «Не знайшов» when the branch had none of it at read-back. */
    static final String OUT_OF_STOCK_SUFFIX = " (немає на складі)";

    /** Whether there is a confirmed baseline to top a small cart up from — decides whether to offer it. */
    public boolean hasBaseline(UUID userId) {
        return baselineBasketRepository.findByUserIdAndIsCurrentTrue(userId).isPresent();
    }

    /**
     * Lines a small cart is short of Silpo's minimum delivery order, taken from the household's own baseline.
     *
     * <p>A dish's ingredients, a blackout lunch or a Friday-night snack cart comes to a few hundred hryvnia, and
     * Silpo's home delivery starts at ₴799 (its own {@code order.cost.min}). The shortfall is filled with what this
     * household buys every week anyway — their confirmed baseline, cheapest lines first, each carrying the product
     * id and price of a real past order — and every added line is named in the cart message with the right to take
     * it out. Nothing is added for a household with no baseline yet; that case is explained instead.
     */
    private List<ResolvedProduct> topUpFromBaseline(
            UUID userId,
            CartContext context,
            java.util.Set<String> alreadyInCart,
            java.util.Set<String> leaveOut,
            BigDecimal total,
            BigDecimal minimum) {
        List<BasketItem> baseline = eligibleForTopUp(
                baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(userId)
                        .map(BaselineBasket::getItems)
                        .orElseGet(List::of),
                alreadyInCart,
                leaveOut);
        if (baseline.isEmpty()) {
            log.info(
                    "cart {} is {} short of the {} minimum and there is no baseline to top it up from",
                    context.cartId(),
                    minimum.subtract(total == null ? BigDecimal.ZERO : total),
                    minimum);
            return List.of();
        }
        BigDecimal running = total == null ? BigDecimal.ZERO : total;
        List<ResolvedProduct> topUp = new ArrayList<>();
        for (BasketItem item : pickTopUp(baseline, running, minimum)) {
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

    /** Baseline lines a top-up may reach for, cheapest line first: priced, not in the cart, not named as had or tried. */
    static List<BasketItem> eligibleForTopUp(
            List<BasketItem> baseline, java.util.Set<String> alreadyInCart, java.util.Set<String> leaveOut) {
        return baseline.stream()
                .filter(item -> item.silpoProductId() != null
                        && item.price() != null
                        && item.price().signum() > 0)
                .filter(item -> !alreadyInCart.contains(item.silpoProductId()))
                .filter(item -> leaveOut.stream().noneMatch(name -> name.equalsIgnoreCase(item.name())))
                .sorted(java.util.Comparator.comparing(
                        item -> item.price().multiply(item.quantity() == null ? BigDecimal.ONE : item.quantity())))
                .toList();
    }

    /**
     * The cheapest eligible lines, in order, until the cart clears the minimum with a margin. Baseline prices are
     * what the household paid last time; today's may be a little lower after a discount, and landing a few hryvnia
     * short means another refused cart — so the aim is a little past the line.
     */
    static List<BasketItem> pickTopUp(List<BasketItem> eligible, BigDecimal total, BigDecimal minimum) {
        BigDecimal running = total == null ? BigDecimal.ZERO : total;
        BigDecimal target = minimum.multiply(new BigDecimal("1.05"));
        List<BasketItem> picked = new ArrayList<>();
        for (BasketItem item : eligible) {
            if (running.compareTo(target) >= 0) {
                break;
            }
            BigDecimal quantity =
                    item.quantity() == null || item.quantity().signum() <= 0 ? BigDecimal.ONE : item.quantity();
            picked.add(item);
            running = running.add(item.price().multiply(quantity));
        }
        return picked;
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
                        line.unitPrice()
                                .multiply(line.quantity())
                                .setScale(2, RoundingMode.HALF_UP)
                                .toPlainString());
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

    /**
     * Points this household's cart at somebody else's door (task 81), and hands back the delivery block it was
     * using so it can be put back later.
     *
     * <p>Order matters and is not a preference: a branch comes with the address, and moving the cart to another
     * branch invalidates every product already in it — probed live on 2026-09-10, three
     * {@code product.offer.not_found} validations for a three-line cart. So this runs before any product is
     * resolved, which also means the search that follows runs against the shelf the order is picked from.
     *
     * @return the cart that was moved and the household's own {@code deliveryType / timeslot / address /
     *     shipments}, for {@link #restoreOwnDelivery} to write back. The only copy — this account has no saved
     *     Silpo address to reconstruct one from — and the two travel together because a snapshot with no cart to
     *     put it back on is not a restore, it is a leak that looks like one.
     */
    public RepointedCart repointCartTo(UUID userId, GiftAddress destination) {
        CartContext context = getOrCreateCartContext(userId);
        JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("shoppingCartId", context.cartId()));
        Map<String, Object> ownDelivery = deliveryBlockOf(cart);

        JsonNode found = call(userId, TOOL_FIND_ADDRESS, Map.of("address", destination.addressText()));
        JsonNode place = McpResponses.findArray(found, McpResponses.ADDRESSES).stream()
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("silpo_find_address matched nothing for a gift address of user {}", userId);
                    return new GiftDeliveryUnavailableException(
                            "Silpo could not place the gift address given by user " + userId);
                });
        BigDecimal latitude = requireNumber(place, McpResponses.LATITUDE, userId, "a gift address had no latitude");
        BigDecimal longitude = requireNumber(place, McpResponses.LONGITUDE, userId, "a gift address had no longitude");

        JsonNode types = call(userId, TOOL_DELIVERY_TYPES, Map.of("latitude", latitude, "longitude", longitude));
        String branchId = McpResponses.findArray(types, McpResponses.DELIVERY_TYPE_OPTIONS).stream()
                .filter(option -> McpResponses.findString(option, McpResponses.DELIVERY_TYPE)
                        .map(DELIVERY_HOME::equals)
                        .orElse(false))
                .findFirst()
                .flatMap(option -> McpResponses.findString(option, McpResponses.BRANCH_ID))
                .orElseThrow(() -> {
                    log.warn("no DeliveryHome option at {},{} for a gift from user {}", latitude, longitude, userId);
                    return new GiftDeliveryUnavailableException(
                            "Silpo offers no home delivery at the gift address for user " + userId);
                });
        String companyId = McpResponses.findArray(cart, McpResponses.SHIPMENTS).stream()
                .findFirst()
                .flatMap(shipment -> McpResponses.findString(shipment, McpResponses.COMPANY_ID))
                .or(() -> McpResponses.findString(cart, McpResponses.COMPANY_ID))
                .orElseThrow(() -> new CartBuildException("no companyId to ship a gift with for user " + userId));

        // The gift's own branch, so the slot lookup asks about the shop that will actually pick this order.
        CartContext giftContext = new CartContext(context.cartId(), branchId, companyId, DELIVERY_HOME, null, null);
        OfferedSlot slot = firstDeliverableSlot(userId, giftContext);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("deliveryType", DELIVERY_HOME);
        Map<String, Object> timeslot = new LinkedHashMap<>();
        timeslot.put("start", slot.id());
        timeslot.put("end", slot.end() == null ? slot.id() : slot.end());
        changes.put("timeslot", timeslot);
        changes.put("address", giftAddressArguments(place, destination));
        changes.put("shipments", List.of(Map.of("companyId", companyId, "branchId", branchId)));
        changes.put("branchId", branchId);
        if (!updateCart(userId, context.cartId(), changes)) {
            throw new CartBuildException("Silpo declined to point cart " + context.cartId() + " at a gift address");
        }
        log.info("pointed cart {} at a gift address on branch {} for user {}", context.cartId(), branchId, userId);
        return new RepointedCart(context.cartId(), ownDelivery);
    }

    /**
     * A cart that is currently pointed somewhere it does not belong, and what to put back on it.
     *
     * @param cartId the cart that was moved
     * @param ownDelivery the delivery block it carried before
     */
    public record RepointedCart(String cartId, Map<String, Object> ownDelivery) {}

    /**
     * Puts the household's own delivery settings back.
     *
     * <p>Best effort, like every other {@code updateCart} caller: a refusal is worth a log line and another go on
     * the next build, never a failed order.
     */
    public boolean restoreOwnDelivery(UUID userId, String cartId, Map<String, Object> ownDelivery) {
        if (ownDelivery == null || ownDelivery.isEmpty()) {
            return false;
        }
        boolean restored = updateCart(userId, cartId, ownDelivery);
        log.info("restoring the household's own delivery on cart {} for user {}: {}", cartId, userId, restored);
        return restored;
    }

    /** The four fields {@code silpo_update_shopping_cart} demands on every call, as the cart currently has them. */
    private static Map<String, Object> deliveryBlockOf(JsonNode cart) {
        Map<String, Object> block = new LinkedHashMap<>();
        McpResponses.findString(cart, McpResponses.DELIVERY_TYPE).ifPresent(v -> block.put("deliveryType", v));
        McpResponses.findNode(cart, McpResponses.TIMESLOT)
                .filter(JsonNode::isObject)
                .ifPresent(node -> block.put("timeslot", MAPPER.convertValue(node, Map.class)));
        McpResponses.findNode(cart, McpResponses.ADDRESS)
                .filter(JsonNode::isObject)
                .ifPresent(node -> block.put("address", MAPPER.convertValue(node, Map.class)));
        List<Map<String, Object>> shipments = new ArrayList<>();
        for (JsonNode shipment : McpResponses.findArray(cart, McpResponses.SHIPMENTS)) {
            Map<String, Object> reduced = new LinkedHashMap<>();
            McpResponses.findString(shipment, McpResponses.COMPANY_ID).ifPresent(v -> reduced.put("companyId", v));
            McpResponses.findString(shipment, McpResponses.BRANCH_ID).ifPresent(v -> reduced.put("branchId", v));
            if (!reduced.isEmpty()) {
                shipments.add(reduced);
            }
        }
        if (!shipments.isEmpty()) {
            block.put("shipments", shipments);
        }
        return block;
    }

    /**
     * The address object for a gift.
     *
     * <p>Coordinates go as strings because that is how the live cart returns them, and {@code courrierComment}
     * says out loud what this delivery is, so a courier at a stranger's door has some idea why.
     */
    static Map<String, Object> giftAddressArguments(JsonNode place, GiftAddress destination) {
        Map<String, Object> address = new LinkedHashMap<>();
        boolean hasFlat = destination.flat() != null && !destination.flat().isBlank();
        address.put("addressType", hasFlat ? "flat" : "house");
        McpResponses.findNumber(place, McpResponses.LATITUDE)
                .ifPresent(v -> address.put("latitude", v.stripTrailingZeros().toPlainString()));
        McpResponses.findNumber(place, McpResponses.LONGITUDE)
                .ifPresent(v -> address.put("longitude", v.stripTrailingZeros().toPlainString()));
        McpResponses.findString(place, McpResponses.CITY).ifPresent(v -> address.put("city", v));
        McpResponses.findString(place, McpResponses.STREET).ifPresent(v -> address.put("street", v));
        McpResponses.findString(place, McpResponses.HOUSE).ifPresent(v -> address.put("house", v));
        McpResponses.findString(place, McpResponses.DISTRICT).ifPresent(v -> address.put("district", v));
        address.put("locality", destination.addressText());
        putIfFilled(address, "flat", destination.flat());
        putIfFilled(address, "entrance", destination.entrance());
        putIfFilled(address, "floor", destination.floor());
        putIfFilled(address, "phone", destination.phone());
        address.put("courrierComment", "Подарунок — телефонуйте отримувачу за номером у замовленні");
        return address;
    }

    private static void putIfFilled(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private BigDecimal requireNumber(JsonNode node, String[] keys, UUID userId, String problem) {
        return McpResponses.findNumber(node, keys).orElseThrow(() -> {
            log.error("{} for user {}. Raw response: {}", problem, userId, node);
            return new CartBuildException(problem + " for user " + userId);
        });
    }

    /**
     * Books a delivery window on an existing cart.
     *
     * <p>{@code silpo_update_shopping_cart} is not a patch: its schema marks the delivery type, the address and the
     * shipments required on every call and says to copy them from {@code silpo_get_shopping_cart_by_id} verbatim. The
     * two confirm flows used to send {@code {cartId, timeslot: <id>}} and were refused with «Invalid arguments» on
     * every live run since task 15 — the household read «Доставка: вт · 10:30–12:00» while Silpo still held the
     * 09:00 window. Best effort by design: a refusal is reported, not fatal, because checkout can still fix the window.
     *
     * @return whether Silpo accepted the change
     */
    public boolean bookSlot(UUID userId, String cartId, OfferedSlot slot) {
        // Real slots carry no id of their own, so the id is the start (see offeredTimeSlots); the end rides along.
        Map<String, Object> timeslot = new LinkedHashMap<>();
        timeslot.put("start", slot.id());
        if (slot.end() != null) {
            timeslot.put("end", slot.end());
        }
        return updateCart(userId, cartId, Map.of("timeslot", timeslot));
    }

    /**
     * Asks Silpo to pay part of the cart with loyalty bonuses. Same tool, same required baggage as {@link #bookSlot}.
     */
    public boolean applyBonuses(UUID userId, String cartId, BigDecimal bonuses) {
        return updateCart(userId, cartId, Map.of("bonusRequested", bonuses));
    }

    /**
     * Puts a promo code on the cart (task 79).
     *
     * <p>The live {@code silpo_update_shopping_cart} schema carries {@code promoCode} — {@code string | null} —
     * right beside {@code bonusRequested}, so this is a real application rather than a display of a code the
     * household would have to retype in the Silpo app. Same best-effort contract as the bonuses: Silpo refusing a
     * code costs a discount, and losing the order over it would cost far more.
     *
     * <p>What a {@code true} here does <em>not</em> mean: that the code is valid. Probed live on 2026-09-10, the
     * tool answered {@code {"success": true, "summary": "Shopping cart updated"}} to an invented code and stored
     * it on the cart verbatim, with no validation and no discount. Only the fact that the code reached the cart is
     * known from here, which is exactly what the message a household reads says.
     */
    public boolean applyPromoCode(UUID userId, String cartId, String promoCode) {
        return updateCart(userId, cartId, Map.of("promoCode", promoCode));
    }

    /**
     * What the cart costs right now, straight from Silpo.
     *
     * <p>Every benefit changes the amount, and {@code silpo_add_or_update_certificates} says in its own description
     * to read the cart back and check whether the total moved. The confirmation message quotes this number, so the
     * saving a household is told about is Silpo's arithmetic and not ours. Empty when the cart could not be read —
     * that is a reason to stay quiet about the new total, never to fail the order.
     */
    public Optional<BigDecimal> readCartTotal(UUID userId, String cartId) {
        try {
            JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("shoppingCartId", cartId));
            return McpResponses.findNumber(cart, McpResponses.TOTAL);
        } catch (RuntimeException e) {
            log.warn("could not re-read cart {} after applying benefits: {}", cartId, e.getMessage());
            return Optional.empty();
        }
    }

    /** One {@code silpo_update_shopping_cart} call: the cart's own required fields read back, plus {@code changes}. */
    private boolean updateCart(UUID userId, String cartId, Map<String, Object> changes) {
        try {
            JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("shoppingCartId", cartId));
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("shoppingCartId", cartId);
            arguments.put(
                    "deliveryType",
                    McpResponses.findString(cart, McpResponses.DELIVERY_TYPE).orElse(""));
            McpResponses.findNode(cart, McpResponses.TIMESLOT)
                    .filter(JsonNode::isObject)
                    .ifPresent(node -> arguments.put("timeslot", MAPPER.convertValue(node, Map.class)));
            McpResponses.findNode(cart, McpResponses.ADDRESS)
                    .filter(JsonNode::isObject)
                    .ifPresent(node -> arguments.put("address", MAPPER.convertValue(node, Map.class)));
            List<Map<String, Object>> shipments = new ArrayList<>();
            for (JsonNode shipment : McpResponses.findArray(cart, McpResponses.SHIPMENTS)) {
                Map<String, Object> reduced = new LinkedHashMap<>();
                McpResponses.findString(shipment, McpResponses.COMPANY_ID).ifPresent(v -> reduced.put("companyId", v));
                McpResponses.findString(shipment, McpResponses.BRANCH_ID).ifPresent(v -> reduced.put("branchId", v));
                if (!reduced.isEmpty()) {
                    shipments.add(reduced);
                }
            }
            if (shipments.isEmpty()) {
                // A cart with no shipments array (a stub, an empty cart): the cart-level branch is the only shipment.
                Map<String, Object> reduced = new LinkedHashMap<>();
                McpResponses.findString(cart, McpResponses.COMPANY_ID).ifPresent(v -> reduced.put("companyId", v));
                McpResponses.findString(cart, McpResponses.BRANCH_ID).ifPresent(v -> reduced.put("branchId", v));
                if (!reduced.isEmpty()) {
                    shipments.add(reduced);
                }
            }
            arguments.put("shipments", shipments);
            arguments.putAll(changes);
            log.debug("MCP -> {} {}", TOOL_UPDATE_CART, arguments);
            McpToolResponse response = silpoMcpClient.callTool(TOOL_UPDATE_CART, arguments, userId);
            if (response.isError()) {
                log.warn("Silpo declined to update cart {} with {}", cartId, changes.keySet());
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("could not update cart {} with {}: {}", cartId, changes.keySet(), e.getMessage());
            return false;
        }
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
        return resolve(userId, context, items, false).resolved();
    }

    /**
     * {@link #resolveProducts}, keeping the lines the sanity check held back — the cart message names them.
     *
     * <p>Two passes. The first searches every line by its own name. Lines that come back with nothing acceptable
     * — no candidates at all, or candidates the matcher refused — get a second search under the other names the
     * product might carry on a shelf («Вівсянка» → «Вівсяні пластівці», «Яйця курячі» → «Яйця»): Silpo's search
     * is a plain text match, and on a live account it returned nothing for eggs and only sauerkraut for cabbage.
     */
    public ProductResolution resolve(
            UUID userId, CartContext context, List<ShoppingListItem> items, boolean preferDiscounted) {
        return resolve(userId, context, items, preferDiscounted, MatchingHints.NONE);
    }

    /**
     * The same, carrying what else is known about this cart (task 72) — see {@link MatchingHints}.
     */
    public ProductResolution resolve(
            UUID userId,
            CartContext context,
            List<ShoppingListItem> items,
            boolean preferDiscounted,
            MatchingHints hints) {
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

        // Task 63: how many plausible candidates the catalog offered for each line, kept for the report's
        // approximated organic baseline. A line the second pass rescues has no first-pass candidate list and
        // stays absent here — an honest missing count rather than a fabricated one.
        Map<String, Integer> candidateCounts = new HashMap<>();

        for (int start = 0; start < needsSearch.size(); start += chunkSize) {
            List<ShoppingListItem> chunk = needsSearch.subList(start, Math.min(needsSearch.size(), start + chunkSize));
            List<String> terms = new ArrayList<>();
            for (ShoppingListItem item : chunk) {
                // The plain name. «Молоко українського виробництва» used to be the term when the UA-only flag
                // was set, and Silpo's plain-text search answered nothing for every line of a 24-line list;
                // the preference is the matcher's job, over candidates a plain search actually returns.
                addTerm(terms, item.getName());
                // Task 72: and every other name this need goes by, in this same pass. Живий приклад: Silpo has no
                // cheap charcoal tablets, only a ₴464 imported supplement, while Атоксіл is ₴119 on the same
                // shelf — a line bound to one name buys that name at whatever it costs.
                hints.alsoSearchFor(item.getName()).forEach(term -> addTerm(terms, term));
                PartnerPromotion promotion = promotionFor.get(item);
                if (promotion != null) {
                    addTerm(terms, promotion.getProductName());
                }
            }
            // A chunk's lines can now ask for more terms than one call takes — a line with alternative names, a
            // partner placement's own product name — and Silpo refuses the whole search past thirty of them.
            Map<String, List<JsonNode>> productsByQuery = search(userId, context, terms);
            // Every line, decided in one call rather than by taking whatever Silpo ranked first — see
            // ProductMatchingService for what that ranking actually returns. A line a partner placement claims is
            // matched here too, deliberately: the placement is only used if it comes back live (task 46), and
            // without an ordinary match behind it a placement that does not would leave the line unresolved.
            List<ShoppingListItem> toMatch = chunk;
            List<List<JsonNode>> candidatesFor = toMatch.stream()
                    .map(item -> {
                        List<String> names = new ArrayList<>(List.of(item.getName()));
                        names.addAll(hints.alsoSearchFor(item.getName()));
                        return candidatesUnder(item, names, productsByQuery, hints.noColdChain());
                    })
                    .toList();
            for (int i = 0; i < toMatch.size(); i++) {
                candidateCounts.putIfAbsent(
                        CategoryWords.normalise(toMatch.get(i).getName()),
                        candidatesFor.get(i).size());
            }
            List<Integer> picked = productMatchingService.choose(
                    matchRequests(toMatch, candidatesFor, preferDiscounted, onlyUaProducer, hints));
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

        secondPass(userId, context, needsSearch, resolved, skipped, preferDiscounted, onlyUaProducer, hints);

        // Task 63: every line that resolved, promoted or not — the denominator a share is computed against.
        categoryResolutionLogService.record(userId, resolved, candidateCounts);

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
            List<String> skipped,
            boolean preferDiscounted,
            boolean preferUaProducer,
            MatchingHints hints) {
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
        // Silpo takes at most thirty terms per search. A 24-line list with two alternatives each is 48, and the
        // whole second pass was refused for it live — thirty at a time, like the first pass.
        Map<String, List<JsonNode>> productsByQuery = new LinkedHashMap<>();
        for (int start = 0; start < terms.size(); start += SEARCH_BATCH_SIZE) {
            List<String> batch = terms.subList(start, Math.min(terms.size(), start + SEARCH_BATCH_SIZE));
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
                                "products", batch));
            } catch (RuntimeException e) {
                log.warn("second search pass failed: {}", e.getMessage());
                return;
            }
            for (JsonNode query : McpResponses.findArray(found, McpResponses.QUERIES)) {
                McpResponses.findString(query, McpResponses.NAME)
                        .ifPresent(text -> productsByQuery.putIfAbsent(
                                text.toLowerCase(Locale.ROOT), McpResponses.findArray(query, McpResponses.PRODUCTS)));
            }
        }
        List<ShoppingListItem> toMatch = new ArrayList<>();
        List<List<JsonNode>> candidatesFor = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : alternatives.entrySet()) {
            ShoppingListItem item = stillMissing.get(entry.getKey());
            List<JsonNode> union = candidatesUnder(item, entry.getValue(), productsByQuery, hints.noColdChain());
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
            picked = productMatchingService.choose(
                    matchRequests(toMatch, candidatesFor, preferDiscounted, preferUaProducer, hints));
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

    /**
     * One search per {@value #SEARCH_BATCH_SIZE} terms, merged into one answer keyed by the query text.
     *
     * <p>Silpo takes at most thirty search terms in a call and refuses the whole call past that — which is how a
     * 24-line list once lost its entire second pass at 48 terms.
     */
    private Map<String, List<JsonNode>> search(UUID userId, CartContext context, List<String> terms) {
        Map<String, List<JsonNode>> productsByQuery = new LinkedHashMap<>();
        for (int start = 0; start < terms.size(); start += SEARCH_BATCH_SIZE) {
            List<String> batch = terms.subList(start, Math.min(terms.size(), start + SEARCH_BATCH_SIZE));
            JsonNode found = call(
                    userId,
                    TOOL_FIND_PRODUCTS,
                    Map.of(
                            "branchId", nullSafe(context.branchId()),
                            "deliveryType", nullSafe(context.deliveryType()),
                            "timeslotStart", nullSafe(context.timeslotStart()),
                            "timeslotEnd", nullSafe(context.timeslotEnd()),
                            "products", batch));
            for (JsonNode query : McpResponses.findArray(found, McpResponses.QUERIES)) {
                McpResponses.findString(query, McpResponses.NAME)
                        .ifPresent(text -> productsByQuery.putIfAbsent(
                                text.toLowerCase(Locale.ROOT), McpResponses.findArray(query, McpResponses.PRODUCTS)));
            }
        }
        return productsByQuery;
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
        return getVerifiedCart(
                userId, context, deliverySlot, unresolved, promotedProductIds, skipped, toppedUp, Recovered.NOTHING);
    }

    /**
     * What this read has already tried to fix, so that neither repair is attempted twice on the same cart. Both
     * are once-only for the same reason: a second refusal of a repair Silpo just accepted is not a race any more,
     * it is a disagreement, and chasing it would spend a person's wait on a loop.
     *
     * @param slot whether the delivery window was re-picked and booked (task 76) — the cart message says so
     */
    private record Recovered(boolean stock, boolean slot) {

        private static final Recovered NOTHING = new Recovered(false, false);

        private Recovered withStock() {
            return new Recovered(true, slot);
        }

        private Recovered withSlot() {
            return new Recovered(stock, true);
        }
    }

    /**
     * The two Silpo refusals the cart can fix by itself: stock, and a delivery window that has gone stale.
     *
     * <p>The stock one. The search prefilter drops a candidate the branch The search prefilter drops a candidate the branch
     * is short of, but a line that never went through a search — a baseline line in a reorder or a top-up, a
     * pre-resolved product id — arrives with no stock figure at all, and Silpo answers the whole cart with
     * {@code product.offer.stock.max} and no checkout link. Live that turned a three-line reorder into «Сільпо
     * тимчасово не відповідає». Silpo's validation names the product and how much is left, which is enough to
     * take the line out (or cut it to what is there), read the cart once more, and tell the household which line
     * went missing. Once only: a second refusal is reported, not chased.
     */
    private CartSummary getVerifiedCart(
            UUID userId,
            CartContext context,
            OfferedSlot deliverySlot,
            List<String> unresolved,
            List<String> promotedProductIds,
            List<String> skipped,
            List<String> toppedUp,
            Recovered recovered) {
        JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("shoppingCartId", context.cartId()));
        if (!recovered.stock()) {
            StockHealing healing = takeOutWhatTheBranchLacks(userId, context, cart);
            if (healing.changedCart()) {
                java.util.Set<String> toppedUpNames = (toppedUp == null ? List.<String>of() : toppedUp)
                        .stream()
                                .map(CartBuildingService::nameOfTopUpLine)
                                .collect(java.util.stream.Collectors.toSet());
                List<String> stillUnresolved = new ArrayList<>(unresolved == null ? List.of() : unresolved);
                // A requested line the branch lacks is reported as missing. A top-up line is not: nobody asked for
                // it by name, and «Не знайшов: Картопля» over a hangover kit reads as a bug.
                healing.gone().stream()
                        .filter(gone -> !toppedUpNames.contains(nameOfTopUpLine(gone)))
                        .forEach(stillUnresolved::add);
                // A top-up line the branch turned out not to have is no longer "added on your behalf".
                List<String> stillToppedUp = (toppedUp == null ? List.<String>of() : toppedUp)
                        .stream()
                                .filter(line -> healing.removedNames().stream()
                                        .noneMatch(name -> line.startsWith(name + " — ")))
                                .toList();
                return getVerifiedCart(
                        userId,
                        context,
                        deliverySlot,
                        stillUnresolved,
                        promotedProductIds,
                        skipped,
                        stillToppedUp,
                        recovered.withStock());
            }
        }

        // Task 76: the window booked on this cart has gone. Time passes between picking a slot and building the
        // cart — a «Змінити» round-trip is minutes of it — and Silpo hands the whole cart back with no checkout
        // link. Nothing about the list is wrong, so nothing is asked of the person: a valid window is picked by
        // the same rule as the first one, booked, and the cart read again. The old message for this said «Виправ
        // список і спробуй ще раз», which sent people to edit a list that was already right.
        if (!recovered.slot() && staleSlot(cart)) {
            List<OfferedSlot> offered = offeredTimeSlots(userId, context);
            if (offered.isEmpty()) {
                log.warn("cart {} is on a stale slot and Silpo offers no other", context.cartId());
                throw new DeliverySlotUnavailableException(
                        "Silpo offered no delivery slot to move cart %s onto".formatted(context.cartId()),
                        List.of("немає доступних слотів доставки"));
            }
            OfferedSlot fresh = offered.getFirst();
            log.info(
                    "the slot booked on cart {} is no longer available; re-picked {} and booking it",
                    context.cartId(),
                    fresh.id());
            bookSlot(userId, context.cartId(), fresh);
            return getVerifiedCart(
                    userId,
                    context.withSlot(fresh),
                    fresh,
                    unresolved,
                    promotedProductIds,
                    skipped,
                    toppedUp,
                    recovered.withSlot());
        }

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
        BigDecimal savings =
                McpResponses.findNumber(cart, McpResponses.CART_DISCOUNT).orElse(null);

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
        if ((isBlank(checkoutWebLink) || isBlank(checkoutMobileLink)) && minimumOrder != null && blocking.size() == 1) {
            // Silpo's one objection is the amount. That is a decision for the household, not a failure: the cart
            // is real, its lines are right, and the message says how far it is from the minimum.
            log.info(
                    "cart {} holds {} of goods against a {} minimum order; leaving the decision to the household",
                    context.cartId(),
                    goodsTotal,
                    minimumOrder);
            return new CartSummary(
                    context.cartId(),
                    deliverySlot == null ? null : deliverySlot.id(),
                    deliverySlot == null ? null : deliverySlot.startsAt(),
                    items,
                    total,
                    validations,
                    bonusAvailable,
                    false,
                    null,
                    null,
                    unresolved,
                    promotedProductIds == null ? List.of() : promotedProductIds,
                    skipped == null ? List.of() : skipped,
                    toppedUp == null ? List.of() : toppedUp,
                    savings,
                    goodsTotal,
                    minimumOrder,
                    recovered.slot());
        }
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
                toppedUp == null ? List.of() : toppedUp,
                savings,
                goodsTotal,
                null,
                recovered.slot());
        log.info(
                "MCP <- cart {} verified: {} items, total {}, bonuses available {}, unresolved {}",
                summary.cartId(),
                summary.items().size(),
                summary.total(),
                summary.bonusAvailable(),
                summary.unresolved().size());
        return summary;
    }

    /**
     * How long to wait before asking again when Silpo answers a tool call with «Rate limit exceeded». Seen live on
     * the minimum-order top-up: the second {@code add_or_update_cart_products} came one second after the first and
     * was refused, and the household read «Сільпо або каталог не відповіли вчасно» for a cart that was one pause
     * away from done. The transport-level 429 is retried by Resilience4j; this is the tool-level variant, which
     * comes back as an ordinary error result and never reaches that retry.
     */
    static final List<Duration> RATE_LIMIT_PAUSES = List.of(Duration.ofSeconds(2), Duration.ofSeconds(4));

    private static final Pattern TOOL_RATE_LIMITED = Pattern.compile("rate limit", Pattern.CASE_INSENSITIVE);

    private JsonNode call(UUID userId, String tool, Map<String, Object> arguments) {
        // Task 58: DEBUG, not INFO — client.AgentCallLog now prints this call as one summarised, redacted
        // line on the demo channel. This one dumps the whole argument map, which is what you want when
        // debugging a shape mismatch and exactly what you do not want on a screen recording.
        log.debug("MCP -> {} {}", tool, arguments);
        McpToolResponse response = silpoMcpClient.callTool(tool, arguments, userId);
        for (Duration pause : RATE_LIMIT_PAUSES) {
            if (!response.isError() || !rateLimited(response)) {
                break;
            }
            log.warn(
                    "Silpo rate-limited {} for cart user {}; waiting {} s and asking again",
                    tool,
                    userId,
                    pause.toSeconds());
            pauseFor(pause);
            response = silpoMcpClient.callTool(tool, arguments, userId);
        }
        if (response.isError()) {
            throw new CartBuildException("Silpo tool %s reported an error".formatted(tool));
        }
        return McpResponses.tree(response);
    }

    private static boolean rateLimited(McpToolResponse response) {
        return response.text() != null
                && TOOL_RATE_LIMITED.matcher(response.text()).find();
    }

    private static void pauseFor(Duration pause) {
        try {
            Thread.sleep(pause.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CartBuildException("interrupted while waiting out a Silpo rate limit");
        }
    }

    /**
     * Removes every line Silpo says the branch has none of, cuts a line the branch has less of down to what is
     * there, and returns the names taken out — each marked so the cart message can say why.
     */
    private StockHealing takeOutWhatTheBranchLacks(UUID userId, CartContext context, JsonNode cart) {
        Map<String, JsonNode> lineByProductId = new LinkedHashMap<>();
        McpResponses.findArray(cart, McpResponses.ITEMS)
                .forEach(node -> McpResponses.findString(node, McpResponses.PRODUCT_ID)
                        .ifPresent(id -> lineByProductId.put(id, node)));
        List<Map<String, Object>> toRemove = new ArrayList<>();
        List<Map<String, Object>> toCut = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        List<String> removedNames = new ArrayList<>();
        for (JsonNode validation : McpResponses.findArray(cart, McpResponses.VALIDATIONS)) {
            if (!"product.offer.stock.max".equals(validation.path("message").asText())) {
                continue;
            }
            JsonNode validationContext = validation.path("context");
            String productId =
                    McpResponses.findString(validationContext, "productId").orElse(null);
            JsonNode line = productId == null ? null : lineByProductId.get(productId);
            if (line == null) {
                continue;
            }
            String name = McpResponses.findString(line, McpResponses.NAME).orElse("товар");
            BigDecimal stock =
                    McpResponses.findNumber(validationContext, "stock").orElse(BigDecimal.ZERO);
            if (stock.signum() <= 0) {
                toRemove.add(Map.of("productId", productId));
                gone.add(name + OUT_OF_STOCK_SUFFIX);
                removedNames.add(name);
                log.info("taking «{}» out of cart {}: the branch has none left", name, context.cartId());
            } else {
                toCut.add(Map.of(
                        "productId",
                        productId,
                        "companyId",
                        nullSafe(context.companyId()),
                        "branchId",
                        nullSafe(context.branchId()),
                        "quantity",
                        stock));
                log.info("cutting «{}» in cart {} down to {}: all the branch has", name, context.cartId(), stock);
            }
        }
        if (!toRemove.isEmpty()) {
            call(userId, TOOL_REMOVE_PRODUCTS, Map.of("shoppingCartId", context.cartId(), "products", toRemove));
        }
        if (!toCut.isEmpty()) {
            call(userId, TOOL_ADD_PRODUCTS, Map.of("shoppingCartId", context.cartId(), "products", toCut));
        }
        return new StockHealing(!toRemove.isEmpty() || !toCut.isEmpty(), gone, removedNames);
    }

    /**
     * What {@link #takeOutWhatTheBranchLacks} did: whether the cart changed at all, the lines it lost as the
     * household will read them, and their bare catalog names for matching other lists.
     */
    private record StockHealing(boolean changedCart, List<String> gone, List<String> removedNames) {}

    /**
     * Whether Silpo is refusing this cart over the window booked on it.
     *
     * <p>Two codes for one condition, both seen live: {@code timeslot.not_available} on a window that has been
     * taken or has passed, {@code timeslot.not_found} on one the branch no longer offers at all.
     */
    private static boolean staleSlot(JsonNode cart) {
        return McpResponses.findArray(cart, McpResponses.VALIDATIONS).stream()
                .map(validation -> validation.path("message").asText())
                .anyMatch(message -> "timeslot.not_available".equals(message) || "timeslot.not_found".equals(message));
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

    private static BigDecimal quantityOf(ShoppingListItem item) {
        return item.getQuantity() == null ? BigDecimal.ONE : item.getQuantity();
    }
}
