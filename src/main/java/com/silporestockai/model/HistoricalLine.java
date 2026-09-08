package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One line a group agreed on before (task 68), as a signal for the next round.
 *
 * @param productName the catalog name, or the plain name when the catalog lacked it
 * @param quantity how much that round took in total
 * @param unit its unit
 * @param perHead quantity divided by that round's counted headcount — what one person came to
 */
public record HistoricalLine(String productName, BigDecimal quantity, String unit, BigDecimal perHead) {}
