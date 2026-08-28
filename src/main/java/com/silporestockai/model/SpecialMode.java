package com.silporestockai.model;

/**
 * A temporary override of the user's normal eating pattern. Persisted by name, so entries may be added but existing
 * names must not be renamed without a migration.
 */
public enum SpecialMode {
    /** Normal profile; no override in effect. */
    NONE,
    /** First, strictest week after the user reports gastritis. */
    MEDICAL_GASTRITIS_ACUTE,
    /** The gentler diet the acute phase steps down into. */
    MEDICAL_DIET_TABLE_5,
    /** High-calorie, high-protein plan for deliberate weight gain. */
    MASS_GAIN,
    /** No cooking and no refrigeration — ready meals and preserves only. */
    BLACKOUT
}
