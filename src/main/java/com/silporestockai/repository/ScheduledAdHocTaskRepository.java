package com.silporestockai.repository;

import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledAdHocTaskRepository extends JpaRepository<ScheduledAdHocTask, UUID> {
    List<ScheduledAdHocTask> findByStatusAndTriggerAtBefore(ScheduledAdHocTaskStatus status, Instant instant);
}
