package com.silporestockai.repository;

import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.model.FirstOrderDelay;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderTotals;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Orders the agent assembled, newest first. */
public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

    List<CustomerOrder> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<CustomerOrder> findByUserIdAndStatus(UUID userId, OrderStatus status);

    /** The most recent order in a given state — the check-in cycle asks for the last confirmed one. */
    Optional<CustomerOrder> findFirstByUserIdAndStatusOrderByConfirmedAtDesc(UUID userId, OrderStatus status);

    /** Resolves the order behind a Silpo cart, which is how a duplicate confirm callback is recognised. */
    Optional<CustomerOrder> findBySilpoCartId(String silpoCartId);

    /**
     * Confirmed-order money and counts, grouped by type — the source of the GMV, average-cart and orders-by-type
     * gauges (task 54).
     *
     * <p>{@code coalesce} keeps the sums numeric when a type has no valued rows yet, so a fresh database produces
     * zeros rather than nulls the gauge would have to defend against. The {@code valueMissing} arm counts confirmed
     * orders with no usable total: rows older than the columns, and any cart Silpo answered without one. Zero is
     * counted as missing on purpose — {@code getVerifiedCart} defaults an absent total to zero, so "Silpo sent no
     * total" and "this cart is worth nothing" are indistinguishable at the entity, and the second never happens.
     */
    @Query("""
            select new com.silporestockai.model.OrderTotals(
                o.type,
                count(o),
                coalesce(sum(o.total), 0),
                coalesce(sum(o.goodsTotal), 0),
                coalesce(sum(o.savings), 0),
                sum(case when o.total is null or o.total = 0 then 1L else 0L end))
            from CustomerOrder o
            where o.status = com.silporestockai.model.OrderStatus.CONFIRMED
            group by o.type
            """)
    List<OrderTotals> confirmedTotalsByType();

    /**
     * How many distinct households ever confirmed an order — the funnel's numerator.
     *
     * <p>Explicit JPQL: as a derived query, {@code countDistinctUserIdByStatus} counted distinct <em>orders</em>
     * (Spring Data ignores the subject between {@code countDistinct} and {@code By}), and the live dashboard read
     * «перше замовлення: 4» for one household — a 400 % conversion.
     */
    @Query("select count(distinct o.userId) from CustomerOrder o where o.status = :status")
    long countDistinctUserIdByStatus(OrderStatus status);

    /**
     * Basket lines across every confirmed order. Native because {@code items} is a JSON attribute rather than a JPA
     * collection, so {@code size(o.items)} does not compile — {@code jsonb_array_length} is the honest equivalent and
     * the column is JSONB (011-customer-order.yaml).
     */
    @Query(value = """
                    select coalesce(sum(jsonb_array_length(items_json)), 0)
                    from customer_order
                    where status = 'CONFIRMED'
                    """, nativeQuery = true)
    long confirmedItemLines();

    /**
     * Lines Silpo did match, over the orders that recorded the split (task 37's columns). Native for the same reason
     * as {@link #confirmedItemLines()}: {@code items} is a JSON attribute, not a JPA collection.
     *
     * <p>Two scalar queries rather than one two-column projection: a projection interface would have to live in this
     * package, and ArchUnit requires everything in {@code ..repository..} to be named {@code *Repository}.
     */
    @Query(value = """
                    select coalesce(sum(jsonb_array_length(items_json)), 0)
                    from customer_order
                    where unresolved_count is not null
                    """, nativeQuery = true)
    long resolvedCartLines();

    /**
     * Lines it did not. Against {@link #resolvedCartLines()} this is the matcher-quality signal: how often the agent
     * cannot find a real product for something the household needs.
     */
    @Query("""
            select coalesce(sum(o.unresolvedCount), 0)
            from CustomerOrder o
            where o.unresolvedCount is not null
            """)
    long unresolvedCartLines();

    /**
     * Every household's account creation paired with its earliest confirmed order — the raw pairs behind «онбординг →
     * перше замовлення», without loading a single whole entity.
     */
    @Query("""
            select new com.silporestockai.model.FirstOrderDelay(o.userId, u.createdAt, min(o.confirmedAt))
            from CustomerOrder o, User u
            where u.id = o.userId
              and o.status = com.silporestockai.model.OrderStatus.CONFIRMED
              and o.confirmedAt is not null
            group by o.userId, u.createdAt
            """)
    List<FirstOrderDelay> firstOrderDelays();
}
