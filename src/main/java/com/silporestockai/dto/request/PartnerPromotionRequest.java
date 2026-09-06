package com.silporestockai.dto.request;

import java.time.Instant;
import java.util.UUID;

/**
 * Configures a paid placement (task 46) through the internal endpoint.
 *
 * @param partnerName who pays
 * @param categoryOrQuery the category word a list line has to contain, e.g. «молоко»
 * @param productQuery how to find the promoted product in the catalog — the product's name; the first catalog hit
 *     is what gets promoted, and its real id and name are stored, never this text
 * @param priorityWeight higher wins when two promotions match the same line; default 100
 * @param activeFrom optional start
 * @param activeTo optional end
 * @param verifyAsUserId a user with a connected Silpo session — the MCP is per-guest OAuth, so the catalog can only
 *     be asked on somebody's behalf; the operator's own account for a demo
 */
public record PartnerPromotionRequest(
        String partnerName,
        String categoryOrQuery,
        String productQuery,
        Integer priorityWeight,
        Instant activeFrom,
        Instant activeTo,
        UUID verifyAsUserId) {}
