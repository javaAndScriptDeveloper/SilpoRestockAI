# Claude API Client Wrapper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One reusable `ClaudeApiClient` in the `client` package offering plain-text completion, typed structured output, and an image call, wrapped in the project's Resilience4j retry and circuit-breaker conventions.

**Architecture:** A thin wrapper over `com.anthropic:anthropic-java:2.57.0`. Plain completion uses `MessageCreateParams`; structured output uses the SDK's native `outputConfig(Class, JsonSchemaLocalValidation)` so the API itself returns schema-shaped JSON that the SDK deserializes into a typed `T`. SDK-level retries are switched off so backoff and the breaker live in `application.yml` alongside the existing `silpoMcp` instance. Every SDK exception is mapped to a project exception that says whether it is worth retrying.

**Tech Stack:** Java 25, Spring Boot 4.1.0, `com.anthropic:anthropic-java:2.57.0` (already a dependency), Resilience4j `@Retry` / `@CircuitBreaker` via `resilience4j-spring-boot3`, Testcontainers PostgreSQL for the Spring context, JDK `com.sun.net.httpserver` stub server, JUnit 5, AssertJ, Logback `ListAppender`.

**Spec:** `docs/superpowers/specs/2026-08-28-claude-api-client-design.md`

## Global Constraints

- Base package is `com.silporestockai`. New code goes in `client/claude`, `config` and `exception`.
- **ArchUnit is enforced.** Names ending in `Impl` are fine in `client` (rules cover `controller`, `service`, `repository`, `job` only) — `client/mcp/SilpoMcpClientImpl` is the precedent. Constructor injection only; no `@Autowired` fields.
- **Spotless (palantir).** Run `make format` before every commit; CI runs `spotlessCheck` before `build`.
- Config idiom is `${ENV_VAR:default}` inline in `application.yml`. `.env` is gitignored, `.env.example` is committed.
- `@Slf4j` for logging, never a manual `LoggerFactory`.
- **The API key is never logged and never appears in an exception message.** A test asserts this over a full call cycle.
- A blank `ANTHROPIC_API_KEY` must not stop the application from booting.
- The model default is `claude-sonnet-5`. One model property; no per-call override.
- Run tests with `./gradlew test`. Docker must be running.

---

### Task 1: Properties, exceptions, stub server and plain completion

**Files:**
- Create: `src/main/java/com/silporestockai/config/ClaudeProperties.java`
- Create: `src/main/java/com/silporestockai/config/ClaudeConfig.java`
- Create: `src/main/java/com/silporestockai/exception/ClaudeApiException.java`
- Create: `src/main/java/com/silporestockai/exception/ClaudeRateLimitedException.java`
- Create: `src/main/java/com/silporestockai/exception/ClaudeUnavailableException.java`
- Create: `src/main/java/com/silporestockai/exception/ClaudeStructuredOutputException.java`
- Create: `src/main/java/com/silporestockai/client/claude/ClaudeApiClient.java`
- Create: `src/main/java/com/silporestockai/client/claude/ClaudeApiClientImpl.java`
- Modify: `src/main/resources/application.yml` (new top-level `claude:` block)
- Test: `src/test/java/com/silporestockai/support/StubAnthropicServer.java`
- Test: `src/test/java/com/silporestockai/integration/ClaudeApiClientIntegrationTest.java`

