package com.silporestockai.repository;

import com.silporestockai.entity.Checkin;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Recorded check-ins, newest first. */
public interface CheckinRepository extends JpaRepository<Checkin, UUID> {

    Optional<Checkin> findFirstByUserIdOrderByReceivedAtDesc(UUID userId);

    /**
     * The latest check-in and the one before it. Trend tracking needs both: an item counts as untouched only when it
     * was reported as still present in two consecutive cycles.
     */
    List<Checkin> findTop2ByUserIdOrderByReceivedAtDesc(UUID userId);

    List<Checkin> findByUserIdOrderByReceivedAtDesc(UUID userId);
}
