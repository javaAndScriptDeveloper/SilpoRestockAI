package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the database-derived Prometheus gauges are kept fresh (task 54).
 *
 * @param refreshInterval how often the snapshot behind the gauges is rebuilt. Shorter than the collector's scrape
 *     interval only adds load; much longer and a demo's first order takes visible minutes to reach the dashboard.
 * @param activeWindow how far back "active households" looks over conversation activity.
 */
@ConfigurationProperties(prefix = "komora.observability")
public record ObservabilityProperties(Duration refreshInterval, Duration activeWindow) {}
