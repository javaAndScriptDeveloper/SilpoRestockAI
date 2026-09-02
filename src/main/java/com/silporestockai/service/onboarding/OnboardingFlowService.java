package com.silporestockai.service.onboarding;

import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OnboardingCompletedEvent;
import com.silporestockai.model.OnboardingStep;
import com.silporestockai.model.SilpoConnectedEvent;
import com.silporestockai.model.SilpoProfileSnapshot;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.SilpoAuthService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Walks a new user from their first message to a saved profile.
 *
 * <p>Telegram delivers every update as an independent request, so the whole conversation lives in
 * {@code conversation_state}: {@code current_step} names where it is, {@code context_json} accumulates the answers. A
 * user who goes silent for an hour resumes where they stopped.
 *
 * <p>Questions the Silpo profile already answered are skipped. The budget is always asked, because MCP cannot know
 * what someone intends to spend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingFlowService {

    public static final String CALLBACK_CONNECTED = "onb:connected";
    public static final String CALLBACK_SKIP = "onb:skip";
    public static final String CALLBACK_CONFIRM = "onb:confirm";
    public static final String CALLBACK_CORRECT = "onb:correct";

    private static final String KEY_HOUSEHOLD = "householdSize";
    private static final String KEY_HAS_KIDS = "hasKids";
    private static final String KEY_KIDS_AGES = "kidsAges";
    private static final String KEY_RESTRICTIONS = "dietaryRestrictions";
    private static final String KEY_DISLIKES = "dislikedFoods";
    private static final String KEY_BUDGET = "weeklyBudget";

    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

    /**
     * A thousands suffix immediately after the number: «3к», «3 тис», «3 тисячі».
     *
     * <p>Without this «3к» parsed as three, and the household got a weekly budget of three hryvnia — which the meal
     * planner then dutifully tried to respect.
     */
    private static final Pattern THOUSANDS = Pattern.compile("\\d[\\d.,]*\\s*(к|k|тис|тыс)", Pattern.CASE_INSENSITIVE);

    /** Enough Ukrainian numerals to cover a realistic answer to "how many of you are there". */
    private static final Map<String, Integer> WORD_NUMBERS = Map.ofEntries(
            Map.entry("один", 1),
            Map.entry("одна", 1),
            Map.entry("двоє", 2),
            Map.entry("два", 2),
            Map.entry("дві", 2),
            Map.entry("троє", 3),
            Map.entry("три", 3),
            Map.entry("четверо", 4),
            Map.entry("чотири", 4),
            Map.entry("п'ятеро", 5),
            Map.entry("п'ять", 5),
            Map.entry("шестеро", 6),
            Map.entry("шість", 6),
            Map.entry("семеро", 7),
            Map.entry("сім", 7));

    private final UserProfileRepository userProfileRepository;
    private final UserRepository userRepository;
    private final ConversationStateService conversationStateService;
    private final ProfileEnrichmentService profileEnrichmentService;
    private final TelegramOutboundService telegramOutboundService;
    private final SilpoAuthService silpoAuthService;
    private final ApplicationEventPublisher events;

    /**
     * Carries the conversation on after the guest finishes the Silpo login in their browser.
     *
     * <p>Asynchronous because enrichment calls four Silpo tools and then a model, and the thread this arrives on is
     * answering the guest's browser. The listener does nothing but leave that thread; everything it would otherwise
     * do lives in {@link #resumeAfterSilpoConnect(UUID)}, which a test can call directly.
     */
    @Async("applicationTaskExecutor")
    @EventListener
    public void onSilpoConnected(SilpoConnectedEvent event) {
        resumeAfterSilpoConnect(event.userId());
    }

    /** Picks the conversation back up at the step the connect button left it on. Runs on the caller's thread. */
    public void resumeAfterSilpoConnect(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            long chatId = user.getTelegramChatId();
            ConversationState state = conversationStateService.load(chatId);
            if (state.getCurrentFlow() != ConversationFlow.ONBOARDING
                    || !OnboardingStep.AWAITING_CONNECT.name().equals(state.getCurrentStep())) {
                // Connected again later, from the settings rather than mid-onboarding. Nothing to resume.
                log.debug("user {} connected Silpo outside onboarding; not resuming a conversation", userId);
                return;
            }
            enrichThenConfirm(user, chatId, new LinkedHashMap<>(state.getContext()));
        });
    }

    public boolean isOnboarded(UUID userId) {
        return userProfileRepository.findByUserId(userId).isPresent();
    }

    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = incoming.chatId();
        ConversationState state = conversationStateService.load(chatId);
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());

        if (state.getCurrentFlow() != ConversationFlow.ONBOARDING) {
            greet(user, chatId, context);
            return;
        }

        OnboardingStep step = OnboardingStep.valueOf(state.getCurrentStep());
        switch (incoming) {
            case TelegramIncomingUpdate.ButtonTap tap -> {
                telegramOutboundService.answerCallback(tap.callbackQueryId());
                handleButton(user, chatId, step, tap.data(), context);
            }
            case TelegramIncomingUpdate.Text text -> handleAnswer(user, chatId, step, text.text(), context);
            // Neither a voice note nor a photo answers "how many of you are there"; both get the same nudge.
            case TelegramIncomingUpdate.Voice ignored ->
                telegramOutboundService.sendMessage(chatId, "Голосові поки не розбираю. Напиши, будь ласка, текстом.");
            case TelegramIncomingUpdate.Photo ignored ->
                telegramOutboundService.sendMessage(chatId, "Фото тут не допоможе. Напиши, будь ласка, текстом.");
        }
    }

    private void greet(User user, long chatId, Map<String, Object> context) {
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                """
                Привіт. Я Комора — беру на себе тижневі закупи їжі.
                Під'єднай акаунт «Сільпо», і я візьму звідти склад сім'ї та обмеження, щоб не питати зайвого.""",
                List.of(
                        TelegramButton.link("Під'єднати Сільпо", silpoAuthService.buildAuthorizationUrl(user.getId())),
                        TelegramButton.callback("Пропустити", CALLBACK_SKIP)));
        save(chatId, OnboardingStep.AWAITING_CONNECT, context);
    }

    private void handleButton(User user, long chatId, OnboardingStep step, String data, Map<String, Object> context) {
        if (step == OnboardingStep.AWAITING_CONNECT && CALLBACK_SKIP.equals(data)) {
            askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user);
            return;
        }
        if (step == OnboardingStep.AWAITING_CONNECT) {
            enrichThenConfirm(user, chatId, context);
            return;
        }
        if (step == OnboardingStep.CONFIRM_PROFILE && CALLBACK_CONFIRM.equals(data)) {
            askNext(chatId, OnboardingStep.ASK_BUDGET, context, user);
            return;
        }
        if (step == OnboardingStep.CONFIRM_PROFILE && CALLBACK_CORRECT.equals(data)) {
            // Corrections walk the same question chain. Clearing the detected values first is what makes
            // every field genuinely overwritable — otherwise askNext would skip the questions it already
            // has answers for.
            context.remove(KEY_HOUSEHOLD);
            context.remove(KEY_RESTRICTIONS);
            context.remove(KEY_DISLIKES);
            askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user);
            return;
        }
        log.debug("ignoring callback {} at step {}", data, step);
    }

    private void enrichThenConfirm(User user, long chatId, Map<String, Object> context) {
        SilpoProfileSnapshot snapshot = profileEnrichmentService.enrich(user.getId());
        if (snapshot.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, "Нічого не знайшов у профілі «Сільпо». Запитаю сам.");
            askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user);
            return;
        }
        putIfPresent(context, KEY_HOUSEHOLD, snapshot.householdSize());
        putIfPresent(context, KEY_HAS_KIDS, snapshot.hasKids());
        putIfPresent(context, KEY_KIDS_AGES, snapshot.kidsAges());
        putIfPresent(context, KEY_RESTRICTIONS, snapshot.dietaryRestrictions());

        String found = describe(context);
        if (found.isBlank()) {
            // isEmpty() looked at fields this confirmation screen does not show — frequentItems, most likely — and
            // called the snapshot non-empty on their account alone. Nothing usable for a person to confirm reached
            // context, so this is the same outcome as an empty snapshot: say so, and ask instead of showing a
            // "Ось що знайшов:" with nothing under it.
            telegramOutboundService.sendMessage(chatId, "Нічого не знайшов у профілі «Сільпо». Запитаю сам.");
            askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user);
            return;
        }

        telegramOutboundService.sendMessageWithButtons(
                chatId,
                "Ось що знайшов:\n" + found + "\nВсе вірно?",
                List.of(
                        TelegramButton.callback("Все вірно", CALLBACK_CONFIRM),
                        TelegramButton.callback("Виправлю", CALLBACK_CORRECT)));
        save(chatId, OnboardingStep.CONFIRM_PROFILE, context);
    }

    private void handleAnswer(User user, long chatId, OnboardingStep step, String answer, Map<String, Object> context) {
        switch (step) {
            case ASK_HOUSEHOLD -> {
                Optional<Integer> size = parseCount(answer);
                if (size.isEmpty()) {
                    telegramOutboundService.sendMessage(chatId, "Не зрозумів. Напиши числом, скільки вас удома.");
                    return;
                }
                context.put(KEY_HOUSEHOLD, size.get());
                askNext(chatId, OnboardingStep.ASK_RESTRICTIONS, context, user);
            }
            case ASK_RESTRICTIONS -> {
                context.put(KEY_RESTRICTIONS, splitAnswer(answer));
                askNext(chatId, OnboardingStep.ASK_DISLIKES, context, user);
            }
            case ASK_DISLIKES -> {
                context.put(KEY_DISLIKES, splitAnswer(answer));
                askNext(chatId, OnboardingStep.ASK_BUDGET, context, user);
            }
            case ASK_BUDGET -> {
                Optional<BigDecimal> budget = parseAmount(answer);
                if (budget.isEmpty()) {
                    telegramOutboundService.sendMessage(chatId, "Не зрозумів суму. Напиши числом, наприклад 2500.");
                    return;
                }
                context.put(KEY_BUDGET, budget.get().toPlainString());
                finish(user, chatId, context);
            }
            default -> telegramOutboundService.sendMessage(chatId, "Скористайся, будь ласка, кнопками вище.");
        }
    }

    /** Moves to {@code step}, skipping any question the Silpo profile already answered. */
    private void askNext(long chatId, OnboardingStep step, Map<String, Object> context, User user) {
        OnboardingStep target = step;
        while (target != OnboardingStep.ASK_BUDGET && answered(context, target)) {
            target = following(target);
        }
        switch (target) {
            case ASK_HOUSEHOLD -> telegramOutboundService.sendMessage(chatId, "Скільки вас удома?");
            case ASK_RESTRICTIONS ->
                telegramOutboundService.sendMessage(
                        chatId, "Є алергії чи дієтичні обмеження? Якщо ні — напиши «нема».");
            case ASK_DISLIKES ->
                telegramOutboundService.sendMessage(chatId, "Що вдома точно не їдять? Якщо все їдять — напиши «нема».");
            case ASK_BUDGET -> telegramOutboundService.sendMessage(chatId, "Який бюджет на тиждень, у гривнях?");
            default -> {
                finish(user, chatId, context);
                return;
            }
        }
        save(chatId, target, context);
    }

    private static OnboardingStep following(OnboardingStep step) {
        return switch (step) {
            case ASK_HOUSEHOLD -> OnboardingStep.ASK_RESTRICTIONS;
            case ASK_RESTRICTIONS -> OnboardingStep.ASK_DISLIKES;
            default -> OnboardingStep.ASK_BUDGET;
        };
    }

    private static boolean answered(Map<String, Object> context, OnboardingStep step) {
        return switch (step) {
            case ASK_HOUSEHOLD -> context.get(KEY_HOUSEHOLD) != null;
            case ASK_RESTRICTIONS -> context.get(KEY_RESTRICTIONS) != null;
            case ASK_DISLIKES -> context.get(KEY_DISLIKES) != null;
            default -> false;
        };
    }

    private void finish(User user, long chatId, Map<String, Object> context) {
        UserProfile profile = userProfileRepository
                .findByUserId(user.getId())
                .orElseGet(() -> UserProfile.builder()
                        .id(UUID.randomUUID())
                        .userId(user.getId())
                        .build());
        profile.setHouseholdSize(intOf(context.get(KEY_HOUSEHOLD)));
        profile.setHasKids(context.get(KEY_HAS_KIDS) instanceof Boolean flag ? flag : null);
        profile.setKidsAges(intListOf(context.get(KEY_KIDS_AGES)));
        profile.setDietaryRestrictions(stringListOf(context.get(KEY_RESTRICTIONS)));
        profile.setDislikedFoods(stringListOf(context.get(KEY_DISLIKES)));
        profile.setWeeklyBudget(
                context.get(KEY_BUDGET) == null
                        ? null
                        : new BigDecimal(context.get(KEY_BUDGET).toString()));
        userProfileRepository.save(profile);

        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        telegramOutboundService.sendMessageWithMainMenu(chatId, "Записав. Готую перший план на тиждень.");
        events.publishEvent(new OnboardingCompletedEvent(user.getId()));
        log.info("onboarding completed for user {}", user.getId());
    }

    private void save(long chatId, OnboardingStep step, Map<String, Object> context) {
        conversationStateService.save(chatId, ConversationFlow.ONBOARDING, step.name(), context);
    }

    private static void putIfPresent(Map<String, Object> context, String key, Object value) {
        // A household size of zero is rejected upstream, in ProfileEnrichmentService, at the boundary where
        // untrusted model output enters the system — not here, where it would be one of several unrelated fields
        // passing through a generic helper.
        if (value != null && !(value instanceof List<?> list && list.isEmpty())) {
            context.put(key, value);
        }
    }

    private static String describe(Map<String, Object> context) {
        List<String> lines = new ArrayList<>();
        if (context.get(KEY_HOUSEHOLD) != null) {
            lines.add("— людей удома: " + context.get(KEY_HOUSEHOLD));
        }
        if (Boolean.TRUE.equals(context.get(KEY_HAS_KIDS))) {
            lines.add("— діти: " + String.valueOf(context.getOrDefault(KEY_KIDS_AGES, "є")));
        }
        if (context.get(KEY_RESTRICTIONS) != null) {
            lines.add("— обмеження: " + String.join(", ", stringListOf(context.get(KEY_RESTRICTIONS))));
        }
        return String.join("\n", lines);
    }

    private static Optional<Integer> parseCount(String answer) {
        Matcher matcher = FIRST_NUMBER.matcher(answer);
        if (matcher.find()) {
            return Optional.of(Integer.parseInt(matcher.group().split("[.,]")[0]));
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        return WORD_NUMBERS.entrySet().stream()
                .filter(entry -> lower.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** «2500», «2500 грн», «3к», «3 тис» — all of them are the amount somebody meant. */
    private static Optional<BigDecimal> parseAmount(String answer) {
        Matcher matcher = FIRST_NUMBER.matcher(answer);
        if (!matcher.find()) {
            return Optional.empty();
        }
        BigDecimal amount = new BigDecimal(matcher.group().replace(',', '.'));
        return Optional.of(THOUSANDS.matcher(answer).find() ? amount.multiply(new BigDecimal(1000)) : amount);
    }

    private static List<String> splitAnswer(String answer) {
        String trimmed = answer.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("нема") || lower.equals("немає") || lower.equals("ні")) {
            return List.of();
        }
        return List.of(trimmed.split("\\s*,\\s*"));
    }

    private static Integer intOf(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> intListOf(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        return ((List<Object>) list)
                .stream()
                        .map(item -> item instanceof Number number ? number.intValue() : null)
                        .filter(Objects::nonNull)
                        .toList();
    }

    private static List<String> stringListOf(Object value) {
        return value instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : null;
    }
}
