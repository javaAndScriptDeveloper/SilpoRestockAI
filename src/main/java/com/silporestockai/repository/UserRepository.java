package com.silporestockai.repository;

import com.silporestockai.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Users, looked up by the identity whichever channel is asking already has. */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Task 06 resolves the person behind an incoming Telegram update this way. */
    Optional<User> findByTelegramChatId(long telegramChatId);

    Optional<User> findBySilpoGuestId(String silpoGuestId);
}
