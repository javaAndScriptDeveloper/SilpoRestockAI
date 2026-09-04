package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.User;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The "Календар" view (task 30): the current week's meal plan broken down by day, day names as tappable
 * buttons — never the ingredient list, which belongs to the Список flow (task 20's List/Calendar
 * separation).
 *
 * <p>Stateless on purpose: every tap re-reads {@link MealPlanRepository}'s latest row for the user rather
 * than trusting anything cached in {@code conversation_state}. A special mode's regenerated plan or a
 * READY_MEALS_ONLY plan's ready-meal names both render the same way — both already converge on the same
 * {@code {"days":[{"day":..., "meals":[{"type":..., "name":...}]}]}} shape (task 22), so this view needs no
 * source-specific branching at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarViewService {

    public static final String CALLBACK_DAY_PREFIX = "cal:";

    private static final Map<String, String> DAY_LABELS = new LinkedHashMap<>();

    static {
        DAY_LABELS.put("MONDAY", "Пн");
        DAY_LABELS.put("TUESDAY", "Вт");
        DAY_LABELS.put("WEDNESDAY", "Ср");
        DAY_LABELS.put("THURSDAY", "Чт");
        DAY_LABELS.put("FRIDAY", "Пт");
        DAY_LABELS.put("SATURDAY", "Сб");
        DAY_LABELS.put("SUNDAY", "Нд");
    }

    private static final Map<String, String> MEAL_LABELS =
            Map.of("BREAKFAST", "🍳 Сніданок", "LUNCH", "🍲 Обід", "DINNER", "🍽 Вечеря");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MealPlanRepository mealPlanRepository;
    private final TelegramOutboundService telegramOutboundService;

    /** The day-selector: one button per day, no meal content yet. */
    public void showWeek(User user) {
        long chatId = user.getTelegramChatId();
        if (currentPlan(user).isEmpty()) {
            telegramOutboundService.sendMessage(chatId, "Плану на цей тиждень ще немає — спершу згенерую раціон.");
            return;
        }
        telegramOutboundService.sendMessageWithButtons(chatId, "Раціон на тиждень. Обери день:", dayButtons());
    }

    /** One day's meals — names only, never ingredients. */
    public void showDay(User user, String dayName) {
        long chatId = user.getTelegramChatId();
        Map<String, Object> plan = currentPlan(user).orElse(null);
        if (plan == null) {
            telegramOutboundService.sendMessage(chatId, "Плану на цей тиждень ще немає — спершу згенерую раціон.");
            return;
        }
        JsonNode days = MAPPER.valueToTree(plan).path("days");
        for (JsonNode day : days) {
            if (!dayName.equalsIgnoreCase(day.path("day").asText())) {
                continue;
            }
            telegramOutboundService.sendMessageWithButtons(chatId, dayText(dayName, day), dayButtons());
            return;
        }
        telegramOutboundService.sendMessage(chatId, "Не знайшов цей день у поточному плані.");
    }

    private String dayText(String dayName, JsonNode day) {
        StringBuilder text = new StringBuilder(DAY_LABELS.getOrDefault(dayName, dayName)).append(":\n");
        for (JsonNode meal : day.path("meals")) {
            String type = meal.path("type").asText();
            text.append('\n')
                    .append(MEAL_LABELS.getOrDefault(type, type))
                    .append(": ")
                    .append(meal.path("name").asText());
        }
        return text.toString();
    }

    private List<TelegramButton> dayButtons() {
        return DAY_LABELS.entrySet().stream()
                .map(entry -> TelegramButton.callback(entry.getValue(), CALLBACK_DAY_PREFIX + entry.getKey()))
                .toList();
    }

    private Optional<Map<String, Object>> currentPlan(User user) {
        return mealPlanRepository
                .findFirstByUserIdOrderByWeekStartDateDesc(user.getId())
                .map(MealPlan::getPlan);
    }
}
