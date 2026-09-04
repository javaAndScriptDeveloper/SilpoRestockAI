package com.silporestockai.service;

import com.silporestockai.config.SpecialModeProperties;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every {@code special_mode}/{@code only_ua_producer} transition: the gastritis two-stage cycle, mass gain,
 * UA-only, and the {@code /normal} early exit.
 *
 * <p>Every regeneration reuses the exact pipeline a normal weekly plan takes ({@link MealPlanService} →
 * {@link ShoppingListService#deriveFromMealPlan} → {@link ShoppingListBuilderService#present}), the same one
 * {@link MealPlanHandoffService#generateFirstPlan} uses. That is what keeps {@code BaselineBasket} safe without a
 * snapshot/restore mechanism: {@link ShoppingListBuilderService#order()} only ever stores a baseline for
 * {@code OrderType.INITIAL}, and a household already using special modes already has one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpecialModeService {

    private final UserProfileRepository userProfileRepository;
    private final MealPlanService mealPlanService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final TelegramOutboundService telegramOutboundService;
    private final SpecialModeProperties specialModeProperties;
    private final Clock clock;
    private final UserRepository userRepository;

    @Transactional
    public void triggerGastritis(User user) {
        UserProfile profile = requireProfile(user);
        if (isActive(profile)) {
            telegramOutboundService.sendMessage(
                    user.getTelegramChatId(),
                    "У вас вже активний інший режим харчування. Спершу завершіть його: /normal.");
            return;
        }
        Instant now = clock.instant();
        profile.setSpecialMode(SpecialMode.MEDICAL_GASTRITIS_ACUTE);
        profile.setSpecialModeStartedAt(now);
        profile.setSpecialModeExpiresAt(now.plus(specialModeProperties.gastritisAcuteDuration()));
        userProfileRepository.save(profile);
        log.info(
                "user {} entered MEDICAL_GASTRITIS_ACUTE, expires {}", user.getId(), profile.getSpecialModeExpiresAt());
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(), "Розумію, гастрит. Перемикаю на щадне харчування — складаю новий план.");
        regenerateAndPresent(user);
    }

    @Transactional
    public void cancel(User user) {
        UserProfile profile = requireProfile(user);
        if (!isActive(profile)) {
            telegramOutboundService.sendMessage(user.getTelegramChatId(), "Звичайний режим і так активний.");
            return;
        }
        revertToNormal(user, profile);
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(), "Повернув звичайний раціон — складаю новий план.");
        regenerateAndPresent(user);
    }

    @Transactional
    public void toggleUaOnly(User user) {
        UserProfile profile = requireProfile(user);
        boolean next = !Boolean.TRUE.equals(profile.getOnlyUaProducer());
        profile.setOnlyUaProducer(next);
        userProfileRepository.save(profile);
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                next
                        ? "Тепер шукатиму переважно товари українського виробництва."
                        : "Прибрав обмеження на українського виробника.");
    }

    /**
     * Advances every user whose current stage expired: ACUTE steps down to DIET_TABLE_5 with a fresh expiry;
     * anything else at expiry (DIET_TABLE_5, or any other durational mode reaching its own expiry) reverts to
     * NONE. One user's failure is logged and skipped, matching {@link CheckinPromptService#sweep()}'s convention.
     */
    @Transactional
    public int sweepExpired() {
        List<UserProfile> due = userProfileRepository.findAllWithExpiredSpecialMode(clock.instant());
        int handled = 0;
        for (UserProfile profile : due) {
            try {
                userRepository.findById(profile.getUserId()).ifPresent(user -> {
                    if (profile.getSpecialMode() == SpecialMode.MEDICAL_GASTRITIS_ACUTE) {
                        stepDownToDietTable5(user, profile);
                    } else {
                        revertToNormal(user, profile);
                        telegramOutboundService.sendMessage(
                                user.getTelegramChatId(),
                                "Два тижні дієтичного харчування завершено, повертаємось до звичайного раціону.");
                        regenerateAndPresent(user);
                    }
                });
                handled++;
            } catch (RuntimeException e) {
                log.error("could not advance special mode for profile {}", profile.getId(), e);
            }
        }
        log.info("special-mode sweep: {} of {} expired profiles advanced", handled, due.size());
        return handled;
    }

    private void stepDownToDietTable5(User user, UserProfile profile) {
        profile.setSpecialMode(SpecialMode.MEDICAL_DIET_TABLE_5);
        profile.setSpecialModeExpiresAt(profile.getSpecialModeStartedAt()
                .plus(specialModeProperties.gastritisAcuteDuration())
                .plus(specialModeProperties.gastritisDiet5Duration()));
        userProfileRepository.save(profile);
        log.info(
                "user {} stepped down to MEDICAL_DIET_TABLE_5, expires {}",
                user.getId(),
                profile.getSpecialModeExpiresAt());
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                "Гострий період завершено, переходимо до дієтичного столу №5 ще на кілька днів.");
        regenerateAndPresent(user);
    }

    /** Fields cleared, so a later {@link #isActive} check and the expiry sweep both see a clean NONE state. */
    void revertToNormal(User user, UserProfile profile) {
        profile.setSpecialMode(SpecialMode.NONE);
        profile.setSpecialModeStartedAt(null);
        profile.setSpecialModeExpiresAt(null);
        userProfileRepository.save(profile);
        log.info("user {} reverted to NONE", user.getId());
    }

    private void regenerateAndPresent(User user) {
        MealPlan plan = mealPlanService.regenerateWithAdjustment(user.getId(), null);
        List<ShoppingListItem> items = shoppingListService.deriveFromMealPlan(plan.getId(), plan.getSourceType());
        shoppingListBuilderService.present(user, items);
    }

    private static boolean isActive(UserProfile profile) {
        return profile.getSpecialMode() != null && profile.getSpecialMode() != SpecialMode.NONE;
    }

    private UserProfile requireProfile(User user) {
        return userProfileRepository
                .findByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("user %s has no profile yet".formatted(user.getId())));
    }
}
