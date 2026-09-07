package com.silporestockai.repository;

import com.silporestockai.entity.ConversationState;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

/** Conversation state, one row per Telegram chat. */
public interface ConversationStateRepository extends JpaRepository<ConversationState, Long> {

    /**
     * Households with any conversation turn since {@code since} — task 54's "active users in period".
     *
     * <p>One row per chat and {@code updated_at} bumped on every save, so this counts distinct households without a
     * {@code distinct}. It is also the only per-household activity timestamp that exists: {@code users} has no
     * last-seen column, and adding one would duplicate what this row already knows.
     */
    long countByUpdatedAtAfter(Instant since);
}
