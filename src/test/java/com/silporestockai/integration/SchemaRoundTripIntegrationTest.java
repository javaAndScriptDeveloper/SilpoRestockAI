package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.InventoryTrend;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.TrustLevel;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CheckinDelta;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.model.TrustTier;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.InventoryTrendRepository;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.TrustLevelRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Round-trips every entity through a real PostgreSQL. {@code ddl-auto: validate} already catches a column that does
 * not exist; only writing and reading a row back catches a jsonb mapping that does not work.
 */
@DisplayName("every entity round-trips through PostgreSQL")
class SchemaRoundTripIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private MealPlanRepository mealPlanRepository;

    @Autowired
    private ShoppingListItemRepository shoppingListItemRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private CheckinRepository checkinRepository;

    @Autowired
    private InventoryTrendRepository inventoryTrendRepository;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private TrustLevelRepository trustLevelRepository;

    @BeforeEach
    void clean() {
        customerOrderRepository.deleteAll();
        trustLevelRepository.deleteAll();
        baselineBasketRepository.deleteAll();
        checkinRepository.deleteAll();
        inventoryTrendRepository.deleteAll();
        shoppingListItemRepository.deleteAll();
        mealPlanRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User persistedUser(long chatId) {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .telegramChatId(chatId)
                .silpoGuestId("guest-" + chatId)
                .createdAt(Instant.now())
                .build());
    }

    @Test
    void usersRoundTripAndAreFoundByTelegramChatId() {
        User saved = persistedUser(5001L);

        Optional<User> byChat = userRepository.findByTelegramChatId(5001L);
        Optional<User> byGuest = userRepository.findBySilpoGuestId("guest-5001");

        assertThat(byChat).isPresent();
        assertThat(byChat.get().getId()).isEqualTo(saved.getId());
        assertThat(byChat.get().getCreatedAt()).isNotNull();
        assertThat(byGuest).isPresent();
        assertThat(userRepository.findByTelegramChatId(9999L)).isEmpty();
    }

    @Test
    void userProfilesRoundTripIncludingTheirJsonColumns() {
        User user = persistedUser(5002L);

        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(4)
                .hasKids(true)
                .kidsAges(List.of(3, 7))
                .dietaryRestrictions(List.of("без горіхів"))
                .weeklyBudget(new BigDecimal("2500.00"))
                .dislikedFoods(List.of("броколі"))
                .onlyUaProducer(true)
                .specialMode(SpecialMode.MEDICAL_DIET_TABLE_5)
                .specialModeStartedAt(Instant.now())
                .build());

        UserProfile reloaded = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(reloaded.getHouseholdSize()).isEqualTo(4);
        assertThat(reloaded.getKidsAges()).containsExactly(3, 7);
        assertThat(reloaded.getDietaryRestrictions()).containsExactly("без горіхів");
        assertThat(reloaded.getDislikedFoods()).containsExactly("броколі");
        assertThat(reloaded.getWeeklyBudget()).isEqualByComparingTo("2500.00");
        assertThat(reloaded.getOnlyUaProducer()).isTrue();
        assertThat(reloaded.getSpecialMode()).isEqualTo(SpecialMode.MEDICAL_DIET_TABLE_5);
    }

    @Test
    void mealPlansRoundTripAndTheLatestOneIsFoundFirst() {
        User user = persistedUser(5003L);
        mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(LocalDate.of(2026, 8, 17))
                .plan(Map.of("monday", "борщ"))
                .createdAt(Instant.now())
                .build());
        mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(LocalDate.of(2026, 8, 24))
                .plan(Map.of("monday", "плов"))
                .createdAt(Instant.now())
                .build());

        MealPlan latest = mealPlanRepository
                .findFirstByUserIdOrderByWeekStartDateDesc(user.getId())
                .orElseThrow();

        assertThat(latest.getWeekStartDate()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(latest.getPlan()).containsEntry("monday", "плов");
        assertThat(mealPlanRepository.findFirstByUserIdAndWeekStartDateOrderByCreatedAtDesc(
                        user.getId(), LocalDate.of(2026, 8, 17)))
                .isPresent();
    }

    @Test
    void shoppingListItemsAttachToAPlanAndCanExistWithoutOne() {
        User user = persistedUser(5004L);
        MealPlan plan = mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(LocalDate.of(2026, 8, 31))
                .plan(Map.of())
                .createdAt(Instant.now())
                .build());

        shoppingListItemRepository.save(ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .mealPlanId(plan.getId())
                .name("молоко")
                .quantity(new BigDecimal("2.000"))
                .unit("шт")
                .category("молочні")
                .build());
        // An ad-hoc list belongs to no weekly plan, which is why meal_plan_id is nullable.
        shoppingListItemRepository.save(ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name("попкорн")
                .quantity(new BigDecimal("1.000"))
                .unit("шт")
                .category("снеки")
                .build());

        assertThat(shoppingListItemRepository.findByMealPlanId(plan.getId())).hasSize(1);
        assertThat(shoppingListItemRepository.count()).isEqualTo(2);
    }

    @Test
    void baselineBasketsRoundTripTheirTypedItemList() {
        User user = persistedUser(5005L);

        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(
                        new BasketItem("silpo-1", "молоко 2.5%", "шт", new BigDecimal("2"), new BigDecimal("41.90"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());

        BaselineBasket current = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow();

        assertThat(current.getItems()).hasSize(1);
        assertThat(current.getItems().getFirst().name()).isEqualTo("молоко 2.5%");
        assertThat(current.getItems().getFirst().price()).isEqualByComparingTo("41.90");
    }

    @Test
    void theTwoMostRecentCheckinsComeBackNewestFirst() {
        User user = persistedUser(5006L);
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            checkinRepository.save(Checkin.builder()
                    .id(UUID.randomUUID())
                    .userId(user.getId())
                    .rawInputText("чек-ін " + i)
                    .parsedDelta(new CheckinDelta(List.of("молоко"), List.of(), List.of("хліб")))
                    .receivedAt(now.plusSeconds(i))
                    .build());
        }

        List<Checkin> lastTwo = checkinRepository.findTop2ByUserIdOrderByReceivedAtDesc(user.getId());

        assertThat(lastTwo).hasSize(2);
        assertThat(lastTwo.get(0).getRawInputText()).isEqualTo("чек-ін 2");
        assertThat(lastTwo.get(1).getRawInputText()).isEqualTo("чек-ін 1");
        assertThat(lastTwo.get(0).getParsedDelta().goneCompletely()).containsExactly("хліб");
        assertThat(checkinRepository
                        .findFirstByUserIdOrderByReceivedAtDesc(user.getId())
                        .orElseThrow()
                        .getRawInputText())
                .isEqualTo("чек-ін 2");
    }

    @Test
    void removalCandidatesAreThoseAtOrAboveTheThreshold() {
        User user = persistedUser(5007L);
        saveTrend(user.getId(), "гречка", 3);
        saveTrend(user.getId(), "молоко", 0);
        saveTrend(user.getId(), "квасоля", 5);

        List<InventoryTrend> candidates =
                inventoryTrendRepository.findByUserIdAndConsecutiveUntouchedCyclesGreaterThanEqual(user.getId(), 3);

        assertThat(candidates).extracting(InventoryTrend::getItemName).containsExactlyInAnyOrder("гречка", "квасоля");
        assertThat(inventoryTrendRepository.findByUserIdAndItemName(user.getId(), "молоко"))
                .isPresent();
        assertThat(inventoryTrendRepository.findByUserId(user.getId())).hasSize(3);
    }

    private void saveTrend(UUID userId, String itemName, int cycles) {
        inventoryTrendRepository.save(InventoryTrend.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .itemName(itemName)
                .consecutiveUntouchedCycles(cycles)
                .lastUpdated(Instant.now())
                .build());
    }

    @Test
    void ordersRoundTripAndAreFilteredByStatusAndCartId() {
        User user = persistedUser(5008L);
        customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(OrderType.INITIAL)
                .items(List.of(new BasketItem("silpo-9", "хліб", "шт", new BigDecimal("1"), new BigDecimal("24.50"))))
                .deliverySlot("2026-08-30 18:00-20:00")
                .status(OrderStatus.CONFIRMED)
                .silpoCartId("cart-abc")
                .createdAt(Instant.now())
                .confirmedAt(Instant.now())
                .build());
        customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(OrderType.AD_HOC)
                .items(List.of())
                .status(OrderStatus.DRAFT)
                .createdAt(Instant.now())
                .build());

        assertThat(customerOrderRepository.findByUserIdOrderByCreatedAtDesc(user.getId()))
                .hasSize(2);
        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CONFIRMED))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.getType()).isEqualTo(OrderType.INITIAL);
                    assertThat(order.getItems().getFirst().name()).isEqualTo("хліб");
                    assertThat(order.getDeliverySlot()).isEqualTo("2026-08-30 18:00-20:00");
                });
        assertThat(customerOrderRepository.findBySilpoCartId("cart-abc")).isPresent();
    }

    @Test
    void trustLevelsRoundTrip() {
        User user = persistedUser(5009L);
        trustLevelRepository.save(TrustLevel.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .consecutiveUneditedConfirmations(2)
                .currentTrustTier(TrustTier.FAST_CONFIRM)
                .build());

        TrustLevel reloaded = trustLevelRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(reloaded.getConsecutiveUneditedConfirmations()).isEqualTo(2);
        assertThat(reloaded.getCurrentTrustTier()).isEqualTo(TrustTier.FAST_CONFIRM);
    }
}
