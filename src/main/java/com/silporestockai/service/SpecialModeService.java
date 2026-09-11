package com.silporestockai.service;

import com.silporestockai.config.SpecialModeProperties;
import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import com.silporestockai.utils.DayLabels;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final UserRepository userRepository;
    private final MealPlanService mealPlanService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final TelegramOutboundService telegramOutboundService;
    private final SpecialModeProperties specialModeProperties;
    private final Clock clock;
    private final ConversationStateService conversationStateService;

    // Not @Transactional on purpose (session 25). These entry points save the mode and then regenerate the plan,
    // and the plan is a minute of Claude. With one transaction around both, the mode row committed only after the
    // plan — so «вже не запара, повертай як було» typed in that minute was answered with «Звичайний режим і так
    // активний», and a second mode asked for right after was refused because the first one had by then landed.
    // The repository save commits on its own; the plan runs in whatever transaction MealPlanService opens.
    public void triggerGastritis(User user) {
        UserProfile profile = requireProfile(user);
        if (isActive(profile)) {
            telegramOutboundService.sendMessage(
                    user.getTelegramChatId(),
                    "У тебе вже активний інший режим харчування. Спершу заверши його — напиши «повертаємось до звичайного раціону».");
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

    /**
     * A crunch week (task 67): ready-to-eat food until the deadline passes, then back to normal on its own.
     *
     * <p>Mechanically the gastritis flow with a different field overridden — one {@code special_mode} row carrying
     * {@code started_at}/{@code expires_at}, swept by the same {@link #sweepExpired()} and endable early by the same
     * {@code SPECIAL_MODE_END} intent. What it must never do is write to {@code cooking_time_preference}: see
     * {@link UserProfile#effectiveCookingTimePreference()} for why the override lives at the read instead.
     */
    public void triggerCrunchWeek(User user) {
        UserProfile profile = requireProfile(user);
        if (profile.getSpecialMode() == SpecialMode.CRUNCH_WEEK) {
            // Saying it twice is what a bad week sounds like. Neither an error nor a silent restart of the clock:
            // the answer is when the one already running ends.
            telegramOutboundService.sendMessage(
                    user.getTelegramChatId(),
                    "Режим запари вже увімкнений — тримаю готову їжу до "
                            + DayLabels.dayAndMonth(profile.getSpecialModeExpiresAt())
                            + ". Скажи «вже не запара», якщо повертаємось раніше.");
            return;
        }
        if (isActive(profile)) {
            telegramOutboundService.sendMessage(
                    user.getTelegramChatId(),
                    "У тебе вже активний інший режим харчування. Спершу заверши його — напиши «повертаємось до звичайного раціону».");
            return;
        }
        if (profile.getCookingTimePreference() == CookingTimePreference.READY_MEALS_ONLY) {
            // Nothing to override. Switching the mode on anyway would cost a plan regeneration and, a week later,
            // an announcement that we are "going back to normal" — to a household that never left it.
            telegramOutboundService.sendMessage(
                    user.getTelegramChatId(),
                    "Ти й так на готовій їжі — план уже без готування, нічого міняти не треба.");
            return;
        }
        Instant now = clock.instant();
        profile.setSpecialMode(SpecialMode.CRUNCH_WEEK);
        profile.setSpecialModeStartedAt(now);
        profile.setSpecialModeExpiresAt(now.plus(specialModeProperties.crunchWeekDuration()));
        userProfileRepository.save(profile);
        log.info(
                "user {} entered CRUNCH_WEEK, expires {} (stored cooking preference {} untouched)",
                user.getId(),
                profile.getSpecialModeExpiresAt(),
                profile.getCookingTimePreference());
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                "Зрозумів, запара. До " + DayLabels.dayAndMonth(profile.getSpecialModeExpiresAt())
                        + " беру тільки готову їжу — нічого готувати не доведеться. Потім поверну як було, "
                        + "твої налаштування я не чіпаю.");
        regenerateAndPresent(user);
    }

    private static final String STEP_ASK_WEIGHT = "ASK_WEIGHT";
    private static final String STEP_ASK_CALORIES = "ASK_CALORIES";
    private static final String STEP_ASK_PROTEIN = "ASK_PROTEIN";
    private static final String KEY_WEIGHT = "weightKg";
    private static final String KEY_CALORIES = "targetCalories";

    public void startMassGainSetup(User user) {
        UserProfile profile = requireProfile(user);
        if (isActive(profile)) {
            telegramOutboundService.sendMessage(
                    user.getTelegramChatId(),
                    "У тебе вже активний інший режим харчування. Спершу заверши його — напиши «повертаємось до звичайного раціону».");
            return;
        }
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.SPECIAL_MODE_SETUP, STEP_ASK_WEIGHT, Map.of());
        telegramOutboundService.sendMessage(user.getTelegramChatId(), "Набір маси. Яка зараз вага, кг?");
    }

    /** Everything a chat sitting in {@link ConversationFlow#SPECIAL_MODE_SETUP} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = incoming.chatId();
        if (!(incoming instanceof TelegramIncomingUpdate.Text text)) {
            telegramOutboundService.sendMessage(chatId, "Напиши, будь ласка, число.");
            return;
        }
        ConversationState state = conversationStateService.load(chatId);
        BigDecimal number;
        try {
            number = new BigDecimal(text.text().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            telegramOutboundService.sendMessage(chatId, "Не зрозумів число, спробуй ще раз.");
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());
        switch (state.getCurrentStep()) {
            case STEP_ASK_WEIGHT -> {
                context.put(KEY_WEIGHT, number.toPlainString());
                conversationStateService.save(chatId, ConversationFlow.SPECIAL_MODE_SETUP, STEP_ASK_CALORIES, context);
                telegramOutboundService.sendMessage(chatId, "Скільки калорій на день — ціль?");
            }
            case STEP_ASK_CALORIES -> {
                context.put(KEY_CALORIES, number.intValue());
                conversationStateService.save(chatId, ConversationFlow.SPECIAL_MODE_SETUP, STEP_ASK_PROTEIN, context);
                telegramOutboundService.sendMessage(chatId, "Скільки грамів білка на день — ціль?");
            }
            case STEP_ASK_PROTEIN -> {
                conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
                finishMassGainSetup(user, context, number.intValue());
            }
            default -> telegramOutboundService.sendMessage(chatId, "Напиши «хочу набрати масу», щоб почати заново.");
        }
    }

    private void finishMassGainSetup(User user, Map<String, Object> context, int targetProteinG) {
        UserProfile profile = requireProfile(user);
        Instant now = clock.instant();
        profile.setSpecialMode(SpecialMode.MASS_GAIN);
        profile.setSpecialModeStartedAt(now);
        profile.setTargetWeightKg(new BigDecimal(context.get(KEY_WEIGHT).toString()));
        profile.setTargetCalories(Integer.parseInt(context.get(KEY_CALORIES).toString()));
        profile.setTargetProteinG(targetProteinG);
        userProfileRepository.save(profile);
        log.info("user {} entered MASS_GAIN", user.getId());
        telegramOutboundService.sendMessage(user.getTelegramChatId(), "Готую план для набору маси.");
        regenerateAndPresent(user);
    }

    public void cancel(User user) {
        UserProfile profile = requireProfile(user);
        if (!isActive(profile)) {
            telegramOutboundService.sendMessage(user.getTelegramChatId(), "Звичайний режим і так активний.");
            return;
        }
        SpecialMode ending = profile.getSpecialMode();
        revertToNormal(user, profile);
        telegramOutboundService.sendMessage(user.getTelegramChatId(), endedEarlyText(ending));
        regenerateAndPresent(user);
    }

    /**
     * What ending a mode ahead of time is called, in the words of the mode being ended (task 67).
     *
     * <p>«Повернув звичайний раціон» is right for a diet and wrong for a crunch week: nobody changed their
     * раціон, they ran out of time to cook. Saying it back the way it was said is how a person can tell the bot
     * understood which thing just ended.
     */
    private static String endedEarlyText(SpecialMode ending) {
        return ending == SpecialMode.CRUNCH_WEEK
                ? "Добре, запара позаду — повертаю звичайний режим готування, як у твоїй анкеті."
                : "Повернув звичайний раціон — складаю новий план.";
    }

    /** The same, for a mode that ran its full course and expired on its own. */
    private static String expiredText(SpecialMode ended) {
        return ended == SpecialMode.CRUNCH_WEEK
                ? "Тиждень запари закінчився — повертаємось до звичайного режиму готування."
                : "Два тижні дієтичного харчування завершено, повертаємось до звичайного раціону.";
    }

    @Transactional
    public void toggleUaOnly(User user) {
        UserProfile profile = requireProfile(user);
        setUaOnly(user, !Boolean.TRUE.equals(profile.getOnlyUaProducer()));
    }

    /**
     * Sets the preference to what the sentence asked for, rather than flipping it: «шукай тільки українського
     * виробника» said twice used to switch the flag off and then on again, and the first answer was «Прибрав
     * обмеження» to a person who had just asked for one.
     */
    @Transactional
    public void setUaOnly(User user, boolean on) {
        UserProfile profile = requireProfile(user);
        boolean already = on == Boolean.TRUE.equals(profile.getOnlyUaProducer());
        profile.setOnlyUaProducer(on);
        userProfileRepository.save(profile);
        String text;
        if (on) {
            text = already
                    ? "Уже шукаю переважно товари українського виробництва."
                    : "Тепер шукатиму переважно товари українського виробництва.";
        } else {
            text = already
                    ? "Обмеження на українського виробника й так немає."
                    : "Прибрав обмеження на українського виробника.";
        }
        telegramOutboundService.sendMessage(user.getTelegramChatId(), text);
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
                        SpecialMode ended = profile.getSpecialMode();
                        revertToNormal(user, profile);
                        telegramOutboundService.sendMessage(user.getTelegramChatId(), expiredText(ended));
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
        List<ShoppingListItem> previousItems = shoppingListService.currentItems(user.getId());
        MealPlan plan = mealPlanService.regenerateWithAdjustment(user.getId(), null);
        List<ShoppingListItem> items = shoppingListService.deriveFromMealPlan(plan.getId(), plan.getSourceType());
        shoppingListBuilderService.presentRegenerated(user, items, previousItems);
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
