package com.silporestockai.service;

import com.silporestockai.config.ObservabilityProperties;
import com.silporestockai.model.FirstOrderDelay;
import com.silporestockai.model.ObservabilitySnapshot;
import com.silporestockai.model.OnboardingCompletedEvent;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderTotals;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.PromotionEventCount;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.utils.MeterNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * The app's Prometheus instrumentation (task 54): the database-derived gauges, and the façade the flows call to record
 * what happened.
 *
 * <p><b>Why gauges and not counters for the cumulative numbers.</b> A Micrometer {@code Counter} is monotonic within
 * one JVM, and this app restarts constantly. "Households registered" and "GMV" are facts about the database, not about
 * this process — after a restart they must still read what the tables say, not zero. So every absolute level is a
 * gauge over an {@link ObservabilitySnapshot} rebuilt on a schedule by {@code ObservabilityRefreshScheduler}, and a
 * Prometheus scrape reads memory instead of running queries. Rates and latencies, which genuinely describe events in
 * this process, stay counters and timers at their call sites — Prometheus handles their restart itself.
 *
 * <p>The gauges are registered once here rather than in a {@code config} {@code MeterBinder}, because a binder would
 * have to reach a service from outside the {@code Controller}/{@code Job} layers ArchUnit allows to do that.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservabilityService {

    private final MeterRegistry meterRegistry;
    private final ObservabilityProperties properties;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final ConversationStateRepository conversationStateRepository;
    private final PartnerPromotionEventRepository partnerPromotionEventRepository;
    private final Clock clock;

    private final AtomicReference<ObservabilitySnapshot> snapshot =
            new AtomicReference<>(ObservabilitySnapshot.empty());

    /** Registered lazily because its tag values — partner and product names — only exist once there are placements. */
    private MultiGauge promotionGauge;

    /**
     * Registers every gauge against the snapshot holder. No database access here on purpose: bean ordering relative to
     * Liquibase is not guaranteed, and a gauge that reads a live reference does not need the data to exist yet.
     */
    @PostConstruct
    void registerGauges() {
        gauge(MeterNames.USERS_REGISTERED, "Households that ever started the bot", s -> s.usersRegistered());
        gauge(MeterNames.USERS_ONBOARDED, "Households with a finished profile", s -> s.usersOnboarded());
        gauge(MeterNames.USERS_ORDERED, "Households with at least one confirmed order", s -> s.usersOrdered());
        gauge(MeterNames.ORDERS_ITEMS, "Basket lines across every confirmed order", s -> s.orderItemLines());

        Gauge.builder(MeterNames.USERS_ACTIVE, snapshot, holder -> holder.get().usersActive())
                .description("Households with a conversation turn inside the window")
                .tag(MeterNames.TAG_WINDOW, humanWindow(properties.activeWindow()))
                .register(meterRegistry);

        for (OrderType type : OrderType.values()) {
            String tag = type.name();
            typed(MeterNames.ORDERS_CONFIRMED, "Confirmed orders", tag, null, OrderTotals::count);
            typed(MeterNames.ORDERS_GMV, "Confirmed order value, delivery included", tag, "uah", t -> money(t.total()));
            typed(MeterNames.ORDERS_GOODS, "Confirmed merchandise value", tag, "uah", t -> money(t.goodsTotal()));
            typed(MeterNames.ORDERS_SAVINGS, "Discount taken off confirmed carts", tag, "uah", t -> money(t.savings()));
            typed(
                    MeterNames.ORDERS_VALUE_MISSING,
                    "Confirmed orders with no stored total",
                    tag,
                    null,
                    t -> t.valueMissing());
        }

        lines(MeterNames.ORDERS_LINES, "resolved", s -> s.resolvedLines());
        lines(MeterNames.ORDERS_LINES, "unresolved", s -> s.unresolvedLines());

        firstOrder("median", s -> s.medianToFirstOrder());
        firstOrder("fastest", s -> s.fastestToFirstOrder());

        promotionGauge = MultiGauge.builder(MeterNames.PROMOTION_EVENTS)
                .description("Partner placement funnel, by event")
                .register(meterRegistry);
        log.debug("registered the observability gauges");
    }

    /**
     * Rebuilds the snapshot behind every gauge. Called on a schedule, and directly by tests — never from a scrape, so
     * that scraping stays free no matter how often a collector does it.
     */
    public void refresh() {
        List<FirstOrderDelay> delays = customerOrderRepository.firstOrderDelays();
        List<Duration> elapsed = new ArrayList<>();
        for (FirstOrderDelay delay : delays) {
            Duration one = MetricsService.onboardingToFirstOrder(delay.createdAt(), delay.firstConfirmedAt());
            if (one != null) {
                elapsed.add(one);
            }
        }
        elapsed.sort(Comparator.naturalOrder());

        List<PromotionEventCount> promotions = partnerPromotionEventRepository.funnelCounts();
        snapshot.set(new ObservabilitySnapshot(
                userRepository.count(),
                userProfileRepository.count(),
                conversationStateRepository.countByUpdatedAtAfter(
                        clock.instant().minus(properties.activeWindow())),
                customerOrderRepository.countDistinctUserIdByStatus(OrderStatus.CONFIRMED),
                customerOrderRepository.confirmedTotalsByType(),
                customerOrderRepository.confirmedItemLines(),
                customerOrderRepository.resolvedCartLines(),
                customerOrderRepository.unresolvedCartLines(),
                MetricsService.median(elapsed),
                elapsed.isEmpty() ? null : elapsed.getFirst(),
                promotions));

        // Tag values here are data, not code, so this set has to be re-registered rather than declared once.
        promotionGauge.register(
                promotions.stream()
                        .map(p -> MultiGauge.Row.of(
                                Tags.of(
                                        MeterNames.TAG_PARTNER, p.partner(),
                                        MeterNames.TAG_PRODUCT, p.product(),
                                        MeterNames.TAG_EVENT, p.eventType().name()),
                                p.count()))
                        .toList(),
                true);
    }

    // --- What the flows call ---

    /** A brand-new household said «/start» — the top of the funnel. */
    public void recordOnboardingStarted() {
        meterRegistry.counter(MeterNames.ONBOARDING_STARTED).increment();
    }

    /** A profile was saved. An event listener rather than a call so the onboarding flow stays unaware of metrics. */
    @EventListener
    public void onOnboardingCompleted(OnboardingCompletedEvent event) {
        meterRegistry.counter(MeterNames.ONBOARDING_COMPLETED).increment();
    }

    /**
     * An order was confirmed. The counter drives the "orders per hour" panel; the two summaries give windowed average
     * cart value and basket size that the all-time gauges cannot — they are the same numbers over a moving window.
     */
    public void recordConfirmedOrder(OrderType type, BigDecimal total, int itemCount) {
        String tag = type == null ? "UNKNOWN" : type.name();
        meterRegistry
                .counter(MeterNames.ORDERS_CONFIRMATIONS, MeterNames.TAG_TYPE, tag)
                .increment();
        if (total != null) {
            DistributionSummary.builder(MeterNames.CART_VALUE)
                    .description("Confirmed cart value")
                    .baseUnit("uah")
                    .tag(MeterNames.TAG_TYPE, tag)
                    .register(meterRegistry)
                    .record(total.doubleValue());
        }
        DistributionSummary.builder(MeterNames.CART_SIZE)
                .description("Lines in a confirmed cart")
                .tag(MeterNames.TAG_TYPE, tag)
                .register(meterRegistry)
                .record(itemCount);
    }

    /**
     * Whether a free-text message reached a flow. Deliberately without an intent tag — which intents fire is task 55's
     * artefact; this one only answers "does the classifier work", which is a reliability question.
     */
    public void recordIntent(String outcome) {
        meterRegistry
                .counter(MeterNames.INTENT_CLASSIFIED, MeterNames.TAG_OUTCOME, outcome)
                .increment();
    }

    /** How a cart build ended, and how long it took. */
    public void recordCartBuild(String outcome, Duration took) {
        meterRegistry
                .timer(MeterNames.CART_BUILD, MeterNames.TAG_OUTCOME, outcome)
                .record(took);
    }

    /** How many list lines Silpo matched a product for, and how many it did not. */
    public void recordCartLines(int resolved, int unresolved) {
        counter(MeterNames.CART_LINES, MeterNames.TAG_RESULT, "resolved").increment(resolved);
        counter(MeterNames.CART_LINES, MeterNames.TAG_RESULT, "unresolved").increment(unresolved);
    }

    /** Whether the built cart cleared Silpo's minimum order on its own. */
    public void recordMinimumOrder(boolean belowMinimum) {
        counter(MeterNames.CART_MINIMUM, MeterNames.TAG_RESULT, belowMinimum ? "below" : "ok")
                .increment();
    }

    /** How a top-up from the household's baseline ended. */
    public void recordTopUp(String result) {
        counter(MeterNames.CART_TOPUP, MeterNames.TAG_RESULT, result).increment();
    }

    /**
     * A message about something going wrong reached a person. Should trend near zero; a spike is a real signal, which
     * is the whole reason it is a metric and not just a log line.
     */
    public void recordFailureMessage(String source, String kind) {
        meterRegistry
                .counter(MeterNames.FAILURE_MESSAGE, MeterNames.TAG_SOURCE, source, MeterNames.TAG_KIND, kind)
                .increment();
    }

    // --- Registration helpers ---

    private void gauge(String name, String description, ToDoubleFunction<ObservabilitySnapshot> read) {
        Gauge.builder(name, snapshot, holder -> read.applyAsDouble(holder.get()))
                .description(description)
                .register(meterRegistry);
    }

    private void typed(String name, String description, String type, String unit, ToDoubleFunction<OrderTotals> read) {
        Gauge.builder(
                        name,
                        snapshot,
                        holder -> holder.get().orders().stream()
                                .filter(t -> t.type() != null && t.type().name().equals(type))
                                .mapToDouble(read)
                                .sum())
                .description(description)
                .baseUnit(unit)
                .tag(MeterNames.TAG_TYPE, type)
                .register(meterRegistry);
    }

    private void lines(String name, String result, ToDoubleFunction<ObservabilitySnapshot> read) {
        Gauge.builder(name, snapshot, holder -> read.applyAsDouble(holder.get()))
                .description("Cart lines Silpo did or did not match a product for")
                .tag(MeterNames.TAG_RESULT, result)
                .register(meterRegistry);
    }

    private void firstOrder(String stat, java.util.function.Function<ObservabilitySnapshot, Duration> read) {
        Gauge.builder(MeterNames.ONBOARDING_FIRST_ORDER, snapshot, holder -> {
                    Duration value = read.apply(holder.get());
                    // NaN, not 0: nobody has ordered yet is not "it took no time", and Prometheus renders a gap
                    // rather than a line at the floor.
                    return value == null ? Double.NaN : value.toSeconds();
                })
                .description("Time from a household's first message to its first confirmed order")
                .baseUnit("seconds")
                .tag(MeterNames.TAG_STAT, stat)
                .register(meterRegistry);
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return meterRegistry.counter(name, tagKey, tagValue);
    }

    /** A gauge takes a double; a missing sum is genuinely zero here because the query coalesces it. */
    private static double money(BigDecimal amount) {
        return amount == null ? 0d : amount.doubleValue();
    }

    /** «7d», «24h» — a tag value a person reads on a panel, not {@code PT168H}. */
    private static String humanWindow(Duration window) {
        return window.toHours() % 24 == 0 ? window.toDays() + "d" : window.toHours() + "h";
    }
}
