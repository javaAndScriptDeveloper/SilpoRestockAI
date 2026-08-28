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
