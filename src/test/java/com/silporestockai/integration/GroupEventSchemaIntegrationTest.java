package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.silporestockai.entity.GroupEvent;
import com.silporestockai.entity.GroupEventApproval;
import com.silporestockai.entity.GroupEventItem;
import com.silporestockai.entity.GroupEventParticipant;
import com.silporestockai.model.GroupEventStatus;
import com.silporestockai.repository.GroupEventApprovalRepository;
import com.silporestockai.repository.GroupEventItemRepository;
import com.silporestockai.repository.GroupEventParticipantRepository;
import com.silporestockai.repository.GroupEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("the group event tables hold a round, its repliers, its lines and its votes")
class GroupEventSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private GroupEventRepository events;

    @Autowired
    private GroupEventParticipantRepository participants;

    @Autowired
    private GroupEventItemRepository items;

    @Autowired
    private GroupEventApprovalRepository approvals;

    @BeforeEach
    void clean() {
        events.deleteAll();
    }

    @Test
    @DisplayName("a round round-trips with its json columns and its children")
    void roundTrips() {
        GroupEvent event = events.save(GroupEvent.builder()
                .id(UUID.randomUUID())
                .telegramGroupChatId(-100123L)
                .chatTitle("Пʼятниця")
                .organizerTelegramUserId(41L)
                .eventTag("новий рік")
                .eventDate(LocalDate.of(2026, 12, 31))
                .budget(new BigDecimal("1500.00"))
                .status(GroupEventStatus.COLLECTING_REPLIES)
                .revisionNotes(List.of("менше пива"))
                .proposalJson(Map.of("version", 1))
                .createdAt(Instant.now())
                .build());
        participants.save(participant(event.getId(), 41L, "вино"));
        participants.save(participant(event.getId(), 42L, "."));
        items.save(GroupEventItem.builder()
                .id(UUID.randomUUID())
                .groupEventId(event.getId())
                .resolvedSilpoProductId("00000000-0000-4000-8000-000000000001")
                .productName("Пиво світле 0.5")
                .requestedName("Пиво світле")
                .quantity(new BigDecimal("6"))
                .unit("шт")
                .unitPrice(new BigDecimal("42.50"))
                .build());
        approvals.save(GroupEventApproval.builder()
                .id(UUID.randomUUID())
                .groupEventId(event.getId())
                .telegramUserId(41L)
                .proposalVersion(1)
                .approvedAt(Instant.now())
                .build());

        GroupEvent stored = events.findById(event.getId()).orElseThrow();
        assertThat(stored.getRevisionNotes()).containsExactly("менше пива");
        assertThat(stored.getProposalJson()).containsEntry("version", 1);
        assertThat(stored.getProposalVersion()).isZero();
        assertThat(participants.findByGroupEventId(event.getId())).hasSize(2);
        assertThat(participants.countByGroupEventIdAndCountedInDenominatorTrue(event.getId()))
                .isZero();
        assertThat(items.findByGroupEventId(event.getId())).hasSize(1);
        assertThat(approvals.countByGroupEventIdAndProposalVersion(event.getId(), 1))
                .isEqualTo(1);
        assertThat(approvals.countByGroupEventIdAndProposalVersion(event.getId(), 2))
                .isZero();
        assertThat(events.findFirstByTelegramGroupChatIdAndStatusInOrderByCreatedAtDesc(
                        -100123L, List.of(GroupEventStatus.COLLECTING_REPLIES, GroupEventStatus.PROPOSED)))
                .isPresent();
    }

    @Test
    @DisplayName("one person has one reply row per round")
    void onePersonOneRow() {
        GroupEvent event = events.save(event());
        participants.save(participant(event.getId(), 41L, "вино"));
        assertThatThrownBy(() -> participants.saveAndFlush(participant(event.getId(), 41L, "пиво")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting a round takes its repliers, lines and votes with it")
    void cascades() {
        GroupEvent event = events.save(event());
        participants.save(participant(event.getId(), 41L, "вино"));
        approvals.save(GroupEventApproval.builder()
                .id(UUID.randomUUID())
                .groupEventId(event.getId())
                .telegramUserId(41L)
                .proposalVersion(1)
                .approvedAt(Instant.now())
                .build());
        items.save(GroupEventItem.builder()
                .id(UUID.randomUUID())
                .groupEventId(event.getId())
                .productName("Вино")
                .quantity(BigDecimal.ONE)
                .build());

        events.deleteById(event.getId());
        events.flush();

        assertThat(participants.findByGroupEventId(event.getId())).isEmpty();
        assertThat(items.findByGroupEventId(event.getId())).isEmpty();
        assertThat(approvals.findByGroupEventIdAndProposalVersion(event.getId(), 1))
                .isEmpty();
    }

    @Test
    @DisplayName("a status transition can be won only once")
    void transitionsAreOneWay() {
        GroupEvent event = events.save(event());
        Instant now = Instant.now();
        assertThat(events.transitionToProposed(
                        event.getId(), GroupEventStatus.COLLECTING_REPLIES, GroupEventStatus.PROPOSED, now))
                .isEqualTo(1);
        assertThat(events.transitionToProposed(
                        event.getId(), GroupEventStatus.COLLECTING_REPLIES, GroupEventStatus.PROPOSED, now))
                .isZero();
        assertThat(events.transitionToApproved(
                        event.getId(), GroupEventStatus.PROPOSED, GroupEventStatus.APPROVED, now))
                .isEqualTo(1);
        assertThat(events.transitionToApproved(
                        event.getId(), GroupEventStatus.PROPOSED, GroupEventStatus.APPROVED, now))
                .isZero();
        GroupEvent stored = events.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(GroupEventStatus.APPROVED);
        assertThat(stored.getProposalVersion()).isEqualTo(1);
        assertThat(stored.getFrozenAt()).isNotNull();
        assertThat(stored.getApprovedAt()).isNotNull();
    }

    private static GroupEvent event() {
        return GroupEvent.builder()
                .id(UUID.randomUUID())
                .telegramGroupChatId(-100123L)
                .organizerTelegramUserId(41L)
                .status(GroupEventStatus.COLLECTING_REPLIES)
                .createdAt(Instant.now())
                .build();
    }

    private static GroupEventParticipant participant(UUID eventId, long userId, String text) {
        return GroupEventParticipant.builder()
                .id(UUID.randomUUID())
                .groupEventId(eventId)
                .telegramUserId(userId)
                .displayName("user" + userId)
                .rawReplyText(text)
                .repliedAt(Instant.now())
                .build();
    }
}
