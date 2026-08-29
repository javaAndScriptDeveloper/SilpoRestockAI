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
    /** Asking how many people eat at home. */
    ASK_HOUSEHOLD,
    /** Asking about allergies and diet restrictions. */
    ASK_RESTRICTIONS,
    /** Asking what nobody in the household will eat. */
    ASK_DISLIKES,
    /** Asking the weekly budget. MCP never knows this, so it is always asked. */
    ASK_BUDGET,
    /** Profile saved; the conversation returns to {@link ConversationFlow#NONE}. */
    DONE
}
