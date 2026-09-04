package com.silporestockai.model;

import java.time.Instant;

/**
 * One delivery window Silpo is offering.
 *
 * @param id what {@code silpo_update_shopping_cart} needs to book it
 * @param label what to show a person; whatever the server called it
 * @param startsAt when it starts, or null when the server's date format defeated parsing — a slot whose day cannot be
 *     read simply never matches a household's habit, which the fallback already covers
 * @param end the slot's own end, raw ISO format as Silpo sent it — {@code silpo_find_products_batch} needs this
 *     slot's own window, not whatever timeslot the cart happened to have stored already
 */
public record OfferedSlot(String id, String label, Instant startsAt, String end) {}
