package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.model.CartBenefits;
import com.silporestockai.model.GiftCertificate;
import com.silporestockai.model.LoyaltyCoupon;
import com.silporestockai.utils.McpResponses;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The one place that reads and applies Silpo's «Лояльність та акції» category (tasks 78 and 79).
 *
 * <p>What can be applied was decided by the live {@code tools/list} schema, not by the documentation's prose:
 * {@code silpo_update_shopping_cart} carries {@code promoCode} and {@code bonusRequested}, and
 * {@code silpo_add_or_update_certificates} takes barcodes. Nothing anywhere in the 40 live tools accepts a coupon,
 * so coupons are read to be shown and never pretended to be applied — see
 * {@code docs/superpowers/specs/2026-09-10-loyalty-benefits-design.md} for the full schema audit.
 *
 * <p>Every read here is best effort. {@code silpo_get_my_certificates} answered HTTP 500 on every live call during
 * this task's own verification, and a household must not lose a cart because a discount lookup was unwell: a failed
 * read becomes «no offer», is logged, and never reaches the caller as an exception.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyBenefitsService {

    static final String TOOL_CERTIFICATES = "silpo_get_my_certificates";
    static final String TOOL_ADD_CERTIFICATES = "silpo_add_or_update_certificates";
    static final String TOOL_PROMO_CODES = "silpo_get_promo_codes";
    static final String TOOL_COUPONS = "silpo_get_my_coupons";

    private final SilpoMcpClient silpoMcpClient;

    /**
     * What could go on the cart in front of the household right now, plus the coupons worth mentioning beside it.
     *
     * <p>Bonuses are deliberately absent: they arrive on the cart read itself ({@code loyalty.bonusAvailable}), and
     * asking a second tool for the same number would only create a way for the two to disagree.
     */
    public CartBenefits cartBenefits(UUID userId) {
        List<GiftCertificate> certificates = read(userId, TOOL_CERTIFICATES, Map.of())
                .map(LoyaltyBenefitsService::certificatesOf)
                .orElseGet(List::of);
        String promoCode = read(userId, TOOL_PROMO_CODES, Map.of())
                .flatMap(LoyaltyBenefitsService::firstPromoCode)
                .orElse(null);
        List<LoyaltyCoupon> coupons =
                read(userId, TOOL_COUPONS, Map.of()).map(LoyaltyBenefitsService::couponsOf).orElseGet(List::of).stream()
                        // A coupon the household has switched off in the Silpo app is not a benefit they have; saying
                        // otherwise beside a cart would be an offer that quietly does not happen at checkout.
                        .filter(LoyaltyCoupon::active)
                        .toList();
        CartBenefits benefits = new CartBenefits(certificates, promoCode, coupons);
        log.debug(
                "loyalty benefits for user {}: {} certificates, promo code {}, {} active coupons",
                userId,
                certificates.size(),
                promoCode == null ? "none" : "present",
                coupons.size());
        return benefits;
    }

    /**
     * Puts gift certificates on a cart and reports which ones Silpo took.
     *
     * <p>The tool's own contract: {@code added[].validations} being non-empty means that certificate was refused,
     * even though the call as a whole succeeded. Those are named to the household rather than silently dropped —
     * a certificate a person believes was spent and was not is worse than one that visibly failed.
     *
     * @return the barcodes Silpo accepted, in the order they were sent
     */
    public List<String> applyCertificates(UUID userId, String cartId, List<GiftCertificate> certificates) {
        if (certificates == null || certificates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> toAdd = new ArrayList<>();
        for (GiftCertificate certificate : certificates) {
            if (certificate.barcode() == null || certificate.barcode().isBlank()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("barcode", certificate.barcode());
            if (certificate.pincode() != null && !certificate.pincode().isBlank()) {
                entry.put("pincode", certificate.pincode());
            }
            toAdd.add(entry);
        }
        if (toAdd.isEmpty()) {
            return List.of();
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("shoppingCartId", cartId);
        arguments.put("certificatesToAdd", toAdd);

        Optional<JsonNode> response = read(userId, TOOL_ADD_CERTIFICATES, arguments);
        if (response.isEmpty()) {
            return List.of();
        }
        List<JsonNode> added = McpResponses.findArray(response.get(), McpResponses.ADDED);
        if (added.isEmpty()) {
            // Silpo answered, but said nothing about what it took. Treating that as success would put a number in
            // front of a household that nobody verified, so it counts as nothing applied.
            log.info("silpo_add_or_update_certificates answered without an 'added' list for cart {}", cartId);
            return List.of();
        }
        List<String> applied = new ArrayList<>();
        for (JsonNode entry : added) {
            String barcode =
                    McpResponses.findString(entry, McpResponses.BARCODE).orElse(null);
            List<JsonNode> validations = McpResponses.findArray(entry, McpResponses.VALIDATIONS);
            if (barcode == null) {
                continue;
            }
            if (validations.isEmpty()) {
                applied.add(barcode);
            } else {
                log.info("Silpo refused certificate {} on cart {}: {}", barcode, cartId, validations);
            }
        }
        return applied;
    }

    /**
     * One read, with every failure flattened to «nothing to offer».
     *
     * <p>Both failure modes are real on this server: a tool-level error result (the certificates 500) and a thrown
     * transport or auth failure. Neither is allowed to escape.
     */
    private Optional<JsonNode> read(UUID userId, String tool, Map<String, Object> arguments) {
        try {
            McpToolResponse response = silpoMcpClient.callTool(tool, arguments, userId);
            if (response.isError()) {
                log.info("{} answered with an error for user {}, skipping it: {}", tool, userId, response.text());
                return Optional.empty();
            }
            return Optional.of(McpResponses.tree(response));
        } catch (RuntimeException e) {
            log.warn("could not read {} for user {}: {}", tool, userId, e.getMessage());
            return Optional.empty();
        }
    }

    private static List<GiftCertificate> certificatesOf(JsonNode root) {
        List<GiftCertificate> certificates = new ArrayList<>();
        for (JsonNode node : McpResponses.findArray(root, McpResponses.CERTIFICATES)) {
            String barcode = McpResponses.findString(node, McpResponses.BARCODE).orElse(null);
            if (barcode == null || barcode.isBlank()) {
                continue;
            }
            certificates.add(new GiftCertificate(
                    barcode,
                    McpResponses.findString(node, McpResponses.PINCODE).orElse(null),
                    McpResponses.findNumber(node, McpResponses.CERTIFICATE_VALUE)
                            .orElse(null),
                    McpResponses.findString(node, McpResponses.EXPIRES_ON).orElse(null)));
        }
        return certificates;
    }

    /**
     * The first usable promo code.
     *
     * <p>The live account holds none ({@code {"promoCodes": [], "meta": {"total": 0}}}), so the element shape has
     * never been seen: a bare string and an object with a {@code code} field are both accepted rather than guessing
     * one and failing silently in front of a household that does have one.
     */
    private static Optional<String> firstPromoCode(JsonNode root) {
        for (JsonNode node : McpResponses.findArray(root, McpResponses.PROMO_CODES)) {
            if (node.isTextual() && !node.asText().isBlank()) {
                return Optional.of(node.asText());
            }
            Optional<String> code = McpResponses.findString(node, McpResponses.PROMO_CODE);
            if (code.isPresent() && !code.get().isBlank()) {
                return code;
            }
        }
        return Optional.empty();
    }

    static List<LoyaltyCoupon> couponsOf(JsonNode root) {
        List<LoyaltyCoupon> coupons = new ArrayList<>();
        for (JsonNode node : McpResponses.findArray(root, McpResponses.COUPONS)) {
            coupons.add(new LoyaltyCoupon(
                    McpResponses.findNumber(node, McpResponses.COUPON_ID)
                            .orElse(BigDecimal.ZERO)
                            .longValue(),
                    McpResponses.findString(node, McpResponses.COUPON_TITLE).orElse(null),
                    McpResponses.findString(node, McpResponses.REWARD_TEXT).orElse(null),
                    McpResponses.findString(node, McpResponses.END_DATE).orElse(null),
                    McpResponses.findNode(node, McpResponses.ACTIVE)
                            .map(JsonNode::asBoolean)
                            .orElse(false),
                    null,
                    McpResponses.findString(node, McpResponses.LIMIT_TEXT).orElse(null),
                    null));
        }
        return coupons;
    }
}
