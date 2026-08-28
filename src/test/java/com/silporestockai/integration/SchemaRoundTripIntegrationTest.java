package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Round-trips every entity through a real PostgreSQL. {@code ddl-auto: validate} already catches a column that does
 * not exist; only writing and reading a row back catches a jsonb mapping that does not work.
 */
@DisplayName("every entity round-trips through PostgreSQL")
class SchemaRoundTripIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    private User persistedUser(long chatId) {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .telegramChatId(chatId)
                .silpoGuestId("guest-" + chatId)
                .createdAt(Instant.now())
                .build());
    }

    @Test
    void usersRoundTripAndAreFoundByTelegramChatId() {
        User saved = persistedUser(5001L);

        Optional<User> byChat = userRepository.findByTelegramChatId(5001L);
        Optional<User> byGuest = userRepository.findBySilpoGuestId("guest-5001");

        assertThat(byChat).isPresent();
        assertThat(byChat.get().getId()).isEqualTo(saved.getId());
        assertThat(byChat.get().getCreatedAt()).isNotNull();
        assertThat(byGuest).isPresent();
        assertThat(userRepository.findByTelegramChatId(9999L)).isEmpty();
    }
}
