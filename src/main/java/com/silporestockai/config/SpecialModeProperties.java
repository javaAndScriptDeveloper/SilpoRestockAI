package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long each stage of a durational special mode lasts, and how often the sweep looks for one that expired.
 *
 * @param gastritisAcuteDuration how long {@code MEDICAL_GASTRITIS_ACUTE} lasts before stepping down to
 *     {@code MEDICAL_DIET_TABLE_5}. A property, not a constant, so a demo can shrink it to seconds.
 * @param gastritisDiet5Duration how long {@code MEDICAL_DIET_TABLE_5} lasts before reverting to {@code NONE}.
 * @param sweepCron when the expiry sweep runs.
 */
@ConfigurationProperties(prefix = "komora.special-mode")
public record SpecialModeProperties(Duration gastritisAcuteDuration, Duration gastritisDiet5Duration, String sweepCron) {}
