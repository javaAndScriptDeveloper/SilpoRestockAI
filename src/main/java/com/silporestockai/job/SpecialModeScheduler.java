package com.silporestockai.job;

import com.silporestockai.service.SpecialModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the special-mode expiry sweep on a clock. Nothing but the trigger lives here — {@code SpecialModeService}
 * decides everything, and a test can call it directly instead of waiting for a cron to come round.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpecialModeScheduler {

    private final SpecialModeService specialModeService;

    @Scheduled(cron = "${komora.special-mode.sweep-cron}")
    public void sweepExpiredSpecialModes() {
        specialModeService.sweepExpired();
    }
}
