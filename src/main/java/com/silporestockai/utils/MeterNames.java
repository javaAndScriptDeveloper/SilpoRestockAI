package com.silporestockai.utils;

/**
 * Every Micrometer meter name the app publishes, in one place (task 54).
 *
 * <p>Two things depend on these strings agreeing across the codebase and the committed Grafana dashboard: the
 * instrumentation that registers them, and {@code grafana/komora-dashboard.json}'s PromQL. A test parses the dashboard
 * and asserts every {@code komora_*} series it queries maps back to a constant here, which is what catches the usual
 * failure of a committed dashboard — a panel querying a metric that was renamed or never existed.
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

    private MeterNames() {}
}
