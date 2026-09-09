package com.silporestockai.repository;

import com.silporestockai.entity.TrustLevel;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** One trust record per user. */
public interface TrustLevelRepository extends JpaRepository<TrustLevel, UUID> {

    Optional<TrustLevel> findByUserId(UUID userId);

    /** The longest run of unedited confirmations any household has reached — the accuracy story's second number. */
    @Query("select coalesce(max(t.consecutiveUneditedConfirmations), 0) from TrustLevel t")
    int longestUneditedStreak();
}
