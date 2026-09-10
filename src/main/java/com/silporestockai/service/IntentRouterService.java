package com.silporestockai.service;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.client.stt.SpeechToTextClient;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.OrderTrigger;
import com.silporestockai.service.telegram.HelpContent;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * The chat-first control surface (task 31): free text that reaches here (no active conversation flow, no
 * matching slash command) is classified into one of a fixed set of intents and dispatched to whichever
 * service already owns that capability. Classification only — no business logic duplicated from the
 * services it dispatches to.
 *
 * <p>Wired in additively, next to the existing slash commands, not instead of them — see
 * {@code docs/OVERNIGHT_QUESTIONS.md}'s "Task 31" entry for why a destructive rewrite of an
 * already-tested command surface was the wrong call for an unsupervised overnight session.
 */
@Slf4j
@Service
public class IntentRouterService {

    private static final double CONFIDENCE_THRESHOLD = 0.6;

    private final ClaudeApiClient claudeApiClient;
    private final SpeechToTextClient speechToTextClient;
    private final AdHocScheduleService adHocScheduleService;
    private final AdHocOrderService adHocOrderService;
    private final SpecialModeService specialModeService;
    private final BlackoutModeService blackoutModeService;
    private final MealPlanService mealPlanService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final CalendarViewService calendarViewService;
    private final CalendarIntegrationService calendarIntegrationService;
    private final ReorderConfirmationService reorderConfirmationService;
    private final TelegramOutboundService telegramOutboundService;
    private final PastOrderSeedService pastOrderSeedService;
    private final OrderHistoryService orderHistoryService;
    private final DishRequestService dishRequestService;
    private final LoyaltyBenefitsService loyaltyBenefitsService;
    private final ObservabilityService observabilityService;
    private final String systemPrompt;

    public IntentRouterService(
            ClaudeApiClient claudeApiClient,
            SpeechToTextClient speechToTextClient,
            AdHocScheduleService adHocScheduleService,
            AdHocOrderService adHocOrderService,
            SpecialModeService specialModeService,
            BlackoutModeService blackoutModeService,
            MealPlanService mealPlanService,
            ShoppingListService shoppingListService,
            ShoppingListBuilderService shoppingListBuilderService,
            CalendarViewService calendarViewService,
            CalendarIntegrationService calendarIntegrationService,
            ReorderConfirmationService reorderConfirmationService,
            TelegramOutboundService telegramOutboundService,
            PastOrderSeedService pastOrderSeedService,
            OrderHistoryService orderHistoryService,
            DishRequestService dishRequestService,
            LoyaltyBenefitsService loyaltyBenefitsService,
            ObservabilityService observabilityService,
            @Value("classpath:prompts/intent-router-system.txt") Resource systemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.speechToTextClient = speechToTextClient;
        this.adHocScheduleService = adHocScheduleService;
        this.adHocOrderService = adHocOrderService;
        this.specialModeService = specialModeService;
        this.blackoutModeService = blackoutModeService;
        this.mealPlanService = mealPlanService;
        this.shoppingListService = shoppingListService;
        this.shoppingListBuilderService = shoppingListBuilderService;
        this.calendarViewService = calendarViewService;
        this.calendarIntegrationService = calendarIntegrationService;
        this.reorderConfirmationService = reorderConfirmationService;
        this.telegramOutboundService = telegramOutboundService;
        this.pastOrderSeedService = pastOrderSeedService;
        this.orderHistoryService = orderHistoryService;
        this.dishRequestService = dishRequestService;
        this.loyaltyBenefitsService = loyaltyBenefitsService;
        this.observabilityService = observabilityService;
        this.systemPrompt = read(systemPromptResource);
    }

    /** The static "❓ Інструкція" content — a persistent-menu button, so it never needs a classification call. */
    public void sendHelp(User user) {
        telegramOutboundService.sendMessage(user.getTelegramChatId(), HelpContent.FULL);
    }

    /** Whether a voice note can be routed at all — decides what the routing layer says to one when it cannot. */
    public boolean voiceSupported() {
        return speechToTextClient.isConfigured();
    }

