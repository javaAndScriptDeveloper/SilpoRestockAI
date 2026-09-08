package com.silporestockai.repository;

import com.silporestockai.entity.GroupEvent;
import com.silporestockai.model.GroupEventStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupEventRepository extends JpaRepository<GroupEvent, UUID> {

    /** The round this chat is in the middle of, if any. */
    Optional<GroupEvent> findFirstByTelegramGroupChatIdAndStatusInOrderByCreatedAtDesc(
            long telegramGroupChatId, Collection<GroupEventStatus> statuses);

    /** History for the signal queries: every round that reached agreement. */
    List<GroupEvent> findByStatusIn(Collection<GroupEventStatus> statuses);

    /** The round whose cart this organizer is confirming right now. */
    Optional<GroupEvent> findFirstByOrganizerUserIdAndStatusOrderByApprovedAtDesc(
            UUID organizerUserId, GroupEventStatus status);
}
