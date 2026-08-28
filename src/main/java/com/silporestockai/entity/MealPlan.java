package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A generated weekly menu.
 *
 * <p>{@code plan} is an untyped map on purpose: task 07 owns the structure of a weekly plan and has not defined it.
 * Typing it here would be invention, and changing it later would mean a migration.
 */
@Entity
@Table(name = "meal_plan")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class MealPlan {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "week_start_date", nullable = false)
    private LocalDate weekStartDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_json", nullable = false)
    @Builder.Default
    private Map<String, Object> plan = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
