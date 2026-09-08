package com.silporestockai.repository;

import com.silporestockai.entity.GroupEventParticipant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupEventParticipantRepository extends JpaRepository<GroupEventParticipant, UUID> {

    List<GroupEventParticipant> findByGroupEventId(UUID groupEventId);

    List<GroupEventParticipant> findByGroupEventIdIn(Collection<UUID> groupEventIds);

    Optional<GroupEventParticipant> findByGroupEventIdAndTelegramUserId(UUID groupEventId, long telegramUserId);

    /** This person's counted replies in every other round — the «what they drink elsewhere» signal. */
    List<GroupEventParticipant> findByTelegramUserIdAndCountedInDenominatorTrueAndGroupEventIdNot(
            long telegramUserId, UUID groupEventId);

    long countByGroupEventIdAndCountedInDenominatorTrue(UUID groupEventId);
}
