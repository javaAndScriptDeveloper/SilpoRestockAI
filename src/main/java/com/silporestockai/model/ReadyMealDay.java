package com.silporestockai.model;

import java.time.DayOfWeek;
import java.util.List;

/** One day of the ready-meals answer. */
public record ReadyMealDay(DayOfWeek day, List<ReadyMealChoice> meals) {}
