package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.exception.ClaudeApiException;
import com.silporestockai.exception.ClaudeStructuredOutputException;
import com.silporestockai.support.StubAnthropicServer;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    /**
     * A call on every outbound message once a chat turns voice replies on must not cost what meal planning costs —
     * that is the entire reason {@code completeFast} exists rather than reusing {@code complete}.
     */
    @Test
    void completeFastUsesTheCheapModelRatherThanTheReasoningOne() {
        STUB.respondWithText("Кошик готовий.");

        String answer = claudeApiClient.completeFast("Перепиши для голосу.", "Разом: 73.50 грн");

        assertThat(answer).isEqualTo("Кошик готовий.");
        assertThat(STUB.requests().getFirst().path("model").asText()).isEqualTo("claude-haiku-4-5-20251001");
    }

    /**
     * The bananas bug and the invented-items bug were both "the model said something we did not expect", diagnosed
     * only by asking the user to paste a chat transcript back. The prompt and the completion are now in the log on
     * every call, so that question no longer needs a live conversation to answer.
     */
    @Test
    void logsThePromptAndTheCompletionAtDebug() {
        STUB.respondWithText("сир, молоко, хліб");

        Logger logger = (Logger) LoggerFactory.getLogger("com.silporestockai.client.claude.ClaudeApiClientImpl");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            claudeApiClient.complete("Ти помічник із закупів.", "Що купити на алергію на молочку?");
        } finally {
            logger.detachAppender(appender);
        }

        var debugLines = appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.DEBUG)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(debugLines).anyMatch(line -> line.contains("Що купити на алергію на молочку?"));
        assertThat(debugLines).anyMatch(line -> line.contains("сир, молоко, хліб"));
    }

    @Test
    void mapsAnAuthenticationFailureToClaudeApiExceptionWithoutRetrying() {
        STUB.injectStatus(401);

        assertThatThrownBy(() -> claudeApiClient.complete("system", "user")).isInstanceOf(ClaudeApiException.class);

        assertThat(STUB.callCount()).isEqualTo(1);
    }

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

        assertThatThrownBy(() -> claudeApiClient.completeStructured("system", "user", InventoryDelta.class))
                .isInstanceOf(ClaudeStructuredOutputException.class);

        assertThat(STUB.callCount()).isEqualTo(1);
    }

    @Test
    void backsOffAndRetriesWhenClaudeRateLimitsTheRequest() {
        STUB.injectStatus(429);
        STUB.respondWithText("після паузи");

        String answer = claudeApiClient.complete("system", "user");

        assertThat(answer).isEqualTo("після паузи");
        assertThat(STUB.callCount()).isEqualTo(2);
    }

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

        assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(API_KEY));
    }
}
