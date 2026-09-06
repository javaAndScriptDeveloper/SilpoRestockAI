package com.silporestockai.entity;

import com.silporestockai.model.FeedbackSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One message somebody typed after tapping «Фідбек» (task 47), stored exactly as typed.
 *
 * <p>Deliberately raw: no category, no sentiment, no reply. Triage is a {@code SELECT ... ORDER BY created_at}
 * during the crunch, not a feature.
 */
@Entity
@Table(name = "feedback")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Feedback {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Nullable by spec — feedback must survive a chat with no finished profile — though every chat has a user row. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "telegram_chat_id", nullable = false)
    private Long telegramChatId;

    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    private String rawText;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private FeedbackSource source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
