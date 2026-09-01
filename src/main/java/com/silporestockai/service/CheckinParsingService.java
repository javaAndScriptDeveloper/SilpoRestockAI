package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.client.stt.SpeechToTextClient;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.Checkin;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CheckinDelta;
import com.silporestockai.model.CheckinResult;
import com.silporestockai.model.CheckinSource;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.CheckinRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Turns "молоко ще є, хліба нема" into three lists of real item names.
 *
 * <p>The household's baseline goes into the prompt so loose phrasing maps onto items the rest of the system already
 * knows. Instructions are not a guarantee, though, so whatever comes back is filtered against those same names here:
 * the prompt is the ask, this filter is the promise.
 *
 * <p>Every check-in is stored, parsed or not. A bad parse that was recorded can be diagnosed later; a bad parse that
 * was dropped is just a gap.
 */
@Slf4j
@Service
public class CheckinParsingService {

    /** Own mapper, as elsewhere in the app: Boot 4 carries both Jackson 2 and Jackson 3. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BaselineBasketRepository baselineBasketRepository;
    private final CheckinRepository checkinRepository;
    private final ClaudeApiClient claudeApiClient;
    private final SpeechToTextClient speechToTextClient;
    private final InventoryTrendService inventoryTrendService;
    private final Clock clock;
    private final String systemPrompt;
    private final String photoSystemPrompt;

    public CheckinParsingService(
            BaselineBasketRepository baselineBasketRepository,
            CheckinRepository checkinRepository,
            ClaudeApiClient claudeApiClient,
            SpeechToTextClient speechToTextClient,
            InventoryTrendService inventoryTrendService,
            Clock clock,
            @Value("classpath:prompts/checkin-system.txt") Resource systemPromptResource,
            @Value("classpath:prompts/checkin-photo-system.txt") Resource photoSystemPromptResource) {
        this.baselineBasketRepository = baselineBasketRepository;
        this.checkinRepository = checkinRepository;
        this.claudeApiClient = claudeApiClient;
        this.speechToTextClient = speechToTextClient;
        this.inventoryTrendService = inventoryTrendService;
        this.clock = clock;
        this.systemPrompt = read(systemPromptResource);
        this.photoSystemPrompt = read(photoSystemPromptResource);
    }

    /** Whether a voice note can be handled at all, which decides what the flow offers the user. */
    public boolean voiceSupported() {
        return speechToTextClient.isConfigured();
    }

    /** Parses what the user typed, stores it, and says whether it was understood. */
    public CheckinResult parseText(UUID userId, String rawText) {
        return parseText(userId, rawText, CheckinSource.TEXT);
    }

    private CheckinResult parseText(UUID userId, String rawText, CheckinSource source) {
        List<String> baseline = baselineItemNames(userId);
        CheckinDelta delta;
        try {
            delta = claudeApiClient.completeStructured(systemPrompt, describe(baseline, rawText), CheckinDelta.class);
        } catch (RuntimeException e) {
            // The raw sentence is still worth keeping: it is the only evidence of what the model choked on.
            log.error("could not parse a check-in for user {}", userId, e);
            store(userId, rawText, null, source);
            return new CheckinResult(empty(), rawText, true);
        }

        CheckinDelta filtered = onlyBaselineItems(delta, baseline);
        boolean understood = !isEmpty(filtered);
        store(userId, rawText, understood ? filtered : null, source);
        return new CheckinResult(filtered, rawText, !understood);
    }

    /** Transcribes a voice note and parses the transcript. The stored raw text is what was heard. */
    public CheckinResult parseVoice(UUID userId, byte[] audio) {
        String transcript = speechToTextClient.transcribe(audio, "checkin.ogg");
        log.info("voice check-in from user {} transcribed", userId);
        return parseText(userId, transcript, CheckinSource.VOICE);
    }

