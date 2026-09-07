package com.silporestockai.job;

import com.silporestockai.service.ObservabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the snapshot behind the database-derived Prometheus gauges (task 54). Nothing but the trigger lives here —
 * {@code ObservabilityService} decides everything, and a test calls it directly instead of waiting for the interval.
 *
 * <p>This is also what keeps the gauges legal: ArchUnit lets only {@code Controller} and {@code Job} reach a service,
 * so a {@code MeterBinder} bean in {@code config} could not do this job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObservabilityRefreshScheduler {

    private final ObservabilityService observabilityService;

    @Scheduled(fixedRateString = "${komora.observability.refresh-interval}")
    public void refreshMetrics() {
        try {
            observabilityService.refresh();
        } catch (RuntimeException e) {
            // A metrics refresh that throws must not kill the scheduler thread and silently freeze every gauge at
            // its last value — a stale dashboard that looks alive is worse than one that plainly stopped.
            log.warn("could not refresh the observability snapshot", e);
        }
    }
}
