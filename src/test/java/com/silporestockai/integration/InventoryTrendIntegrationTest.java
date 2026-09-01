package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.User;
import com.silporestockai.model.CheckinDelta;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.InventoryTrendRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.InventoryTrendService;
import com.silporestockai.service.UserAccountService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("what a household keeps not eating, counted across check-ins")
class InventoryTrendIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 9601L;

    /** Matches {@code komora.checkin.removal-threshold} in {@code application-test.yml}. */
    private static final int THRESHOLD = 3;

    @Autowired
    private InventoryTrendService inventoryTrendService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private InventoryTrendRepository inventoryTrendRepository;

    @Autowired
    private CheckinRepository checkinRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void clean() {
        inventoryTrendRepository.deleteAll();
        checkinRepository.deleteAll();
        userRepository.deleteAll();
        User user = userAccountService.findOrCreate(CHAT_ID);
        userId = user.getId();
    }

    private static CheckinDelta delta(List<String> stillHave, List<String> runningLow, List<String> gone) {
        return new CheckinDelta(stillHave, runningLow, gone);
    }

    private int streakOf(String itemName) {
        return inventoryTrendRepository
                .findByUserIdAndItemName(userId, itemName)
                .orElseThrow()
                .getConsecutiveUntouchedCycles();
    }

    private void storeCheckin(CheckinDelta parsed, Instant receivedAt) {
        checkinRepository.save(Checkin.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .rawInputText("stub")
                .parsedDelta(parsed)
                .receivedAt(receivedAt)
                .build());
    }

    @Test
    void threeCheckinsSayingAnItemIsStillThereMakeItARemovalCandidate() {
        for (int cycle = 0; cycle < THRESHOLD; cycle++) {
            inventoryTrendService.recordCheckin(userId, delta(List.of("Кіноа"), List.of(), List.of()));
        }

        assertThat(streakOf("Кіноа")).isEqualTo(THRESHOLD);
        assertThat(inventoryTrendService.getRemovalCandidates(userId)).containsExactly("Кіноа");
    }

    @Test
    void twoAreNotEnough() {
        inventoryTrendService.recordCheckin(userId, delta(List.of("Кіноа"), List.of(), List.of()));
        inventoryTrendService.recordCheckin(userId, delta(List.of("Кіноа"), List.of(), List.of()));

        assertThat(inventoryTrendService.getRemovalCandidates(userId)).isEmpty();
    }

    @Test
    void normalConsumptionNeverAccumulates() {
        inventoryTrendService.recordCheckin(userId, delta(List.of("Молоко"), List.of(), List.of()));
        inventoryTrendService.recordCheckin(userId, delta(List.of(), List.of(), List.of("Молоко")));
        inventoryTrendService.recordCheckin(userId, delta(List.of("Молоко"), List.of(), List.of()));

        // 1, 0, 1 — the restock in between needs no special handling: being gone is what breaks the streak.
        assertThat(streakOf("Молоко")).isEqualTo(1);
        assertThat(inventoryTrendService.getRemovalCandidates(userId)).isEmpty();
    }

    @Test
    void runningLowBreaksTheStreakTheSameWayGoneDoes() {
        inventoryTrendService.recordCheckin(userId, delta(List.of("Гречка"), List.of(), List.of()));
        inventoryTrendService.recordCheckin(userId, delta(List.of("Гречка"), List.of(), List.of()));
        inventoryTrendService.recordCheckin(userId, delta(List.of(), List.of("Гречка"), List.of()));

        assertThat(streakOf("Гречка")).isZero();
    }

    @Test
    void anItemNobodyMentionedKeepsWhateverItHad() {
        inventoryTrendService.recordCheckin(userId, delta(List.of("Кіноа"), List.of(), List.of()));
        inventoryTrendService.recordCheckin(userId, delta(List.of("Кіноа"), List.of(), List.of()));

        inventoryTrendService.recordCheckin(userId, delta(List.of("Хліб"), List.of(), List.of()));

        // Silence is not evidence: a check-in names two or three things, not the whole basket.
        assertThat(streakOf("Кіноа")).isEqualTo(2);
    }

    @Test
    void upcomingNeedsPutGoneItemsFirstAndSayEachOnce() {
        storeCheckin(delta(List.of("Кіноа"), List.of("Гречка", "Хліб"), List.of("Молоко", "Хліб")), Instant.now());

        assertThat(inventoryTrendService.getUpcomingNeeds(userId)).containsExactly("Молоко", "Хліб", "Гречка");
    }

    @Test
    void upcomingNeedsReadTheNewestCheckinThatActuallyParsed() {
        storeCheckin(
                delta(List.of(), List.of(), List.of("Молоко")), Instant.now().minusSeconds(3600));
        storeCheckin(null, Instant.now());

        // An unparsed check-in must not read as "nothing to buy" — task 12 already asked that user to clarify.
        assertThat(inventoryTrendService.getUpcomingNeeds(userId)).containsExactly("Молоко");
    }

    @Test
    void aUserWithNoCheckinsNeedsNothingYet() {
        assertThat(inventoryTrendService.getUpcomingNeeds(userId)).isEmpty();
        assertThat(inventoryTrendService.getRemovalCandidates(userId)).isEmpty();
    }
}
