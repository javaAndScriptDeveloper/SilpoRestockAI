package com.silporestockai.client.stt;

/**
 * Turns a voice note into text.
 *
 * <p>One method on purpose. Everything vendor-specific — which endpoint, which model, which language — is
 * configuration, so the check-in flow never learns whose transcription this is.
 */
public interface SpeechToTextClient {

    /** False when no API key is configured, which makes the voice path degrade to "please type it" instead of failing. */
    boolean isConfigured();

    /**
     * @param audio raw bytes as Telegram served them, normally OGG/Opus
     * @param filename name to send in the multipart part; the extension is how the service infers the container
     * @throws com.silporestockai.exception.SpeechToTextException if transcription is unconfigured or fails
     */
    String transcribe(byte[] audio, String filename);
}
