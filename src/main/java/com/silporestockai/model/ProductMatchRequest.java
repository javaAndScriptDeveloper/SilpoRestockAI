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
 * @param preferUaProducer whether the household asked for Ukrainian producers — a Ukrainian brand wins a tie,
 *     judged by the matcher from the candidate's name, since the catalog carries no producer field
 * @param personsWords the sentence that asked for this order, when there was one (task 72). Since a bare line is
 *     matched to the cheapest suitable candidate by default, a brand the person named themselves — «привези
 *     Evian» — has to reach the matcher to beat it: the line «вода мінеральна» carries no brand of its own. Null
 *     for lines nobody wrote a sentence for: a weekly plan, a baseline reorder.
 */
public record ProductMatchRequest(
        String requestedName,
        BigDecimal quantity,
        String unit,
        List<ProductCandidate> candidates,
        boolean preferDiscounted,
        boolean preferUaProducer,
        String personsWords) {

    /** The ordinary shape: no preferences. */
    public ProductMatchRequest(
            String requestedName, BigDecimal quantity, String unit, List<ProductCandidate> candidates) {
        this(requestedName, quantity, unit, candidates, false, false, null);
    }

    /** The pre-producer-preference shape. */
    public ProductMatchRequest(
            String requestedName,
            BigDecimal quantity,
            String unit,
            List<ProductCandidate> candidates,
            boolean preferDiscounted) {
        this(requestedName, quantity, unit, candidates, preferDiscounted, false, null);
    }

    /** The pre-task-72 shape: nobody's own words behind this line. */
    public ProductMatchRequest(
            String requestedName,
            BigDecimal quantity,
            String unit,
            List<ProductCandidate> candidates,
            boolean preferDiscounted,
            boolean preferUaProducer) {
        this(requestedName, quantity, unit, candidates, preferDiscounted, preferUaProducer, null);
    }
}
