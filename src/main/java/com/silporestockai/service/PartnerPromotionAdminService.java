package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.dto.request.PartnerPromotionRequest;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.exception.ApplicationException;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.utils.McpResponses;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Creates a placement (task 46) the only way one may exist: with a product the catalog itself answered.
 *
 * <p>Separate from {@link PartnerPromotionService} because creation needs {@link CartBuildingService} for a cart
 * context and delivery slot to search against, and {@code CartBuildingService} already depends on the promotion
 * service for resolution — this class sits above both.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerPromotionAdminService {

    private static final String TOOL_FIND_PRODUCTS = "silpo_find_products_batch";

    private final PartnerPromotionRepository partnerPromotionRepository;
    private final SilpoAuthService silpoAuthService;
    private final SilpoMcpClient silpoMcpClient;
    private final CartBuildingService cartBuildingService;
    private final Clock clock;

    public PartnerPromotion create(PartnerPromotionRequest request) {
        if (isBlank(request.partnerName()) || isBlank(request.categoryOrQuery()) || isBlank(request.productQuery())) {
            throw new ApplicationException(
                    HttpStatus.BAD_REQUEST, "partnerName, categoryOrQuery and productQuery are all required");
        }
        if (request.verifyAsUserId() == null || !silpoAuthService.isConnected(request.verifyAsUserId())) {
            throw new ApplicationException(
                    HttpStatus.BAD_REQUEST,
                    "verifyAsUserId must be a user with a connected Silpo session — the catalog is only reachable "
                            + "through a guest's own OAuth session");
        }
        UUID userId = request.verifyAsUserId();
        CartContext context = cartBuildingService.getOrCreateCartContext(userId);
        OfferedSlot slot = cartBuildingService.firstDeliverableSlot(userId, context);
        McpToolResponse response = silpoMcpClient.callTool(
                TOOL_FIND_PRODUCTS,
                Map.of(
                        "branchId", nullSafe(context.branchId()),
                        "deliveryType", nullSafe(context.deliveryType()),
                        "timeslotStart", nullSafe(slot.label()),
                        "timeslotEnd", nullSafe(slot.end()),
                        "products", List.of(request.productQuery().trim())),
                userId);
        if (response.isError()) {
            throw new ApplicationException(HttpStatus.BAD_GATEWAY, "Silpo could not search the catalog right now");
        }
        JsonNode found = McpResponses.tree(response);
        String wanted = request.productQuery().trim();
        List<JsonNode> queries = McpResponses.findArray(found, McpResponses.QUERIES);
        // The entry that answers this very query, when the server labels them; otherwise the first hit anywhere.
        JsonNode product = queries.stream()
                .filter(query -> McpResponses.findString(query, McpResponses.NAME)
                        .map(wanted::equalsIgnoreCase)
                        .orElse(false))
                .flatMap(query -> McpResponses.findArray(query, McpResponses.PRODUCTS).stream())
                .findFirst()
                .or(() -> queries.stream()
                        .flatMap(query -> McpResponses.findArray(query, McpResponses.PRODUCTS).stream())
                        .findFirst())
                .orElseThrow(() -> new ApplicationException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "no catalog product matched «%s» — a placement needs a real product"
                                .formatted(request.productQuery())));
        String productId = McpResponses.findString(product, McpResponses.PRODUCT_ID)
                .orElseThrow(() ->
                        new ApplicationException(HttpStatus.BAD_GATEWAY, "the catalog hit carried no product id"));
        String productName = McpResponses.findString(product, McpResponses.NAME)
                .orElse(request.productQuery().trim());

        PartnerPromotion promotion = partnerPromotionRepository.save(PartnerPromotion.builder()
                .id(UUID.randomUUID())
                .partnerName(request.partnerName().trim())
                .categoryOrQuery(request.categoryOrQuery().trim())
                .silpoProductId(productId)
                .productName(productName)
                .priorityWeight(request.priorityWeight() == null ? 100 : request.priorityWeight())
                .activeFrom(request.activeFrom())
                .activeTo(request.activeTo())
                .status(PartnerPromotionStatus.ACTIVE)
                .createdAt(clock.instant())
                .build());
        log.info(
                "partner placement {} created: «{}» → {} ({}) for {}",
                promotion.getId(),
                promotion.getCategoryOrQuery(),
                productName,
                productId,
                promotion.getPartnerName());
        return promotion;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
