package com.silporestockai.client.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import com.silporestockai.config.ClaudeProperties;
import com.silporestockai.exception.ClaudeApiException;
import com.silporestockai.exception.ClaudeRateLimitedException;
import com.silporestockai.exception.ClaudeStructuredOutputException;
import com.silporestockai.exception.ClaudeUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Claude API client.
 *
 * <p>The SDK's own retry policy is switched off ({@code maxRetries(0)}) so backoff lives in one place —
 * {@code resilience4j.retry.instances.claude} in {@code application.yml}, next to the {@code silpoMcp} instance. Only
 * {@link ClaudeRateLimitedException} and {@link ClaudeUnavailableException} are retried; a malformed request must fail
 * on the first attempt and must not trip the circuit breaker.
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
    @CircuitBreaker(name = "claude")
    @Retry(name = "claude")
    public String complete(String systemPrompt, String userPrompt) {
        MessageCreateParams params =
                baseParams(systemPrompt).addUserMessage(userPrompt).build();
        return textOf(call(() -> client().messages().create(params)));
    }

    @Override
    @CircuitBreaker(name = "claude")
    @Retry(name = "claude")
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
            // The SDK raises when the reply does not deserialise into the target type. Surface it as our own type so
            // callers can retry with a different prompt or fall back — silently retrying would hide a prompt or
            // schema problem.
            throw new ClaudeStructuredOutputException(
                    "Claude returned output that does not match " + responseType.getSimpleName(), e);
        }
    }

    @Override
    @CircuitBreaker(name = "claude")
    @Retry(name = "claude")
    public String image(String systemPrompt, String userPrompt, byte[] imageBytes, String mediaType) {
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
     * Runs one SDK call and translates its failure into an exception that says whether retrying is worth it. The SDK's
     * message carries the API's error text, never the key.
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
