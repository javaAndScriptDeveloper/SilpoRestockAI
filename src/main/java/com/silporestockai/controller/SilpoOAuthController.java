package com.silporestockai.controller;

import com.silporestockai.service.ConnectNotificationService;
import com.silporestockai.service.SilpoAuthService;
import com.silporestockai.utils.OAuthCallbackPage;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * branded confirmation page — the access and refresh tokens stay inside the service, which is the security requirement
 * the Silpo MCP documentation states explicitly.
 *
 * <p>The page is not the only thing that happens: the chat is told too, on both outcomes, because that is where the
 * person actually is.
 */
@Slf4j
@RestController
@RequestMapping("/auth/silpo")
@RequiredArgsConstructor
public class SilpoOAuthController {

    private static final String SUCCESS_TITLE = "Акаунт «Сільпо» підключено";
    private static final String SUCCESS_MESSAGE = "Можна повертатися в Telegram — я вже написав туди.";
    private static final String FAILURE_TITLE = "Не вдалось підключити «Сільпо»";
    private static final String FAILURE_MESSAGE = "Спробуй ще раз — натисни кнопку підключення в Telegram.";
    private static final String CHAT_SUCCESS = "✅ Акаунт «Сільпо» підключено.";
    private static final String CHAT_FAILURE =
            "Не вдалось підключити акаунт «Сільпо». Спробуй ще раз — натисни кнопку підключення.";

    private final SilpoAuthService silpoAuthService;
    private final ConnectNotificationService connectNotificationService;

    /** Redirects the guest to Silpo to authorize this application. */
    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam UUID userId) {
        String authorizationUrl = silpoAuthService.buildAuthorizationUrl(userId);
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, authorizationUrl)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /**
     * Receives the authorization code and completes the login. Silpo answers with either a {@code code} or an
     * {@code error}, so neither is required.
     */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam String state) {
        // Read the owner before completeLogin consumes the state: on every failure path it is gone afterwards, and
        // without it the chat cannot be told anything.
        Optional<UUID> owner = silpoAuthService.pendingUserId(state);

        if (code == null) {
            log.info("the Silpo authorization came back without a code (error={})", error);
            return failure(owner);
        }
        try {
            UUID userId = silpoAuthService.completeLogin(code, state);
            log.info("completed the Silpo OAuth callback for user {}", userId);
            connectNotificationService.push(userId, CHAT_SUCCESS);
            return page(HttpStatus.OK, OAuthCallbackPage.success(SUCCESS_TITLE, SUCCESS_MESSAGE));
        } catch (RuntimeException e) {
            log.warn("the Silpo OAuth callback failed", e);
            return failure(owner);
        }
    }

    private ResponseEntity<String> failure(Optional<UUID> owner) {
        owner.ifPresent(userId -> connectNotificationService.push(userId, CHAT_FAILURE));
        // 400 for every failure: an unknown state, a declined authorization and a refused exchange are all "this
        // connect did not happen" to the one person reading the page.
        return page(HttpStatus.BAD_REQUEST, OAuthCallbackPage.failure(FAILURE_TITLE, FAILURE_MESSAGE));
    }

    private ResponseEntity<String> page(HttpStatus status, String html) {
        return ResponseEntity.status(status)
                // Charset spelled out: text/html without one is served as ISO-8859-1, and every word on this page is
                // Cyrillic. The <meta charset> tag rescues a browser, but only after the header has already lied.
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(html);
    }
}
