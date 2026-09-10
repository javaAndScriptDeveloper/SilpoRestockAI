package com.silporestockai.repository;

import com.silporestockai.entity.GroupEvent;
import com.silporestockai.model.GroupEventStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface GroupEventRepository extends JpaRepository<GroupEvent, UUID> {

    /** The round this chat is in the middle of, if any. */
    Optional<GroupEvent> findFirstByTelegramGroupChatIdAndStatusInOrderByCreatedAtDesc(
            long telegramGroupChatId, Collection<GroupEventStatus> statuses);

    /** History for the signal queries: every round that reached agreement. */
    List<GroupEvent> findByStatusIn(Collection<GroupEventStatus> statuses);

    /** How many rounds sit in one state, for the social-channel gauges (task 80). */
    long countByStatus(GroupEventStatus status);

    /** The round whose cart this organizer is confirming right now. */
    Optional<GroupEvent> findFirstByOrganizerUserIdAndStatusOrderByApprovedAtDesc(
            UUID organizerUserId, GroupEventStatus status);

    /**
     * One-way status transitions that only one caller can win. Every group update runs on the async executor, so
     * two people tapping 👍 in the same instant both count «3 з 3»; whoever flips the row from {@code from} to
     * {@code to} first gets 1 back and does the work, the other gets 0 and stops.
     */
    @Modifying
    @Transactional
    @Query("update GroupEvent e set e.status = :to, e.approvedAt = :at where e.id = :id and e.status = :from")
    int transitionToApproved(
            @Param("id") UUID id,
            @Param("from") GroupEventStatus from,
            @Param("to") GroupEventStatus to,
            @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query("update GroupEvent e set e.status = :to, e.frozenAt = :at, e.proposalVersion = 1 "
            + "where e.id = :id and e.status = :from")
    int transitionToProposed(
            @Param("id") UUID id,
            @Param("from") GroupEventStatus from,
            @Param("to") GroupEventStatus to,
            @Param("at") Instant at);
}
