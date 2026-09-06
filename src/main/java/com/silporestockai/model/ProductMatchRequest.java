package com.silporestockai.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * One shopping list line and everything Silpo offered for it, for the matcher to choose from.
 *
 * @param requestedName the line as the household wrote it
 * @param quantity how much the line asks for
 * @param unit the line's unit
 * @param candidates what the catalog search returned, in Silpo's own order
 * @param preferDiscounted whether the person asked for this «по знижці» — a discounted candidate wins a tie
 */
public record ProductMatchRequest(
        String requestedName,
        BigDecimal quantity,
        String unit,
        List<ProductCandidate> candidates,
        boolean preferDiscounted) {

    /** The ordinary shape: no discount preference. */
    public ProductMatchRequest(
            String requestedName, BigDecimal quantity, String unit, List<ProductCandidate> candidates) {
        this(requestedName, quantity, unit, candidates, false);
    }
}
