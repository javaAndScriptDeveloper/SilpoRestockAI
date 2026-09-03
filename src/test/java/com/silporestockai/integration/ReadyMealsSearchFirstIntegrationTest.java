package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.ShoppingListSourceType;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CartBuildingService;
import com.silporestockai.service.MealPlanService;
import com.silporestockai.service.ShoppingListService;
import com.silporestockai.service.UserAccountService;
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

/**
 * Repeats, as closely as an automated test can, the manual test that surfaced task 22's bug: a
 * READY_MEALS_ONLY household generates a plan and tries to build a cart from it. Production evidence was 16
 * of 16 items unresolved; this asserts 0.
 */
@DisplayName("READY_MEALS_ONLY: generate a plan, then actually build the cart from it")
class ReadyMealsSearchFirstIntegrationTest extends AbstractIntegrationTest {

    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MealPlanService mealPlanService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private CartBuildingService cartBuildingService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
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
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    private UUID readyMealsUser(long chatId) {
        User user = userAccountService.findOrCreate(chatId);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .cookingTimePreference(CookingTimePreference.READY_MEALS_ONLY)
                .build());
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user.getId();
    }

    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-1\"}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3",\
                "deliveryType":"delivery","items":[]}""");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        // 16 candidates across a few search terms — the same order of magnitude as the production bug report.
        MCP.respondToTool("silpo_find_products_batch", """
                {"queries":[{"query":"готові страви","products":[
                {"name":"Сир кисломолочний з ягодами, порція","productId":"p-1"},
                {"name":"Салат «Грецький» готовий","productId":"p-2"},
                {"name":"Гречка з яловичиною готова страва","productId":"p-3"},
                {"name":"Йогурт натуральний грецький","productId":"p-4"},
                {"name":"Плов з куркою готовий","productId":"p-5"},
                {"name":"Борщ готовий, порція","productId":"p-6"},
                {"name":"Салат Цезар готовий","productId":"p-7"},
                {"name":"Суп-пюре гарбузовий готовий","productId":"p-8"},
                {"name":"Котлета по-київськи готова","productId":"p-9"},
                {"name":"Рагу овочеве готове","productId":"p-10"},
                {"name":"Сендвіч з куркою готовий","productId":"p-11"},
                {"name":"Круасан з шинкою готовий","productId":"p-12"},
                {"name":"Консерви тунець готові до вживання","productId":"p-13"},
                {"name":"Запіканка сирна готова, порція","productId":"p-14"},
                {"name":"Плов вегетаріанський готовий","productId":"p-15"},
                {"name":"Салат з тунцем готовий","productId":"p-16"}]}]}""".replace("\n", ""));
    }

    private static String curatedWeekJson() {
        // The exact ready-meal names from the stubbed catalog above, echoed back verbatim — what a correctly
        // curating Claude does, and what the new ready-meals system prompt asks for.
        String[] names = {
            "Сир кисломолочний з ягодами, порція",
            "Салат «Грецький» готовий",
            "Гречка з яловичиною готова страва",
            "Йогурт натуральний грецький",
            "Плов з куркою готовий",
            "Борщ готовий, порція",
            "Салат Цезар готовий"
        };
        StringBuilder days = new StringBuilder();
        String[] dayNames = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"};
        for (int d = 0; d < 7; d++) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            String breakfast = names[d % names.length];
            String lunch = names[(d + 1) % names.length];
            String dinner = names[(d + 2) % names.length];
            days.append("""
                    {"day":"%s","meals":[\
                    {"type":"BREAKFAST","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція","category":"Готові страви"}]},\
                    {"type":"LUNCH","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція","category":"Готові страви"}]},\
                    {"type":"DINNER","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція","category":"Готові страви"}]}]}""".formatted(dayNames[d], breakfast, breakfast, lunch, lunch, dinner, dinner));
        }
        return "{\"days\":[" + days + "]}";
    }

    @Test
    void everyGeneratedItemResolvesAndTheCartActuallyBuilds() {
        UUID userId = readyMealsUser(8420L);
        scriptSilpo();
        CLAUDE.respondWithText(curatedWeekJson());

        MealPlan plan = mealPlanService.generateWeeklyPlan(userId);
        List<ShoppingListItem> items = shoppingListService.deriveFromMealPlan(plan.getId(), plan.getSourceType());

        assertThat(plan.getSourceType()).isEqualTo(ShoppingListSourceType.READY_MEAL_DIRECT);
        assertThat(items).isNotEmpty();
        assertThat(items)
                .allSatisfy(item -> assertThat(item.getSilpoProductId()).isNotBlank());

        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[],"total":0,"validations":[]}""");

        CartSummary summary = cartBuildingService.buildCart(userId, items);

        assertThat(summary.unresolved()).isEmpty();
        JsonNode added = MCP.callArguments("silpo_add_or_update_cart_products").getFirst();
        assertThat(added.path("products")).hasSize(items.size());
        // Only Step A's own search, during generation — none during cart-building for these pre-resolved lines.
        assertThat(MCP.calledTools().stream()
                        .filter("silpo_find_products_batch"::equals)
                        .count())
                .isEqualTo(1);
    }
}
