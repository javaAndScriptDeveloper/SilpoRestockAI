package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One classification by the chat-first router (task 55). The evidence behind «the router really does dispatch
 * across the whole taxonomy» — a count over this table, not a number typed into a slide.
 *
 * <p>Failures are rows too. A distribution that shows only successes invites exactly the question it should be
 * answering: how often does the router get it wrong?
 */
@Entity
@Table(name = "intent_classification")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentClassification {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** The intent name for a routed classification, otherwise the outcome — {@code UNCLASSIFIED} / {@code FAILED}. */
    @Column(name = "intent", nullable = false, length = 64)
    private String intent;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    /** The model's own confidence; null when the classification call itself threw. */
    @Column(name = "confidence")
    private Double confidence;

    /** Whose message it was — never rendered on the public page, only here so a count can be traced. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "classified_at", nullable = false)
    private Instant classifiedAt;
}
