package com.silporestockai.job;

import com.silporestockai.service.CheckinPromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the check-in sweep on a clock.
 *
 * <p>Nothing but the trigger lives here. Everything the sweep decides is in {@code CheckinPromptService}, which a test
 * can call directly instead of waiting for a cron to come round.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckinScheduler {

    private final CheckinPromptService checkinPromptService;

    @Scheduled(cron = "${komora.checkin.sweep-cron}")
    public void sweepForDueCheckins() {
        checkinPromptService.sweep();
    }
}
