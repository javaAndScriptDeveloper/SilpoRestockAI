package com.silporestockai.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * One shopping list line and the products Silpo offered for it — the unit of work
 * {@code ProductMatchingService} decides on.
 *
 * @param requestedName the line as the household's list has it, e.g. {@code Яловичина}
 * @param quantity how much of it the list asks for
 * @param unit the unit that quantity is in, e.g. {@code г}, {@code шт}
 * @param candidates what Silpo's search returned for it, in Silpo's own order
 */
public record ProductMatchRequest(
        String requestedName, BigDecimal quantity, String unit, List<ProductCandidate> candidates) {}
