package com.silporestockai.client.tts;

/**
 * Turns text into speech.
 *
 * <p>The mirror of {@code SpeechToTextClient}, and deliberately as small: which vendor, which model and which voice
 * are configuration, so nothing upstream learns whose voice this is.
 */
public interface TextToSpeechClient {

    /** False when no API key is configured, which is what makes spoken replies an opt-in rather than a dependency. */
    boolean isConfigured();

    /**
     * @param text what to say, already written the way it should be spoken
     * @return WAV bytes, 16-bit LE PCM
     * @throws com.silporestockai.exception.TextToSpeechException if synthesis is unconfigured or fails
     */
    byte[] synthesize(String text);
}
