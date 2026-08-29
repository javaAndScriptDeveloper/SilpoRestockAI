package com.silporestockai.model;

import java.util.UUID;

/**
 * Published once a user's profile is saved.
 *
 * <p>An event rather than a call into a meal-planning service: task 07 has not been designed yet, and inventing an
 * interface for it here would be guessing at someone else's shape.
 *
 * @param userId the user whose profile is now complete
 */
public record OnboardingCompletedEvent(UUID userId) {}
