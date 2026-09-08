# Branded OAuth Callback Pages + Telegram Chat Confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Both OAuth callbacks (Google Calendar, Silpo MCP) render a branded Silpo-orange success/error page in the browser and push a confirmation (or an honest failure) message into the user's Telegram chat the moment the token is stored.

**Architecture:** One HTML template on the classpath, substituted by a static helper in `utils`, is the single source of both pages, so the Google and Silpo landings cannot drift apart. A new `ConnectNotificationService` resolves `userId → User.telegramChatId → TelegramOutboundService.sendMessage` and swallows Telegram failures so a chat outage never turns the browser page into a 500. Each controller peeks the pending login for its owner *before* `completeLogin` consumes the state, so the failure path still knows whom to message.

**Tech Stack:** Java 21 / Spring Boot 4, Lombok, MockMvc + Testcontainers integration tests, AssertJ, Spotless (palantir), Gradle via `make`.

**Spec:** Notion task *60. Branded OAuth callback pages + Telegram chat confirmation after connect* — https://app.notion.com/p/3d57227def1c81b5a112e3852ce3b082 (Depends on: 02, 18, 27)

## Global Constraints

- **Palette is reused, never re-invented.** The only brand color is `--silpo-primary: #FF8200`, verified in task 27 from every `<path>` fill of `static.silpo.ua/content/Logotype.svg`. Anything else on the page is a neutral (white / `#2E2E2E` / greys). Do not add a second brand hue.
- **The task's premise about `chat_id` is wrong and the plan corrects it.** Neither `SilpoLoginState` nor `GoogleLoginState` carries a chat id — both carry `userId`. The chat id comes from `User.telegramChatId` via `UserRepository`.
- **ArchUnit (`src/test/java/.../architecture/ArchitectureTest.java`) is enforced:** constructor injection only via `@RequiredArgsConstructor` (no `@Autowired` fields); classes in `..controller..` end in `Controller`; `@Service` beans end in `Service`; and — critically here — `oauthTokensNeverReachTheWeb` forbids anything in `..controller..` from depending on `*OAuthToken`, `*OAuthTokenRepository` or `TokenCipher`. The new controller code touches none of those.
- **`@Slf4j` for logging**, never `LoggerFactory` by hand.
- **Spotless:** run `make format` before the final commit; CI runs `spotlessCheck` before `build`.
- **Tokens are never rendered into anything a client sees** — neither page nor chat message may contain a code, access token or refresh token.
- **Do not name a method `notify(...)`** — it collides with `java.lang.Object.notify()`. The push method is `push(...)`.
- **Running `make test` while `make run` is live corrupts the shared `build/classes`** and produces phantom failures. Stop any running app first.
- All user-facing copy is Ukrainian, informal second person singular («спробуй»), matching the existing bot voice.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/resources/oauth/callback.html` (create) | The one branded landing page. Placeholders `{{accent}}`, `{{glyph}}`, `{{title}}`, `{{message}}`. |
| `src/main/java/com/silporestockai/utils/OAuthCallbackPage.java` (create) | Loads that template once and substitutes it. Two entry points: `success(title, message)`, `failure(title, message)`. |
| `src/main/java/com/silporestockai/service/ConnectNotificationService.java` (create) | `userId → chat id → Telegram message`, best-effort. |
| `src/main/java/com/silporestockai/service/GoogleAuthService.java` (modify) | Add read-only `pendingUserId(String state)`. |
| `src/main/java/com/silporestockai/service/SilpoAuthService.java` (modify) | Add read-only `pendingUserId(String state)`. |
| `src/main/java/com/silporestockai/controller/GoogleOAuthController.java` (modify) | Optional `code`, accept `error`, branded pages, chat push. |
| `src/main/java/com/silporestockai/controller/SilpoOAuthController.java` (modify) | Same. |
| `src/test/java/com/silporestockai/unit/OAuthCallbackPageTest.java` (create) | The renderer, without a Spring context. |
| `src/test/java/com/silporestockai/integration/ConnectNotificationIntegrationTest.java` (create) | The push, against the Telegram stub. |
| `src/test/java/com/silporestockai/integration/GoogleOAuthCallbackIntegrationTest.java` (create) | Google callback: success page + push, denied consent page + push. |
| `src/test/java/com/silporestockai/integration/SilpoOAuthIntegrationTest.java` (modify) | Same two cases for Silpo, plus a Telegram stub the class does not have yet. |

---

### Task 1: The branded page and its renderer

**Files:**
- Create: `src/main/resources/oauth/callback.html`
- Create: `src/main/java/com/silporestockai/utils/OAuthCallbackPage.java`
- Test: `src/test/java/com/silporestockai/unit/OAuthCallbackPageTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.silporestockai.utils.OAuthCallbackPage` with two public statics —
  `public static String success(String title, String message)` and
  `public static String failure(String title, String message)`, both returning a complete HTML document.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/unit/OAuthCallbackPageTest.java`:

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.utils.OAuthCallbackPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the OAuth landing page is branded, escaped and the same shape for both providers")
class OAuthCallbackPageTest {