    /**
     * A voice note is the same request as the typed sentence — the product brief puts text and voice on equal
     * footing for every flow, not just check-ins. Transcribe, then route exactly as text. A transcription failure
     * gets the same clarifying question as an unclassifiable sentence: there is nothing else to do with it.
     */
    public void routeVoice(User user, byte[] audio) {
        String transcript;
        try {
            transcript = speechToTextClient.transcribe(audio, "voice.ogg");
        } catch (RuntimeException e) {
            log.warn("could not transcribe a voice note for user {}", user.getId(), e);
            askClarifyingQuestion(user);
            return;
        }
        log.info("voice note from user {} transcribed, routing as text", user.getId());
        route(user, transcript);
    }

    /**
     * A photo with a caption (task 36): the caption says what the picture is for. «Замов усе для цього» under a
     * plated dish goes to dish identification; any other caption — «ось мій холодильник» — keeps the photo on the
     * list builder, which is what a bare photo has always meant.
     */
    public void routePhoto(User user, String caption, byte[] image, String mediaType) {
        ClassifiedIntent classified;
        try {
            classified = claudeApiClient.completeStructured(systemPrompt, caption, ClassifiedIntent.class);
        } catch (RuntimeException e) {
            log.warn("could not classify a photo caption; treating the photo as a list input", e);
            classified = null;
        }
        if (classified != null
                && parse(classified.intent()) == IntentType.DISH_INGREDIENTS_ORDER
                && classified.confidence() >= CONFIDENCE_THRESHOLD) {
            log.info("user {} sent a dish photo (caption classified as DISH_INGREDIENTS_ORDER)", user.getId());
            dishRequestService.startFromPhoto(user, image, mediaType);
            return;
        }
        shoppingListBuilderService.buildAndShow(user, caption, image);
    }

    public void route(User user, String text) {
        if (!tryRoute(user, text)) {
            askClarifyingQuestion(user);
        }
    }

    /**
     * Classifies and dispatches, and says whether it recognised anything — without the clarifying question, so a
     * flow that owns the chat can decide what to say when the answer was not for it.
     *
     * <p>A check-in prompt sits open until it is answered, and on a live account that meant «замов сир з вином»
     * typed while one was open came back as «Не розібрав. Скажи коротко по цих: Хек Norven…». The check-in now
     * asks here first; a confident intent wins, and the fridge question waits for the next sweep.
     */
    public boolean tryRoute(User user, String text) {
        return tryRoute(user, text, java.util.Set.of());
    }

    /**
     * {@link #tryRoute(User, String)}, except that an intent named in {@code unlessIntents} counts as «not for
     * me». The open «Що беремо на цей тиждень?» question uses this: a sentence the classifier reads as a list
     * request or a list edit is exactly the description that question asked for, so it goes to the list builder
     * rather than looping back through the router.
     */
    public boolean tryRoute(User user, String text, java.util.Set<String> unlessIntents) {
        // The clock for intent→order speed (task 75) starts here, before the classification call, so the number
        // includes the thinking and not just the shopping.
        Instant receivedAt = Instant.now();
        ClassifiedIntent classified;
        try {
            classified = claudeApiClient.completeStructured(systemPrompt, withToday(text), ClassifiedIntent.class);
        } catch (RuntimeException e) {
            log.warn("could not classify intent for text", e);
            observabilityService.recordIntent("failed");
            return false;
        }
        IntentType intent = parse(classified.intent());
        if (intent == IntentType.UNKNOWN || classified.confidence() < CONFIDENCE_THRESHOLD) {
            // Deliberately no per-intent tag: which intents fire is task 55's artefact, a plain list rather than a
            // Grafana panel, and keeping IntentType private is what stops the two from drifting into each other.
            observabilityService.recordIntent("unclassified");
            return false;
        }
        if (unlessIntents.contains(intent.name())) {
            return false;
        }
        observabilityService.recordIntent("routed");
        dispatch(user, text, classified, intent, receivedAt);
        return true;
    }

