# Anthropic Claude API client wrapper

Notion task `04. Anthropic Claude API client wrapper` (Phase `0. Setup`, Must have, size S, depends on `01`).

## Context

Three later tasks call Claude: `07` generates the weekly meal plan, `12` parses free-text and voice
check-ins into structured inventory deltas, and `17` (stretch) reads a fridge photo. One shared transport
client, not three HTTP integrations. No prompt content lives here — prompts belong to the tasks that own
them.

## Decisions

### The SDK's native structured outputs, not hand-rolled JSON parsing

The task text proposes instructing the model through the system prompt to return only JSON and parsing that
with Jackson. `anthropic-java:2.57.0` — already on the classpath from task 01 — ships something stronger:

```java
StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
        .model(...)
        .maxTokens(...)
        .system(systemPrompt)
        .addUserMessage(userPrompt)
        .outputConfig(responseType, JsonSchemaLocalValidation.YES)
        .build();
StructuredMessage<T> message = client.messages().create(params);
```

The SDK derives the JSON schema from the target class, sends it as the request's output config, and
validates the response locally before handing back a typed `T` from
`StructuredContentBlock.text().get().text()`. Malformed model output becomes an SDK-side failure rather than
something we detect after a `readValue` blows up. Verified against the shipped class files rather than
assumed: `MessageService.create(StructuredMessageCreateParams<T>) -> StructuredMessage<T>`,
`StructuredTextBlock<T>.text() -> T`, `JsonSchemaLocalValidation.YES`.

### Resilience4j owns retry and the breaker; the SDK's own retries are off

`AnthropicOkHttpClient.builder().maxRetries(0)`. Backoff belongs in `application.yml` next to the `silpoMcp`
instance, not split between the SDK's internal policy and ours. The acceptance criteria also ask for the
breaker config to be visible there.

### One model

`claude.model`, default `claude-sonnet-5`. Tasks 07 and 12 have different cost profiles but can add an
override when they measure a real problem.

### Missing API key is not a startup failure

A blank `ANTHROPIC_API_KEY` logs a WARN at startup and makes any call throw
`ClaudeApiException("ANTHROPIC_API_KEY is not configured")` before any HTTP attempt. `make run`, CI and every
test that does not exercise Claude keep working. The key is never logged and never appears in an exception
message.

## Design

```
service (tasks 07, 12, 17)
        │
        ▼
client/claude/ClaudeApiClient        interface
        │
client/claude/ClaudeApiClientImpl    @CircuitBreaker(name="claude") @Retry(name="claude")
        │
        ▼
com.anthropic.client.AnthropicClient ──► api.anthropic.com (or a stub, via claude.base-url)
```

`Impl` as a suffix is legal in `client`: the ArchUnit naming rules cover `controller`, `service`,
`repository` and `job` only, and `client/mcp/SilpoMcpClientImpl` is the existing precedent.

### Interface

```java
String complete(String systemPrompt, String userPrompt);

<T> T completeStructured(String systemPrompt, String userPrompt, Class<T> responseType);

String image(String systemPrompt, String userPrompt, byte[] imageBytes, String mediaType);
```

`image` ships now although only task 17 uses it: adding the signature while the client is being built is
cheaper than bolting it on later.

### Error mapping

| SDK exception | Ours | Retried | Recorded by the breaker |
|---|---|---|---|
| `RateLimitException` (429) | `ClaudeRateLimitedException` (429) | yes | yes |
| `InternalServerException`, `AnthropicIoException`, `AnthropicRetryableException` | `ClaudeUnavailableException` (502) | yes | yes |
| `BadRequestException`, `UnauthorizedException`, any other | `ClaudeApiException` (502) | no | no |
| no structured content block, or local validation fails | `ClaudeStructuredOutputException` (502) | no | no |

`ClaudeRateLimitedException`, `ClaudeUnavailableException` and `ClaudeStructuredOutputException` all extend
`ClaudeApiException`, which extends `ApplicationException`.

The breaker's `record-exceptions` lists only the two retryable types. A malformed prompt that earns a 400
must not trip the circuit for every other caller.

Structured-output failures are deliberately **not** retried: the acceptance criteria ask for them to surface
so the caller can retry with a different prompt or fall back, which is a decision the caller owns.

### New files

| File | Holds |
|---|---|
| `config/ClaudeProperties` | `@ConfigurationProperties("claude")`: apiKey, model, maxTokens, timeout, baseUrl |
| `config/ClaudeConfig` | `@EnableConfigurationProperties` |
| `client/claude/ClaudeApiClient` | the interface above |
| `client/claude/ClaudeApiClientImpl` | the implementation |
| `exception/ClaudeApiException` | base, `BAD_GATEWAY` |
| `exception/ClaudeRateLimitedException` | `TOO_MANY_REQUESTS` |
| `exception/ClaudeUnavailableException` | `BAD_GATEWAY` |
| `exception/ClaudeStructuredOutputException` | `BAD_GATEWAY` |

### Modified

`application.yml` (`claude.*`, `resilience4j.retry.instances.claude`,
`resilience4j.circuitbreaker.instances.claude`), `.env.example`, `README.md` (config table row).

### Configuration

```yaml
claude:
  api-key:    ${ANTHROPIC_API_KEY:}
  model:      ${ANTHROPIC_MODEL:claude-sonnet-5}
  max-tokens: ${ANTHROPIC_MAX_TOKENS:4096}
  timeout:    ${ANTHROPIC_TIMEOUT:120s}
  base-url:   ${ANTHROPIC_BASE_URL:https://api.anthropic.com}
```

`base-url` exists so integration tests can aim the SDK at a local stub. `timeout` defaults high because meal
plan generation is a long call.

## Testing

`support/StubAnthropicServer` — JDK `com.sun.net.httpserver` stub serving `POST /v1/messages`, in the shape
of `StubMcpServer` and `StubTelegramServer`: scripted status injection per call, canned response bodies,
every request body recorded.

| Test | Asserts |
|---|---|
| `complete` | text round-trips out of the response's content blocks |
| `completeStructured` | a record comes back populated, and the request carries the generated output config |
| prose instead of a structured block | `ClaudeStructuredOutputException` |
| 429 then 200 | retried; the stub sees 2 calls |
| 401 | `ClaudeApiException`; the stub sees exactly 1 call |
| `image` | the request body carries a base64 image source with the given media type |
| blank api key | typed `ClaudeApiException`, zero HTTP calls |
| logging | a Logback `ListAppender` over a full call cycle never sees the key |

No live smoke test: no `ANTHROPIC_API_KEY` is available. The README documents the command, and the commit
states the gap, as with tasks 02 and 03.

## Out of scope

No prompts for meal planning or check-in parsing — tasks 07 and 12. No streaming. No token accounting or
cost tracking. No caching of responses.
