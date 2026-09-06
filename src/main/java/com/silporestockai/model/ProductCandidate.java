package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One product Silpo's search returned for a shopping list line, as {@code ProductMatchingService} shows it to the
 * model that has to pick between them.
 *
 * <p>Deliberately not the raw catalogue node: the model is given what a person comparing shelf tags would look at
 * and nothing it could copy back as an identity. The choice comes back as a position in the list, never as an id.
 *
 * @param name the product's name as Silpo's catalog has it
 * @param price its price — per package for a packaged product, per kilogram for a weighted one
 * @param displayRatio how much one unit contains, e.g. {@code 400г}, {@code 0,75л}, {@code шт}
 * @param weighted whether Silpo sells it by weight rather than by the package
 * @param stock how much of it the branch has left, in the same unit its quantity is counted in
 */
public record ProductCandidate(
        String name, BigDecimal price, String displayRatio, boolean weighted, BigDecimal stock) {}
