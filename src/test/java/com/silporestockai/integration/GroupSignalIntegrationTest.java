package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.silporestockai.service.GroupSignalService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Task 68's signal tiers against fixture rounds: the same company's last round, a person's other rounds, and a
 * seasonal average — each lands in its own slot and nowhere else.
 */
@DisplayName("group signals are gathered in priority order from real rounds")
class GroupSignalIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private GroupEventRepository events;

    @Autowired
    private GroupEventParticipantRepository participants;

    @Autowired
    private GroupEventItemRepository items;

    @Autowired
    private GroupSignalService signals;

    private GroupEvent current;

    @BeforeEach
    void fixtures() {
        events.deleteAll();
        // A: the same chat, three of this round's four people, ordered last New Year.
        GroupEvent a = agreed(-100L, LocalDate.of(2025, 12, 30), GroupEventStatus.ORDERED, "новий рік", 41L, 42L, 43L);
        items.save(item(a, "Пиво «Львівське» світле 0.5", "10", "шт"));
        items.save(item(a, "Вино червоне сухе Los Cardos", "2", "шт"));
        // B: a different company with 41 in it, where 41 was summarised as a whisky drinker.
        GroupEvent b = agreed(-200L, LocalDate.of(2026, 3, 8), GroupEventStatus.APPROVED, null, 41L, 55L);
        participants.findByGroupEventIdAndTelegramUserId(b.getId(), 41L).ifPresent(row -> {
            row.setPreferenceSummary("віскі");
            participants.save(row);
        });
        // C: strangers, similar size, around the same date — seasonal only.
        GroupEvent c = agreed(-300L, LocalDate.of(2025, 12, 28), GroupEventStatus.ORDERED, null, 61L, 62L, 63L);
        items.save(item(c, "Шампанське Artwinery", "3", "шт"));
        // D: strangers, far from the date — must not count as seasonal.
        GroupEvent d = agreed(-400L, LocalDate.of(2025, 7, 1), GroupEventStatus.ORDERED, null, 71L, 72L, 73L);
        items.save(item(d, "Сидр", "6", "шт"));

        current = events.save(GroupEvent.builder()
                .id(UUID.randomUUID())
                .telegramGroupChatId(-100L)
                .organizerTelegramUserId(41L)
                .eventDate(LocalDate.of(2026, 12, 31))
                .status(GroupEventStatus.PROPOSED)
                .createdAt(Instant.now())
                .build());
        participants.save(counted(current, 41L, "вино"));
        participants.save(counted(current, 42L, "."));
        participants.save(counted(current, 43L, "."));
        participants.save(counted(current, 44L, "."));
    }

    @Test
    @DisplayName("each tier lands in its slot: same group, personal history, seasonal for the unknown person only")
    void tiersAreSeparated() {
        GroupSignals gathered = signals.gather(current, participants.findByGroupEventId(current.getId()));

        assertThat(gathered.sameGroupLastTime())
                .extracting(HistoricalLine::productName)
                .containsExactlyInAnyOrder("Пиво «Львівське» світле 0.5", "Вино червоне сухе Los Cardos");
        assertThat(gathered.sameGroupLastTimeTag()).isEqualTo("новий рік");
        HistoricalLine beer = gathered.sameGroupLastTime().stream()
                .filter(line -> line.productName().startsWith("Пиво"))
                .findFirst()
                .orElseThrow();
        assertThat(beer.perHead()).isEqualByComparingTo("3.33");

        Map<Long, ParticipantSignals> byId = gathered.participants().stream()
                .collect(java.util.stream.Collectors.toMap(ParticipantSignals::telegramUserId, p -> p));
        assertThat(byId.get(41L).rawReply()).isEqualTo("вино");
        assertThat(byId.get(41L).personalHistory()).containsExactly("віскі", "пиво");
        assertThat(byId.get(41L).needsSeasonal()).isFalse();
        // 42 was in round A with no stored summary, so tier 3 falls back to that round's reply text.
        assertThat(byId.get(42L).personalHistory()).containsExactly("пиво");
        assertThat(byId.get(42L).needsSeasonal()).isFalse(); // the same-group round covers them
        assertThat(byId.get(44L).personalHistory()).isEmpty();
        assertThat(byId.get(44L).needsSeasonal()).isFalse(); // likewise: tier 2 exists for this round
        // Nothing needed the seasonal tier, so it is not computed at all.
        assertThat(gathered.seasonal()).isEmpty();
    }

    @Test
    @DisplayName("with no same-group round, a «.» with no history gets the seasonal average and nobody else does")
    void seasonalOnlyForTheUnknown() {
        // Take the same-group round away by making its participants strangers.
        events.findByStatusIn(List.of(GroupEventStatus.ORDERED)).stream()
                .filter(e -> e.getTelegramGroupChatId() == -100L)
                .forEach(e -> participants.findByGroupEventId(e.getId()).forEach(row -> {
                    row.setTelegramUserId(row.getTelegramUserId() + 1000);
                    participants.save(row);
                }));

        GroupSignals gathered = signals.gather(current, participants.findByGroupEventId(current.getId()));

        assertThat(gathered.sameGroupLastTime()).isEmpty();
        Map<Long, ParticipantSignals> byId = gathered.participants().stream()
                .collect(java.util.stream.Collectors.toMap(ParticipantSignals::telegramUserId, p -> p));
        assertThat(byId.get(41L).needsSeasonal()).isFalse(); // has a reply and history
        assertThat(byId.get(44L).needsSeasonal()).isTrue();
        assertThat(gathered.seasonal())
                .extracting(HistoricalLine::productName)
                .contains("Шампанське Artwinery", "Пиво «Львівське» світле 0.5")
                .doesNotContain("Сидр");
        HistoricalLine champagne = gathered.seasonal().stream()
                .filter(line -> line.productName().startsWith("Шампанське"))
                .findFirst()
                .orElseThrow();
        assertThat(champagne.perHead()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a round that does not overlap enough is not «this same company»")
    void jaccardThreshold() {
        assertThat(GroupSignalService.jaccard(Set.of(1L, 2L, 3L), Set.of(1L, 2L, 4L)))
                .isEqualTo(0.5);
        assertThat(GroupSignalService.jaccard(Set.of(41L, 55L), Set.of(41L, 42L, 43L, 44L)))
                .isLessThan(GroupSignalService.SAME_GROUP_THRESHOLD);
        assertThat(GroupSignalService.dayOfYearDistance(LocalDate.of(2026, 12, 31), LocalDate.of(2025, 1, 5)))
                .isEqualTo(5);
    }

    private GroupEvent agreed(long chatId, LocalDate date, GroupEventStatus status, String tag, long... userIds) {
        GroupEvent event = events.save(GroupEvent.builder()
                .id(UUID.randomUUID())
                .telegramGroupChatId(chatId)
                .organizerTelegramUserId(userIds[0])
                .eventTag(tag)
                .eventDate(date)
                .status(status)
                .approvedAt(Instant.now())
                .createdAt(date.atStartOfDay(java.time.ZoneId.of("Europe/Kyiv")).toInstant())
                .build());
        for (long userId : userIds) {
            participants.save(counted(event, userId, "пиво"));
        }
        return event;
    }

    private static GroupEventParticipant counted(GroupEvent event, long userId, String text) {
        return GroupEventParticipant.builder()
                .id(UUID.randomUUID())
                .groupEventId(event.getId())
                .telegramUserId(userId)
                .displayName("user" + userId)
                .rawReplyText(text)
                .repliedAt(Instant.now())
                .countedInDenominator(true)
                .build();
    }

    private static GroupEventItem item(GroupEvent event, String name, String quantity, String unit) {
        return GroupEventItem.builder()
                .id(UUID.randomUUID())
                .groupEventId(event.getId())
                .resolvedSilpoProductId(UUID.randomUUID().toString())
                .productName(name)
                .requestedName(name)
                .quantity(new BigDecimal(quantity))
                .unit(unit)
                .unitPrice(new BigDecimal("50"))
                .build();
    }
}
