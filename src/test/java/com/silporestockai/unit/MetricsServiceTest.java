package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.McpToolCall;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.PitchMetrics;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.McpToolCallRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.MetricsService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetricsServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-06T08:00:00Z");

    private final MetricsService service = new MetricsService(
            mock(UserRepository.class),
            mock(UserProfileRepository.class),
            mock(CustomerOrderRepository.class),
            mock(CheckinRepository.class),
            mock(McpToolCallRepository.class),
            Clock.fixed(T0, ZoneOffset.UTC));

    @Test
    void computesEveryNumberNextToItsSample() {
        User fast = user(T0, 3);
        User slow = user(T0, 1);
        User noOrder = user(T0, 0);
        List<UserProfile> profiles = List.of(profile(fast), profile(slow), profile(noOrder));
        List<CustomerOrder> orders = List.of(
                confirmed(fast, OrderType.INITIAL, T0.plus(Duration.ofMinutes(10)), 12, 1, null),
                // A later order must not move the "first order" mark.
                confirmed(fast, OrderType.SCHEDULED_REORDER, T0.plus(Duration.ofDays(7)), 5, 0, false),
                confirmed(fast, OrderType.SCHEDULED_REORDER, T0.plus(Duration.ofDays(14)), 5, 0, true),
                confirmed(slow, OrderType.INITIAL, T0.plus(Duration.ofHours(2)), 8, 2, null),
                draft(noOrder));
        List<Checkin> checkins = List.of(checkin(fast), checkin(fast));
        List<McpToolCall> calls = List.of(
                call("silpo_get_my_shopping_cart", false),
                call("silpo_find_products_batch", false),
                call("silpo_find_products_batch", true),
                call("silpo_add_or_update_cart_products", false));

        PitchMetrics metrics = MetricsService.compute(List.of(fast, slow, noOrder), profiles, orders, checkins, calls);

        assertThat(metrics.onboardedHouseholds()).isEqualTo(3);
        assertThat(metrics.householdsWithConfirmedOrder()).isEqualTo(2);
        assertThat(metrics.fastestOnboardingToFirstOrder()).isEqualTo(Duration.ofMinutes(10));
        // Even count: the median is the mean of the two middle values — 10 min and 2 h.
        assertThat(metrics.medianOnboardingToFirstOrder()).isEqualTo(Duration.ofMinutes(65));
        assertThat(metrics.checkinPromptsSent()).isEqualTo(4);
        assertThat(metrics.checkinResponses()).isEqualTo(2);
        assertThat(metrics.checkinResponseRate()).isEqualTo(0.5);
        assertThat(metrics.reorderConfirmations()).isEqualTo(2);
        assertThat(metrics.reorderConfirmationsUnedited()).isEqualTo(1);
        // The draft has no unresolved count and is not a measured cart build.
        assertThat(metrics.cartBuildsMeasured()).isEqualTo(4);
        assertThat(metrics.resolvedLines()).isEqualTo(30);
        assertThat(metrics.unresolvedLines()).isEqualTo(3);
        assertThat(metrics.resolveRate()).isCloseTo(30.0 / 33.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metrics.mcpCallsTotal()).isEqualTo(4);
        assertThat(metrics.mcpCallsFailed()).isEqualTo(1);
        assertThat(metrics.mcpDistinctTools())
                .containsExactly(
                        "silpo_add_or_update_cart_products", "silpo_find_products_batch", "silpo_get_my_shopping_cart");
    }

    @Test
    void anEmptyDatabaseProducesDashesNotDivisionsByZero() {
        PitchMetrics metrics = MetricsService.compute(List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(metrics.checkinResponseRate()).isNull();
        assertThat(metrics.uneditedReorderShare()).isNull();
        assertThat(metrics.resolveRate()).isNull();
        assertThat(metrics.medianOnboardingToFirstOrder()).isNull();

        String markdown = service.markdown(metrics);
        assertThat(markdown)
                .contains("| — | 0 з 0 онбордених")
                .contains("| 0 з 40 |")
                .doesNotContain("Інструменти:");
    }

    @Test
    void markdownShowsEveryRatioWithItsNumeratorAndDenominator() {
        PitchMetrics metrics = new PitchMetrics(
                5,
                3,
                Duration.ofMinutes(12).plusSeconds(30),
                Duration.ofMinutes(8),
                6,
                4,
                4,
                3,
                6,
                69,
                6,
                74,
                2,
                List.of("silpo_find_products_batch", "silpo_get_time_slots"));

        String markdown = service.markdown(metrics);

        assertThat(markdown)
                .contains("12 хв 30 с (найшвидше 8 хв 0 с) | 3 з 5 онбордених домогосподарств")
                .contains("| 67 % | 4 відповідей на 6 запитів |")
                .contains("| 75 % | 3 з 4 |")
                .contains("| 92 % | 69 знайдено / 6 не знайдено у 6 кошиках |")
                .contains("| 2 з 40 | 74 викликів, 2 з помилкою |")
                .contains("Інструменти: silpo_find_products_batch, silpo_get_time_slots");
    }

    @Test
    void humanDurationsPickTheTwoUnitsThatMatter() {
        assertThat(MetricsService.human(Duration.ofDays(2).plusHours(3))).isEqualTo("2 дн 3 год");
        assertThat(MetricsService.human(Duration.ofHours(1).plusMinutes(5))).isEqualTo("1 год 5 хв");
        assertThat(MetricsService.human(Duration.ofSeconds(45))).isEqualTo("0 хв 45 с");
    }

    private static User user(Instant createdAt, int promptsSent) {
        return User.builder()
                .id(UUID.randomUUID())
                .telegramChatId((long) (Math.random() * 1_000_000))
                .createdAt(createdAt)
                .checkinPromptsSent(promptsSent)
                .build();
    }

    private static UserProfile profile(User user) {
        return UserProfile.builder().id(UUID.randomUUID()).userId(user.getId()).build();
    }

    private static CustomerOrder confirmed(
            User user, OrderType type, Instant confirmedAt, int items, int unresolved, Boolean edited) {
        return CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(type)
                .items(Collections.nCopies(items, new BasketItem("p", "x", "шт", BigDecimal.ONE, BigDecimal.TEN)))
                .status(OrderStatus.CONFIRMED)
                .createdAt(confirmedAt.minusSeconds(60))
                .confirmedAt(confirmedAt)
                .unresolvedCount(unresolved)
                .editedBeforeConfirm(edited)
                .build();
    }

    private static CustomerOrder draft(User user) {
        return CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(OrderType.INITIAL)
                .status(OrderStatus.DRAFT)
                .createdAt(T0)
                .build();
    }

    private static Checkin checkin(User user) {
        return Checkin.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .rawInputText("молоко є")
                .receivedAt(T0.plusSeconds(1))
                .build();
    }

    private static McpToolCall call(String tool, boolean error) {
        return McpToolCall.builder()
                .id(UUID.randomUUID())
                .toolName(tool)
                .error(error)
                .calledAt(T0)
                .build();
    }
}
