package com.silporestockai.service;

import com.silporestockai.config.CheckinProperties;
import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.CheckinMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The one place the agent speaks first.
 *
 * <p>Everything else in the application answers something the user did. This asks, on its own schedule, what is left
 * in the fridge — and the whole difficulty is restraint: only households with a basket to compare against, only when
 * enough time has passed, and never twice for the same window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinPromptService {

    /** The step task 12 reads to know the next message is a fridge report rather than a new request. */
    public static final String STEP_AWAITING_REPORT = "AWAITING_REPORT";

    private final UserRepository userRepository;
    private final CheckinRepository checkinRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final ConversationStateService conversationStateService;
    private final CheckinMessageService checkinMessageService;
    private final TelegramOutboundService telegramOutboundService;
    private final CheckinProperties checkinProperties;
    private final Clock clock;

    /**
     * Prompts everyone who is due, and answers with how many that was.
     *
     * <p>A loop over one query rather than a single clever statement: the anchor is the newest of three timestamps
     * living in three tables, and the JPQL that computes it would be unreadable long before the household count made
     * it worth having. One user's Telegram failure is logged and skipped — a blocked chat must not cost everybody
     * else their check-in.
     */
    public int sweep() {
        List<User> candidates = userRepository.findAllWithCurrentBaseline();
        int prompted = 0;
        for (User user : candidates) {
            try {
                if (isDue(user) && !isBusyElsewhere(user)) {
                    prompt(user);
                    prompted++;
                }
            } catch (RuntimeException e) {
                log.error("could not send a check-in prompt to user {}", user.getId(), e);
            }
        }
        log.info("check-in sweep: {} of {} eligible users prompted", prompted, candidates.size());
        return prompted;
    }

    /**
     * Due when nothing has been heard for a whole interval.
     *
     * <p>The anchor is the newest of the last prompt, the last check-in and the last confirmed order, so every kind of
     * contact counts as contact: nobody is asked what is left in their fridge the day after they ordered it, and
     * somebody who ignored the last prompt is asked again after a full interval rather than never.
     */
    public boolean isDue(User user) {
        Instant lastCheckin = checkinRepository
                .findFirstByUserIdOrderByReceivedAtDesc(user.getId())
                .map(Checkin::getReceivedAt)
                .orElse(null);
        Instant lastOrder = customerOrderRepository
                .findFirstByUserIdAndStatusOrderByConfirmedAtDesc(user.getId(), OrderStatus.CONFIRMED)
                .map(CustomerOrder::getConfirmedAt)
                .orElse(null);
        Instant anchor = Stream.of(user.getLastCheckinPromptSentAt(), lastCheckin, lastOrder)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                // No anchor at all means a baseline exists but nothing is dated: ask rather than stay silent forever.
                .orElse(Instant.EPOCH);
        return !clock.instant().isBefore(anchor.plus(checkinProperties.interval()));
    }

    /**
     * True while the user owes the agent an answer to something else.
     *
     * <p>A chat already in {@link ConversationFlow#CHECK_IN} is deliberately not busy: that is the un-answered prompt,
     * whose cadence the interval already governs.
     */
    private boolean isBusyElsewhere(User user) {
        ConversationFlow flow =
                conversationStateService.load(user.getTelegramChatId()).getCurrentFlow();
        return flow == ConversationFlow.ONBOARDING || flow == ConversationFlow.CART_CONFIRMATION;
    }

    /** Sends the prompt, leaves the flag task 12 reads, and records that the agent spoke. */
    public void prompt(User user) {
        telegramOutboundService.sendMessage(user.getTelegramChatId(), checkinMessageService.promptText());
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.CHECK_IN, STEP_AWAITING_REPORT, Map.of());
        user.setLastCheckinPromptSentAt(clock.instant());
        userRepository.save(user);
        log.info("check-in prompt sent to user {}", user.getId());
    }
}
