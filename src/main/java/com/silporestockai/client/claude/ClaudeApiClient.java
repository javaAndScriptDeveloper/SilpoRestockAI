package com.silporestockai.client.claude;

/**
 * Transport client for the Anthropic Claude API, shared by meal plan generation (task 07), check-in parsing (task 12)
 * and fridge-photo parsing (task 17).
 *
 * <p>Carries no prompt content: prompts belong to the services that own them.
 */
public interface ClaudeApiClient {

    /** Plain text completion. Returns the concatenated text blocks of the reply. */
    String complete(String systemPrompt, String userPrompt);

    /**
     * The same call, on {@code claude.fast-model} rather than {@code claude.model}.
     *
     * <p>For a task with no judgement call to make — turning an already-written sentence into a spoken one is the
     * only caller today. Meal planning, check-in parsing and list building stay on the flagship model: those exist
     * because a weaker model got a nuanced Ukrainian instruction wrong, and downgrading them would risk exactly that
     * again. A style rewrite carries no such risk, and it runs on every outbound message once a chat turns voice
     * replies on — the one call in this application cheap enough to be worth pricing separately.
     */
    String completeFast(String systemPrompt, String userPrompt);

    /**
     * Completion constrained to {@code responseType}. The SDK derives a JSON schema from the class, sends it as the
     * request's output config and deserialises the reply, so a malformed answer fails inside the SDK rather than at a
     * later parse.
     *
     * @throws com.silporestockai.exception.ClaudeStructuredOutputException if the model returned nothing matching the
     *     type
     */
    <T> T completeStructured(String systemPrompt, String userPrompt, Class<T> responseType);

    /**
     * Completion over an image plus a text prompt.
     *
     * @param imageBytes raw image bytes, base64-encoded before sending
     * @param mediaType MIME type, e.g. {@code image/jpeg}
     */
    String image(String systemPrompt, String userPrompt, byte[] imageBytes, String mediaType);
}
