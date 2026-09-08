package com.silporestockai.model;

/**
 * How an organic baseline share was arrived at (task 63) — printed beside every baseline and every lift, because a
 * lift is only as good as the baseline under it and the two must never be read apart.
 */
public enum BaselineMethod {
    /** Observed: how often the ordinary matcher picked this product when the placement did not answer. */
    MEASURED("виміряно"),
    /** Guessed from how many candidates the catalog offered — one brand's naive share of them. */
    APPROXIMATED("наближення (1/N кандидатів)"),
    /** Not enough data for either. The baseline and the lift both print «—» rather than a made-up number. */
    UNKNOWN("—");

    private final String label;

    BaselineMethod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