    @Test
    void successCarriesTheBrandColourAndTheGivenCopy() {
        String html = OAuthCallbackPage.success("Календар підключено", "Можна повертатися в Telegram.");

        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("#FF8200");
        assertThat(html).contains("Календар підключено").contains("Можна повертатися в Telegram.");
        assertThat(html).doesNotContain("{{");
    }

    @Test
    void failureUsesANeutralAccentSoItCannotBeMistakenForSuccess() {
        String success = OAuthCallbackPage.success("Готово", "Все добре.");
        String failure = OAuthCallbackPage.failure("Не вдалось", "Спробуй ще раз.");

        assertThat(failure).contains("Не вдалось").contains("Спробуй ще раз.");
        assertThat(failure).doesNotContain("{{");
        // The two states must be visually distinguishable, not the same page with different words.
        assertThat(failure).isNotEqualTo(success);
        assertThat(failure).contains("#2E2E2E");
    }

    /**
     * The title and message are ours today, but a page that interpolates raw HTML is one refactor away from
     * reflecting a query parameter into the document.
     */
    @Test
    void copyIsHtmlEscaped() {
        String html = OAuthCallbackPage.success("<script>alert(1)</script>", "a & b");

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;").contains("a &amp; b");
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew test --tests '*OAuthCallbackPageTest'`
Expected: FAIL — compilation error, `package com.silporestockai.utils` has no `OAuthCallbackPage`.

- [ ] **Step 3: Create the template**

Create `src/main/resources/oauth/callback.html`. Note it is a *template*, not a served static resource — it lives outside `static/` on purpose so Spring never serves it directly.

```html
<!doctype html>
<html lang="uk">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Комора</title>
<style>
  :root {
    /* Verified in task 27 from every <path> fill of static.silpo.ua/content/Logotype.svg. Do not re-guess. */
    --silpo-primary: #FF8200;
    --silpo-primary-text: #ffffff;
    --neutral: #2E2E2E;
    --surface: #ffffff;
    --text: #1a1a1a;
    --muted: #6b6b6b;
  }
  @media (prefers-color-scheme: dark) {
    :root { --surface: #1c1c1e; --text: #f2f2f2; --muted: #9a9a9a; --neutral: #d4d4d4; }
    body { background: #121214; }
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
    background: #f5f5f7;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    color: var(--text);
  }
  .card {
    background: var(--surface);
    border-radius: 16px;
    padding: 32px 28px 28px;
    max-width: 360px;
    width: 100%;
    text-align: center;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
  }
  .brand {
    font-size: 13px;
    font-weight: 700;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--silpo-primary);
    margin-bottom: 20px;
  }
  .glyph {
    width: 64px;
    height: 64px;
    margin: 0 auto 20px;
    border-radius: 50%;
    background: {{accent}};
    color: var(--silpo-primary-text);
    font-size: 32px;
    line-height: 64px;
  }
  h1 { font-size: 20px; font-weight: 700; margin: 0 0 10px; }
  p { font-size: 15px; line-height: 1.5; margin: 0; color: var(--muted); }
</style>
</head>
<body>
<div class="card">
  <div class="brand">Комора</div>
  <div class="glyph">{{glyph}}</div>
  <h1>{{title}}</h1>
  <p>{{message}}</p>
</div>
</body>
</html>
```

- [ ] **Step 4: Write the renderer**

Create `src/main/java/com/silporestockai/utils/OAuthCallbackPage.java`:

```java
package com.silporestockai.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The one landing page both OAuth callbacks render.
 *
 * <p>A single template rather than a text block per controller: the Silpo connect and the Google Calendar connect are
 * the same product, and two copies of the CSS would let them drift apart the first time one is touched. The template
 * is read once at class-init — it is a few kilobytes on the classpath, and a per-request read would buy nothing.
 *
 * <p>The brand colour is task 27's verified {@code #FF8200}; the failure state deliberately swaps it for a neutral so
 * that a page nobody reads carefully still cannot be mistaken for a success.
 */
public final class OAuthCallbackPage {

    private static final String TEMPLATE = load();

    private static final String SUCCESS_ACCENT = "var(--silpo-primary)";
    private static final String FAILURE_ACCENT = "var(--neutral)";

    private OAuthCallbackPage() {}

    /** A finished connection: brand-orange glyph, a checkmark. */
    public static String success(String title, String message) {
        return render(SUCCESS_ACCENT, "✓", title, message);
    }

    /** A connection that did not happen: neutral glyph, a cross, and honest copy from the caller. */
    public static String failure(String title, String message) {
        return render(FAILURE_ACCENT, "✕", title, message);
    }

    private static String render(String accent, String glyph, String title, String message) {
        return TEMPLATE.replace("{{accent}}", accent)
                .replace("{{glyph}}", glyph)
                .replace("{{title}}", escape(title))
                .replace("{{message}}", escape(message));
    }

    /**
     * Everything interpolated today is a constant of ours, which is exactly why this is cheap to add now: the day
     * somebody reflects a query parameter into the copy, the page must not become an injection point.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String load() {
        try (InputStream stream = OAuthCallbackPage.class.getResourceAsStream("/oauth/callback.html")) {
            if (stream == null) {
                throw new IllegalStateException("/oauth/callback.html is missing from the classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the OAuth callback template", e);
        }
    }
}
```

Note the accents are emitted as CSS variable references, so the literal `#FF8200` and `#2E2E2E` the test asserts on come from the `:root` block in the template — which is what makes "the page still carries the brand colour" a true statement about the rendered document.

- [ ] **Step 5: Run the test and watch it pass**

Run: `./gradlew test --tests '*OAuthCallbackPageTest'`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/oauth/callback.html \
        src/main/java/com/silporestockai/utils/OAuthCallbackPage.java \
        src/test/java/com/silporestockai/unit/OAuthCallbackPageTest.java
git commit -m "Give the OAuth landings one branded page instead of a sentence

Both callbacks answered with an unstyled <p>. One template on the
classpath, rendered by a static helper, keeps the Silpo connect and the
Google Calendar connect from drifting into two different-looking
products, and reuses task 27's verified #FF8200 rather than guessing a
palette again.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR"
```

---

### Task 2: Pushing the confirmation into the chat

**Files:**
- Create: `src/main/java/com/silporestockai/service/ConnectNotificationService.java`
- Test: `src/test/java/com/silporestockai/integration/ConnectNotificationIntegrationTest.java`

**Interfaces:**
- Consumes: `UserRepository.findById(UUID)`, `TelegramOutboundService.sendMessage(long chatId, String text)`.
- Produces: `com.silporestockai.service.ConnectNotificationService` with one public method —
  `public void push(UUID userId, String text)`. Never throws.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/ConnectNotificationIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.silporestockai.entity.User;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConnectNotificationService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("a finished OAuth connect reaches the chat the person is actually waiting in")
class ConnectNotificationIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "606:stub-bot-token";
    private static final long CHAT_ID = 60601L;
    private static final StubTelegramServer TELEGRAM = startTelegram();

    @Autowired
    private ConnectNotificationService connectNotificationService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        userRepository.deleteAll();
    }

    @Test
    void resolvesTheChatFromTheUserAndSends() {
        User user = userAccountService.findOrCreate(CHAT_ID);

        connectNotificationService.push(user.getId(), "✅ Акаунт «Сільпо» підключено.");

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
        assertThat(TELEGRAM.sentMessages().getFirst().path("chat_id").asLong()).isEqualTo(CHAT_ID);
        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText())
                .isEqualTo("✅ Акаунт «Сільпо» підключено.");
    }

    /**
     * The caller is a browser-facing controller: whatever goes wrong reaching Telegram, the person staring at the
     * landing page must still get their page, not a 500.
     */
    @Test
    void anUnknownUserIsSilentlyIgnoredRatherThanThrown() {
        assertThatCode(() -> connectNotificationService.push(UUID.randomUUID(), "не має кому"))
                .doesNotThrowAnyException();

        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew test --tests '*ConnectNotificationIntegrationTest'`
Expected: FAIL — compilation error, `com.silporestockai.service.ConnectNotificationService` does not exist.

- [ ] **Step 3: Write the service**

Create `src/main/java/com/silporestockai/service/ConnectNotificationService.java`:

```java
package com.silporestockai.service;

import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Says out loud, in the chat, that a browser-side OAuth connect finished.
 *
 * <p>The OAuth callbacks land in a browser tab, and Telegram sends no callback for a URL button — so without this the
 * conversation stays exactly where the person left it and they have to guess whether it worked. The chat id is not in
 * the OAuth state: both login states carry a {@code userId}, and {@code User.telegramChatId} is the chat.
 *
 * <p>Every failure is swallowed. The caller is answering a browser; a Telegram outage must cost the person their
 * confirmation message, never their landing page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectNotificationService {

    private final UserRepository userRepository;
    private final TelegramOutboundService telegramOutboundService;

    /** Best-effort. Returns normally whether the message was sent, skipped or failed. */
    public void push(UUID userId, String text) {
        try {
            userRepository
                    .findById(userId)
                    .ifPresentOrElse(
                            user -> telegramOutboundService.sendMessage(user.getTelegramChatId(), text),
                            () -> log.warn("no user {} to notify about an OAuth connect", userId));
        } catch (RuntimeException e) {
            log.warn("could not push the OAuth connect confirmation to user {}", userId, e);
        }
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew test --tests '*ConnectNotificationIntegrationTest'`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/ConnectNotificationService.java \
        src/test/java/com/silporestockai/integration/ConnectNotificationIntegrationTest.java
git commit -m "Add the one way an OAuth callback can talk to the chat

The callback finishes in a browser tab and Telegram sends no callback for
a URL button, so nothing told the conversation the connect had happened.
The chat id is not in the OAuth state, contrary to what task 60 assumed —
both login states carry a userId, and User.telegramChatId is the chat.

Every failure is swallowed: the caller is answering a browser, and a
Telegram outage must not cost the person their landing page.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR"
```

---

### Task 3: The Google Calendar callback

**Files:**
- Modify: `src/main/java/com/silporestockai/service/GoogleAuthService.java` (add `pendingUserId`)
- Modify: `src/main/java/com/silporestockai/controller/GoogleOAuthController.java:36-49` (whole `callback` method)
- Test: `src/test/java/com/silporestockai/integration/GoogleOAuthCallbackIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `OAuthCallbackPage.success/failure` (Task 1), `ConnectNotificationService.push` (Task 2),
  `GoogleAuthService.completeLogin(String code, String state) → UUID`,
  `GoogleAuthService.buildAuthorizationUrl(UUID userId) → String`.
- Produces: `GoogleAuthService.pendingUserId(String state) → Optional<UUID>` — reads the pending-login map **without**
  consuming the entry, so `completeLogin` still works afterwards.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/GoogleOAuthCallbackIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.silporestockai.entity.User;
import com.silporestockai.repository.GoogleOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubGoogleServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("finishing the Google consent screen is visible in the browser and in the chat")
class GoogleOAuthCallbackIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "601:stub-bot-token";
    private static final long CHAT_ID = 60101L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubGoogleServer GOOGLE = startGoogle();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GoogleOAuthTokenRepository googleTokenRepository;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubGoogleServer startGoogle() {
        try {
            return new StubGoogleServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Google stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("google.calendar.client-id", () -> "stub-google-client");
        registry.add("google.calendar.client-secret", () -> "stub-google-secret");
        registry.add("google.calendar.token-endpoint", GOOGLE::tokenEndpoint);
        registry.add("google.calendar.api-url", GOOGLE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        GOOGLE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        GOOGLE.reset();
        googleTokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userAccountService.findOrCreate(CHAT_ID);
    }

    @Test
    void aFinishedConsentRendersABrandedPageAndTellsTheChatWithoutBeingAsked() throws Exception {
        String state = startLogin();

        MvcResult callback = mockMvc.perform(
                        get("/auth/google/callback").param("code", "google-code").param("state", state))
                .andReturn();

        assertThat(callback.getResponse().getStatus()).isEqualTo(200);
        String html = callback.getResponse().getContentAsString();
        assertThat(html).contains("#FF8200").contains("Комора").contains("Календар підключено");
        assertThat(html).doesNotContain("stub-google-access").doesNotContain("google-code");

        assertThat(googleTokenRepository.findById(user.getId())).isPresent();

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
        assertThat(TELEGRAM.sentMessages().getFirst().path("chat_id").asLong()).isEqualTo(CHAT_ID);
        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText()).contains("Календар підключено");
    }

    /**
     * Declining the consent screen sends back {@code error=access_denied} and no {@code code} at all. Before this the
     * required {@code code} parameter turned that into a problem+json 500 in the user's face, and the chat heard
     * nothing.
     */
    @Test
    void aDeclinedConsentRendersABrandedErrorPageAndSaysSoInTheChat() throws Exception {
        String state = startLogin();

        MvcResult callback = mockMvc.perform(
                        get("/auth/google/callback").param("error", "access_denied").param("state", state))
                .andReturn();

        assertThat(callback.getResponse().getStatus()).isEqualTo(400);
        assertThat(callback.getResponse().getContentType()).startsWith("text/html");
        String html = callback.getResponse().getContentAsString();
        assertThat(html).contains("Комора").contains("Не вдалось підключити календар");
        assertThat(html).doesNotContain("Exception").doesNotContain("at com.silporestockai");

        assertThat(googleTokenRepository.findById(user.getId())).isEmpty();

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText()).contains("Не вдалось підключити");
    }

    private String startLogin() throws Exception {
        String location = mockMvc.perform(get("/auth/google/start").param("userId", user.getId().toString()))
                .andReturn()
                .getResponse()
                .getHeader("Location");
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : URI.create(location).getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            query.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return query.get("state");
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew test --tests '*GoogleOAuthCallbackIntegrationTest'`
Expected: FAIL — the success test fails on `contains("#FF8200")` (the current body is a bare `<p>`); the declined test fails with a 500 and `application/problem+json` because `code` is still a required parameter.

- [ ] **Step 3: Add the pending-login peek to `GoogleAuthService`**

In `src/main/java/com/silporestockai/service/GoogleAuthService.java`, add this method directly after `completeLogin` (around line 101). Note `java.util.Optional` is referenced fully-qualified in this class already — `accessToken` does the same — so no import is needed:

```java
    /**
     * Who started the login this state belongs to, without consuming it.
     *
     * <p>The callback needs the owner even when the login is about to fail — a declined consent screen carries no code
     * to exchange, and a failed exchange has already removed the pending entry by the time anything can be reported.
     * Peeking before {@code completeLogin} is what lets the failure reach the person's chat rather than only the log.
     */
    public java.util.Optional<UUID> pendingUserId(String state) {
        return java.util.Optional.ofNullable(pendingLogins.get(state)).map(GoogleLoginState::userId);
    }
```

- [ ] **Step 4: Rewrite the controller**

Replace the whole of `src/main/java/com/silporestockai/controller/GoogleOAuthController.java` with:

```java
package com.silporestockai.controller;

import com.silporestockai.service.ConnectNotificationService;
import com.silporestockai.service.GoogleAuthService;
import com.silporestockai.utils.OAuthCallbackPage;
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
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(html);
    }
}
```

- [ ] **Step 5: Run the test and watch it pass**

Run: `./gradlew test --tests '*GoogleOAuthCallbackIntegrationTest'`
Expected: PASS, 2 tests.

- [ ] **Step 6: Run the calendar suite to prove nothing regressed**

Run: `./gradlew test --tests '*CalendarIntegrationIntegrationTest' --tests '*CalendarViewIntegrationTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/silporestockai/controller/GoogleOAuthController.java \
        src/main/java/com/silporestockai/service/GoogleAuthService.java \
        src/test/java/com/silporestockai/integration/GoogleOAuthCallbackIntegrationTest.java
git commit -m "Answer the Google consent screen in both places the user is

Finishing consent left an unstyled sentence in the browser and total
silence in the chat, so the only way to learn it had worked was to go
looking. Declining was worse: code was a required parameter, so
access_denied came back as a problem+json 500.

code and error are now both optional, every outcome renders the branded
page, and the owner is read out of the pending login before completeLogin
consumes it — which is what lets a failure reach the chat at all.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR"
```

---

### Task 4: The Silpo MCP callback

**Files:**
- Modify: `src/main/java/com/silporestockai/service/SilpoAuthService.java` (add `pendingUserId`)
- Modify: `src/main/java/com/silporestockai/controller/SilpoOAuthController.java:41-54` (whole `callback` method)
- Test: `src/test/java/com/silporestockai/integration/SilpoOAuthIntegrationTest.java` (add a Telegram stub and two tests)

**Interfaces:**
- Consumes: `OAuthCallbackPage.success/failure` (Task 1), `ConnectNotificationService.push` (Task 2),
  `SilpoAuthService.completeLogin(String code, String state) → UUID`.
- Produces: `SilpoAuthService.pendingUserId(String state) → Optional<UUID>` — same contract as Task 3's Google one.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/silporestockai/integration/SilpoOAuthIntegrationTest.java`:

Add these imports to the existing block:

```java
import com.silporestockai.support.StubTelegramServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
```

Add the stub next to the existing `STUB` field:

```java
    private static final String BOT_TOKEN = "602:stub-bot-token";
    private static final StubTelegramServer TELEGRAM = startTelegram();

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }
```

Extend the existing `@DynamicPropertySource` method so it also points the bot at the stub:

```java
    @DynamicPropertySource
    static void oauthIssuer(DynamicPropertyRegistry registry) {
        registry.add("silpo.mcp.issuer", STUB::issuer);
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
    }

    @AfterAll
    static void stopTelegramStub() {
        TELEGRAM.close();
    }

    @BeforeEach
    void resetTelegramStub() {
        TELEGRAM.reset();
    }
```

Then add the two new tests:

```java
    @Test
    void aFinishedLoginRendersABrandedPageAndTellsTheChatWithoutBeingAsked() throws Exception {
        UUID userId = persistedUser();
        String state = startLogin(userId);

        MvcResult callback = mockMvc.perform(
                        get("/auth/silpo/callback").param("code", "auth-code-123").param("state", state))
                .andReturn();

        assertThat(callback.getResponse().getStatus()).isEqualTo(200);
        String html = callback.getResponse().getContentAsString();
        assertThat(html).contains("#FF8200").contains("Комора").contains("«Сільпо» підключено");

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
        assertThat(TELEGRAM.sentMessages().getFirst().path("chat_id").asLong())
                .isEqualTo(userRepository.findById(userId).orElseThrow().getTelegramChatId());
        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText()).contains("«Сільпо» підключено");
    }

    @Test
    void aDeclinedLoginRendersABrandedErrorPageAndSaysSoInTheChat() throws Exception {
        UUID userId = persistedUser();
        String state = startLogin(userId);

        MvcResult callback = mockMvc.perform(
                        get("/auth/silpo/callback").param("error", "access_denied").param("state", state))
                .andReturn();

        assertThat(callback.getResponse().getStatus()).isEqualTo(400);
        assertThat(callback.getResponse().getContentType()).startsWith("text/html");
        assertThat(callback.getResponse().getContentAsString())
                .contains("Не вдалось підключити")
                .doesNotContain("at com.silporestockai");

        assertThat(tokenRepository.findByUserId(userId)).isEmpty();
        assertThat(TELEGRAM.sentMessages()).hasSize(1);
        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText()).contains("Не вдалось підключити");
    }

    private String startLogin(UUID userId) throws Exception {
        return queryOf(mockMvc.perform(get("/auth/silpo/start").param("userId", userId.toString()))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location"))
                .get("state");
    }
```

Finally, tighten the existing `callbackRejectsAnUnknownState` so it pins the new behaviour rather than only the status:

```java
    @Test
    void callbackRejectsAnUnknownState() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/auth/silpo/callback").param("code", "whatever").param("state", "forged-state"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        // A forged state has no pending login, so there is nobody to tell — and nothing to tell them.
        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew test --tests '*SilpoOAuthIntegrationTest'`
Expected: FAIL — the success test on `contains("#FF8200")`, the declined test with a 500 and `application/problem+json`.

- [ ] **Step 3: Add the pending-login peek to `SilpoAuthService`**

In `src/main/java/com/silporestockai/service/SilpoAuthService.java`, add `import java.util.Optional;` to the import block and this method directly after `completeLogin` (around line 116):

```java
    /**
     * Who started the login this state belongs to, without consuming it.
     *
     * <p>The callback needs the owner even when the login is about to fail — a declined authorization carries no code
     * to exchange, and a failed exchange has already removed the pending entry by the time anything can be reported.
     * Peeking before {@code completeLogin} is what lets the failure reach the person's chat rather than only the log.
     */
    public Optional<UUID> pendingUserId(String state) {
        return Optional.ofNullable(pendingLogins.get(state)).map(SilpoLoginState::userId);
    }
```

- [ ] **Step 4: Rewrite the controller**

Replace the whole of `src/main/java/com/silporestockai/controller/SilpoOAuthController.java` with:

```java
package com.silporestockai.controller;

import com.silporestockai.service.ConnectNotificationService;
import com.silporestockai.service.SilpoAuthService;
import com.silporestockai.utils.OAuthCallbackPage;
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
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(html);
    }
}
```

The existing `SilpoConnectedEvent` publication inside `SilpoAuthService.completeLogin` stays exactly as it is. Mid-onboarding a user now gets the short «✅ Акаунт «Сільпо» підключено.» immediately and the enrichment message a few seconds later — that gap is four MCP tool calls plus a model round-trip, so filling it is the point, not noise.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `./gradlew test --tests '*SilpoOAuthIntegrationTest'`
Expected: PASS, all tests in the class including the pre-existing three.

- [ ] **Step 6: Run the onboarding suite to prove the event path still works**

Run: `./gradlew test --tests '*OnboardingFlowIntegrationTest'`
Expected: PASS. If a test now counts one message more than it expects, that is the new confirmation — update the expectation rather than removing the push.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/silporestockai/controller/SilpoOAuthController.java \
        src/main/java/com/silporestockai/service/SilpoAuthService.java \
        src/test/java/com/silporestockai/integration/SilpoOAuthIntegrationTest.java
git commit -m "Give the Silpo connect the same landing and the same push

The Silpo callback had the same bare sentence as the Google one and, off
the onboarding path, the same silence: SilpoConnectedEvent only resumes a
conversation parked at AWAITING_CONNECT, so reconnecting from anywhere
else told the user nothing.

Both connects now land on the same branded page and confirm in the chat,
which is the point — this is one product, not two.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR"
```

---

### Task 5: Whole-suite verification and live proof

**Files:**
- Modify: any file Spotless reformats.

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: a green build and a recorded manual result.

- [ ] **Step 1: Format**

Run: `make format`

- [ ] **Step 2: Run the whole suite**

Make sure no `make run` / `bootRun` is live first — it shares `build/classes` with the tests and produces phantom failures:

```bash
pgrep -af "bootRun|silpoRestockAI.*java" || echo "nothing running"
```

Run: `make test`
Expected: PASS, whole suite. ArchUnit included — the new controller code depends on `ConnectNotificationService` and `OAuthCallbackPage`, neither of which is an `*OAuthToken`, `*OAuthTokenRepository` or `TokenCipher`, so `oauthTokensNeverReachTheWeb` stays satisfied.

- [ ] **Step 3: Live manual test — Google Calendar**

Start the app with the real `.env` and a tunnel, open the connect button from the Telegram chat, and finish the Google consent screen. Confirm, in this order:
1. the browser lands on the orange-accented card, not a bare sentence;
2. the chat shows «✅ Календар підключено — доставки з'являтимуться там автоматично.» **on its own**, with no message sent from the tester and no reload;
3. the log carries `completed the Google OAuth callback for user …`.

Then repeat with the consent screen **declined**: expect the neutral-accented error card and «Не вдалось підключити Google Календар…» in the chat.

- [ ] **Step 4: Live manual test — Silpo MCP**

Same three checks for `/auth/silpo/start`, expecting «Акаунт «Сільпо» підключено» on the page and «✅ Акаунт «Сільпо» підключено.» in the chat. Reconnecting an already-connected account is safe: `store(...)` upserts the row by `userId`, so an existing Silpo connection is refreshed, not broken.

- [ ] **Step 5: Commit anything the formatter touched**

```bash
git add -A
git commit -m "Apply spotless formatting

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR"
```

(Skip this commit entirely if `git status` is clean.)

- [ ] **Step 6: Record the result in Notion**

Update task 60's Status. `Done` only if both live tests passed end to end, including the declined-consent path; otherwise `In review` with a note naming exactly which criterion was not proven live and why.

---

## Self-Review

**Spec coverage:**

| Acceptance criterion | Task |
|---|---|
| Google success page branded with #27's colors | 1, 3 |
| Silpo success page branded the same way | 1, 4 |
| Both error states branded, no stack trace or plain text | 1, 3, 4 |
| Chat message after Google connect, unprompted | 2, 3 |
| Same for Silpo connect | 2, 4 |
| Failure pushes an honest chat message, not silence | 3, 4 |
| Manual test: reconnect both live, chat updates in real time | 5 |
| Out of scope: no OAuth flow/scope changes | `pendingUserId` only reads the existing map; `completeLogin`, PKCE, scopes and storage are untouched |

**Placeholder scan:** no TBD/TODO; every code step carries the literal code.

**Type consistency:** `OAuthCallbackPage.success/failure(String, String)` — defined Task 1, used Tasks 3 and 4. `ConnectNotificationService.push(UUID, String)` — defined Task 2, used Tasks 3 and 4. `pendingUserId(String) → Optional<UUID>` — defined per service in Tasks 3 and 4, used in the same task. `StubTelegramServer.sentMessages()`, `.reset()`, `.close()`, `.baseUrl()` and `UserAccountService.findOrCreate(long)` match the existing test-support signatures.
