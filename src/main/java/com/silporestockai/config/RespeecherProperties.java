package com.silporestockai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for spoken replies through Respeecher's Space API.
 *
 * <p>A blank key disables the feature deployment-wide: {@code /voice} says so and every reply stays text, which is
 * exactly how the bot behaved before this existed.
 *
 * @param apiKey Space API key from the Respeecher playground; sent as {@code X-API-Key}
 * @param baseUrl API base; the model is part of the path, not a parameter
 * @param model {@code ua-rt} for Ukrainian, which is also the model that supports explicit stress marking
 * @param voiceId which voice speaks; the voices endpoint lists what an account has
 * @param maxCharacters the bytes endpoint is documented for roughly 5000 characters — longer text is not spoken
 */
@ConfigurationProperties(prefix = "respeecher")
public record RespeecherProperties(String apiKey, String baseUrl, String model, String voiceId, int maxCharacters) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
