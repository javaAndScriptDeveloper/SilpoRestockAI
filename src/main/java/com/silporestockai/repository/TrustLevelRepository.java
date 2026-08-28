package com.silporestockai.repository;

import com.silporestockai.entity.TrustLevel;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One trust record per user. */
public interface TrustLevelRepository extends JpaRepository<TrustLevel, UUID> {

    Optional<TrustLevel> findByUserId(UUID userId);
}
