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
 * One person who replied in one round (task 68).
 *
 * <p>{@code rawReplyText} is the sentence as typed — «пиво світле, це на ДР», «сьогодні не п'ю віскі», or «.» —
 * and is what the model reads for this event. {@code preferenceSummary} is the durable part the model extracted
 * («світле пиво»), written onto this row only; it is what a later round reads as «what this person drinks
 * elsewhere», so a one-evening exception never rewrites anyone's long-term pattern.
 *
 * <p>{@code countedInDenominator} is set at the organizer's «всі відповіли» tap and never afterwards: a reply
 * that lands later is kept, acknowledged as late, and excluded from that round's vote.
 */
@Entity
@Table(name = "group_event_participant")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupEventParticipant {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "group_event_id", nullable = false)
    private UUID groupEventId;

    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(name = "raw_reply_text", nullable = false)
    private String rawReplyText;

    @Column(name = "preference_summary", length = 256)
    private String preferenceSummary;

    @Column(name = "replied_at", nullable = false)
    private Instant repliedAt;

    @Column(name = "counted_in_denominator", nullable = false)
    @Builder.Default
    private boolean countedInDenominator = false;

    /** «.» — the person is in, and leaves the choice to the bot. */
    public boolean noPreference() {
        return rawReplyText == null || rawReplyText.isBlank() || ".".equals(rawReplyText.strip());
    }
}
