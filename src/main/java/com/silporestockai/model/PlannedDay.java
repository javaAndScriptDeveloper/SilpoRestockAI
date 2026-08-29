package com.silporestockai.model;

import java.time.DayOfWeek;
import java.util.List;

/**
 * One day of a weekly plan.
 *
 * @param day the weekday, which is how a plan is aligned to {@code meal_plan.week_start_date}
 * @param meals the dishes for that day
 */
public record PlannedDay(DayOfWeek day, List<PlannedMeal> meals) {}
