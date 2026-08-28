package com.silporestockai.repository;

import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.model.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Orders the agent assembled, newest first. */
public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

    List<CustomerOrder> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<CustomerOrder> findByUserIdAndStatus(UUID userId, OrderStatus status);

    /** Resolves the order behind a Silpo cart, which is how a duplicate confirm callback is recognised. */
    Optional<CustomerOrder> findBySilpoCartId(String silpoCartId);
}