    /** The ways a person says «stop preferring Ukrainian producers»; anything else under the intent turns it on. */
    private static final java.util.regex.Pattern DROP_UA_ONLY = java.util.regex.Pattern.compile(
            "не (тільки|лише|обов'язково)|будь-як|прибери|зніми|скасуй|вимкни|без обмеж|не важлив|неважлив|байдуже",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static boolean asksToDropUaOnly(String text) {
        return text != null && DROP_UA_ONLY.matcher(text).find();
    }

    private void dispatch(User user, String text, ClassifiedIntent classified, IntentType intent, Instant receivedAt) {
        log.info("user {} classified as {} (confidence {})", user.getId(), intent, classified.confidence());
        OrderTrigger trigger = OrderTrigger.of(intent.name(), receivedAt);
        switch (intent) {
            case AD_HOC_SCHEDULED_PURCHASE -> scheduleAdHoc(user, classified);
            case SPECIAL_MODE_MEDICAL_GASTRITIS -> specialModeService.triggerGastritis(user);
            // The person's own sentence goes to the planner, not a paraphrase of it: «мінус 200 ккал на день»
            // carries a number the plan should respect, and a fixed "make it leaner" would drop it.
            case SPECIAL_MODE_LEANER ->
                adjustPlan(user, "Зроби раціон менш калорійним. Людина написала: «" + text + "».");
            case SPECIAL_MODE_END -> specialModeService.cancel(user);
            case REORDER -> reorderConfirmationService.startNow(user, trigger);
            case SPECIAL_MODE_MASS_GAIN -> {
                telegramOutboundService.sendMessage(
                        user.getTelegramChatId(),
                        // Not "можу підказати, якщо цікаво" — nothing handled "цікаво", so the offer was a dead
                        // end. LIST_MODIFY does handle «додай протеїн», so point there.
                        "Під набір маси часто беруть протеїн або гейнер. Коли список буде готовий, напиши "
                                + "«додай протеїн» — додам.");
                specialModeService.startMassGainSetup(user);
            }
            // One intent for both directions; the sentence says which. Setting rather than toggling: the same
            // request twice must not undo itself.
            case FILTER_UA_PRODUCER_ONLY -> specialModeService.setUaOnly(user, !asksToDropUaOnly(text));
            case HANGOVER_RELIEF -> adHocOrderService.buildHangoverReliefOrder(user, text, trigger);
            case BLACKOUT -> {
                telegramOutboundService.sendMessage(
                        user.getTelegramChatId(), "Збираю щось на поїсти без плити й холодильника.");
                blackoutModeService.buildBlackoutOrder(user, trigger);
            }
            case LIST_VIEW -> shoppingListBuilderService.showCurrentOrAsk(user);
            // Straight into the edit, skipping the "Що беремо на цей тиждень?" opener — the person already
            // said what to change; asking them to say it again is the failure mode task 31 was built to remove.
            case LIST_MODIFY ->
                shoppingListBuilderService.buildAndShow(user, "Поточний список треба змінити так: " + text, null);
            // «Що їмо в середу?» opens Wednesday; a day-less «покажи календар» opens the picker.
            case CALENDAR_VIEW ->
                dayOf(classified.themeDescription())
                        .ifPresentOrElse(
                                day -> calendarViewService.showDay(user, day.name()),
                                () -> calendarViewService.showWeek(user));
            case CALENDAR_CONNECT -> calendarIntegrationService.offerConnection(user);
            case PAST_ORDER_SEED -> pastOrderSeedService.offer(user);
            // Read-only, and the short form: the sentence asked about one order, so answer about that one.
            // The «📦 Замовлення» button (task 57) asks the same service for the fuller view.
            case WHERE_IS_MY_ORDER -> orderHistoryService.showStatus(user, false);
            case DISH_INGREDIENTS_ORDER -> dishRequestService.start(user, classified.themeDescription(), trigger);
            // Read-only, and honest about the split: what the bot applies at checkout versus what only the
            // Silpo app can do, because the live API has no action for coupons, promos or Premium (task 79).
            case MY_BENEFITS -> loyaltyBenefitsService.showOverview(user);
            case HELP -> sendHelp(user);
            case UNKNOWN -> askClarifyingQuestion(user);
        }
    }

    /**
     * The message with today's date in front of it. «До п'ятниці», «завтра» and «що їмо в середу» all need to
     * know what day it is, and the model does not.
     */
    private static String withToday(String text) {
        java.time.LocalDate today = java.time.LocalDate.now(KYIV);
        return "Сьогодні: %s, %s.\nПовідомлення: %s"
                .formatted(
                        today.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, UKRAINIAN), today, text);
    }

