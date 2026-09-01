package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How often the agent opens a check-in, and how often it looks for someone to open one with.
 *
 * @param interval how long after the last contact — prompt, answer or confirmed order — a user is asked again. A
 *     property rather than a constant because a three-day demo is not a demo.
 * @param sweepCron when the sweep runs. A cron rather than a fixed delay: {@code fixedDelay} fires once at startup,
 *     and an agent that messages every household the moment the process comes up is a bad thing to own.
 */
@ConfigurationProperties(prefix = "komora.checkin")
public record CheckinProperties(Duration interval, String sweepCron) {}
