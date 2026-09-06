package com.silporestockai.model;

/**
 * Which multi-step conversation a chat is currently in. Persisted by name, so entries may be added but existing names
 * must not be renamed without a migration.
 */
public enum ConversationFlow {
    /** No flow in progress — the next message starts one. */
    NONE,
    /** First-run profile collection (task 06). */
    ONBOARDING,
    /** Periodic "what is left in the fridge" exchange (tasks 11 and 12). */
    CHECK_IN,
    /** Reviewing and confirming a proposed cart (task 10). */
    CART_CONFIRMATION,
    /** Reviewing a delta reorder: substitutes, delivery slot, confirm (task 15). */
    REORDER_CONFIRMATION,
    /** Building a shopping list from a photo, a receipt or a description, and getting it approved. */
    LIST_BUILDING,
    /** Collecting mass-gain parameters (weight, calorie/protein target) before generating that plan. */
    SPECIAL_MODE_SETUP,
    /** The Анкета button reopens the profile form after onboarding; awaiting its resubmission or a regenerate confirm. */
    PROFILE_REEDIT,
    /** Editing one pending scheduled_ad_hoc_task's time or theme — awaiting the free-text replacement value. */
    SCHEDULED_TASK_EDIT,
    /**
     * The «Фідбек» button is waiting for one message (task 47). Its context is a snapshot of the flow it
     * interrupted, written back the moment the message arrives.
     */
    FEEDBACK,
    /** Choosing which past Silpo order to seed the list from (task 35); the candidates live in the context. */
    PAST_ORDER_PICK,
    /** Waiting for a dish name, or for a yes/no on the dish a photo was identified as (task 36). */
    DISH_CONFIRM
}
