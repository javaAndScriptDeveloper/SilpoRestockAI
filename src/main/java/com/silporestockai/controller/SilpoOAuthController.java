package com.silporestockai.controller;

import com.silporestockai.service.SilpoAuthService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The browser half of the Silpo OAuth login.
 *
 * <p>Neither endpoint ever renders a token. The guest's browser only sees a redirect to Silpo and, afterwards, a
 * confirmation page — the access and refresh tokens stay inside the service, which is the security requirement the
 * Silpo MCP documentation states explicitly.
 */
@Slf4j
@RestController
@RequestMapping("/auth/silpo")
@RequiredArgsConstructor
public class SilpoOAuthController {

    private final SilpoAuthService silpoAuthService;

    /** Redirects the guest to Silpo to authorize this application. */
    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam UUID userId) {
        String authorizationUrl = silpoAuthService.buildAuthorizationUrl(userId);
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, authorizationUrl)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /** Receives the authorization code and completes the login. */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam String code, @RequestParam String state) {
        UUID userId = silpoAuthService.completeLogin(code, state);
        log.info("completed the Silpo OAuth callback for user {}", userId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body("""
                        <!doctype html>
                        <meta charset="utf-8">
                        <title>Комора</title>
                        <p>Акаунт «Сільпо» підключено. Можна повертатися в Telegram.</p>
                        """);
    }
}
