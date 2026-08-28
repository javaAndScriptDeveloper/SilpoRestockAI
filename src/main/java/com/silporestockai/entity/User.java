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
import lombok.ToString;

/**
 * One person using the bot.
 *
 * <p>{@code telegramChatId} is the identity that actually arrives on every webhook call. {@code silpoGuestId} is
 * filled in once the guest connects their Silpo account and stays null until then.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class User {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "telegram_chat_id", nullable = false, unique = true)
    private Long telegramChatId;

    @Column(name = "silpo_guest_id")
    private String silpoGuestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
