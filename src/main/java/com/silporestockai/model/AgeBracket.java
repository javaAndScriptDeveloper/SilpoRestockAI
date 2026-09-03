package com.silporestockai.model;

/**
 * A child's age band, as collected by the onboarding WebApp form. Persisted by name in
 * {@code user_profile.children_age_brackets}, so entries may be added but existing names must not be renamed
 * without a migration.
 */
public enum AgeBracket {
    AGE_0_3,
    AGE_4_7,
    AGE_8_12,
    AGE_13_17
}
