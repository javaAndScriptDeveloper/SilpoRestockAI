package com.silporestockai.entity;

import com.silporestockai.model.ShoppingListSourceType;
import com.silporestockai.model.ShoppingListStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One line of a shopping list.
 *
 * <p>{@code mealPlanId} is nullable: the same table carries ad-hoc lists that belong to no weekly plan. {@code userId}
 * is what makes those findable again.
 */
@Entity
@Table(name = "shopping_list_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ShoppingListItem {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "meal_plan_id")
    private UUID mealPlanId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "quantity", precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "category", length = 64)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private ShoppingListStatus status = ShoppingListStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 24)
    private ShoppingListSourceType sourceType;
}
