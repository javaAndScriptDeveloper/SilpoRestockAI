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

        assertThatThrownBy(() -> claudeApiClient.complete("system", "user")).isInstanceOf(ClaudeApiException.class);

        assertThat(STUB.callCount()).isEqualTo(1);
    }
}
