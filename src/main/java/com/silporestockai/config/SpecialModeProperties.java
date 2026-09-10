package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long each stage of a durational special mode lasts, and how often the sweep looks for one that expired.
 *
 * @param gastritisAcuteDuration how long {@code MEDICAL_GASTRITIS_ACUTE} lasts before stepping down to
 *     {@code MEDICAL_DIET_TABLE_5}. A property, not a constant, so a demo can shrink it to seconds.
 * @param gastritisDiet5Duration how long {@code MEDICAL_DIET_TABLE_5} lasts before reverting to {@code NONE}.
 * @param crunchWeekDuration how long {@code CRUNCH_WEEK} (task 67) holds a household on ready meals before
 *     reverting on its own. A week by default, because that is the unit people say it in — «цей тиждень нема часу».
 * @param sweepCron when the expiry sweep runs.
 */
@ConfigurationProperties(prefix = "komora.special-mode")
public record SpecialModeProperties(
        Duration gastritisAcuteDuration,
        Duration gastritisDiet5Duration,
        Duration crunchWeekDuration,
        String sweepCron) {}