**Interfaces:**
- Consumes: `com.silporestockai.integration.AbstractIntegrationTest` (existing: `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, Testcontainers PostgreSQL); `com.silporestockai.exception.ApplicationException(HttpStatus, String)` and `(HttpStatus, String, Throwable)`.
- Produces:
  - `ClaudeProperties(String apiKey, String model, long maxTokens, Duration timeout, String baseUrl)` with `boolean apiKeyConfigured()`
  - `ClaudeApiClient.complete(String systemPrompt, String userPrompt) -> String`
  - `ClaudeApiClient.completeStructured(String systemPrompt, String userPrompt, Class<T> responseType) -> T`
  - `ClaudeApiClient.image(String systemPrompt, String userPrompt, byte[] imageBytes, String mediaType) -> String`
  - `ClaudeApiException(String)` and `(String, Throwable)`, both `BAD_GATEWAY`
  - `ClaudeRateLimitedException(String, Throwable)` — `TOO_MANY_REQUESTS`
  - `ClaudeUnavailableException(String, Throwable)` — `BAD_GATEWAY`
  - `ClaudeStructuredOutputException(String)` and `(String, Throwable)` — `BAD_GATEWAY`
  - `StubAnthropicServer` with `baseUrl()`, `requests()`, `callCount()`, `injectStatus(int)`, `respondWithText(String)`, `reset()`, `close()`

- [ ] **Step 1: Add configuration**

Append a new top-level block to `src/main/resources/application.yml`, after the existing `telegram:` block:

```yaml
claude:
  api-key: ${ANTHROPIC_API_KEY:}
  model: ${ANTHROPIC_MODEL:claude-sonnet-5}
  max-tokens: ${ANTHROPIC_MAX_TOKENS:4096}
  # Meal plan generation is a long call; keep this well above a normal HTTP timeout.
  timeout: ${ANTHROPIC_TIMEOUT:120s}
  # Overridden by tests to point the SDK at a local stub. Not a production knob.
  base-url: ${ANTHROPIC_BASE_URL:https://api.anthropic.com}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/silporestockai/support/StubAnthropicServer.java`:

```java
package com.silporestockai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal Anthropic Messages API over plain HTTP, enough to drive {@code ClaudeApiClientImpl} in tests.
 *
 * <p>Answers {@code POST /v1/messages} with a single text content block whose text is whatever
 * {@link #respondWithText(String)} was last given. Statuses can be injected one call at a time so a test can
 * script "429 then 200" and assert the retry happened, and every request body is recorded so a test can
 * assert what was actually sent.
 */
public final class StubAnthropicServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();
    private final Deque<Integer> injectedStatuses = new ArrayDeque<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile String responseText = "stub completion";

    public StubAnthropicServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/messages", this::handle);
        this.server.start();
    }

    /** Base URL to hand to {@code claude.base-url}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Text the next responses carry in their single content block. */
    public void respondWithText(String text) {
        this.responseText = text;
    }

    /** Makes the next call answer with {@code status} and an Anthropic-shaped error body. */
    public synchronized void injectStatus(int status) {
        injectedStatuses.add(status);
    }

    public synchronized List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    public int callCount() {
        return callCount.get();
    }

    public synchronized void reset() {
        requests.clear();
        injectedStatuses.clear();
        callCount.set(0);
        responseText = "stub completion";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            callCount.incrementAndGet();
            byte[] rawBody = exchange.getRequestBody().readAllBytes();
            record(rawBody.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(rawBody));

            Integer injected = nextInjectedStatus();
            if (injected != null) {
                respond(exchange, injected, errorBody(injected));
                return;
            }
            respond(exchange, 200, successBody(responseText));
        } finally {
            exchange.close();
        }
    }

    private synchronized void record(JsonNode body) {
        requests.add(body);
    }

    private synchronized Integer nextInjectedStatus() {
        return injectedStatuses.poll();
    }

    private static String successBody(String text) {
        return """
                {"id":"msg_stub","type":"message","role":"assistant","model":"claude-sonnet-5",\
                "content":[{"type":"text","text":%s}],"stop_reason":"end_turn","stop_sequence":null,\
                "usage":{"input_tokens":10,"output_tokens":20}}"""
                .formatted(quote(text));
    }

    private static String errorBody(int status) {
        String type = status == 429 ? "rate_limit_error" : status == 401 ? "authentication_error" : "api_error";
        return """
                {"type":"error","error":{"type":"%s","message":"stubbed %d"}}""".formatted(type, status);
    }

    /** JSON-quotes a string, which is how arbitrary model output gets embedded in the canned response. */
    private static String quote(String raw) {
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (IOException e) {
            throw new IllegalStateException("could not quote the stub response text", e);
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
```

Create `src/test/java/com/silporestockai/integration/ClaudeApiClientIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.exception.ClaudeApiException;
import com.silporestockai.support.StubAnthropicServer;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("ClaudeApiClient completes text against the Messages API")
class ClaudeApiClientIntegrationTest extends AbstractIntegrationTest {

    private static final String API_KEY = "sk-ant-stub-key-do-not-use";
    private static final StubAnthropicServer STUB = start();

    @Autowired
    private ClaudeApiClient claudeApiClient;

    private static StubAnthropicServer start() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void claudeProperties(DynamicPropertyRegistry registry) {
        registry.add("claude.api-key", () -> API_KEY);
        registry.add("claude.base-url", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
    }

    @Test
    void returnsTheModelsTextAndSendsTheConfiguredModelAndPrompts() {
        STUB.respondWithText("сир, молоко, хліб");

        String answer = claudeApiClient.complete("Ти помічник із закупів.", "Що купити?");

        assertThat(answer).isEqualTo("сир, молоко, хліб");
        var request = STUB.requests().getFirst();
        assertThat(request.path("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(request.path("system").asText()).contains("Ти помічник із закупів.");
        assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    void mapsAnAuthenticationFailureToClaudeApiExceptionWithoutRetrying() {
        STUB.injectStatus(401);

        assertThatThrownBy(() -> claudeApiClient.complete("system", "user"))
                .isInstanceOf(ClaudeApiException.class);

        assertThat(STUB.callCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests '*ClaudeApiClientIntegrationTest*'`
Expected: FAIL — compilation error, `ClaudeApiClient` and `ClaudeApiException` do not exist.

- [ ] **Step 4: Write the properties and config**

Create `src/main/java/com/silporestockai/config/ClaudeProperties.java`:

```java
package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Anthropic Claude API.
 *
 * @param apiKey Anthropic API key; blank in tests and CI, which makes every call fail with a clear message
 *     rather than stopping the application from booting
 * @param model model id, e.g. {@code claude-sonnet-5}
 * @param maxTokens output token ceiling for a single call
 * @param timeout per-request timeout; high because meal plan generation is a long call
 * @param baseUrl API base URL; overridden by tests to reach a local stub
 */
@ConfigurationProperties(prefix = "claude")
public record ClaudeProperties(String apiKey, String model, long maxTokens, Duration timeout, String baseUrl) {

    public boolean apiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
```

Create `src/main/java/com/silporestockai/config/ClaudeConfig.java`:

```java
package com.silporestockai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link ClaudeProperties} from {@code application.yml}. */
@Configuration
@EnableConfigurationProperties(ClaudeProperties.class)
public class ClaudeConfig {}
```

- [ ] **Step 5: Write the exceptions**

Create `src/main/java/com/silporestockai/exception/ClaudeApiException.java`:

```java
package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a Claude API call fails for a reason the caller cannot fix by retrying.
 *
 * <p>Never carries the API key: the SDK keeps it in a header, and the messages built here quote only the
 * API's own error text.
 */
public class ClaudeApiException extends ApplicationException {

    public ClaudeApiException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }

    public ClaudeApiException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }

    protected ClaudeApiException(HttpStatus status, String message, Throwable cause) {
        super(status, message, cause);
    }
}
```

Create `src/main/java/com/silporestockai/exception/ClaudeRateLimitedException.java`:

```java
package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Claude answered 429. One of the two exceptions the {@code claude} Resilience4j retry backs off on. */
public class ClaudeRateLimitedException extends ClaudeApiException {

    public ClaudeRateLimitedException(String message, Throwable cause) {
        super(HttpStatus.TOO_MANY_REQUESTS, message, cause);
    }
}
```

Create `src/main/java/com/silporestockai/exception/ClaudeUnavailableException.java`:

```java
package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Claude was unreachable or answered 5xx. Worth retrying, unlike a malformed request. */
public class ClaudeUnavailableException extends ClaudeApiException {

    public ClaudeUnavailableException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
```

Create `src/main/java/com/silporestockai/exception/ClaudeStructuredOutputException.java`:

```java
package com.silporestockai.exception;

/**
 * The model did not return output matching the requested type.
 *
 * <p>Deliberately not retried: whether to ask again with a different prompt or fall back is the caller's
 * decision, and silently retrying would hide a prompt or schema problem.
 */
public class ClaudeStructuredOutputException extends ClaudeApiException {

    public ClaudeStructuredOutputException(String message) {
        super(message);
    }

    public ClaudeStructuredOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 6: Write the client interface**

Create `src/main/java/com/silporestockai/client/claude/ClaudeApiClient.java`:

```java
package com.silporestockai.client.claude;

/**
 * Transport client for the Anthropic Claude API, shared by meal plan generation (task 07), check-in parsing
 * (task 12) and fridge-photo parsing (task 17).
 *
 * <p>Carries no prompt content: prompts belong to the services that own them.
 */
public interface ClaudeApiClient {

    /** Plain text completion. Returns the concatenated text blocks of the reply. */
    String complete(String systemPrompt, String userPrompt);

    /**
     * Completion constrained to {@code responseType}. The SDK derives a JSON schema from the class, sends it
     * as the request's output config and deserialises the reply, so a malformed answer fails inside the SDK
     * rather than at a later parse.
     *
     * @throws com.silporestockai.exception.ClaudeStructuredOutputException if the model returned nothing
     *     matching the type
     */
    <T> T completeStructured(String systemPrompt, String userPrompt, Class<T> responseType);

    /**
     * Completion over an image plus a text prompt.
     *
     * @param imageBytes raw image bytes, base64-encoded before sending
     * @param mediaType MIME type, e.g. {@code image/jpeg}
     */
    String image(String systemPrompt, String userPrompt, byte[] imageBytes, String mediaType);
}
```

- [ ] **Step 7: Write the implementation with plain completion only**

Create `src/main/java/com/silporestockai/client/claude/ClaudeApiClientImpl.java`:

```java
package com.silporestockai.client.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.silporestockai.config.ClaudeProperties;
import com.silporestockai.exception.ClaudeApiException;
import com.silporestockai.exception.ClaudeRateLimitedException;
import com.silporestockai.exception.ClaudeUnavailableException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Claude API client.
 *
 * <p>The SDK's own retry policy is switched off ({@code maxRetries(0)}) so backoff lives in one place —
 * {@code resilience4j.retry.instances.claude} in {@code application.yml}, next to the {@code silpoMcp}
 * instance. Only {@link ClaudeRateLimitedException} and {@link ClaudeUnavailableException} are retried; a
 * malformed request must fail on the first attempt and must not trip the circuit breaker.
 *
 * <p>A blank API key is not a startup failure: the client is simply not built, and any call throws a clear
 * {@link ClaudeApiException} before touching the network. The key is never logged.
 */
@Slf4j
@Component
public class ClaudeApiClientImpl implements ClaudeApiClient {

    private final ClaudeProperties properties;
    private final AnthropicClient client;

    public ClaudeApiClientImpl(ClaudeProperties properties) {
        this.properties = properties;
        if (!properties.apiKeyConfigured()) {
            log.warn("ANTHROPIC_API_KEY is not set — Claude calls will fail until it is configured");
            this.client = null;
        } else {
            this.client = AnthropicOkHttpClient.builder()
                    .apiKey(properties.apiKey())
                    .baseUrl(properties.baseUrl())
                    .timeout(properties.timeout())
                    // Backoff is Resilience4j's job; two retry policies stacked would multiply the wait.
                    .maxRetries(0)
                    .build();
        }
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        MessageCreateParams params =
                baseParams(systemPrompt).addUserMessage(userPrompt).build();
        return textOf(call(() -> client().messages().create(params)));
    }

    @Override
    public <T> T completeStructured(String systemPrompt, String userPrompt, Class<T> responseType) {
        throw new UnsupportedOperationException("structured output arrives in task 2 of the plan");
    }

    @Override
    public String image(String systemPrompt, String userPrompt, byte[] imageBytes, String mediaType) {
        throw new UnsupportedOperationException("image input arrives in task 4 of the plan");
    }

    private MessageCreateParams.Builder baseParams(String systemPrompt) {
        return MessageCreateParams.builder()
                .model(Model.of(properties.model()))
                .maxTokens(properties.maxTokens())
                .system(systemPrompt);
    }

    private static String textOf(Message message) {
        return message.content().stream()
                .filter(ContentBlock::isText)
                .map(block -> block.asText().text())
                .collect(Collectors.joining());
    }

    private AnthropicClient client() {
        if (client == null) {
            throw new ClaudeApiException("ANTHROPIC_API_KEY is not configured");
        }
        return client;
    }

    /**
     * Runs one SDK call and translates its failure into an exception that says whether retrying is worth it.
     * The SDK's message carries the API's error text, never the key.
     */
    private <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (RateLimitException e) {
            throw new ClaudeRateLimitedException("Claude rate limited the request", e);
        } catch (InternalServerException | AnthropicIoException | AnthropicRetryableException e) {
            throw new ClaudeUnavailableException("Claude is unavailable: " + e.getMessage(), e);
        } catch (AnthropicException e) {
            throw new ClaudeApiException("Claude call failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew test --tests '*ClaudeApiClientIntegrationTest*'`
Expected: PASS, 2 tests.

If the first test fails on `system`, note that the SDK may send `system` as an array of text blocks rather
than a bare string; the assertion uses `contains` on `asText()` for that reason. If it still fails, assert on
`request.path("system").toString()` instead.

- [ ] **Step 9: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/java/com/silporestockai/config/ClaudeProperties.java \
        src/main/java/com/silporestockai/config/ClaudeConfig.java \
        src/main/java/com/silporestockai/exception/ClaudeApiException.java \
        src/main/java/com/silporestockai/exception/ClaudeRateLimitedException.java \
        src/main/java/com/silporestockai/exception/ClaudeUnavailableException.java \
        src/main/java/com/silporestockai/exception/ClaudeStructuredOutputException.java \
        src/main/java/com/silporestockai/client/claude/ClaudeApiClient.java \
        src/main/java/com/silporestockai/client/claude/ClaudeApiClientImpl.java \
        src/main/resources/application.yml \
        src/test/java/com/silporestockai/support/StubAnthropicServer.java \
        src/test/java/com/silporestockai/integration/ClaudeApiClientIntegrationTest.java
git commit -m "Add a Claude API client for text completion"
```

---

### Task 2: Structured output

**Files:**
- Modify: `src/main/java/com/silporestockai/client/claude/ClaudeApiClientImpl.java` (replace the `completeStructured` stub)
- Test: `src/test/java/com/silporestockai/integration/ClaudeApiClientIntegrationTest.java` (add a nested record and three tests)

**Interfaces:**
- Consumes: `ClaudeApiClient.completeStructured`, `ClaudeStructuredOutputException`, `StubAnthropicServer.respondWithText` from Task 1.
- Produces: nothing new; fills in the declared method.

- [ ] **Step 1: Write the failing tests**

Append to `ClaudeApiClientIntegrationTest`:

```java
    /** Target type for the structured-output tests. Deliberately trivial: this is a transport test. */
    record InventoryDelta(String item, int quantity, boolean runningOut) {}

    @Test
    void deserialisesStructuredOutputIntoTheRequestedRecord() {
        STUB.respondWithText("{\"item\":\"молоко\",\"quantity\":2,\"runningOut\":true}");

        InventoryDelta delta =
                claudeApiClient.completeStructured("system", "молока лишилось два", InventoryDelta.class);

        assertThat(delta.item()).isEqualTo("молоко");
        assertThat(delta.quantity()).isEqualTo(2);
        assertThat(delta.runningOut()).isTrue();
    }

    @Test
    void sendsAnOutputConfigDerivedFromTheTargetType() {
        STUB.respondWithText("{\"item\":\"хліб\",\"quantity\":1,\"runningOut\":false}");

        claudeApiClient.completeStructured("system", "user", InventoryDelta.class);

        assertThat(STUB.requests().getFirst().toString()).contains("runningOut");
    }

    @Test
    void surfacesProseInsteadOfStructuredOutputAsATypedException() {
        STUB.respondWithText("Вибач, я не зрозумів запит.");

        assertThatThrownBy(
                        () -> claudeApiClient.completeStructured("system", "user", InventoryDelta.class))
                .isInstanceOf(ClaudeStructuredOutputException.class);

        assertThat(STUB.callCount()).isEqualTo(1);
    }
```

Add the import `import com.silporestockai.exception.ClaudeStructuredOutputException;` to the test file.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*ClaudeApiClientIntegrationTest*'`
Expected: FAIL with `UnsupportedOperationException: structured output arrives in task 2 of the plan`.

- [ ] **Step 3: Implement structured output**

In `ClaudeApiClientImpl`, replace the `completeStructured` stub with:

```java
    @Override
    public <T> T completeStructured(String systemPrompt, String userPrompt, Class<T> responseType) {
        StructuredMessageCreateParams<T> params = baseParams(systemPrompt)
                .addUserMessage(userPrompt)
                .outputConfig(responseType, JsonSchemaLocalValidation.YES)
                .build();
        StructuredMessage<T> message = call(() -> client().messages().create(params));
        try {
            return message.content().stream()
                    .map(StructuredContentBlock::text)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .map(StructuredTextBlock::text)
                    .orElseThrow(() -> new ClaudeStructuredOutputException(
                            "Claude returned no output matching " + responseType.getSimpleName()));
        } catch (ClaudeStructuredOutputException e) {
            throw e;
        } catch (RuntimeException e) {
            // The SDK raises when the reply does not deserialise into the target type. Surface it as our own
            // type so callers can retry with a different prompt or fall back — silently retrying would hide
            // a prompt or schema problem.
            throw new ClaudeStructuredOutputException(
                    "Claude returned output that does not match " + responseType.getSimpleName(), e);
        }
    }
```

Add these imports to `ClaudeApiClientImpl`:

```java
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import com.silporestockai.exception.ClaudeStructuredOutputException;
import java.util.Optional;
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests '*ClaudeApiClientIntegrationTest*'`
Expected: PASS, 5 tests.

If `outputConfig(Class, JsonSchemaLocalValidation)` rejects a record, switch the argument to
`JsonSchemaLocalValidation.NO` — that disables the SDK's local schema check while keeping the typed
deserialisation, and the third test still proves malformed output is surfaced.

- [ ] **Step 5: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/java/com/silporestockai/client/claude/ClaudeApiClientImpl.java \
        src/test/java/com/silporestockai/integration/ClaudeApiClientIntegrationTest.java
git commit -m "Return typed structured output from Claude"
```

---

### Task 3: Retry and circuit breaker

**Files:**
- Modify: `src/main/resources/application.yml` (`resilience4j.retry.instances.claude`, `resilience4j.circuitbreaker.instances.claude`)
- Modify: `src/main/java/com/silporestockai/client/claude/ClaudeApiClientImpl.java` (annotate the three public methods)
- Test: `src/test/java/com/silporestockai/integration/ClaudeApiClientIntegrationTest.java` (add one test)

**Interfaces:**
- Consumes: `ClaudeRateLimitedException`, `ClaudeUnavailableException` from Task 1; `StubAnthropicServer.injectStatus(int)`.
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Write the failing test**

Append to `ClaudeApiClientIntegrationTest`:

```java
    @Test
    void backsOffAndRetriesWhenClaudeRateLimitsTheRequest() {
        STUB.injectStatus(429);
        STUB.respondWithText("після паузи");

        String answer = claudeApiClient.complete("system", "user");

        assertThat(answer).isEqualTo("після паузи");
        assertThat(STUB.callCount()).isEqualTo(2);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ClaudeApiClientIntegrationTest.backsOffAndRetries*'`
Expected: FAIL — `ClaudeRateLimitedException` propagates because nothing retries yet, and `callCount()` is 1.

- [ ] **Step 3: Add the Resilience4j configuration**

In `src/main/resources/application.yml`, add a `claude` instance under `resilience4j.retry.instances`,
directly after the existing `silpoMcp` block:

```yaml
      # Claude answers 429 under load and 5xx during incidents; both are worth waiting out. A malformed
      # request or a bad key must fail on the first attempt, so they are not listed here.
      claude:
        base-config: default
        max-attempts: 3
        wait-duration: 2s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - com.silporestockai.exception.ClaudeRateLimitedException
          - com.silporestockai.exception.ClaudeUnavailableException
```

and add an `instances` section under `resilience4j.circuitbreaker`, after the existing `configs` block:

```yaml
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 5
    instances:
      # Only genuine upstream trouble counts towards opening the breaker. A malformed prompt earning a 400
      # is our bug, not Claude's, and must not cut everyone else off.
      claude:
        base-config: default
        record-exceptions:
          - com.silporestockai.exception.ClaudeRateLimitedException
          - com.silporestockai.exception.ClaudeUnavailableException
```

Note the existing file already has `resilience4j.circuitbreaker.configs.default` — keep it exactly as it is
and add only the `instances:` key at the same level as `configs:`.

- [ ] **Step 4: Annotate the client methods**

In `ClaudeApiClientImpl`, add these two imports:

```java
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
```

and put both annotations on each of `complete`, `completeStructured` and `image`, above the existing
`@Override`:

```java
    @Override
    @CircuitBreaker(name = "claude")
    @Retry(name = "claude")
    public String complete(String systemPrompt, String userPrompt) {
```

```java
    @Override
    @CircuitBreaker(name = "claude")
    @Retry(name = "claude")
    public <T> T completeStructured(String systemPrompt, String userPrompt, Class<T> responseType) {
```

```java
    @Override
    @CircuitBreaker(name = "claude")
    @Retry(name = "claude")
    public String image(String systemPrompt, String userPrompt, byte[] imageBytes, String mediaType) {
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests '*ClaudeApiClientIntegrationTest*'`
Expected: PASS, 6 tests. In particular `mapsAnAuthenticationFailureToClaudeApiExceptionWithoutRetrying` must
still see exactly 1 call, which is what proves the retry list is not catching plain `ClaudeApiException`.

- [ ] **Step 6: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/resources/application.yml \
        src/main/java/com/silporestockai/client/claude/ClaudeApiClientImpl.java \
        src/test/java/com/silporestockai/integration/ClaudeApiClientIntegrationTest.java
git commit -m "Back off on Claude rate limits and outages"
```

---

### Task 4: Image input, the missing-key path, log safety and documentation

**Files:**
- Modify: `src/main/java/com/silporestockai/client/claude/ClaudeApiClientImpl.java` (replace the `image` stub)
- Modify: `.env.example`
- Modify: `README.md` (Configuration section)
- Test: `src/test/java/com/silporestockai/integration/ClaudeApiClientIntegrationTest.java` (add two tests)
- Test: `src/test/java/com/silporestockai/integration/ClaudeApiClientWithoutKeyIntegrationTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1 to 3.
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Write the failing tests**

Append to `ClaudeApiClientIntegrationTest`:

```java
    @Test
    void sendsAnImageAsABase64BlockAlongsideTheTextPrompt() {
        STUB.respondWithText("бачу молоко і сир");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

        String answer = claudeApiClient.image("system", "Що в холодильнику?", png, "image/png");

        assertThat(answer).isEqualTo("бачу молоко і сир");
        var content = STUB.requests().getFirst().path("messages").get(0).path("content");
        assertThat(content.get(0).path("type").asText()).isEqualTo("image");
        assertThat(content.get(0).path("source").path("media_type").asText()).isEqualTo("image/png");
        assertThat(content.get(0).path("source").path("data").asText())
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(png));
        assertThat(content.get(1).path("text").asText()).isEqualTo("Що в холодильнику?");
    }

    @Test
    void neverLogsTheApiKey() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            STUB.respondWithText("ok");
            claudeApiClient.complete("system", "user");
            STUB.injectStatus(401);
            try {
                claudeApiClient.complete("system", "user");
            } catch (ClaudeApiException expected) {
                // The failure path is exactly where a key is most likely to be logged, so exercise it.
            }
        } finally {
            root.detachAppender(appender);
        }

        assertThat(appender.list)
                .noneMatch(event -> event.getFormattedMessage().contains(API_KEY));
    }
```

Add these imports to the test file:

```java
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
```

Create `src/test/java/com/silporestockai/integration/ClaudeApiClientWithoutKeyIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.exception.ClaudeApiException;
import com.silporestockai.support.StubAnthropicServer;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("a missing API key fails the call, not the application startup")
class ClaudeApiClientWithoutKeyIntegrationTest extends AbstractIntegrationTest {

    private static final StubAnthropicServer STUB = start();

    @Autowired
    private ClaudeApiClient claudeApiClient;

    private static StubAnthropicServer start() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void claudeProperties(DynamicPropertyRegistry registry) {
        registry.add("claude.api-key", () -> "");
        registry.add("claude.base-url", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Test
    void theContextStartsAndTheCallFailsWithAClearMessageWithoutTouchingTheNetwork() {
        assertThat(claudeApiClient).isNotNull();

        assertThatThrownBy(() -> claudeApiClient.complete("system", "user"))
                .isInstanceOf(ClaudeApiException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY is not configured");

        assertThat(STUB.callCount()).isZero();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*ClaudeApiClient*'`
Expected: FAIL — `UnsupportedOperationException: image input arrives in task 4 of the plan` for the image
test; the other two should already pass, which is fine.

- [ ] **Step 3: Implement the image call**

In `ClaudeApiClientImpl`, replace the `image` stub body with:

```java
        Base64ImageSource source = Base64ImageSource.builder()
                .data(Base64.getEncoder().encodeToString(imageBytes))
                .mediaType(Base64ImageSource.MediaType.of(mediaType))
                .build();
        MessageCreateParams params = baseParams(systemPrompt)
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(
                                ImageBlockParam.builder().source(source).build()),
                        ContentBlockParam.ofText(userPrompt)))
                .build();
        return textOf(call(() -> client().messages().create(params)));
```

and add these imports:

```java
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import java.util.Base64;
import java.util.List;
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests '*ClaudeApiClient*'`
Expected: PASS, 9 tests across the two classes.

- [ ] **Step 5: Document the configuration**

In `.env.example`, append:

```bash
# --- Anthropic Claude ---
# API key from https://console.anthropic.com/settings/keys
ANTHROPIC_API_KEY=
# Optional overrides; the defaults in application.yml are usually right.
# ANTHROPIC_MODEL=claude-sonnet-5
# ANTHROPIC_MAX_TOKENS=4096
```

In `README.md`, inside the `## Configuration` section, after the `### Telegram` subsection and before the
line beginning `The schema is owned by **Liquibase**`, add:

````markdown
### Anthropic Claude

Used for meal plan generation, check-in parsing and (stretch) fridge-photo parsing:

| Variable              | Default                     | Purpose                                        |
|-----------------------|-----------------------------|------------------------------------------------|
| `ANTHROPIC_API_KEY`   | *(empty)*                   | API key; blank makes Claude calls fail with a clear message, the app still boots |
| `ANTHROPIC_MODEL`     | `claude-sonnet-5`           | Model id                                       |
| `ANTHROPIC_MAX_TOKENS`| `4096`                      | Output token ceiling per call                  |
| `ANTHROPIC_TIMEOUT`   | `120s`                      | Per-request timeout; meal plan generation is slow |

Retry and circuit-breaker behaviour lives under `resilience4j.retry.instances.claude` and
`resilience4j.circuitbreaker.instances.claude` in `application.yml`. Only rate limits and upstream outages are
retried; a malformed request fails on the first attempt.

To smoke-test against the real API, export a key and run:

```bash
ANTHROPIC_API_KEY=sk-ant-... ./gradlew test --tests '*ClaudeApiClientIntegrationTest*' \
  -Dclaude.base-url=https://api.anthropic.com
```
````

- [ ] **Step 6: Verify everything**

```bash
make format
./gradlew test
./gradlew build
```
Expected: `BUILD SUCCESSFUL` for all three.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/silporestockai/client/claude/ClaudeApiClientImpl.java \
        src/test/java/com/silporestockai/integration/ClaudeApiClientIntegrationTest.java \
        src/test/java/com/silporestockai/integration/ClaudeApiClientWithoutKeyIntegrationTest.java \
        .env.example README.md
git commit -m "Accept images and document the Claude client"
```

---

## Acceptance criteria mapping

| Notion criterion | Proven by |
|---|---|
| `complete(...)` and `completeStructured(...)` work against a mocked API response | Task 1 `returnsTheModelsTextAndSendsTheConfiguredModelAndPrompts`; Task 2 `deserialisesStructuredOutputIntoTheRequestedRecord` |
| Malformed model output surfaces a specific exception type | Task 2 `surfacesProseInsteadOfStructuredOutputAsATypedException` |
| API key read from the environment, never hardcoded, never logged | Task 1 `application.yml` `${ANTHROPIC_API_KEY:}`; Task 4 `neverLogsTheApiKey` and `ClaudeApiClientWithoutKeyIntegrationTest` |
| Circuit breaker config visible in `application.yml` under `resilience4j.*` | Task 3 |
| `image(...)` signature exists for the stretch goal | Task 4 `sendsAnImageAsABase64BlockAlongsideTheTextPrompt` |

Not covered by automated tests: a live call to `api.anthropic.com`. No `ANTHROPIC_API_KEY` is available. The
README carries the command; state the gap in the final commit message.
