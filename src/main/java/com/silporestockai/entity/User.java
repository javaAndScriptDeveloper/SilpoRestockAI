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
import org.hibernate.annotations.DynamicUpdate;

/**
 * One person using the bot.
 *
 * <p>{@code telegramChatId} is the identity that actually arrives on every webhook call. {@code silpoGuestId} is
 * filled in once the guest connects their Silpo account and stays null until then.
 */
@Entity
@Table(name = "users")
// Only dirty columns go into an UPDATE. Live (session 25), the /start handler saved this row to store a Telegram
// username it had loaded seconds earlier, and the full-row write put last_checkin_prompt_sent_at back to its old
// value — the sweep had stamped it in between — so the same household was asked «що закінчилось?» twice in three
// minutes. Several threads write different columns of this row; none of them should write the others'.
@DynamicUpdate
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

    /**
     * The {@code @nickname} this person is known by in Telegram, without the {@code @}. Null when they have none
     * — Telegram does not require one — and re-read on every update, so a rename follows them here.
     */
    @Column(name = "telegram_username", length = 64)
    private String telegramUsername;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Whether this chat asked for spoken replies with {@code /voice}. Off until somebody says otherwise. */
    @Column(name = "voice_replies_enabled", nullable = false)
    @Builder.Default
    private boolean voiceRepliesEnabled = false;

    /** When the agent last opened a check-in. Null until the first prompt goes out. */
    @Column(name = "last_checkin_prompt_sent_at")
    private Instant lastCheckinPromptSentAt;

    /** How many check-in prompts the agent has ever sent this chat — the denominator of the response rate (task 37). */
    @Column(name = "checkin_prompts_sent", nullable = false)
    @Builder.Default
    private int checkinPromptsSent = 0;
}
