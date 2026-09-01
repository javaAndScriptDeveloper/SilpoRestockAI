package com.silporestockai.repository;

import com.silporestockai.entity.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Users, looked up by the identity whichever channel is asking already has. */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Task 06 resolves the person behind an incoming Telegram update this way. */
    Optional<User> findByTelegramChatId(long telegramChatId);

    Optional<User> findBySilpoGuestId(String silpoGuestId);

    /**
     * Everyone the check-in cycle can address: a current baseline exists only once a cart was confirmed, so this one
     * condition covers both "finished onboarding" and "has a first order".
     */
    @Query("select u from User u where exists "
            + "(select 1 from BaselineBasket b where b.userId = u.id and b.isCurrent = true)")
    List<User> findAllWithCurrentBaseline();
}
