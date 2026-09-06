package com.silporestockai.controller;

import com.silporestockai.config.MetricsProperties;
import com.silporestockai.service.MetricsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pitch-metrics report (task 37): {@code GET /internal/metrics/pitch} with {@code X-Metrics-Token}.
 *
 * <p>Not a dashboard. A markdown table to paste into the pitch notes after a rehearsal, behind a shared secret so a
 * tunnelled demo box does not serve its usage numbers to whoever finds the URL. Unset token: the endpoint does not
 * exist.
 */
@RestController
@RequestMapping("/internal/metrics")
@RequiredArgsConstructor
public class InternalMetricsController {

    public static final String TOKEN_HEADER = "X-Metrics-Token";

    private final MetricsProperties properties;
    private final MetricsService metricsService;

    @GetMapping(value = "/pitch", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> pitch(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!properties.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (token == null || !constantTimeEquals(properties.token(), token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(metricsService.markdown(metricsService.compute()));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
