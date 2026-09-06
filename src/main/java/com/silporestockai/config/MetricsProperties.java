package com.silporestockai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gate for the pitch-metrics report (task 37).
 *
 * @param token the value {@code X-Metrics-Token} has to carry; blank means the endpoint does not exist (404). Not a
 *     login system — a shared secret so a tunnelled demo box does not serve its usage numbers to whoever finds the
 *     URL.
 */
@ConfigurationProperties(prefix = "komora.metrics")
public record MetricsProperties(String token) {

    public boolean enabled() {
        return token != null && !token.isBlank();
    }
}
