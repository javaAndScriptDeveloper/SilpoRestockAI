package com.silporestockai.repository;

import com.silporestockai.entity.BaselineBasket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Confirmed basket snapshots. */
public interface BaselineBasketRepository extends JpaRepository<BaselineBasket, UUID> {

    /** At most one row can match: a partial unique index enforces that. */
    Optional<BaselineBasket> findByUserIdAndIsCurrentTrue(UUID userId);

    List<BaselineBasket> findByUserIdOrderByConfirmedAtDesc(UUID userId);
}