    private static final java.time.ZoneId KYIV = java.time.ZoneId.of("Europe/Kyiv");
    private static final Locale UKRAINIAN = Locale.forLanguageTag("uk");

    /** A day the classifier named for CALENDAR_VIEW, as the enum the calendar view is keyed by; empty otherwise. */
    private static java.util.Optional<java.time.DayOfWeek> dayOf(String themeDescription) {
        if (themeDescription == null || themeDescription.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(
                    java.time.DayOfWeek.valueOf(themeDescription.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    private void scheduleAdHoc(User user, ClassifiedIntent classified) {
        String theme = classified.themeDescription() == null
                        || classified.themeDescription().isBlank()
                ? "щось смачне"
                : classified.themeDescription();
        // classified.targetDateTimeIso() is a deadline ("до п'ятниці"), not a desired start time — a live
        // test caught this firing literally on Friday instead of right away. Waiting until near a deadline
        // only delays a purchase that could just as well happen now, and "as soon as possible" is always at
        // or before any deadline that matters — see docs/OVERNIGHT_QUESTIONS.md's follow-up entry. Fire on
        // the very next sweep instead of trusting the extracted date as a trigger time.
        adHocScheduleService.schedule(user, theme, Instant.now());
    }

    private void adjustPlan(User user, String instruction) {
        // The regenerate is a 20-30 s model call; live, «зроби менш калорійним» was answered by nothing at all
        // until the new list appeared, which reads as a dead bot on a recording.
        telegramOutboundService.sendMessage(user.getTelegramChatId(), "Перероблю план — хвилинку.");
        List<ShoppingListItem> previousItems = shoppingListService.currentItems(user.getId());
        MealPlan plan = mealPlanService.regenerateWithAdjustment(user.getId(), instruction);
        List<ShoppingListItem> items = shoppingListService.deriveFromMealPlan(plan.getId(), plan.getSourceType());
        shoppingListBuilderService.presentRegenerated(user, items, previousItems);
    }

    private void askClarifyingQuestion(User user) {
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                "Не зовсім зрозумів. Напиши інакше — або натисни «Інструкція», там приклади того, що я вмію.");
    }

    private static IntentType parse(String raw) {
        if (raw == null) {
            return IntentType.UNKNOWN;
        }
        try {
            return IntentType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return IntentType.UNKNOWN;
        }
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the intent router system prompt", e);
        }
    }

    private enum IntentType {
        AD_HOC_SCHEDULED_PURCHASE,
        REORDER,
        SPECIAL_MODE_MEDICAL_GASTRITIS,
        SPECIAL_MODE_LEANER,
        SPECIAL_MODE_MASS_GAIN,
        SPECIAL_MODE_END,
        FILTER_UA_PRODUCER_ONLY,
        HANGOVER_RELIEF,
        BLACKOUT,
        LIST_VIEW,
        LIST_MODIFY,
        CALENDAR_VIEW,
        CALENDAR_CONNECT,
        PAST_ORDER_SEED,
        WHERE_IS_MY_ORDER,
        DISH_INGREDIENTS_ORDER,
        MY_BENEFITS,
        HELP,
        UNKNOWN
    }

    private record ClassifiedIntent(
            String intent, double confidence, String themeDescription, String targetDateTimeIso) {}
}
