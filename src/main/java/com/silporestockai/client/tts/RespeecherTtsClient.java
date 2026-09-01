package com.silporestockai.client.tts;

import com.silporestockai.config.RespeecherProperties;
import com.silporestockai.exception.TextToSpeechException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Spoken replies through Respeecher.
 *
 * <p>A blank key is not a startup failure: {@link #isConfigured()} answers false, {@code /voice} says the feature is
 * off, and every reply stays written. The key is sent as a header and never logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RespeecherTtsClient implements TextToSpeechClient {

    private final RespeecherProperties properties;
    private final RespeecherApiClient apiClient;

    @Override
    public boolean isConfigured() {
        return properties.configured();
    }

    @Override
    public byte[] synthesize(String text) {
        if (!isConfigured()) {
            throw new TextToSpeechException("no Respeecher API key is configured");
        }
        if (text == null || text.isBlank()) {
            throw new TextToSpeechException("nothing to say");
        }
        if (text.length() > properties.maxCharacters()) {
            // The bytes endpoint is documented for roughly 5000 characters. A chat reply that long is a bug
            // upstream, not something to truncate and read out half of.
            throw new TextToSpeechException("text is too long to speak: " + text.length() + " characters");
        }
        try {
            byte[] wav = apiClient.synthesize(
                    properties.apiKey(),
                    properties.model(),
                    RespeecherApiClient.SynthesisRequest.of(text, properties.voiceId()));
            if (wav == null || wav.length == 0) {
                throw new TextToSpeechException("Respeecher returned no audio");
            }
            log.info("synthesised {} characters into {} bytes of audio", text.length(), wav.length);
            return wav;
        } catch (TextToSpeechException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TextToSpeechException("could not reach Respeecher: " + e.getMessage(), e);
        }
    }
}
