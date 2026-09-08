package com.silporestockai.service;

import com.silporestockai.entity.GroupEvent;
import com.silporestockai.entity.GroupEventItem;
import com.silporestockai.entity.GroupEventParticipant;
import com.silporestockai.model.GroupEventStatus;
import com.silporestockai.model.GroupSignals;
import com.silporestockai.model.HistoricalLine;
import com.silporestockai.model.ParticipantSignals;
import com.silporestockai.repository.GroupEventItemRepository;
import com.silporestockai.repository.GroupEventParticipantRepository;
import com.silporestockai.repository.GroupEventRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The «Act» half of the group proposal (task 68): real facts from the four tables, gathered before any model is
 * asked, in the priority the product agreed on.
 *
 * <ol>
 *   <li>the reply given now — carried through verbatim;
 *   <li>what a closely matching set of these same people agreed on last time;
 *   <li>what each person drank in other rounds — their stored preference summaries, never a past raw sentence;
 *   <li>a seasonal average for rounds of similar size around the same date — only for a person who gave no
 *       preference and about whom nothing else is known.
 * </ol>
 *
 * <p>Every query here is one hop — an exact set comparison, a per-person aggregation, a date-window aggregation —
 * which is why this is plain repository work on Postgres and not a graph database (see the task page).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupSignalService {

    /** A past round counts as «this same company» when the counted sets overlap at least this much. */
    public static final double SAME_GROUP_THRESHOLD = 0.5;

    /** ±14 days around the round's date, in any year. */
    static final int SEASONAL_WINDOW_DAYS = 14;

    /** A round of «similar size» has between half and one-and-a-half times this round's headcount. */
    static final double SIZE_TOLERANCE = 0.5;

    static final int PERSONAL_HISTORY_LIMIT = 5;
    static final int SEASONAL_LINE_LIMIT = 8;

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final List<GroupEventStatus> AGREED = List.of(GroupEventStatus.APPROVED, GroupEventStatus.ORDERED);

    private final GroupEventRepository eventRepository;
    private final GroupEventParticipantRepository participantRepository;
    private final GroupEventItemRepository itemRepository;

    @Transactional(readOnly = true)
    public GroupSignals gather(GroupEvent event, List<GroupEventParticipant> counted) {
        List<GroupEvent> history = eventRepository.findByStatusIn(AGREED).stream()
                .filter(past -> !past.getId().equals(event.getId()))
                .sorted(Comparator.comparing(GroupEvent::getCreatedAt).reversed())
                .toList();
        Set<UUID> historyIds = history.stream().map(GroupEvent::getId).collect(Collectors.toSet());
        Map<UUID, Set<Long>> countedSets = new HashMap<>();
        for (GroupEventParticipant row : historyIds.isEmpty()
                ? List.<GroupEventParticipant>of()
                : participantRepository.findByGroupEventIdIn(historyIds)) {
            if (row.isCountedInDenominator()) {
                countedSets
                        .computeIfAbsent(row.getGroupEventId(), id -> new HashSet<>())
                        .add(row.getTelegramUserId());
            }
        }
        Map<UUID, List<GroupEventItem>> itemsByEvent = historyIds.isEmpty()
                ? Map.of()
                : itemRepository.findByGroupEventIdIn(historyIds).stream()
                        .collect(Collectors.groupingBy(GroupEventItem::getGroupEventId));

        Set<Long> now =
                counted.stream().map(GroupEventParticipant::getTelegramUserId).collect(Collectors.toSet());
        Optional<GroupEvent> sameGroup = sameGroupLastTime(history, countedSets, now);
        List<HistoricalLine> sameGroupLines = sameGroup
                .map(past -> lines(itemsByEvent.getOrDefault(past.getId(), List.of()), headcount(countedSets, past)))
                .orElse(List.of());

        List<ParticipantSignals> participants = new ArrayList<>();
        boolean anyoneNeedsSeasonal = false;
        for (GroupEventParticipant person : counted) {
            List<String> personal = personalHistory(person, event, history);
            boolean needsSeasonal = person.noPreference() && personal.isEmpty() && sameGroupLines.isEmpty();
            anyoneNeedsSeasonal |= needsSeasonal;
            participants.add(new ParticipantSignals(
                    person.getTelegramUserId(),
                    person.getDisplayName(),
                    person.getRawReplyText() == null ? "." : person.getRawReplyText(),
                    personal,
                    needsSeasonal));
        }

        List<HistoricalLine> seasonal =
                anyoneNeedsSeasonal ? seasonal(event, counted.size(), history, countedSets, itemsByEvent) : List.of();
        log.info(
                "gathered signals for group event {}: {} participants, same-group round {}, {} seasonal lines",
                event.getId(),
                participants.size(),
                sameGroup.map(GroupEvent::getId).orElse(null),
                seasonal.size());
        return new GroupSignals(
                participants,
                sameGroupLines,
                sameGroup.map(GroupEvent::getEventTag).orElse(null),
                seasonal);
    }

    /** |A ∩ B| / |A ∪ B|; zero when both are empty. */
    public static double jaccard(Set<Long> a, Set<Long> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<Long> union = new HashSet<>(a);
        union.addAll(b);
        Set<Long> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return (double) intersection.size() / union.size();
    }

    /** The best-overlapping earlier round, newest first among equals; empty below the threshold. */
    private static Optional<GroupEvent> sameGroupLastTime(
            List<GroupEvent> history, Map<UUID, Set<Long>> countedSets, Set<Long> now) {
        GroupEvent best = null;
        double bestScore = 0.0;
        for (GroupEvent past : history) {
            double score = jaccard(countedSets.getOrDefault(past.getId(), Set.of()), now);
            if (score >= SAME_GROUP_THRESHOLD && score > bestScore) {
                best = past;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Tier 3: this person's stored summaries from other agreed rounds, newest first, distinct, capped. */
    private List<String> personalHistory(GroupEventParticipant person, GroupEvent event, List<GroupEvent> history) {
        Map<UUID, Integer> order = new HashMap<>();
        for (int i = 0; i < history.size(); i++) {
            order.put(history.get(i).getId(), i);
        }
        return participantRepository
                .findByTelegramUserIdAndCountedInDenominatorTrueAndGroupEventIdNot(
                        person.getTelegramUserId(), event.getId())
                .stream()
                .filter(row -> order.containsKey(row.getGroupEventId()))
                .sorted(Comparator.comparing(row -> order.get(row.getGroupEventId())))
                .map(row -> row.getPreferenceSummary() != null
                                && !row.getPreferenceSummary().isBlank()
                        ? row.getPreferenceSummary().strip()
                        : row.noPreference() ? null : row.getRawReplyText().strip())
                .filter(summary -> summary != null)
                .distinct()
                .limit(PERSONAL_HISTORY_LIMIT)
                .toList();
    }

    /**
     * Tier 4: rounds of similar size within the window around this round's day of the year, any year, aggregated
     * per product as quantity per head.
     */
    private static List<HistoricalLine> seasonal(
            GroupEvent event,
            int headcount,
            List<GroupEvent> history,
            Map<UUID, Set<Long>> countedSets,
            Map<UUID, List<GroupEventItem>> itemsByEvent) {
        LocalDate date = event.effectiveDate(KYIV);
        Map<String, BigDecimal[]> totals = new LinkedHashMap<>(); // name -> {quantity, headcount, unitless marker}
        Map<String, String> units = new HashMap<>();
        for (GroupEvent past : history) {
            int pastHeadcount = headcount(countedSets, past);
            if (pastHeadcount == 0 || !similarSize(headcount, pastHeadcount)) {
                continue;
            }
            if (dayOfYearDistance(date, past.effectiveDate(KYIV)) > SEASONAL_WINDOW_DAYS) {
                continue;
            }
            for (GroupEventItem item : itemsByEvent.getOrDefault(past.getId(), List.of())) {
                BigDecimal[] sums = totals.computeIfAbsent(
                        item.getProductName(), name -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                sums[0] = sums[0].add(item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity());
                sums[1] = sums[1].add(BigDecimal.valueOf(pastHeadcount));
                units.putIfAbsent(item.getProductName(), item.getUnit());
            }
        }
        return totals.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal[]>comparingByValue(Comparator.comparing(sums -> sums[0]))
                        .reversed())
                .limit(SEASONAL_LINE_LIMIT)
                .map(entry -> new HistoricalLine(
                        entry.getKey(),
                        entry.getValue()[0],
                        units.get(entry.getKey()),
                        perHead(entry.getValue()[0], entry.getValue()[1])))
                .toList();
    }

    private static boolean similarSize(int now, int past) {
        return past >= Math.ceil(now * (1 - SIZE_TOLERANCE)) && past <= Math.floor(now * (1 + SIZE_TOLERANCE));
    }

    /** Distance in days between two days of the year, the short way round the calendar. */
    public static int dayOfYearDistance(LocalDate a, LocalDate b) {
        int diff = Math.abs(a.getDayOfYear() - b.getDayOfYear());
        return Math.min(diff, 365 - diff);
    }

    private static int headcount(Map<UUID, Set<Long>> countedSets, GroupEvent past) {
        return countedSets.getOrDefault(past.getId(), Set.of()).size();
    }

    private static List<HistoricalLine> lines(List<GroupEventItem> items, int headcount) {
        return items.stream()
                .map(item -> new HistoricalLine(
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnit(),
                        perHead(item.getQuantity(), BigDecimal.valueOf(Math.max(headcount, 1)))))
                .toList();
    }

    private static BigDecimal perHead(BigDecimal quantity, BigDecimal heads) {
        if (quantity == null || heads == null || heads.signum() == 0) {
            return null;
        }
        return quantity.divide(heads, 2, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
