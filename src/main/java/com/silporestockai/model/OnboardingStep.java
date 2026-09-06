package com.silporestockai.model;

/**
 * Where an onboarding conversation is. Stored by name in {@code conversation_state.current_step}, so a webhook call an
 * hour later resumes rather than restarts.
 */
public enum OnboardingStep {
    /** Welcome sent; waiting for the guest to connect Silpo or to skip. */
    AWAITING_CONNECT,
    /** Showing what MCP found; waiting for confirmation or a correction. */
    CONFIRM_PROFILE,
    /**
     * The Telegram WebApp form is open (or its manual-fallback button was offered); waiting for
     * {@code web_app_data} or the fallback button's text.
     */
    AWAITING_WEBAPP_FORM,
    /**
     * Asking how the household cooks — first, because that one answer picks the whole planner path (task 22's
     * ready-meals fork). Buttons only; a typed answer is re-asked.
     */
    ASK_COOKING_TIME,
    /** Asking how many people eat at home. */
    ASK_HOUSEHOLD,
    /** Asking about allergies and diet restrictions. */
    ASK_RESTRICTIONS,
    /** Asking what nobody in the household will eat. */
    ASK_DISLIKES,
    /** Asking the weekly budget — only reached on the manual-fallback path; the WebApp form asks it directly. */
    ASK_BUDGET,
    /** Profile saved; the conversation returns to {@link ConversationFlow#NONE}. */
    DONE
}
