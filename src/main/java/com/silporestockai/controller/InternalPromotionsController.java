package com.silporestockai.controller;

import com.silporestockai.config.MetricsProperties;
import com.silporestockai.dto.request.PartnerPromotionRequest;
import com.silporestockai.dto.response.PartnerPromotionResponse;
import com.silporestockai.service.PartnerPromotionAdminService;
import com.silporestockai.service.PartnerPromotionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The partner side of task 46, for the hackathon: create a placement, read its funnel. Behind the same shared
 * token as the pitch metrics ({@code X-Metrics-Token}); no token configured, no endpoint.
 */
@RestController
@RequestMapping("/internal/promotions")
@RequiredArgsConstructor
public class InternalPromotionsController {

    private final MetricsProperties properties;
    private final PartnerPromotionAdminService adminService;
    private final PartnerPromotionService partnerPromotionService;

    @PostMapping
    public ResponseEntity<PartnerPromotionResponse> create(
            @RequestHeader(value = InternalMetricsController.TOKEN_HEADER, required = false) String token,
            @RequestBody PartnerPromotionRequest request) {
        ResponseEntity<PartnerPromotionResponse> refusal = gate(token);
        if (refusal != null) {
            return refusal;
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PartnerPromotionResponse.of(adminService.create(request)));
    }

    @GetMapping(value = "/report", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> report(
            @RequestHeader(value = InternalMetricsController.TOKEN_HEADER, required = false) String token) {
        ResponseEntity<String> refusal = gate(token);
        if (refusal != null) {
            return refusal;
        }
        return ResponseEntity.ok(partnerPromotionService.report());
    }

    private <T> ResponseEntity<T> gate(String token) {
        if (!properties.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (token == null
                || !MessageDigest.isEqual(
                        properties.token().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return null;
    }
}
