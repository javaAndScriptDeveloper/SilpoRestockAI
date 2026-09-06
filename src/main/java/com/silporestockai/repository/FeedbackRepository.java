package com.silporestockai.repository;

import com.silporestockai.entity.Feedback;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Write-mostly: the read side of task 47 is a SQL query, on purpose. */
public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {}
