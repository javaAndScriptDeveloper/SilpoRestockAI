package com.silporestockai.service.telegram;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.client.tts.TextToSpeechClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
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

    /**
     * A short confirmation like «Записав.» is already something a person could say. Rewriting it still pays the full
     * style-guide system prompt for a handful of words of output — the exact shape of the day a two-minute check-in
     * loop and a left-on voice toggle turned into seven million input tokens for forty-seven thousand out. Anything
     * this long is presumed to have real structure worth a rewrite regardless of what it contains.
     */
    private static final int SHORT_MESSAGE_CHARS = 120;

    /**
     * What actually needs the style guide: a digit, a URL, or a character that would show up as markdown rather than
     * speech. A bare em dash is ordinary Ukrainian punctuation, not a list marker, and is deliberately not one of
     * these — most of this application's short confirmations use one.
     */
    private static final Pattern NEEDS_REWRITE = Pattern.compile("\\d|https?://|[*_]|\\n");

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
            String toSpeak = needsStyleRewrite(text) ? rewriteForSpeech(text) : text;
            if (toSpeak == null) {
                return Optional.empty();
            }
            return Optional.of(textToSpeechClient.synthesize(toSpeak.strip()));
        } catch (RuntimeException e) {
            log.warn("could not produce a voice reply: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Whether this message reads like something a person could already say, or still needs Silpo's style guide
     * applied — a digit, a link, a markdown character, a second line. Checked against text this application wrote
     * itself, never against a user's free-form words, so a mechanical presence check is the right tool here: nothing
     * is being inferred about what someone meant, only whether specific characters are in it.
     */
    static boolean needsStyleRewrite(String text) {
        return text.length() > SHORT_MESSAGE_CHARS
                || NEEDS_REWRITE.matcher(text).find();
    }

    /**
     * completeFast, not complete: this rewrite has no judgement call to make, and it runs on every outbound message
     * that reaches it once a chat turns voice replies on — the one call in this application cheap enough to price
     * separately from meal planning and check-in parsing, which stay on the flagship model on purpose.
     */
    private String rewriteForSpeech(String text) {
        String spoken = claudeApiClient.completeFast(voiceStylePrompt, text);
        if (spoken == null || spoken.isBlank()) {
            log.warn("the voice rewrite came back empty; not speaking");
            return null;
        }
        return spoken;
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the voice style prompt", e);
        }
    }
}
