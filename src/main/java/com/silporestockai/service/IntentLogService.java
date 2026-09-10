package com.silporestockai.service;

import com.silporestockai.entity.IntentClassification;
import com.silporestockai.model.IntentClassifiedEvent;
import com.silporestockai.repository.IntentClassificationRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Writes one {@code intent_classification} row per {@link IntentClassifiedEvent} (task 55).
 *
 * <p>Synchronous and swallowing its own failures, for the same reason {@link McpCallLogService} is: the row is
 * evidence for a pitch, and a broken evidence log must never turn a working message into a dead one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentLogService {

    public static final String ROUTED = "ROUTED";
    public static final String UNCLASSIFIED = "UNCLASSIFIED";
    public static final String FAILED = "FAILED";

    private final IntentClassificationRepository intentClassificationRepository;

    @EventListener
    public void onIntentClassified(IntentClassifiedEvent event) {
        try {
            intentClassificationRepository.save(IntentClassification.builder()
                    .id(UUID.randomUUID())
                    .intent(event.intent())
                    .outcome(event.outcome())
                    .confidence(event.confidence())
                    .userId(event.userId())
                    .classifiedAt(event.classifiedAt())
                    .build());
        } catch (RuntimeException e) {
            log.warn("could not log intent classification {}: {}", event.intent(), e.getMessage());
        }
    }
}
