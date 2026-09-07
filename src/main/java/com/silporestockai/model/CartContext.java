package com.silporestockai.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the Silpo cart tools say about a guest's current cart, and everything later calls in the sequence need.
 *
 * @param cartId the cart every later call addresses
 * @param branchId the store the cart is bound to; product search needs it
 * @param companyId Silpo's company identifier for that branch
 * @param deliveryType delivery or pickup, as the cart already has it
 * @param timeslotStart the cart's chosen delivery window start, ISO format, null when none is chosen yet
 * @param timeslotEnd the cart's chosen delivery window end, ISO format, null when none is chosen yet
 */
public record CartContext(
        String cartId,
        String branchId,
        String companyId,
        String deliveryType,
        String timeslotStart,
        String timeslotEnd) {

    /**
     * The branch / delivery-type / time-slot triple that every catalog-scoped Silpo tool wants
     * ({@code silpo_get_my_favorites}, {@code silpo_get_my_offline_orders}, the product searches). Their schemas
     * mark all three required, so a missing value goes out as an empty string rather than being dropped — the
     * tool then refuses that one argument by name instead of the whole call being «Invalid arguments».
     */
    public Map<String, Object> catalogArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("branchId", branchId == null ? "" : branchId);
        arguments.put("deliveryType", deliveryType == null ? "" : deliveryType);
        arguments.put("timeslotStart", timeslotStart == null ? "" : timeslotStart);
        arguments.put("timeslotEnd", timeslotEnd == null ? "" : timeslotEnd);
        return arguments;
    }
}
