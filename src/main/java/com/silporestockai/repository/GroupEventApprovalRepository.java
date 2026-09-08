package com.silporestockai.repository;

import com.silporestockai.entity.GroupEventApproval;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupEventApprovalRepository extends JpaRepository<GroupEventApproval, UUID> {

    long countByGroupEventIdAndProposalVersion(UUID groupEventId, int proposalVersion);

    boolean existsByGroupEventIdAndTelegramUserIdAndProposalVersion(
            UUID groupEventId, long telegramUserId, int proposalVersion);

    List<GroupEventApproval> findByGroupEventIdAndProposalVersion(UUID groupEventId, int proposalVersion);
}
