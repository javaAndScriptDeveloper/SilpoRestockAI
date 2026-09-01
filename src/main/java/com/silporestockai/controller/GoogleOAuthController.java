package com.silporestockai.controller;

import com.silporestockai.service.GoogleAuthService;
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
 * The browser half of connecting a Google calendar.
 *
 * <p>Like the Silpo controller, it renders no token — the browser sees a redirect and then a sentence.
 */
@Slf4j
@RestController
@RequestMapping("/auth/google")
@RequiredArgsConstructor
public class GoogleOAuthController {

    private final GoogleAuthService googleAuthService;

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam UUID userId) {
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, googleAuthService.buildAuthorizationUrl(userId))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam String code, @RequestParam String state) {
        UUID userId = googleAuthService.completeLogin(code, state);
        log.info("completed the Google OAuth callback for user {}", userId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body("""
                        <!doctype html>
                        <meta charset="utf-8">
                        <title>Комора</title>
                        <p>Календар підключено. Доставки з'являтимуться там автоматично.</p>
                        """);
    }
}
