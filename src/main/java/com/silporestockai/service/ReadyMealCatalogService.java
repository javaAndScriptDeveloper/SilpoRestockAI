package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.CatalogCandidate;
import com.silporestockai.utils.McpResponses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Step A of the {@code READY_MEALS_ONLY} pipeline (task 22): finds real, currently-available Silpo products
 * before any AI call, so {@code MealPlanService} has something real to curate from instead of inventing dish
 * names that would later fail to match.
 *
 * <p>Reuses {@code silpo_find_products_batch} — the only product-search tool this application has ever
 * integrated or observed live — rather than assuming an unverified, undocumented category-browse tool exists.
 * Unlike {@link CartBuildingService#resolveProducts}, which keeps only the first match per search term because
 * it is resolving a specific requested item, this keeps every product returned for every term: it is building
 * a candidate pool, not resolving named items.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadyMealCatalogService {

    private static final String TOOL_FIND_PRODUCTS = "silpo_find_products_batch";

    /**
     * Ukrainian dish-category search terms, drawn from the ready-meals prompt's own vocabulary. Fixed, not
     * profile-dependent — dietary constraints are applied by Claude at curation time, not by narrowing this list.
     */
    private static final List<String> CATEGORY_SEARCH_TERMS = List.of(
            "готові страви",
            "салат готовий",
            "гарячий обід готовий",
            "консерви готові до вживання",
            "заморожені готові обіди",
            "випічка готова",
            "сендвіч готовий",
            "суп готовий",
            "борщ готовий",
            "плов готовий");

    private final SilpoMcpClient silpoMcpClient;
    private final CartBuildingService cartBuildingService;

    /**
     * Every real ready-to-eat product Silpo currently offers this household's branch, deduplicated by
     * {@code productId} — the same product can legitimately answer more than one search term.
     */
    public List<CatalogCandidate> findCandidates(UUID userId) {
        CartContext context = cartBuildingService.getOrCreateCartContext(userId);
        // Same fail-fast convention CartBuildingService.buildCart uses: a household nothing can be delivered to
        // should not spend an AI call curating a menu it can never actually order.
        cartBuildingService.firstDeliverableSlot(userId, context);

        JsonNode found = call(
                userId,
                TOOL_FIND_PRODUCTS,
                Map.of(
                        "branchId", nullSafe(context.branchId()),
                        "deliveryType", nullSafe(context.deliveryType()),
                        "timeslotStart", nullSafe(context.timeslotStart()),
                        "timeslotEnd", nullSafe(context.timeslotEnd()),
                        "products", CATEGORY_SEARCH_TERMS));

        Map<String, CatalogCandidate> byProductId = new LinkedHashMap<>();
        for (JsonNode query : McpResponses.findArray(found, McpResponses.QUERIES)) {
            for (JsonNode product : McpResponses.findArray(query, McpResponses.PRODUCTS)) {
                String productId = McpResponses.findString(product, McpResponses.PRODUCT_ID).orElse(null);
                String name = McpResponses.findString(product, McpResponses.NAME).orElse(null);
                if (productId == null || name == null) {
                    continue;
                }
                byProductId.putIfAbsent(
                        productId,
                        new CatalogCandidate(
                                name,
                                productId,
                                McpResponses.findString(product, McpResponses.COMPANY_ID).orElse(context.companyId()),
                                McpResponses.findString(product, McpResponses.BRANCH_ID).orElse(context.branchId()),
                                McpResponses.findNumber(product, McpResponses.PRICE).orElse(null)));
            }
        }
        log.info(
                "MCP <- {} distinct ready-meal candidates from {} search terms",
                byProductId.size(),
                CATEGORY_SEARCH_TERMS.size());
        return List.copyOf(byProductId.values());
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
}
