package com.silporestockai.utils;

/**
 * Every Micrometer meter name the app publishes, in one place (task 54).
 *
 * <p>Two things depend on these strings agreeing across the codebase and the committed Grafana dashboard: the
 * instrumentation that registers them, and the PromQL in {@code observability/grafana/*.json} (two dashboards since
 * task 75). A test parses each dashboard and asserts every {@code komora_*} series it queries maps back to a constant
 * here, which is what catches the usual failure of a committed dashboard — a panel querying a metric that was renamed
 * or never existed.
 *
 * <p>Names are dot-separated and unitless; the unit goes in {@code baseUnit(...)} so Prometheus appends the suffix
 * itself and nothing ends up called {@code _uah_uah}.
 */
public final class MeterNames {

    // --- Gauges, refreshed from the database on a schedule ---
    public static final String USERS_REGISTERED = "komora.users.registered";
    public static final String USERS_ONBOARDED = "komora.users.onboarded";
    public static final String USERS_ACTIVE = "komora.users.active";
    public static final String USERS_ORDERED = "komora.users.ordered";
    public static final String ORDERS_CONFIRMED = "komora.orders.confirmed";
    public static final String ORDERS_GMV = "komora.orders.gmv";
    public static final String ORDERS_GOODS = "komora.orders.goods";
    public static final String ORDERS_SAVINGS = "komora.orders.savings";
    public static final String ORDERS_ITEMS = "komora.orders.items";
    public static final String ORDERS_VALUE_MISSING = "komora.orders.value.missing";
    public static final String ORDERS_LINES = "komora.orders.lines";
    public static final String ONBOARDING_FIRST_ORDER = "komora.onboarding.first.order";
    public static final String PROMOTION_EVENTS = "komora.promotion.events";
    public static final String PROMOTION_SHARE = "komora.promotion.share";
    public static final String PROMOTION_BASELINE = "komora.promotion.baseline";
    public static final String PROMOTION_LIFT = "komora.promotion.lift";
    public static final String PROMOTION_REVENUE = "komora.promotion.revenue";
    public static final String PROMOTION_SHARE_OVERALL = "komora.promotion.share.overall";
    public static final String PROMOTION_REVENUE_OVERALL = "komora.promotion.revenue.overall";
    public static final String PROMOTION_CATEGORIES = "komora.promotion.categories";
    /** Task 75: intent→order speed, per intent and as an {@code ALL} row, read off the table. */
    public static final String INTENT_ORDER_MEDIAN = "komora.intent.order.median";

    public static final String INTENT_ORDERS = "komora.intent.orders";
    /** Task 37's pitch numbers as gauges: check-in prompts vs answers, reorders by edited, longest unedited run. */
    public static final String CHECKINS = "komora.checkins";

    public static final String REORDERS = "komora.reorders";
    public static final String TRUST_STREAK = "komora.trust.streak";

    // --- Counters, timers and summaries, recorded at the call site ---
    public static final String ONBOARDING_STARTED = "komora.onboarding.started";
    public static final String ONBOARDING_COMPLETED = "komora.onboarding.completed";
    public static final String ORDERS_CONFIRMATIONS = "komora.orders.confirmations";
    public static final String CART_VALUE = "komora.cart.value";
    public static final String CART_SIZE = "komora.cart.size";
    public static final String CART_BUILD = "komora.cart.build";
    public static final String CART_LINES = "komora.cart.lines";
    public static final String CART_MINIMUM = "komora.cart.minimum";
    public static final String CART_TOPUP = "komora.cart.topup";
    public static final String MCP_CALL = "komora.mcp.call";
    public static final String CLAUDE_CALL = "komora.claude.call";
    public static final String FAILURE_MESSAGE = "komora.failure.message";
    public static final String INTENT_CLASSIFIED = "komora.intent.classified";
    /** Task 75: the timer from a routed sentence to the confirmed order it produced, tagged by intent. */
    public static final String INTENT_ORDER = "komora.intent.order";

    /** Task 80: the social primitive (person → agent → person) as numbers — rounds and gifts by status. */
    public static final String GROUP_ROUNDS = "komora.group.rounds";

    public static final String GROUP_PARTICIPANTS = "komora.group.participants";
    public static final String GIFT_ORDERS = "komora.gift.orders";

    // --- Tag keys, so a dashboard filter and the code that sets it cannot drift ---
    public static final String TAG_TYPE = "type";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_RESULT = "result";
    public static final String TAG_TOOL = "tool";
    public static final String TAG_CALL = "call";
    public static final String TAG_MODEL = "model";
    public static final String TAG_SOURCE = "source";
    public static final String TAG_KIND = "kind";
    public static final String TAG_STAT = "stat";
    public static final String TAG_WINDOW = "window";
    public static final String TAG_PARTNER = "partner";
    public static final String TAG_PRODUCT = "product";
    public static final String TAG_EVENT = "event";
    public static final String TAG_CATEGORY = "category";
    public static final String TAG_METHOD = "method";
    public static final String TAG_INTENT = "intent";
    public static final String TAG_EDITED = "edited";
    public static final String TAG_STATUS = "status";

    /** The tag value the combined («обидва пули») rollup series carries, so one panel can pick it out. */
    public static final String POOL_ALL = "ALL";

    /** The {@code intent} tag value of the row that folds every intent together. */
    public static final String INTENT_ALL = "ALL";

    private MeterNames() {}
}
