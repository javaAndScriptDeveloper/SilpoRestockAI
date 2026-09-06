package com.silporestockai.service;

import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.McpToolCall;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.PitchMetrics;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.McpToolCallRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The pitch's validation numbers, read straight off the tables that already exist (task 37).
 *
 * <p>Deliberately a full-table read and a loop: the whole dataset is a hackathon's worth of test sessions, and a
 * pure static method over lists is what makes the arithmetic unit-testable without a database. A warehouse would
 * be a different product.
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    /** How many tools the official server exposes — the pitch's "N of 39" denominator. */
    public static final int SILPO_MCP_TOOL_COUNT = 39;

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final CheckinRepository checkinRepository;
    private final McpToolCallRepository mcpToolCallRepository;
    private final Clock clock;

    public PitchMetrics compute() {
        return compute(
                userRepository.findAll(),
                userProfileRepository.findAll(),
                customerOrderRepository.findAll(),
                checkinRepository.findAll(),
                mcpToolCallRepository.findAll());
    }

    public static PitchMetrics compute(
            List<User> users,
            List<UserProfile> profiles,
            List<CustomerOrder> orders,
            List<Checkin> checkins,
            List<McpToolCall> toolCalls) {
        Map<UUID, Instant> createdAt = new HashMap<>();
        int promptsSent = 0;
        for (User user : users) {
            createdAt.put(user.getId(), user.getCreatedAt());
            promptsSent += user.getCheckinPromptsSent();
        }

        Map<UUID, Instant> firstConfirmed = new HashMap<>();
        int reorders = 0;
        int reordersUnedited = 0;
        int cartBuilds = 0;
        int resolved = 0;
        int unresolved = 0;
        for (CustomerOrder order : orders) {
            if (order.getStatus() == OrderStatus.CONFIRMED && order.getConfirmedAt() != null) {
                firstConfirmed.merge(order.getUserId(), order.getConfirmedAt(), (a, b) -> a.isBefore(b) ? a : b);
                // The flag is set only by ReorderConfirmationService (scheduled reorder or an early-trigger AD_HOC
                // delta), so its presence is what says "this was a reorder proposal somebody could have edited".
                if (order.getEditedBeforeConfirm() != null) {
                    reorders++;
                    if (!order.getEditedBeforeConfirm()) {
                        reordersUnedited++;
                    }
                }
            }
            if (order.getUnresolvedCount() != null) {
                cartBuilds++;
                resolved += order.getItems() == null ? 0 : order.getItems().size();
                unresolved += order.getUnresolvedCount();
            }
        }

        List<Duration> toFirstOrder = new ArrayList<>();
        firstConfirmed.forEach((userId, confirmedAt) -> {
            Instant start = createdAt.get(userId);
            if (start != null && !confirmedAt.isBefore(start)) {
                toFirstOrder.add(Duration.between(start, confirmedAt));
            }
        });
        toFirstOrder.sort(Comparator.naturalOrder());

        List<String> tools = toolCalls.stream()
                .map(McpToolCall::getToolName)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        int failed = (int) toolCalls.stream().filter(McpToolCall::isError).count();

        return new PitchMetrics(
                profiles.size(),
                firstConfirmed.size(),
                median(toFirstOrder),
                toFirstOrder.isEmpty() ? null : toFirstOrder.getFirst(),
                promptsSent,
                checkins.size(),
                reorders,
                reordersUnedited,
                cartBuilds,
                resolved,
                unresolved,
                toolCalls.size(),
                failed,
                tools);
    }

    /** The report as it goes into the pitch notes: one table, Ukrainian labels, every ratio with its sample. */
    public String markdown(PitchMetrics metrics) {
        StringBuilder text = new StringBuilder();
        text.append("# Комора — цифри для пітчу (згенеровано ")
                .append(clock.instant())
                .append(")\n\n");
        text.append("| Метрика | Значення | Вибірка |\n|---|---|---|\n");
        text.append("| Час від онбордингу до першого підтвердженого замовлення (медіана) | ")
                .append(
                        metrics.medianOnboardingToFirstOrder() == null
                                ? "—"
                                : human(metrics.medianOnboardingToFirstOrder()) + " (найшвидше "
                                        + human(metrics.fastestOnboardingToFirstOrder()) + ")")
                .append(" | ")
                .append(metrics.householdsWithConfirmedOrder())
                .append(" з ")
                .append(metrics.onboardedHouseholds())
                .append(" онбордених домогосподарств |\n");
        text.append("| Відповіді на чек-іни | ")
                .append(percent(metrics.checkinResponseRate()))
                .append(" | ")
                .append(metrics.checkinResponses())
                .append(" відповідей на ")
                .append(metrics.checkinPromptsSent())
                .append(" запитів |\n");
        text.append("| Дозамовлення, підтверджені без правок | ")
                .append(percent(metrics.uneditedReorderShare()))
                .append(" | ")
                .append(metrics.reorderConfirmationsUnedited())
                .append(" з ")
                .append(metrics.reorderConfirmations())
                .append(" |\n");
        text.append("| Позиції списку, знайдені в каталозі при збірці кошика | ")
                .append(percent(metrics.resolveRate()))
                .append(" | ")
                .append(metrics.resolvedLines())
                .append(" знайдено / ")
                .append(metrics.unresolvedLines())
                .append(" не знайдено у ")
                .append(metrics.cartBuildsMeasured())
                .append(" кошиках |\n");
        text.append("| Різних MCP-інструментів задіяно | ")
                .append(metrics.mcpDistinctTools().size())
                .append(" з ")
                .append(SILPO_MCP_TOOL_COUNT)
                .append(" | ")
                .append(metrics.mcpCallsTotal())
                .append(" викликів, ")
                .append(metrics.mcpCallsFailed())
                .append(" з помилкою |\n");
        if (!metrics.mcpDistinctTools().isEmpty()) {
            text.append("\nІнструменти: ")
                    .append(String.join(", ", metrics.mcpDistinctTools()))
                    .append('\n');
        }
        return text.toString();
    }

    private static Duration median(List<Duration> sorted) {
        if (sorted.isEmpty()) {
            return null;
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).plus(sorted.get(middle)).dividedBy(2);
    }

    private static String percent(Double ratio) {
        return ratio == null ? "—" : String.format(Locale.ROOT, "%.0f %%", ratio * 100);
    }

    /** «2 дні 3 год», «12 хв 30 с» — whichever two units matter. */
    public static String human(Duration duration) {
        long seconds = duration.getSeconds();
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return days + " дн " + hours + " год";
        }
        if (hours > 0) {
            return hours + " год " + minutes + " хв";
        }
        return minutes + " хв " + secs + " с";
    }
}
