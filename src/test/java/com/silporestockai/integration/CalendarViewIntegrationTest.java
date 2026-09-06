package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CalendarViewService;
import com.silporestockai.service.MealPlanService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("the calendar view is a day-selectable read of the current plan, never the ingredient list")
class CalendarViewIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 16101L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CalendarViewService calendarViewService;

    @Autowired
    private MealPlanService mealPlanService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private MealPlanRepository mealPlanRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer("5050:stub-bot-token");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> "5050:stub-bot-token");
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        CLAUDE.reset();
        mealPlanRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
    }

    private static String fullWeekJson() {
        StringBuilder days = new StringBuilder();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            days.append("""
                    {"day":"%s","meals":[\
                    {"type":"BREAKFAST","name":"Вівсянка"},\
                    {"type":"LUNCH","name":"Курячий суп"},\
                    {"type":"DINNER","name":"Гречка з овочами"}]}""".formatted(day.name()));
        }
        return "{\"days\":[" + days + "]," + MealPlanIntegrationTest.shoppingListJson() + "}";
    }

    private void tapButton(int updateId, String data) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"callback_query":{"id":"cb-%d","chat_instance":"ci",\
                                "from":{"id":5,"is_bot":false,"first_name":"Тест"},"data":"%s",\
                                "message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}}}}""".formatted(updateId, updateId, data, updateId, CHAT_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void noPlanYetGivesAHonestMessageInsteadOfAnEmptyView() {
        calendarViewService.showWeek(user);

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("немає");
    }

    @Test
    void showWeekOffersSevenDayButtonsAndNoIngredients() {
        CLAUDE.respondWithText(fullWeekJson());
        mealPlanService.generateWeeklyPlan(user.getId());

        calendarViewService.showWeek(user);

        var message = TELEGRAM.sentMessages().getLast();
        var buttons = message.path("reply_markup").path("inline_keyboard").get(0);
        assertThat(buttons).hasSize(7);
        assertThat(buttons.get(0).path("callback_data").asText()).isEqualTo("cal:MONDAY");
        assertThat(message.toString()).doesNotContain("вівсяні пластівці");
    }

    @Test
    void showDayRendersMealNamesOnlyForThatDay() {
        CLAUDE.respondWithText(fullWeekJson());
        mealPlanService.generateWeeklyPlan(user.getId());

        calendarViewService.showDay(user, "MONDAY");

        String text = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(text)
                .contains("Пн")
                .contains("Вівсянка")
                .contains("Курячий суп")
                .contains("Гречка з овочами");
        assertThat(text).doesNotContain("куряче стегно", "вівсяні пластівці");
    }

    @Test
    void tappingADayButtonInTelegramRendersThatDay() throws Exception {
        CLAUDE.respondWithText(fullWeekJson());
        mealPlanService.generateWeeklyPlan(user.getId());

        tapButton(1, CalendarViewService.CALLBACK_DAY_PREFIX + "TUESDAY");

        String text = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(text).contains("Вт").contains("Вівсянка");
    }

    @Test
    void reflectsTheLatestPlanNotAnOlderOne() {
        MealPlan stale = MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(java.time.LocalDate.now().minusWeeks(2))
                .plan(java.util.Map.of(
                        "days",
                        List.of(java.util.Map.of(
                                "day",
                                "MONDAY",
                                "meals",
                                List.of(java.util.Map.of("type", "BREAKFAST", "name", "Старий сніданок"))))))
                .createdAt(java.time.Instant.now().minusSeconds(3600))
                .build();
        mealPlanRepository.save(stale);
        CLAUDE.respondWithText(fullWeekJson());
        mealPlanService.generateWeeklyPlan(user.getId());

        calendarViewService.showDay(user, "MONDAY");

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText())
                .contains("Вівсянка")
                .doesNotContain("Старий сніданок");
    }
}
