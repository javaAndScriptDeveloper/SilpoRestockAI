package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.entity.McpToolCall;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.repository.McpToolCallRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Task 37: the report endpoint end-to-end, fed by a real (stubbed) MCP tool call. */
class InternalMetricsIntegrationTest extends AbstractIntegrationTest {

    private static final StubMcpServer MCP = startMcp();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SilpoMcpClient silpoMcpClient;

    @Autowired
    private McpToolCallRepository mcpToolCallRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of("silpo_get_my_profile"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("komora.metrics.token", () -> "pitch-token");
    }

    @AfterAll
    static void stop() {
        MCP.close();
    }

    @BeforeEach
    void clean() {
        MCP.reset();
        mcpToolCallRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** A user whose Silpo session exists, the way the OAuth callback leaves it. */
    private UUID connectedUser() {
        User user = userAccountService.findOrCreate(3701L);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user.getId();
    }

    @Test
    void everyMcpToolCallLeavesARowTheReportCounts() throws Exception {
        UUID userId = connectedUser();
        silpoMcpClient.callTool("silpo_get_my_profile", Map.of(), userId);
        silpoMcpClient.disconnect(userId);

        List<McpToolCall> rows = mcpToolCallRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getToolName()).isEqualTo("silpo_get_my_profile");
        assertThat(rows.getFirst().getUserId()).isEqualTo(userId);
        assertThat(rows.getFirst().isError()).isFalse();

        String body = mockMvc.perform(get("/internal/metrics/pitch").header("X-Metrics-Token", "pitch-token"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).contains("цифри для пітчу").contains("| 1 з 40 |").contains("silpo_get_my_profile");
    }

    @Test
    void theReportIsNotServedWithoutTheToken() throws Exception {
        mockMvc.perform(get("/internal/metrics/pitch")).andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/metrics/pitch").header("X-Metrics-Token", "nope"))
                .andExpect(status().isForbidden());
    }

    /** Task 55: the same real call the report counts is the one the published page names. */
    @Test
    void theArtifactPageNamesTheToolThatWasReallyCalled() throws Exception {
        UUID userId = connectedUser();
        silpoMcpClient.callTool("silpo_get_my_profile", Map.of(), userId);
        silpoMcpClient.disconnect(userId);

        String page = mockMvc.perform(get("/internal/metrics/pitch-artifact").header("X-Metrics-Token", "pitch-token"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(page).startsWith("<!doctype html>");
        assertThat(page).contains("silpo_get_my_profile").contains("1 з 40");
        // The page goes on a public URL, so it carries counters and names — never who made the call.
        assertThat(page).doesNotContain(userId.toString());
    }

    @Test
    void theArtifactPageIsNotServedWithoutTheToken() throws Exception {
        mockMvc.perform(get("/internal/metrics/pitch-artifact")).andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/metrics/pitch-artifact").header("X-Metrics-Token", "nope"))
                .andExpect(status().isForbidden());
    }
}
