package com.silporestockai.entity;

import com.silporestockai.model.ScheduledAdHocTaskKind;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A free-text "закажи до п'ятниці..." request, waiting for its trigger time. */
@Entity
@Table(name = "scheduled_ad_hoc_task")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledAdHocTask {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "trigger_at", nullable = false)
    private Instant triggerAt;

    @Column(name = "theme_description", nullable = false, length = 256)
    private String themeDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ScheduledAdHocTaskStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Which kind of one-off order this fires (task 36); the theme is a snack theme or a dish name accordingly. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    @Builder.Default
    private ScheduledAdHocTaskKind kind = ScheduledAdHocTaskKind.SNACK_THEME;
}
