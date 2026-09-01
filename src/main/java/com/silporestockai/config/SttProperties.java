package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for speech-to-text.
 *
 * <p>Deliberately an OpenAI-compatible transcription endpoint rather than a named vendor: the same three settings
 * point at OpenAI, at Groq, or at a whisper server on the same machine, and no code changes when they do.
 *
 * @param apiKey bearer token; blank disables the voice path, which then asks the user to type instead
 * @param endpoint full transcription URL, e.g. {@code https://api.openai.com/v1/audio/transcriptions}
 * @param model transcription model id
 * @param language ISO-639-1 hint, so the model does not have to guess Ukrainian
 * @param timeout per-request timeout; a voice note is small, the model is not
 */
@ConfigurationProperties(prefix = "stt")
public record SttProperties(String apiKey, String endpoint, String model, String language, Duration timeout) {

    public boolean apiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
