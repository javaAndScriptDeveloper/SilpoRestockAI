package com.silporestockai.entity;

import com.silporestockai.model.BasketItem;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
 * An order the agent assembled.
 *
 * <p>Named {@code customer_order} in the database because {@code ORDER} is reserved in PostgreSQL. Payment is not
 * modelled: the guest completes checkout on Silpo's own page and there is no MCP payment tool.
 */
@Entity
@Table(name = "customer_order")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class CustomerOrder {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private OrderType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", nullable = false)
    @Builder.Default
    private List<BasketItem> items = new ArrayList<>();

    @Column(name = "delivery_slot")
    private String deliverySlot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "silpo_cart_id")
    private String silpoCartId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** List lines Silpo matched no product for when this cart was built (task 37). Null for rows older than that. */
    @Column(name = "unresolved_count")
    private Integer unresolvedCount;

    /**
     * Whether the person changed the proposal before confirming — refused a substitute, in a reorder. Set only for
     * reorders at confirmation time; null otherwise (task 37's "% confirmed with zero edits").
     */
    @Column(name = "edited_before_confirm")
    private Boolean editedBeforeConfirm;

    /**
     * What the household is billed for this cart — Silpo's own {@code total}, delivery included (task 54). Null for
     * rows written before the column existed, and those rows are excluded from every GMV aggregate rather than being
     * counted as zero.
     */
    @Column(name = "total", precision = 10, scale = 2)
    private BigDecimal total;

    /** Merchandise only — Silpo's {@code productsTotal}, the figure its ₴799 minimum is measured against. */
    @Column(name = "goods_total", precision = 10, scale = 2)
    private BigDecimal goodsTotal;

    /** What promotions took off this cart — Silpo's {@code subDiscount}. */
    @Column(name = "savings", precision = 10, scale = 2)
    private BigDecimal savings;

    /** How many lines the ₴799 minimum-order top-up added from the household's baseline (task 51). */
    @Column(name = "topped_up_count")
    private Integer toppedUpCount;
}
