package com.silporestockai.service;

import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.telegram.HelpContent;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The one-time «ось що я ще вмію» teaser (task 70).
 *
 * <p>A household that has only ever seen a weekly plan has no way of guessing that «я захворів, гастрит» or
 * «світло вимкнули» do anything — the «❓ Інструкція» button holds that answer, but a button has to be tapped, and
 * nobody taps a button to find out about a feature they do not know exists. So it is pushed once, at the first
 * moment it is relevant, and the stamp on the profile makes sure it is pushed exactly once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityRevealService {

    private final UserProfileRepository userProfileRepository;
    private final TelegramOutboundService telegramOutboundService;

    /** Sends the teaser if this household has never seen it. Otherwise does nothing at all. */
    public void revealOnce(User user) {
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElse(null);
        if (profile == null || profile.getCapabilityRevealSentAt() != null) {
            return;
        }
        telegramOutboundService.sendMessage(user.getTelegramChatId(), HelpContent.REVEAL);
        profile.setCapabilityRevealSentAt(Instant.now());
        userProfileRepository.save(profile);
        log.info("capability reveal sent to user {}", user.getId());
    }
}
