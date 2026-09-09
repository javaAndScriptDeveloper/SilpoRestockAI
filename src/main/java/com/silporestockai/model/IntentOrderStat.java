package com.silporestockai.model;

import java.time.Duration;

/**
 * Intent→order speed for one intent, or for {@code ALL} of them (task 75).
 *
 * @param intent the router's intent name, or {@code MeterNames.INTENT_ALL}
 * @param count confirmed orders this intent produced
 * @param median median sentence→confirmation time across them
 */
public record IntentOrderStat(String intent, long count, Duration median) {}
