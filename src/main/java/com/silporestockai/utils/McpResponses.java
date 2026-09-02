package com.silporestockai.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.silporestockai.client.mcp.McpToolResponse;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads values out of a Silpo MCP tool response without binding to a schema.
 *
 * <p>No real guest account exists in this repository, so the exact shape these tools answer with has never been
 * observed. Rather than guess a nesting and fail as a silent null in front of a jury, every lookup is a breadth-first
 * search for a key name, and every key name this application relies on is in the block below — the one place to fix
 * when the live server disagrees.
 */
@Slf4j
public final class McpResponses {

    public static final String[] CART_ID = {"cartId", "shoppingCartId", "id"};
    public static final String[] CART_EXISTS = {"exists"};
    public static final String[] BRANCH_ID = {"branchId", "filialId"};
    public static final String[] COMPANY_ID = {"companyId"};
    public static final String[] PRODUCT_ID = {"productId", "id"};
    public static final String[] DELIVERY_TYPE = {"deliveryType", "type"};
    public static final String[] TIMESLOT = {"timeslot", "timeSlot", "slot"};
    public static final String[] TIME_SLOTS = {"timeSlots", "timeslots", "slots"};
    public static final String[] SLOT_ID = {"id", "slotId", "code"};
    public static final String[] SLOT_START = {"from", "start", "startTime", "dateTime", "date"};
    public static final String[] PRODUCTS = {"products", "items", "results"};
    public static final String[] QUERIES = {"queries", "results"};
    public static final String[] STEP = {"step"};
    public static final String[] DISPLAY_RATIO = {"displayRatio"};
    public static final String[] ITEMS = {"items", "products", "lines"};
    public static final String[] NAME = {"name", "title", "query", "requestedName"};
    public static final String[] QUANTITY = {"quantity", "amount", "count"};
    public static final String[] UNIT = {"unit", "measure"};
    public static final String[] PRICE = {"price", "sum", "amount"};
    public static final String[] OLD_PRICE = {"oldPrice", "priceOld", "basePrice", "regularPrice"};
    public static final String[] REPLACEMENTS = {"replacements", "substitutes", "alternatives", "products"};
    public static final String[] PROMOTIONS = {"promotions", "promos", "offers", "products"};
    public static final String[] TOTAL = {"total", "totalSum", "sum"};
    public static final String[] VALIDATIONS = {"validations", "errors", "warnings"};
    public static final String[] LOYALTY = {"loyalty"};
    public static final String[] BONUS_AVAILABLE = {"bonusAvailable"};
    public static final String[] BONUS_REQUESTED = {"bonusRequested"};
    public static final String[] LOYALTY_ENABLED = {"isEnabled", "enabled"};
    public static final String[] CHECKOUT_WEB = {"checkoutWebLink", "webLink"};
    public static final String[] CHECKOUT_MOBILE = {"checkoutMobileLink", "mobileLink"};

    // Cart creation for a guest with no cart yet (silpo_create_shopping_cart's own documented workflow).
    public static final String[] ADDRESSES = {"addresses", "items", "results"};
    public static final String[] ADDRESS_TYPE = {"addressType", "type"};
    public static final String[] LATITUDE = {"latitude", "lat"};
    public static final String[] LONGITUDE = {"longitude", "lng", "lon"};
    public static final String[] CITY = {"city"};
    public static final String[] STREET = {"street"};
    public static final String[] HOUSE = {"houseNumber", "house"};
    public static final String[] DISTRICT = {"district"};
    public static final String[] DELIVERY_TYPE_OPTIONS = {"deliveryTypes", "types", "results", "options"};
    public static final String[] BRANCHES = {"branches", "items", "results"};
    public static final String[] SLOT_END = {"to", "end", "endTime"};
    public static final String[] SLOT_AVAILABLE = {"available"};

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private McpResponses() {}

    /** Keeps a log line readable when a tool answers with something unexpectedly large. */
    private static String truncate(String text) {
        return text.length() > 2000 ? text.substring(0, 2000) + "…(truncated)" : text;
    }

    /** The response as a tree: structured content when the server sent it, otherwise the text block parsed as JSON. */
    public static JsonNode tree(McpToolResponse response) {
        if (response == null) {
            return MissingNode.getInstance();
        }
        if (response.structuredContent() != null) {
            return MAPPER.valueToTree(response.structuredContent());
        }
        if (response.text() == null || response.text().isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return MAPPER.readTree(response.text());
        } catch (Exception e) {
            log.warn("MCP response was not JSON ({}): {}", e.getMessage(), truncate(response.text()));
            return MissingNode.getInstance();
        }
    }

    /** The first value found under any of {@code keys}, at any depth, searching breadth-first. */
    public static Optional<JsonNode> findNode(JsonNode root, String... keys) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(root == null ? MissingNode.getInstance() : root);
        while (!queue.isEmpty()) {
            JsonNode node = queue.poll();
            if (node.isObject()) {
                for (String key : keys) {
                    JsonNode found = node.get(key);
                    if (found != null && !found.isNull()) {
                        return Optional.of(found);
                    }
                }
            }
            node.forEach(queue::add);
        }
        return Optional.empty();
    }

    public static Optional<String> findString(JsonNode root, String... keys) {
        return findNode(root, keys).filter(JsonNode::isValueNode).map(JsonNode::asText);
    }

    public static Optional<BigDecimal> findNumber(JsonNode root, String... keys) {
        return findNode(root, keys).filter(JsonNode::isNumber).map(JsonNode::decimalValue);
    }

    /** The first array found under any of the key names, or empty — never null, never a partial list. */
    public static List<JsonNode> findArray(JsonNode root, String... keys) {
        return findNode(root, keys)
                .filter(JsonNode::isArray)
                .map(array -> {
                    List<JsonNode> items = new ArrayList<>();
                    array.forEach(items::add);
                    return items;
                })
                .orElseGet(List::of);
    }
}
