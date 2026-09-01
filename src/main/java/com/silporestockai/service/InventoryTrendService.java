package com.silporestockai.service;

import com.silporestockai.config.CheckinProperties;
import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.InventoryTrend;
import com.silporestockai.model.CheckinDelta;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.InventoryTrendRepository;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which items this household actually eats, and which ones just sit there.
 *
 * <p>Approximate on purpose. Nobody weighs their buckwheat, so this counts how many check-ins in a row an item was
 * reported as still present and treats a long streak as "stop suggesting this" — a trend, not a stock level.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryTrendService {

    private final InventoryTrendRepository inventoryTrendRepository;
    private final CheckinRepository checkinRepository;
    private final CheckinProperties checkinProperties;
    private final Clock clock;

    /**
     * Moves every counter the check-in has something to say about.
     *
     * <p>Being consumed — running low or gone — resets the streak, which is also what makes a restock reset it: a
     * restocked item was reported gone first, and nothing here needs to know an order happened in between.
     *
     * <p>An item the message never mentioned keeps whatever it had. A check-in names two or three things, and reading
     * silence as evidence would turn the counter into a measure of how talkative someone is.
     */
    @Transactional
    public void recordCheckin(UUID userId, CheckinDelta delta) {
        if (delta == null) {
            return;
        }
        orEmpty(delta.stillHave()).forEach(item -> bump(userId, item));
        orEmpty(delta.runningLow()).forEach(item -> reset(userId, item));
        orEmpty(delta.goneCompletely()).forEach(item -> reset(userId, item));
    }

    /** Items the household has left alone long enough that the agent should stop putting them in the plan. */
    @Transactional(readOnly = true)
    public List<String> getRemovalCandidates(UUID userId) {
        return inventoryTrendRepository
                .findByUserIdAndConsecutiveUntouchedCyclesGreaterThanEqual(userId, checkinProperties.removalThreshold())
                .stream()
                .map(InventoryTrend::getItemName)
                .toList();
    }

    /**
     * What the next order has to cover, most urgent first.
     *
     * <p>Reads the newest check-in that actually parsed. An unparsed one is skipped rather than answered with an empty
     * list: task 12 has already asked that user to clarify, and "nothing to buy" is the wrong thing to infer from "I
     * did not understand you".
     */
    @Transactional(readOnly = true)
    public List<String> getUpcomingNeeds(UUID userId) {
        return checkinRepository.findByUserIdOrderByReceivedAtDesc(userId).stream()
                .map(Checkin::getParsedDelta)
                .filter(Objects::nonNull)
                .findFirst()
                .map(delta -> {
                    // A LinkedHashSet keeps "gone before running low" while dropping anything said twice.
                    LinkedHashSet<String> needs = new LinkedHashSet<>(orEmpty(delta.goneCompletely()));
                    needs.addAll(orEmpty(delta.runningLow()));
                    return List.copyOf(needs);
                })
                .orElseGet(List::of);
    }

    private void bump(UUID userId, String itemName) {
        InventoryTrend trend = trendOf(userId, itemName);
        trend.setConsecutiveUntouchedCycles(trend.getConsecutiveUntouchedCycles() + 1);
        save(trend);
        if (trend.getConsecutiveUntouchedCycles() >= checkinProperties.removalThreshold()) {
            log.info(
                    "'{}' has been untouched for {} check-ins for user {}",
                    itemName,
                    trend.getConsecutiveUntouchedCycles(),
                    userId);
        }
    }

    private void reset(UUID userId, String itemName) {
        InventoryTrend trend = trendOf(userId, itemName);
        trend.setConsecutiveUntouchedCycles(0);
        save(trend);
    }

    /** Existing row or a fresh one — never a second row for the same item, which the unique constraint forbids. */
    private InventoryTrend trendOf(UUID userId, String itemName) {
        return inventoryTrendRepository
                .findByUserIdAndItemName(userId, itemName)
                .orElseGet(() -> InventoryTrend.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .itemName(itemName)
                        .consecutiveUntouchedCycles(0)
                        .build());
    }

    private void save(InventoryTrend trend) {
        trend.setLastUpdated(clock.instant());
        inventoryTrendRepository.save(trend);
    }

    /** A bucket the model left out arrives as null; it reads the same as an empty one here. */
    private static List<String> orEmpty(List<String> items) {
        return items == null ? List.of() : items;
    }
}
