package com.silporestockai.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.client.mcp.McpToolResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The half of the demo line with judgement in it: turning a whole tool response into the two or three words that fit
 * after the tick. Every shape here is one the live server actually answers with.
 */
class AgentCallLogWiringTest {

    @Test
    void aListOfProductsIsSummarizedAsACount() {
        McpToolResponse response = McpToolResponse.of(
                List.of("{\"items\":[]}"), Map.of("items", List.of(Map.of("id", 1), Map.of("id", 2))), false);

        assertThat(AgentCallLog.summarizeResult(response.structuredContent(), response.text()))
                .isEqualTo("2 items");
    }

    @Test
    void aResponseWithoutAListFallsBackToItsFieldCount() {
        McpToolResponse response = McpToolResponse.of(List.of("ok"), Map.of("cartId", "abc", "total", 799), false);

        assertThat(AgentCallLog.summarizeResult(response.structuredContent(), response.text()))
                .isEqualTo("2 fields");
    }

    @Test
    void aResponseWithNoStructureFallsBackToTrimmedText() {
        McpToolResponse response = McpToolResponse.of(List.of("cart cleared"), null, false);

        assertThat(AgentCallLog.summarizeResult(response.structuredContent(), response.text()))
                .isEqualTo("cart cleared");
    }
}
