package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.model.SilpoProfileSnapshot;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.onboarding.ProfileEnrichmentService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("MCP enrichment fills what it can and never breaks the flow")
class ProfileEnrichmentIntegrationTest extends AbstractIntegrationTest {

    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private ProfileEnrichmentService profileEnrichmentService;

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
            return new StubMcpServer(List.of(
                    "silpo_get_my_family",
                    "silpo_get_my_food_restrictions",
                    "silpo_get_my_online_orders",
                    "silpo_get_my_favorites"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        MCP.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        MCP.reset();
        CLAUDE.reset();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** isConnected reads the database, so a connected guest is simulated by inserting a token row. */
    private UUID connectedUser(long chatId) {
        User user = userAccountService.findOrCreate(chatId);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .refreshToken(tokenCipher.encrypt("stub-refresh-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user.getId();
    }

    @Test
    void returnsAnEmptySnapshotWhenTheGuestNeverConnected() {
        UUID userId = userAccountService.findOrCreate(7201L).getId();

        SilpoProfileSnapshot snapshot = profileEnrichmentService.enrich(userId);

        assertThat(snapshot.isEmpty()).isTrue();
        assertThat(MCP.callCount("tools/call")).isZero();
        assertThat(CLAUDE.callCount()).isZero();
    }

    @Test
    void callsTheFourProfileToolsAndLetsClaudeNormaliseTheirOutput() {
        UUID userId = connectedUser(7202L);
        CLAUDE.respondWithText("""
                {"householdSize":4,"hasKids":true,"kidsAges":[3,7],\
                "dietaryRestrictions":["без горіхів"],"frequentItems":["молоко","хліб"]}""");

        SilpoProfileSnapshot snapshot = profileEnrichmentService.enrich(userId);

        assertThat(MCP.callCount("tools/call")).isEqualTo(4);
        assertThat(snapshot.householdSize()).isEqualTo(4);
        assertThat(snapshot.hasKids()).isTrue();
        assertThat(snapshot.kidsAges()).containsExactly(3, 7);
        assertThat(snapshot.dietaryRestrictions()).containsExactly("без горіхів");
        assertThat(snapshot.frequentItems()).containsExactly("молоко", "хліб");
        assertThat(snapshot.isEmpty()).isFalse();
    }

    @Test
    void keepsGoingWhenOneToolIsNotGrantedForThisGuest() {
        UUID userId = connectedUser(7203L);
        // 403 means the tool is not granted. The three that did answer must still reach Claude.
        MCP.injectStatus("tools/call", 403);
        CLAUDE.respondWithText("{\"householdSize\":2,\"hasKids\":false}");

        SilpoProfileSnapshot snapshot = profileEnrichmentService.enrich(userId);

        assertThat(snapshot.householdSize()).isEqualTo(2);
        assertThat(CLAUDE.callCount()).isEqualTo(1);
    }

    @Test
    void degradesToAnEmptySnapshotWhenClaudeCannotBeReached() {
        UUID userId = connectedUser(7204L);
        CLAUDE.injectStatus(401);

        SilpoProfileSnapshot snapshot = profileEnrichmentService.enrich(userId);

        assertThat(snapshot.isEmpty()).isTrue();
    }
}
