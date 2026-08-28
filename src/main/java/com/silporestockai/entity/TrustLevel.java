package com.silporestockai.entity;

import com.silporestockai.model.TrustTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** How much confirmation ceremony a user still needs. One row per user. */
@Entity
@Table(name = "trust_level")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class TrustLevel {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "consecutive_unedited_confirmations", nullable = false)
    @Builder.Default
    private int consecutiveUneditedConfirmations = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_trust_tier", nullable = false, length = 32)
    @Builder.Default
    private TrustTier currentTrustTier = TrustTier.MANUAL_CONFIRM;
}
