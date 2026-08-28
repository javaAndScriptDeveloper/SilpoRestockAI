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
