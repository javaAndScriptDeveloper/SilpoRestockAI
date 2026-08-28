package com.silporestockai.entity;

import com.silporestockai.model.BasketItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * A snapshot of a basket the user confirmed, and the reference point every later check-in is compared against.
 *
 * <p>Superseded snapshots are kept with {@code isCurrent = false} rather than deleted. A partial unique index
 * guarantees at most one current row per user.
 */
@Entity
@Table(name = "baseline_basket")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class BaselineBasket {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", nullable = false)
    @Builder.Default
    private List<BasketItem> items = new ArrayList<>();

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private Boolean isCurrent = false;
}
