package com.silporestockai.service;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

    private static final String HELP_TEXT = """
            Ось що можна написати мені звичайним текстом:

            — «Список» — показати поточний список покупок.
            — «Закажи до п'ятниці вино та сир по знижці» — разове замовлення на конкретний час.
            — «Я захворів, гастрит» — тимчасово перемкнутись на щадне харчування.
            — «Зроби менш калорійним» — зменшити калорійність поточного плану.
            — «Хочу набрати масу» / «більше протеїну» — почати набір маси.
            — «Шукай тільки український виробник» — фільтрувати товари за походженням.
            — «Голова після вчорашнього» — швидке замовлення регідратації й сорбентів.""";

    private final ClaudeApiClient claudeApiClient;
    private final AdHocScheduleService adHocScheduleService;
    private final AdHocOrderService adHocOrderService;
    private final SpecialModeService specialModeService;
    private final MealPlanService mealPlanService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final TelegramOutboundService telegramOutboundService;
    private final String systemPrompt;

    public IntentRouterService(
            ClaudeApiClient claudeApiClient,
            AdHocScheduleService adHocScheduleService,
            AdHocOrderService adHocOrderService,
            SpecialModeService specialModeService,
            MealPlanService mealPlanService,
            ShoppingListService shoppingListService,
            ShoppingListBuilderService shoppingListBuilderService,
            TelegramOutboundService telegramOutboundService,
            @Value("classpath:prompts/intent-router-system.txt") Resource systemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.adHocScheduleService = adHocScheduleService;
        this.adHocOrderService = adHocOrderService;
        this.specialModeService = specialModeService;
        this.mealPlanService = mealPlanService;
        this.shoppingListService = shoppingListService;
        this.shoppingListBuilderService = shoppingListBuilderService;
        this.telegramOutboundService = telegramOutboundService;
        this.systemPrompt = read(systemPromptResource);
    }

    public void route(User user, String text) {
        ClassifiedIntent classified;
        try {
            classified = claudeApiClient.completeStructured(systemPrompt, text, ClassifiedIntent.class);
        } catch (RuntimeException e) {
            log.warn("could not classify intent for text, asking a clarifying question", e);
            askClarifyingQuestion(user);
            return;
        }
        IntentType intent = parse(classified.intent());
        if (intent == IntentType.UNKNOWN || classified.confidence() < CONFIDENCE_THRESHOLD) {
            askClarifyingQuestion(user);
            return;
        }
        log.info("user {} classified as {} (confidence {})", user.getId(), intent, classified.confidence());
        switch (intent) {
            case AD_HOC_SCHEDULED_PURCHASE -> scheduleAdHoc(user, classified);
            case SPECIAL_MODE_MEDICAL_GASTRITIS -> specialModeService.triggerGastritis(user);
            case SPECIAL_MODE_LEANER -> adjustPlan(user, "Зроби раціон менш калорійним.");
            case SPECIAL_MODE_MASS_GAIN -> {
                telegramOutboundService.sendMessage(
                        user.getTelegramChatId(),
                        "До речі, для набору маси часто беруть протеїн або гейнер — можу підказати, якщо цікаво.");
                specialModeService.startMassGainSetup(user);
            }
            case FILTER_UA_PRODUCER_ONLY -> specialModeService.toggleUaOnly(user);
            case HANGOVER_RELIEF -> adHocOrderService.buildHangoverReliefOrder(user);
            case LIST_VIEW -> shoppingListBuilderService.askForInput(user);
            case HELP -> telegramOutboundService.sendMessage(user.getTelegramChatId(), HELP_TEXT);
            case UNKNOWN -> askClarifyingQuestion(user);
        }
    }

    private void scheduleAdHoc(User user, ClassifiedIntent classified) {
        Instant triggerAt = parseTriggerAt(classified.targetDateTimeIso());
        String theme = classified.themeDescription() == null
                        || classified.themeDescription().isBlank()
                ? "щось смачне"
                : classified.themeDescription();
        adHocScheduleService.schedule(user, theme, triggerAt);
    }

    private void adjustPlan(User user, String instruction) {
        MealPlan plan = mealPlanService.regenerateWithAdjustment(user.getId(), instruction);
        List<ShoppingListItem> items = shoppingListService.deriveFromMealPlan(plan.getId(), plan.getSourceType());
        shoppingListBuilderService.present(user, items);
    }

    private void askClarifyingQuestion(User user) {
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                "Не зовсім зрозумів. Напиши, будь ласка, інакше, або напиши «Інструкція», щоб побачити приклади.");
    }

    /** No extractable time defaults to "soon" — one hour out, so the sweep picks it up on its next pass. */
    private static Instant parseTriggerAt(String iso) {
        if (iso == null || iso.isBlank()) {
            return Instant.now().plusSeconds(3600);
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return Instant.now().plusSeconds(3600);
        }
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
        SPECIAL_MODE_MEDICAL_GASTRITIS,
        SPECIAL_MODE_LEANER,
        SPECIAL_MODE_MASS_GAIN,
        FILTER_UA_PRODUCER_ONLY,
        HANGOVER_RELIEF,
        LIST_VIEW,
        HELP,
        UNKNOWN
    }

    private record ClassifiedIntent(
            String intent, double confidence, String themeDescription, String targetDateTimeIso) {}
}
