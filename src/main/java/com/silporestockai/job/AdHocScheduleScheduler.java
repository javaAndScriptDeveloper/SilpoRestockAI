package com.silporestockai.job;

import com.silporestockai.service.AdHocScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the scheduled-ad-hoc-purchase sweep on a clock. All logic lives in {@link AdHocScheduleService}. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdHocScheduleScheduler {

    private final AdHocScheduleService adHocScheduleService;

    @Scheduled(cron = "${komora.ad-hoc-schedule.sweep-cron}")
    public void sweepDueAdHocPurchases() {
        adHocScheduleService.sweepDue();
    }
}
