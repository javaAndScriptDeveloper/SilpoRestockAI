package com.silporestockai.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MCP responses are read by key name, wherever the server nested it")
class McpResponsesTest {

    @Test
    void readsAValueFromStructuredContent() {
        McpToolResponse response = new McpToolResponse(null, Map.of("cartId", "c-1"), false);

        assertThat(McpResponses.findString(McpResponses.tree(response), McpResponses.CART_ID))
                .contains("c-1");
    }

    @Test
    void fallsBackToTheTextBlockWhenThereIsNoStructuredContent() {
        McpToolResponse response = new McpToolResponse("{\"data\":{\"cartId\":\"c-2\"}}", null, false);

        assertThat(McpResponses.findString(McpResponses.tree(response), McpResponses.CART_ID))
                .contains("c-2");
    }

    @Test
    void findsAKeyNestedAnyDepthDown() {
        McpToolResponse response =
                new McpToolResponse(null, Map.of("result", Map.of("cart", Map.of("branchId", "b-9"))), false);

        assertThat(McpResponses.findString(McpResponses.tree(response), McpResponses.BRANCH_ID))
                .contains("b-9");
    }

    @Test
    void acceptsAnyOfSeveralKeyNamesInOrder() {
        JsonNode tree = McpResponses.tree(new McpToolResponse(null, Map.of("id", "c-3"), false));

        // cartId is preferred; id is the documented fallback for a response that carries only one identifier.
        assertThat(McpResponses.findString(tree, McpResponses.CART_ID)).contains("c-3");
    }

    @Test
    void readsNumbersAndArrays() {
        McpToolResponse response = new McpToolResponse(
                null,
                Map.of(
                        "loyalty",
                        Map.of("bonusAvailable", 120.5),
                        "products",
                        List.of(Map.of("productId", "p-1"), Map.of("productId", "p-2"))),
                false);
        JsonNode tree = McpResponses.tree(response);

        assertThat(McpResponses.findNumber(tree, McpResponses.BONUS_AVAILABLE)).contains(new BigDecimal("120.5"));
        assertThat(McpResponses.findArray(tree, McpResponses.PRODUCTS)).hasSize(2);
    }

    @Test
    void answersEmptyRatherThanThrowingForNonsense() {
        JsonNode tree = McpResponses.tree(new McpToolResponse("not json at all", null, false));

        assertThat(McpResponses.findString(tree, McpResponses.CART_ID)).isEmpty();
        assertThat(McpResponses.findArray(tree, McpResponses.PRODUCTS)).isEmpty();
    }
}
