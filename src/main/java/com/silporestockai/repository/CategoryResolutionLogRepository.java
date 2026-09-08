package com.silporestockai.repository;

import com.silporestockai.entity.CategoryResolutionLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Resolutions are read whole and grouped in memory (task 63): a category is decided by the same word matcher the
 * cart uses, which is a regex with word boundaries, not something SQL should be asked to re-implement and get
 * subtly different. At this project's volume — roughly twenty rows per cart build — that is the honest trade.
 */
public interface CategoryResolutionLogRepository extends JpaRepository<CategoryResolutionLog, UUID> {}