    /**
     * Reads a photo of the fridge.
     *
     * <p>The vision call answers with text rather than a schema-checked object — {@code completeStructured} is a
     * different SDK call and widening the client interface for one stretch feature would cost more than parsing here.
     * A reply that is not JSON is treated exactly like an unparseable sentence: the raw text is kept, and the user is
     * asked to say it in words.
     */
    public CheckinResult parsePhoto(UUID userId, byte[] image, String mediaType) {
        List<String> baseline = baselineItemNames(userId);
        String answer;
        try {
            answer =
                    claudeApiClient.image(photoSystemPrompt, describe(baseline, "Що видно на фото?"), image, mediaType);
        } catch (RuntimeException e) {
            log.error("could not read a fridge photo for user {}", userId, e);
            store(userId, null, null, CheckinSource.PHOTO);
            return new CheckinResult(empty(), null, true);
        }

        CheckinDelta delta;
        try {
            delta = MAPPER.readValue(answer, CheckinDelta.class);
        } catch (Exception e) {
            log.warn("fridge photo answer for user {} was not JSON: {}", userId, e.getMessage());
            store(userId, answer, null, CheckinSource.PHOTO);
            return new CheckinResult(empty(), answer, true);
        }

        CheckinDelta filtered = onlyBaselineItems(delta, baseline);
        boolean understood = !isEmpty(filtered);
        store(userId, answer, understood ? filtered : null, CheckinSource.PHOTO);
        return new CheckinResult(filtered, answer, !understood);
    }

    /** The names the model is allowed to use — the current baseline, in its own spelling. */
    public List<String> baselineItemNames(UUID userId) {
        return baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(userId)
                .map(BaselineBasket::getItems)
                .orElseGet(List::of)
                .stream()
                .map(BasketItem::name)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Drops anything that is not a baseline item and restores the baseline's own spelling.
     *
     * <p>Static and pure so the hallucination guard can be tested without a model behind it. Matching ignores case and
     * surrounding whitespace, which is the whole distance between what a model returns and what was asked for.
     */
    public static CheckinDelta onlyBaselineItems(CheckinDelta delta, List<String> baselineNames) {
        if (delta == null) {
            return empty();
        }
        Map<String, String> byNormalised = new LinkedHashMap<>();
        for (String name : baselineNames) {
            byNormalised.putIfAbsent(normalise(name), name);
        }
        return new CheckinDelta(
                keepKnown(delta.stillHave(), byNormalised),
                keepKnown(delta.runningLow(), byNormalised),
                keepKnown(delta.goneCompletely(), byNormalised));
    }

    private static List<String> keepKnown(List<String> reported, Map<String, String> byNormalised) {
        if (reported == null) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (String name : reported) {
            String known = name == null ? null : byNormalised.get(normalise(name));
            if (known == null) {
                log.debug("dropping '{}': not in the baseline", name);
                continue;
            }
            if (!kept.contains(known)) {
                kept.add(known);
            }
        }
        return List.copyOf(kept);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isEmpty(CheckinDelta delta) {
        return delta.stillHave().isEmpty()
                && delta.runningLow().isEmpty()
                && delta.goneCompletely().isEmpty();
    }

    private static CheckinDelta empty() {
        return new CheckinDelta(List.of(), List.of(), List.of());
    }

    /**
     * Stores the check-in and moves the trend counters it implies.
     *
     * <p>Both together, here, rather than in the Telegram flow: every stored check-in updates the trend, whichever
     * channel it arrived through — including the fridge-photo path of task 17.
     */
    private void store(UUID userId, String rawText, CheckinDelta delta, CheckinSource source) {
        checkinRepository.save(Checkin.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .rawInputText(rawText)
                .parsedDelta(delta)
                .source(source)
                .receivedAt(clock.instant())
                .build());
        inventoryTrendService.recordCheckin(userId, delta);
    }

    private static String describe(List<String> baseline, String rawText) {
        return """
                Еталонний набір родини:
                %s

                Повідомлення людини:
                %s""".formatted(
                baseline.isEmpty()
                        ? "(порожній)"
                        : String.join("\n", baseline.stream().map("- "::concat).toList()),
                rawText);
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the check-in system prompt", e);
        }
    }
}
