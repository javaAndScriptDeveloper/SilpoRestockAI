package com.silporestockai.entity;

import com.silporestockai.model.ConversationFlow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Where a Telegram chat is inside a multi-step conversation.
 *
 * <p>Telegram delivers every update as an independent HTTP request, so nothing survives in memory between two messages
 * from the same person. This row is that memory.
 *
 * <p>{@code context} is mapped straight onto a Postgres {@code jsonb} column through Hibernate's native JSON support —
 * no {@code AttributeConverter}. Task 05 may generalise that later.
 */
@Entity
@Table(name = "conversation_state")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ConversationState {

    @Id
    @Column(name = "telegram_chat_id", nullable = false)
    private Long telegramChatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_flow", nullable = false, length = 64)
    private ConversationFlow currentFlow;

    @Column(name = "current_step", length = 64)
    private String currentStep;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json", nullable = false)
    @Builder.Default
    private Map<String, Object> context = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
