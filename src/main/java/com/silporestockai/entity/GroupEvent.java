package com.silporestockai.entity;

import com.silporestockai.model.GroupEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One drinks round in one Telegram group (task 68).
 *
 * <p>{@code organizerTelegramUserId} is whoever added the bot or typed {@code /drinks} — read from the update,
 * never inferred. {@code organizerUserId} is that person's household row when they have one (a private chat's
 * id equals the person's Telegram id), and stays null until they connect Silpo — the cart can only be built
 * through their session.
 *
 * <p>{@code proposalJson} is the current {@code GroupProposal}; {@code proposalVersion} bumps on every revision,
 * and approvals are keyed by it, which is what empties the vote after a revision.
 */
@Entity
@Table(name = "group_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupEvent {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "telegram_group_chat_id", nullable = false)
    private Long telegramGroupChatId;

    @Column(name = "chat_title", length = 256)
    private String chatTitle;

    @Column(name = "organizer_telegram_user_id", nullable = false)
    private Long organizerTelegramUserId;

    @Column(name = "organizer_user_id")
    private UUID organizerUserId;

    @Column(name = "event_tag", length = 256)
    private String eventTag;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "budget", precision = 10, scale = 2)
    private BigDecimal budget;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private GroupEventStatus status;

    @Column(name = "proposal_version", nullable = false)
    @Builder.Default
    private int proposalVersion = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal_json")
    private Map<String, Object> proposalJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "revision_notes")
    @Builder.Default
    private List<String> revisionNotes = new ArrayList<>();

    @Column(name = "greeting_message_id")
    private Integer greetingMessageId;

    @Column(name = "proposal_message_id")
    private Integer proposalMessageId;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Never null, whatever the stored column holds. */
    public List<String> revisionNotesOrEmpty() {
        return revisionNotes == null ? List.of() : revisionNotes;
    }

    /** The day the round is about — the stated date, or the day it was opened when none was given. */
    public LocalDate effectiveDate(java.time.ZoneId zone) {
        return eventDate != null ? eventDate : createdAt.atZone(zone).toLocalDate();
    }
}
