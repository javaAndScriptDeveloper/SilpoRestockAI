package com.silporestockai.controller;

import com.silporestockai.config.MetricsProperties;
import com.silporestockai.service.MetricsService;
import com.silporestockai.service.PitchArtifactService;
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
 * Two reports for the pitch, both behind {@code X-Metrics-Token}: the metrics table (task 37) at
 * {@code GET /internal/metrics/pitch}, and the MCP call sheet (task 55) at
 * {@code GET /internal/metrics/pitch-artifact}.
 *
 * <p>Neither is a dashboard. The first is markdown to paste into the pitch notes after a rehearsal; the second is
 * the HTML that {@code make pitch-artifact} writes to {@code static/pitch.html} and a commit publishes. Both sit
 * behind a shared secret so a tunnelled demo box does not serve its usage numbers to whoever finds the URL.
 * Unset token: neither endpoint exists.
 */
@RestController
@RequestMapping("/internal/metrics")
@RequiredArgsConstructor
public class InternalMetricsController {

    public static final String TOKEN_HEADER = "X-Metrics-Token";

    private final MetricsProperties properties;
    private final MetricsService metricsService;
    private final PitchArtifactService pitchArtifactService;

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

    /**
     * The page itself, rendered from the current tables. Nothing public reads this — {@code make pitch-artifact}
     * saves the response as a static file, which is what a QR code eventually points at.
     */
    @GetMapping(value = "/pitch-artifact", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> pitchArtifact(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!properties.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (token == null || !constantTimeEquals(properties.token(), token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(pitchArtifactService.html());
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
