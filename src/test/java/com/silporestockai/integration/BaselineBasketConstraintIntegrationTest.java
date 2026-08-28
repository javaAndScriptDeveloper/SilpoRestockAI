package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.User;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The database, not application code, guarantees one current baseline per user. Task 10's confirm flow can be
 * delivered twice by Telegram, so the invariant has to survive a race rather than a careful caller.
 */
@DisplayName("a user cannot have two current baseline baskets")
class BaselineBasketConstraintIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    private UUID userId;

    @BeforeEach
    void clean() {
        baselineBasketRepository.deleteAll();
        userRepository.deleteAll();
        userId = userRepository
                .save(User.builder()
                        .id(UUID.randomUUID())
                        .telegramChatId(6001L)
                        .createdAt(Instant.now())
                        .build())
                .getId();
    }

    private BaselineBasket basket(boolean current) {
        return BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .items(List.of())
                .confirmedAt(Instant.now())
                .isCurrent(current)
                .build();
    }

    @Test
    void rejectsASecondCurrentBasket() {
        baselineBasketRepository.saveAndFlush(basket(true));

        assertThatThrownBy(() -> baselineBasketRepository.saveAndFlush(basket(true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsManySupersededBaskets() {
        baselineBasketRepository.saveAndFlush(basket(false));
        baselineBasketRepository.saveAndFlush(basket(false));
        baselineBasketRepository.saveAndFlush(basket(true));
    }
}
