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
     * The person behind an {@code @nickname} (task 81). Most recent first, because a released username can be
     * taken over: the newest row is the one that answer belongs to now.
     */
    Optional<User> findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc(String telegramUsername);

    /**
     * Everyone the check-in cycle can address: a current baseline exists only once a cart was confirmed, so this one
     * condition covers both "finished onboarding" and "has a first order".
     */
    @Query("select u from User u where exists "
            + "(select 1 from BaselineBasket b where b.userId = u.id and b.isCurrent = true)")
    List<User> findAllWithCurrentBaseline();

    /** Check-in questions asked across every household — the denominator of the response rate (task 37). */
    @Query("select coalesce(sum(u.checkinPromptsSent), 0) from User u")
    long checkinPromptsSent();
}
