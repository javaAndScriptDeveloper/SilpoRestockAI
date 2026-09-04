package com.silporestockai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** How often the scheduled-ad-hoc-purchase sweep looks for a task whose trigger time has passed. */
@ConfigurationProperties(prefix = "komora.ad-hoc-schedule")
public record AdHocScheduleProperties(String sweepCron) {}
