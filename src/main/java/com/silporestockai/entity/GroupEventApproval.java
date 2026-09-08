package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One «👍 Погоджуюсь» on one version of one proposal (task 68).
 *
 * <p>Keyed by {@code proposalVersion} on purpose: a revision bumps the version, and every earlier approval stops
 * matching without being deleted — the reset the product asks for, and an audit trail of who agreed to what.
 */
@Entity
@Table(name = "group_event_approval")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupEventApproval {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "group_event_id", nullable = false)
    private UUID groupEventId;

    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;

    @Column(name = "proposal_version", nullable = false)
    private int proposalVersion;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;
}
