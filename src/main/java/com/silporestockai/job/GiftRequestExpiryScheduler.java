package com.silporestockai.job;

import com.silporestockai.service.GiftOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks on «куди привезти подарунок?» questions nobody answered (task 81). All logic lives in
 * {@link GiftOrderService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GiftRequestExpiryScheduler {

    private final GiftOrderService giftOrderService;

    @Scheduled(cron = "${komora.gift.sweep-cron}")
    public void sweepUnansweredGiftRequests() {
        giftOrderService.expireUnanswered();
    }
}
