package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One real product Silpo currently offers, found by {@code ReadyMealCatalogService}'s Step A search (task 22) —
 * before any AI call, so {@code MealPlanService} has something real to curate from.
 *
 * @param name the product's name as Silpo's catalog has it
 * @param productId Silpo's product identifier
 * @param companyId company the product belongs to
 * @param branchId branch the price and availability apply to
 * @param price the product's price, when Silpo's search response carried one
 */
public record CatalogCandidate(String name, String productId, String companyId, String branchId, BigDecimal price) {}
