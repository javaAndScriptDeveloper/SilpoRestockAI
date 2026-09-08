package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One line the model proposes for a group round (task 68), before the catalog has been asked.
 *
 * @param name a plain catalog-style name — never a product id, which the model must not invent
 * @param quantity how much, in {@code unit}
 * @param forWhom who this line is for, as the model read the replies
 * @param reason one sentence on why this line, for the group message
 */
public record GroupDrinkLine(String name, BigDecimal quantity, String unit, String forWhom, String reason) {}
