package com.silporestockai.controller;

import com.silporestockai.service.ConnectNotificationService;
import com.silporestockai.service.GoogleAuthService;
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
 * The browser half of connecting a Google calendar.
 *
 * <p>Like the Silpo controller, it renders no token — the browser sees a redirect and then a branded landing page. The
 * chat is told separately and immediately: the person is waiting in Telegram, not in this tab.
 */
@Slf4j
@RestController
@RequestMapping("/auth/google")
@RequiredArgsConstructor
public class GoogleOAuthController {

    private static final String SUCCESS_TITLE = "Календар підключено";
    private static final String SUCCESS_MESSAGE =
            "Доставки з'являтимуться в Google Календарі автоматично. Можна повертатися в Telegram.";
    private static final String FAILURE_TITLE = "Не вдалось підключити календар";
    private static final String FAILURE_MESSAGE = "Спробуй ще раз — натисни кнопку підключення в Telegram.";
    private static final String CHAT_SUCCESS = "✅ Календар підключено — доставки з'являтимуться там автоматично.";
    private static final String CHAT_FAILURE =
            "Не вдалось підключити Google Календар. Спробуй ще раз — натисни кнопку підключення.";

    private final GoogleAuthService googleAuthService;
    private final ConnectNotificationService connectNotificationService;

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam UUID userId) {
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, googleAuthService.buildAuthorizationUrl(userId))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /**
     * Google answers here with either a {@code code} or an {@code error} — declining the consent screen sends the
     * latter and no code at all, which is why neither is required.
     */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam String state) {
        // Read the owner before completeLogin consumes the state: on every failure path it is gone afterwards, and
        // without it the chat cannot be told anything.
        Optional<UUID> owner = googleAuthService.pendingUserId(state);

        if (code == null) {
            log.info("the Google consent screen came back without a code (error={})", error);
            return failure(owner);
        }
        try {
            UUID userId = googleAuthService.completeLogin(code, state);
            log.info("completed the Google OAuth callback for user {}", userId);
            connectNotificationService.push(userId, CHAT_SUCCESS);
            return page(HttpStatus.OK, OAuthCallbackPage.success(SUCCESS_TITLE, SUCCESS_MESSAGE));
        } catch (RuntimeException e) {
            log.warn("the Google OAuth callback failed", e);
            return failure(owner);
        }
    }

    private ResponseEntity<String> failure(Optional<UUID> owner) {
        owner.ifPresent(userId -> connectNotificationService.push(userId, CHAT_FAILURE));
        // 400 for every failure: an unknown state, a declined consent and a refused exchange are all "this connect
        // did not happen" to the one person reading the page, and the browser does nothing different with 502.
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
