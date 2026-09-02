package com.silporestockai.model;

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
        String cartId, String branchId, String companyId, String deliveryType, String timeslotStart, String timeslotEnd) {}
