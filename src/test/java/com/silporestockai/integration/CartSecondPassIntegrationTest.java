package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.model.CartSummary;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CartBuildingService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.math.BigDecimal;
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

/**
 * The second search pass: a line whose own name finds nothing in the catalog is searched again under the other
 * names the product might carry on a shelf. On a live account «Яйця курячі» returned zero candidates and
 * «Вівсянка» only flavoured porridge cups; the products exist, the search term was the problem.
 */
@DisplayName("a line the first catalog search missed gets a second search under other names")
class CartSecondPassIntegrationTest extends AbstractIntegrationTest {

    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private CartBuildingService cartBuildingService;

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
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_clear_shopping_cart",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products"));
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
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-1\"}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"egg-1","name":"Яйця курячі С1","unit":"шт","quantity":10,"price":79}],\
                "total":79,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-1","checkoutMobileLink":"silpo://checkout/cart-1"}""");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_clear_shopping_cart", "{\"ok\":true}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
    }

    private UUID connectedUser(long chatId) {
        User user = userAccountService.findOrCreate(chatId);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user.getId();
    }

    private static ShoppingListItem item(String name, String quantity, String unit) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .quantity(new BigDecimal(quantity))
                .unit(unit)
                .build();
    }

    @Test
    void searchesAgainUnderTheSuggestedNamesAndResolvesTheLine() {
        UUID userId = connectedUser(8501L);
        // First pass: nothing at all for the line's own name.
        // Second pass: the suggested term finds the eggs.
        MCP.respondToToolInOrder(
                "silpo_find_products_batch", "{\"queries\":[{\"query\":\"яйця курячі\",\"products\":[]}]}", """
                {"queries":[{"query":"Яйця","products":[{"name":"Яйця курячі С1","productId":"egg-1",\
                "companyId":"company-3","branchId":"branch-7","step":1,"displayRatio":"10шт","price":79}]}]}""");
        // First Claude call is the suggestion (the matcher is not asked when every line has zero candidates);
        // the second is the matcher over the recovered candidates.
        CLAUDE.respondWithTexts(
                "{\"suggestions\":[{\"lineIndex\":0,\"terms\":[\"Яйця\"]}]}",
                "{\"choices\":[{\"lineIndex\":0,\"candidateIndex\":0,\"reason\":\"звичайні яйця\"}]}");

        CartSummary summary = cartBuildingService.buildCart(userId, List.of(item("яйця курячі", "10", "шт")));

        List<JsonNode> searches = MCP.callArguments("silpo_find_products_batch");
        assertThat(searches).hasSize(2);
        assertThat(searches.get(1).path("products").get(0).asText()).isEqualTo("Яйця");
        JsonNode added = MCP.callArguments("silpo_add_or_update_cart_products").getFirst();
        assertThat(added.path("products").get(0).path("productId").asText()).isEqualTo("egg-1");
        assertThat(added.path("products").get(0).path("quantity").asInt()).isEqualTo(1);
        assertThat(summary.unresolved()).isEmpty();
    }

    @Test
    void aLineTheSecondPassCannotHelpStaysHonestlyUnresolved() {
        UUID userId = connectedUser(8502L);
        MCP.respondToTool("silpo_find_products_batch", "{\"queries\":[{\"query\":\"хамон\",\"products\":[]}]}");
        CLAUDE.respondWithText("{\"suggestions\":[{\"lineIndex\":0,\"terms\":[\"Джамон\"]}]}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[],"total":0,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-1","checkoutMobileLink":"silpo://checkout/cart-1"}""");

        CartSummary summary = cartBuildingService.buildCart(userId, List.of(item("хамон", "1", "шт")));

        assertThat(MCP.callArguments("silpo_find_products_batch")).hasSize(2);
        assertThat(MCP.callArguments("silpo_add_or_update_cart_products")).isEmpty();
        assertThat(summary.unresolved()).containsExactly("хамон");
    }
}
