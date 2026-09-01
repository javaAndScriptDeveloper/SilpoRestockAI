package com.silporestockai.service.telegram;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.client.tts.TextToSpeechClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Turns a message written for a screen into one that can be said out loud.
 *
 * <p>Two steps, because they are two different problems. Our copy is shaped for a chat window — itemised carts,
 * prices in digits, checkout links — and Silpo's voice guidance forbids most of that aloud. So the message is first
 * rewritten by Claude under that guidance, and only then synthesised. Maintaining a second hand-written copy of every
 * string would have been the alternative, and it would have drifted within a week.
 *
 * <p>Nothing here throws. A voice reply is an enhancement to a message the user has already received; it must never
 * be able to swallow one.
 */
@Slf4j
@Service
public class VoiceReplyService {

    private final ClaudeApiClient claudeApiClient;
    private final TextToSpeechClient textToSpeechClient;
    private final String voiceStylePrompt;

    public VoiceReplyService(
            ClaudeApiClient claudeApiClient,
            TextToSpeechClient textToSpeechClient,
            @Value("classpath:prompts/voice-style-system.txt") Resource voiceStylePromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.textToSpeechClient = textToSpeechClient;
        this.voiceStylePrompt = read(voiceStylePromptResource);
    }

    /** Whether this deployment can speak at all. The per-chat switch is separate and also defaults to off. */
    public boolean enabled() {
        return textToSpeechClient.isConfigured();
    }

    /** The message as audio, or empty when anything at all went wrong. */
    public Optional<byte[]> speak(String text) {
        if (!enabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            String spoken = claudeApiClient.complete(voiceStylePrompt, text);
            if (spoken == null || spoken.isBlank()) {
                log.warn("the voice rewrite came back empty; not speaking");
                return Optional.empty();
            }
            return Optional.of(textToSpeechClient.synthesize(spoken.strip()));
        } catch (RuntimeException e) {
            log.warn("could not produce a voice reply: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the voice style prompt", e);
        }
    }
}
