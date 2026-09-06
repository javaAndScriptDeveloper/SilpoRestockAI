package com.silporestockai.model;

import java.util.List;

/**
 * What became of a shopping list after the catalog search: the lines that resolved to a product, and the lines
 * whose product was found but deliberately held back.
 *
 * @param resolved lines with a real Silpo product behind them, ready to add
 * @param skipped held-back lines, each described for the household with the reason — see
 *     {@code CartBuildingService.sanityProblem}
 */
public record ProductResolution(List<ResolvedProduct> resolved, List<String> skipped) {}
