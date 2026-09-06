package com.silporestockai.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * A past Silpo order the household can seed their list from (task 35). Round-trips through
 * {@code conversation_state.context_json} between the "pick one" message and the tap.
 *
 * @param orderId Silpo's order identifier, when the response carried one
 * @param source which tool it came from — {@code online} or {@code offline}
 * @param dateLabel the order's date as text, best effort; empty when unknown
 * @param total what it cost, when the response said
 * @param lines its line items — may be empty when the listing tool returns summaries only
 */
public record PastOrderSummary(
        String orderId, String source, String dateLabel, BigDecimal total, List<PastOrderLine> lines) {

    public int itemCount() {
        return lines == null ? 0 : lines.size();
    }
}
