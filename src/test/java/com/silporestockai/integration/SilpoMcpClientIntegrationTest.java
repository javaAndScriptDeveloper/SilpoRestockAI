package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.silporestockai.client.mcp.McpToolInfo;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoAccessTokenProvider;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.exception.SilpoMcpException;
import com.silporestockai.support.RecordingTokenProvider;
import com.silporestockai.support.StubMcpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drives the real {@code SilpoMcpClientImpl} — Spring proxy and Resilience4j retry included — against a stub MCP
 * server, so the transport behaviour we depend on is verified rather than assumed.
 */
class SilpoMcpClientIntegrationTest extends AbstractIntegrationTest {

    private static final StubMcpServer STUB = startStub();

    @Autowired
    private SilpoMcpClient silpoMcpClient;

    @Autowired
    private SilpoAccessTokenProvider tokenProvider;

    private RecordingTokenProvider recordingTokenProvider;
    private UUID userId;

    @DynamicPropertySource
    static void mcpEndpoint(DynamicPropertyRegistry registry) {
        registry.add("silpo.mcp.endpoint", STUB::endpoint);
    }

    private static StubMcpServer startStub() {
        try {
            return new StubMcpServer(List.of("silpo_get_my_profile", "silpo_find_products_batch"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void setUp() {
        STUB.reset();
        recordingTokenProvider = (RecordingTokenProvider) tokenProvider;
        recordingTokenProvider.reset();
        userId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        silpoMcpClient.disconnect(userId);
    }

    @Test
    void listsToolsFromTheLiveServerRatherThanAHardcodedCatalogue() {
        List<McpToolInfo> tools = silpoMcpClient.listTools(userId);

        assertThat(tools)
                .extracting(McpToolInfo::name)
                .containsExactly("silpo_get_my_profile", "silpo_find_products_batch");
        assertThat(tools).allSatisfy(tool -> assertThat(tool.inputSchema()).isNotNull());
    }

    @Test
    void callsAToolAndSendsTheBearerTokenAndPerUserRateLimitCookie() {
        McpToolResponse response = silpoMcpClient.callTool("silpo_get_my_profile", Map.of("foo", "bar"), userId);

        assertThat(response.isError()).isFalse();
        assertThat(response.text()).isEqualTo("stub tool result");
        assertThat(STUB.seenAuthorizationHeaders()).contains("Bearer " + RecordingTokenProvider.INITIAL_TOKEN);
        assertThat(STUB.seenCookieHeaders()).contains("mcp-user=" + userId);
    }

    /**
     * The exact question task 09's cart-id bug could not answer at the time: what did Silpo actually send. Every
     * call now says so, not just the ones that end in a shape mismatch.
     */
    @Test
    void logsTheRawResponseOfEveryCallNotJustFailures() {
        STUB.respondToTool("silpo_get_my_profile", "{\"householdSize\":4}");

        Logger logger = (Logger) LoggerFactory.getLogger("com.silporestockai.client.mcp.SilpoMcpClientImpl");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            silpoMcpClient.callTool("silpo_get_my_profile", Map.of(), userId);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list.stream()
                        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.DEBUG)
                        .map(ILoggingEvent::getFormattedMessage))
                .anyMatch(line -> line.contains("silpo_get_my_profile") && line.contains("householdSize"));
    }

    @Test
    void backsOffAndRetriesWhenTheServerRateLimits() {
        STUB.injectStatus("tools/call", 429);

        McpToolResponse response = silpoMcpClient.callTool("silpo_get_my_profile", Map.of(), userId);

        assertThat(response.text()).isEqualTo("stub tool result");
        assertThat(STUB.callCount("tools/call")).isEqualTo(2);
    }

    @Test
    void refreshesTheTokenExactlyOnceOnUnauthorizedAndReplaysTheCall() {
        STUB.injectStatus("tools/call", 401);

        McpToolResponse response = silpoMcpClient.callTool("silpo_get_my_profile", Map.of(), userId);

        assertThat(response.text()).isEqualTo("stub tool result");
        assertThat(recordingTokenProvider.refreshCount()).isEqualTo(1);
        assertThat(STUB.seenAuthorizationHeaders()).contains("Bearer " + RecordingTokenProvider.REFRESHED_TOKEN);
    }

    @Test
    void propagatesTheFailureWhenTheRefreshDoesNotHelp() {
        recordingTokenProvider.refreshSucceeds(false);
        STUB.injectStatus("tools/call", 401);

        assertThatThrownBy(() -> silpoMcpClient.callTool("silpo_get_my_profile", Map.of(), userId))
                .isInstanceOf(SilpoMcpException.class);

        assertThat(recordingTokenProvider.refreshCount()).isEqualTo(1);
    }

    @Test
    void neverLogsTheAccessToken() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            STUB.injectStatus("tools/call", 401);
            silpoMcpClient.callTool("silpo_get_my_profile", Map.of("query", "молоко"), userId);
            silpoMcpClient.listTools(userId);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(RecordingTokenProvider.INITIAL_TOKEN)
                        || message.contains(RecordingTokenProvider.REFRESHED_TOKEN));
    }

    /** Replaces the OAuth-backed provider so these tests exercise the transport, not the token lifecycle. */
    @TestConfiguration
    static class StubTokenProviderConfiguration {

        @Bean
        @Primary
        RecordingTokenProvider recordingTokenProvider() {
            return new RecordingTokenProvider();
        }
    }
}
