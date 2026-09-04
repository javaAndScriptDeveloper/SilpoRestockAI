package com.silporestockai.repository;

import com.silporestockai.entity.UserProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** One profile per user. */
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    Optional<UserProfile> findByUserId(UUID userId);

    @Query("select p from UserProfile p where p.specialModeExpiresAt is not null and p.specialModeExpiresAt <= :now")
    List<UserProfile> findAllWithExpiredSpecialMode(@Param("now") Instant now);
}
