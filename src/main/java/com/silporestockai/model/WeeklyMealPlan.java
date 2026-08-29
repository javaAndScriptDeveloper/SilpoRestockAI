package com.silporestockai.model;

import java.util.List;

/**
 * A week of meals, as Claude returns it.
 *
 * <p>Deliberately carries no start date. The model does not know today, cannot know the household's timezone, and a
 * date it invented would disagree with the {@code meal_plan.week_start_date} column that everything downstream reads.
 * The service owns the date.
 *
 * @param days one entry per weekday, {@code MONDAY} to {@code SUNDAY}
 */
public record WeeklyMealPlan(List<PlannedDay> days) {}
