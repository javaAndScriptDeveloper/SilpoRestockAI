package com.silporestockai.service;

import com.silporestockai.config.CheckinProperties;
import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.CheckinMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /** No prompt this soon after the chat's state last moved: the person is mid-exchange, or a plan is on its way. */
    static final java.time.Duration QUIET_AFTER_ACTIVITY = java.time.Duration.ofMinutes(2);

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
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
                Optional<UserProfile> profile = userProfileRepository.findByUserId(user.getId());
                if (profile.isEmpty()) {
                    // A baseline with no profile is a household mid-onboarding (or one whose profile was reset for a
                    // fresh run). Asking «що закінчилось?» before the greeting has even landed is the wrong first
                    // frame, and the answer would be routed to onboarding anyway.
                    log.debug("check-in skipped for user {}: no finished profile", user.getId());
                    continue;
                }
                if (isDue(user, profile.get()) && !isBusyElsewhere(user)) {
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
    public boolean isDue(User user, UserProfile profile) {
        Instant lastCheckin = checkinRepository
                .findFirstByUserIdOrderByReceivedAtDesc(user.getId())
                .map(Checkin::getReceivedAt)
                .orElse(null);
        Instant lastOrder = customerOrderRepository
                .findFirstByUserIdAndStatusOrderByConfirmedAtDesc(user.getId(), OrderStatus.CONFIRMED)
                .map(CustomerOrder::getConfirmedAt)
                .orElse(null);
        // Finishing onboarding is contact too: the household has just been handed a plan and a list, and «що
        // закінчилось?» three minutes later (live, session 25) reads as the agent forgetting what it just did.
        // capability_reveal_sent_at is stamped exactly when onboarding completes (task 70).
        Instant anchor = Stream.of(
                        user.getLastCheckinPromptSentAt(), lastCheckin, lastOrder, profile.getCapabilityRevealSentAt())
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                // No anchor at all means a baseline exists but nothing is dated: ask rather than stay silent forever.
                .orElse(Instant.EPOCH);
        return !clock.instant().isBefore(anchor.plus(checkinProperties.interval()));
    }

    /**
     * True while the user owes the agent an answer to something else.
     *
     * <p>Any flow at all, not a named few: {@link #prompt} overwrites {@code conversation_state}, and every flow keeps
     * its working data there — a reorder waiting on its Підтвердити tap, a mass-gain setup half-way through its
     * questions, an edit of a scheduled purchase. A prompt landing mid-flow used to wipe that state, and the buttons
     * the person was about to tap silently stopped doing anything. Whatever flow exists, the check-in can wait an
     * hour for the next sweep.
     *
     * <p>A chat already in {@link ConversationFlow#CHECK_IN} is deliberately not busy: that is the un-answered prompt,
     * whose cadence the interval already governs.
     */
    private boolean isBusyElsewhere(User user) {
        ConversationState state = conversationStateService.load(user.getTelegramChatId());
        if (state.getUpdatedAt() != null
                && clock.instant().isBefore(state.getUpdatedAt().plus(QUIET_AFTER_ACTIVITY))) {
            // Something just happened in this chat. Twice in one night the prompt landed inside the thirty
            // seconds between «Записав. Готую перший план» and the plan itself — the onboarding had just closed
            // its flow, and a plan being generated is no flow at all. A check-in is the agent speaking first;
            // it can wait for the next sweep rather than interrupt an exchange that is still going.
            return true;
        }
        ConversationFlow flow = state.getCurrentFlow();
        if (flow == ConversationFlow.NONE || flow == ConversationFlow.CHECK_IN) {
            return false;
        }
        // The one exception to "any flow at all": a list awaiting approval is not a pending question (see
        // ShoppingListBuilderService.awaitsAnAnswer) and nothing ever clears it, so counting it as busy ended
        // check-ins permanently for any household shown a list they did not go on to order. Its keyboard is
        // dispatched globally by the routing layer, so overwriting conversation_state here costs it nothing.
        return !(flow == ConversationFlow.LIST_BUILDING
                && ShoppingListBuilderService.STEP_AWAITING_APPROVAL.equals(state.getCurrentStep()));
    }

    /** Sends the prompt, leaves the flag task 12 reads, and records that the agent spoke. */
    public void prompt(User user) {
        // Recorded before the send, not after. Live, a Telegram call timed out on this side after the message
        // had already been delivered; nothing was recorded, and the next sweep asked the same household again a
        // minute later. A prompt that genuinely never left costs one interval of silence, which is the cheaper
        // mistake by far — the household is asked again, not nagged.
        user.setLastCheckinPromptSentAt(clock.instant());
        user.setCheckinPromptsSent(user.getCheckinPromptsSent() + 1);
        userRepository.save(user);
        telegramOutboundService.sendMessage(user.getTelegramChatId(), checkinMessageService.promptText());
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.CHECK_IN, STEP_AWAITING_REPORT, Map.of());
        log.info("check-in prompt sent to user {}", user.getId());
    }
}
