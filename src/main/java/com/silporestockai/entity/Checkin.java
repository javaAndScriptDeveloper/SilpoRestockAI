package com.silporestockai.entity;

import com.silporestockai.model.CheckinDelta;
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
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recorded check-in.
 *
 * <p>The raw wording is kept next to the parsed delta: a bad parse can then be diagnosed after the fact, and
 * preference learning has the original sentence rather than a lossy summary of it.
 */
@Entity
@Table(name = "checkin")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Checkin {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "raw_input_text", columnDefinition = "text")
    private String rawInputText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_delta_json")
    private CheckinDelta parsedDelta;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
