# Send-as-gift Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a household order a themed package delivered to a friend's address, resolving that address three ways — typed outright, from the friend's stored consent, or by asking the friend directly — without the sender ever seeing it.

**Architecture:** A gift is an ad-hoc order (tasks 24/52) whose cart is repointed at somebody else's door before any product is resolved, because the branch changes with the address. The account has one cart, so the household's own delivery block is snapshotted into a `gift_order` row and written back lazily, on the next ordinary build — never at confirmation time, because the sender pays on a Silpo link that reads the live cart.

**Tech Stack:** Spring Boot 4, Java 21, Liquibase, JPA/Postgres, Telegram Bot SDK behind `controller.telegram` / `service.telegram`, Silpo MCP over Streamable HTTP, Claude structured output.

**Spec:** `docs/superpowers/specs/2026-09-10-send-as-gift-design.md`

## Global Constraints

- **Liquibase owns the schema.** `ddl-auto: validate`. Every new entity or column needs a changeset under `src/main/resources/db/changelog/changes/`, named `NNN-...yaml`, or every `@SpringBootTest` fails. Next free numbers: `035`, `036`, `037`.
- **ArchUnit is enforced.** Constructor injection only (`@RequiredArgsConstructor`, never `@Autowired` fields). `@Service` beans must end in `Service`, schedulers in `Scheduler`. Telegram SDK types stay inside `controller.telegram` / `service.telegram`.
- **Spotless (palantir).** Run `make format` before every commit; CI runs `spotlessCheck` before `build`.
- **`@Slf4j` for logging**, never a manual `LoggerFactory`.
- **Conversation state is the only memory between webhook calls.** Nothing in a field of a flow service.
- **Delivery scope is `DeliveryHome` only.** No user-facing string may mention SelfPickup or Nova Poshta as a gift option.
- **The sender must never see a resolved address.** `gift_address_text`, `gift_flat` and `gift_phone` may reach the MCP argument map and nothing else.
- **Consent columns default to null / null / false** and are written only by an explicit user action.
- Run the suite with `make test`. **Stop the running app first** — `make run` shares `build/classes` with the tests.

---

### Task 1: Remember a person's Telegram username

Resolving `@нік` needs a username; `users` has none today.

**Files:**
- Create: `src/main/resources/db/changelog/changes/035-users-telegram-username.yaml`
- Modify: `src/main/java/com/silporestockai/entity/User.java`
- Modify: `src/main/java/com/silporestockai/repository/UserRepository.java`
- Modify: `src/main/java/com/silporestockai/service/UserAccountService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Test: `src/test/java/com/silporestockai/integration/GiftUsernameIntegrationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `UserAccountService.findOrCreate(long telegramChatId, String telegramUsername)`; `UserRepository.findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc(String)` returning `Optional<User>`; `User.getTelegramUsername()`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/silporestockai/integration/GiftUsernameIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("a person's Telegram username, so a friend can be named by it")
class GiftUsernameIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 9101L;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    @Test
    void recordsTheUsernameOnFirstContactAndFindsItCaseInsensitively() {
        userAccountService.findOrCreate(CHAT_ID, "Olena");

        assertThat(userRepository.findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc("olena"))
                .map(User::getTelegramChatId)
                .contains(CHAT_ID);
    }

    @Test
    void followsARename() {
        userAccountService.findOrCreate(CHAT_ID, "olena");
        userAccountService.findOrCreate(CHAT_ID, "olena_k");

        assertThat(userRepository.findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc("olena"))
                .isEmpty();
        assertThat(userRepository.findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc("olena_k"))
                .isPresent();
    }

    @Test
    void keepsTheStoredNameWhenAnUpdateCarriesNone() {
        userAccountService.findOrCreate(CHAT_ID, "olena");
        userAccountService.findOrCreate(CHAT_ID, null);

        assertThat(userRepository.findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc("olena"))
                .isPresent();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftUsernameIntegrationTest*'`
Expected: compilation failure — `findOrCreate(long, String)` and the repository method do not exist.

- [ ] **Step 3: Add the changeset**

`035-users-telegram-username.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 035-users-telegram-username
      author: komora
      comment: >-
        Task 81: the @nickname a gift is addressed to. Deliberately not unique — Telegram usernames can
        be released and taken over by somebody else, and a unique constraint would reject the second,
        legitimate owner. Lookups take the most recently created match instead.
      changes:
        - addColumn:
            tableName: users
            columns:
              - column:
                  name: telegram_username
                  type: VARCHAR(64)
        - createIndex:
            indexName: ix_users_telegram_username
            tableName: users
            columns:
              - column:
                  name: telegram_username
```

- [ ] **Step 4: Add the field, the lookup and the write**

In `User.java`, after `silpoGuestId`:

```java
    /**
     * The {@code @nickname} this person is known by in Telegram, without the {@code @}. Null when they have
     * none — Telegram does not require one — and re-read on every update, so a rename follows them here.
     */
    @Column(name = "telegram_username", length = 64)
    private String telegramUsername;
```

In `UserRepository.java`:

```java
    /**
     * The person behind an {@code @nickname} (task 81). Most recent first, because a released username can be
     * taken over: the newest row is the one that answer belongs to now.
     */
    Optional<User> findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc(String telegramUsername);
```

Replace `UserAccountService.findOrCreate`:

```java
    @Transactional
    public User findOrCreate(long telegramChatId) {
        return findOrCreate(telegramChatId, null);
    }

    /**
     * The same lookup, told what Telegram called this person on the update that arrived (task 81).
     *
     * <p>A missing username never erases a stored one: Telegram omits it from some update shapes, and a gift
     * addressed to «@olena» must not stop resolving because she happened to tap a button last.
     */
    @Transactional
    public User findOrCreate(long telegramChatId, String telegramUsername) {
        User user = userRepository.findByTelegramChatId(telegramChatId).orElseGet(() -> {
            User created = userRepository.save(User.builder()
                    .id(UUID.randomUUID())
                    .telegramChatId(telegramChatId)
                    .createdAt(Instant.now())
                    .build());
            log.info("registered a new user for chat {}", telegramChatId);
            // Only on the insert branch: this is the top of the funnel, not a count of messages.
            observabilityService.recordOnboardingStarted();
            return created;
        });
        if (telegramUsername != null
                && !telegramUsername.isBlank()
                && !telegramUsername.equals(user.getTelegramUsername())) {
            user.setTelegramUsername(telegramUsername.strip());
            userRepository.save(user);
        }
        return user;
    }
```

- [ ] **Step 5: Carry the username through the router**

In `TelegramRoutingService`, `route(Update)` currently calls `handle(incoming)`. Read the name off the raw update before the SDK types are dropped, and pass it down.

Add near `displayName`:

```java
    /** The {@code @nickname} on whichever part of the update carries a sender, or null. */
    private static String usernameOf(Update update) {
        org.telegram.telegrambots.meta.api.objects.User from = null;
        if (update.hasMessage()) {
            from = update.getMessage().getFrom();
        } else if (update.hasCallbackQuery()) {
            from = update.getCallbackQuery().getFrom();
        }
        return from == null ? null : from.getUserName();
    }
```

In `route`, change the dispatch to:

```java
        String username = usernameOf(update);
        toIncoming(update)
                .ifPresentOrElse(
                        incoming -> {
                            try {
                                handle(incoming, username);
                            } catch (RuntimeException e) {
```

and change the signature `private void handle(TelegramIncomingUpdate incoming)` to
`private void handle(TelegramIncomingUpdate incoming, String telegramUsername)`, whose first line becomes:

```java
        User user = userAccountService.findOrCreate(incoming.chatId(), telegramUsername);
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests '*GiftUsernameIntegrationTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 7: Format and commit**

```bash
make format
git add -A
git commit -m "Remember the Telegram username a gift can be addressed to"
```

---

### Task 2: Consent columns on the profile

**Files:**
- Create: `src/main/resources/db/changelog/changes/036-user-profile-gift-address.yaml`
- Modify: `src/main/java/com/silporestockai/entity/UserProfile.java`
- Test: `src/test/java/com/silporestockai/integration/GiftConsentDefaultsIntegrationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `UserProfile.getGiftDeliveryAddress()`, `getGiftDeliveryPhone()`, `getGiftAddressShareable()` (`Boolean`, never null once saved), and `acceptsGifts()` returning `boolean`.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("a profile shares no gift address until somebody says so")
class GiftConsentDefaultsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void aFreshProfileIsNullNullFalse() {
        var user = userAccountService.findOrCreate(9201L);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .onlyUaProducer(false)
                .build());

        UserProfile stored =
                userProfileRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(stored.getGiftDeliveryAddress()).isNull();
        assertThat(stored.getGiftDeliveryPhone()).isNull();
        assertThat(stored.getGiftAddressShareable()).isFalse();
        assertThat(stored.acceptsGifts()).isFalse();
    }

    @Test
    void acceptsGiftsOnlyWithBothAnAddressAndTheFlag() {
        var user = userAccountService.findOrCreate(9202L);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .onlyUaProducer(false)
                .giftAddressShareable(true)
                .build());

        assertThat(userProfileRepository
                        .findByUserId(user.getId())
                        .orElseThrow()
                        .acceptsGifts())
                .isFalse();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftConsentDefaultsIntegrationTest*'`
Expected: compilation failure — the getters do not exist.

- [ ] **Step 3: Add the changeset**

`036-user-profile-gift-address.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 036-user-profile-gift-address
      author: komora
      comment: >-
        Task 81: where a friend's gift may be delivered, and whether this household allows that at all.
        All three default to "no" — null address, null phone, shareable false — and nothing backfills
        them: an address is only ever stored by an explicit opt-in, in the Анкета or in a chat request.
        The phone sits beside the address rather than being asked for at send time, which is what makes
        the by-nickname path need no exchange with the recipient.
      changes:
        - addColumn:
            tableName: user_profile
            columns:
              - column:
                  name: gift_delivery_address
                  type: VARCHAR(512)
              - column:
                  name: gift_delivery_phone
                  type: VARCHAR(32)
              - column:
                  name: gift_address_shareable
                  type: BOOLEAN
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
```

- [ ] **Step 4: Add the fields**

In `UserProfile.java`, after `capabilityRevealSentAt`:

```java
    /** Where a friend's gift may be delivered (task 81). Null unless this household opted in. */
    @Column(name = "gift_delivery_address", length = 512)
    private String giftDeliveryAddress;

    /** The number a courier delivering that gift should call. Stored with the address, never separately. */
    @Column(name = "gift_delivery_phone", length = 32)
    private String giftDeliveryPhone;

    /**
     * Whether a friend naming this household by {@code @nickname} may have a gift sent here without being asked
     * (task 81). False for every profile that has not said otherwise, including every profile that existed
     * before the column did.
     */
    @Column(name = "gift_address_shareable", nullable = false)
    @Builder.Default
    private Boolean giftAddressShareable = false;

    /**
     * Whether a gift can be sent here with no exchange at all: the flag alone is not enough, because an address
     * that was cleared afterwards would otherwise resolve to nothing at the moment a cart is being built.
     */
    public boolean acceptsGifts() {
        return Boolean.TRUE.equals(giftAddressShareable)
                && giftDeliveryAddress != null
                && !giftDeliveryAddress.isBlank();
    }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests '*GiftConsentDefaultsIntegrationTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 6: Format and commit**

```bash
make format
git add -A
git commit -m "Store a gift address only when the household opts in"
```

---

### Task 3: The `gift_order` row

The sender's request and the recipient's answer arrive in two different chats, so `conversation_state` — keyed by one chat — cannot hold this.

**Files:**
- Create: `src/main/resources/db/changelog/changes/037-gift-order.yaml`
- Create: `src/main/java/com/silporestockai/entity/GiftOrder.java`
- Create: `src/main/java/com/silporestockai/model/GiftOrderStatus.java`
- Create: `src/main/java/com/silporestockai/model/GiftResolution.java`
- Create: `src/main/java/com/silporestockai/repository/GiftOrderRepository.java`
- Test: `src/test/java/com/silporestockai/integration/GiftOrderSchemaIntegrationTest.java`

**Interfaces:**
- Consumes: `users.id`.
- Produces: `GiftOrder` (Lombok `@Getter @Setter @Builder`), `GiftOrderStatus.{AWAITING_ADDRESS, RESOLVED, CART_PRESENTED, CONFIRMED, EXPIRED, UNREACHABLE, CANCELLED}`, `GiftResolution.{DIRECT, CONSENTED, ASKED}`, and on the repository:
  `Optional<GiftOrder> findFirstByRecipientChatIdAndStatusOrderByCreatedAtDesc(Long, GiftOrderStatus)`,
  `Optional<GiftOrder> findFirstBySenderUserIdAndStatusInOrderByCreatedAtDesc(UUID, Collection<GiftOrderStatus>)`,
  `List<GiftOrder> findAllByStatusAndExpiresAtBefore(GiftOrderStatus, Instant)`.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.GiftResolution;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("a gift order survives the round trip between two chats")
class GiftOrderSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private GiftOrderRepository giftOrderRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        giftOrderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void storesTheSnapshotAndFindsTheRowByTheRecipientsChat() {
        var sender = userAccountService.findOrCreate(9301L);
        giftOrderRepository.save(GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername("olena")
                .recipientChatId(9302L)
                .status(GiftOrderStatus.AWAITING_ADDRESS)
                .resolution(GiftResolution.ASKED)
                .theme("щось до кави")
                .ownDelivery(Map.of("deliveryType", "DeliveryHome"))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86_400))
                .build());

        GiftOrder found = giftOrderRepository
                .findFirstByRecipientChatIdAndStatusOrderByCreatedAtDesc(9302L, GiftOrderStatus.AWAITING_ADDRESS)
                .orElseThrow();

        assertThat(found.getTheme()).isEqualTo("щось до кави");
        assertThat(found.getOwnDelivery()).containsEntry("deliveryType", "DeliveryHome");
    }

    @Test
    void findsWhatHasExpired() {
        var sender = userAccountService.findOrCreate(9303L);
        giftOrderRepository.save(GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername("stale")
                .status(GiftOrderStatus.AWAITING_ADDRESS)
                .resolution(GiftResolution.ASKED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .expiresAt(Instant.now().minusSeconds(60))
                .build());

        assertThat(giftOrderRepository.findAllByStatusAndExpiresAtBefore(
                        GiftOrderStatus.AWAITING_ADDRESS, Instant.now()))
                .hasSize(1);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftOrderSchemaIntegrationTest*'`
Expected: compilation failure — none of these types exist.

- [ ] **Step 3: Add the changeset**

`037-gift-order.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 037-gift-order
      author: komora
      comment: >-
        Task 81: one gift in flight. Shaped like task 68's group_event and for the same reason — the
        sender's request and the recipient's answer happen in two different chats, and conversation_state
        is keyed by exactly one. own_delivery_json is the household's own delivery block, snapshotted
        before the cart was pointed at somebody else's door; the next ordinary order writes it back.
        The recipient's address, flat and phone live here and reach nothing but an MCP argument map.
      changes:
        - createTable:
            tableName: gift_order
            columns:
              - column: { name: id, type: UUID, constraints: { primaryKey: true, nullable: false } }
              - column: { name: sender_user_id, type: UUID, constraints: { nullable: false } }
              - column: { name: recipient_username, type: VARCHAR(64) }
              - column: { name: recipient_user_id, type: UUID }
              - column: { name: recipient_chat_id, type: BIGINT }
              - column: { name: status, type: VARCHAR(24), constraints: { nullable: false } }
              - column: { name: resolution, type: VARCHAR(16), constraints: { nullable: false } }
              - column: { name: theme, type: VARCHAR(512) }
              - column: { name: gift_address_text, type: VARCHAR(512) }
              - column: { name: gift_flat, type: VARCHAR(64) }
              - column: { name: gift_phone, type: VARCHAR(32) }
              - column: { name: own_delivery_json, type: JSONB }
              - column: { name: silpo_cart_id, type: VARCHAR(64) }
              - column: { name: created_at, type: TIMESTAMP WITH TIME ZONE, constraints: { nullable: false } }
              - column: { name: updated_at, type: TIMESTAMP WITH TIME ZONE, constraints: { nullable: false } }
              - column: { name: expires_at, type: TIMESTAMP WITH TIME ZONE }
        - addForeignKeyConstraint:
            constraintName: fk_gift_order_sender
            baseTableName: gift_order
            baseColumnNames: sender_user_id
            referencedTableName: users
            referencedColumnNames: id
            onDelete: CASCADE
        - addForeignKeyConstraint:
            constraintName: fk_gift_order_recipient
            baseTableName: gift_order
            baseColumnNames: recipient_user_id
            referencedTableName: users
            referencedColumnNames: id
            onDelete: SET NULL
        - createIndex:
            indexName: ix_gift_order_recipient_chat_status
            tableName: gift_order
            columns:
              - column:
                  name: recipient_chat_id
              - column:
                  name: status
        - createIndex:
            indexName: ix_gift_order_sender_status
            tableName: gift_order
            columns:
              - column:
                  name: sender_user_id
              - column:
                  name: status
```

- [ ] **Step 4: Add the enums**

`GiftOrderStatus.java`:

```java
package com.silporestockai.model;

/** Where one gift is in its life. Persisted by name, so entries may be added but not renamed. */
public enum GiftOrderStatus {
    /** The recipient was asked for an address in their own chat and has not answered yet. */
    AWAITING_ADDRESS,
    /** An address is in hand; the cart has not been built from it yet. */
    RESOLVED,
    /** The cart is in front of the sender, and it is holding the household's own delivery settings hostage. */
    CART_PRESENTED,
    /** The sender confirmed. The cart stays pointed at the friend until the household orders again. */
    CONFIRMED,
    /** Nobody answered inside the window; the sender was told. */
    EXPIRED,
    /** The named friend has never spoken to the bot, so there was no chat to ask in. */
    UNREACHABLE,
    /** The sender backed out, or the household's own delivery block has been written back. */
    CANCELLED
}
```

`GiftResolution.java`:

```java
package com.silporestockai.model;

/** How a gift's destination was arrived at (task 81). Persisted by name. */
public enum GiftResolution {
    /** The sender typed the address themselves. */
    DIRECT,
    /** The recipient had already opted in, so their stored address and phone were used untouched. */
    CONSENTED,
    /** The recipient was asked, in their own chat, and answered. */
    ASKED
}
```

- [ ] **Step 5: Add the entity and the repository**

`GiftOrder.java`:

```java
package com.silporestockai.entity;

import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.GiftResolution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One gift on its way to somebody else's door (task 81).
 *
 * <p>Deliberately not {@code conversation_state}: that table is keyed by a single Telegram chat, and this flow
 * spans two — the sender asks in theirs, the recipient answers in theirs, minutes or hours apart, possibly on
 * different instances. Same shape and same reasoning as task 68's {@code group_event}.
 *
 * <p>{@code ownDelivery} is the household's own {@code deliveryType / timeslot / address / shipments}, read off
 * the cart before it was repointed. It is the only copy: the account has no saved delivery addresses to fall
 * back on, so losing this row would leave the household's cart pointed at a friend.
 *
 * <p>{@code giftAddressText}, {@code giftFlat} and {@code giftPhone} are the recipient's, and the sender never
 * sees them. Their only consumer is the argument map handed to {@code silpo_update_shopping_cart}.
 */
@Entity
@Table(name = "gift_order")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GiftOrder {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "sender_user_id", nullable = false)
    private UUID senderUserId;

    /** Lower-cased, without the {@code @} — what the sender typed, normalised once on the way in. */
    @Column(name = "recipient_username", length = 64)
    private String recipientUsername;

    @Column(name = "recipient_user_id")
    private UUID recipientUserId;

    @Column(name = "recipient_chat_id")
    private Long recipientChatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private GiftOrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", nullable = false, length = 16)
    private GiftResolution resolution;

    @Column(name = "theme", length = 512)
    private String theme;

    @Column(name = "gift_address_text", length = 512)
    private String giftAddressText;

    @Column(name = "gift_flat", length = 64)
    private String giftFlat;

    @Column(name = "gift_phone", length = 32)
    private String giftPhone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "own_delivery_json")
    private Map<String, Object> ownDelivery;

    @Column(name = "silpo_cart_id", length = 64)
    private String silpoCartId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** When an unanswered request stops waiting. Null once an address is in hand. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Whether this gift is still holding the household's cart. */
    public boolean holdsTheCart() {
        return (status == GiftOrderStatus.CART_PRESENTED || status == GiftOrderStatus.CONFIRMED)
                && ownDelivery != null
                && !ownDelivery.isEmpty();
    }
}
```

`GiftOrderRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.model.GiftOrderStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Gifts in flight (task 81). */
public interface GiftOrderRepository extends JpaRepository<GiftOrder, UUID> {

    /** The open question in this recipient's chat, if there is one. */
    Optional<GiftOrder> findFirstByRecipientChatIdAndStatusOrderByCreatedAtDesc(
            Long recipientChatId, GiftOrderStatus status);

    /** The gift this household has in flight, in any of the given states. */
    Optional<GiftOrder> findFirstBySenderUserIdAndStatusInOrderByCreatedAtDesc(
            UUID senderUserId, Collection<GiftOrderStatus> statuses);

    /** Requests nobody answered in time. */
    List<GiftOrder> findAllByStatusAndExpiresAtBefore(GiftOrderStatus status, Instant before);
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests '*GiftOrderSchemaIntegrationTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 7: Format and commit**

```bash
make format
git add -A
git commit -m "Add the gift_order row that joins two chats"
```

---

### Task 4: Point the cart at somebody else's door

**Files:**
- Create: `src/main/java/com/silporestockai/model/GiftAddress.java`
- Create: `src/main/java/com/silporestockai/exception/GiftDeliveryUnavailableException.java`
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java`
- Test: `src/test/java/com/silporestockai/unit/GiftAddressArgumentsTest.java`

**Interfaces:**
- Consumes: `CartContext`, `OfferedSlot`, the private `updateCart` and `call` helpers.
- Produces: `GiftAddress(String addressText, String flat, String entrance, String floor, String phone)`;
  `CartBuildingService.repointCartTo(UUID userId, GiftAddress destination)` returning `Map<String, Object>` (the household's own delivery block);
  `CartBuildingService.restoreOwnDelivery(UUID userId, String cartId, Map<String, Object> ownDelivery)` returning `boolean`;
  `GiftAddress.addressArguments(JsonNode place, ...)` is *not* public — the shaping lives in a package-private static `giftAddressArguments` on `CartBuildingService` so the test can reach it.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/silporestockai/unit/GiftAddressArgumentsTest.java`:

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.model.GiftAddress;
import com.silporestockai.service.CartBuildingService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The address object handed to {@code silpo_update_shopping_cart}. Probed live on 2026-09-10: the server keeps
 * {@code phone}, {@code flat}, {@code entrance}, {@code floor} and {@code courrierComment} verbatim, and returns
 * the coordinates as strings — so this is what has to be sent, not a convenient JSON shape of our own.
 */
@DisplayName("the address a gift is delivered to")
class GiftAddressArgumentsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode place() throws Exception {
        return MAPPER.readTree(
                """
                {"address":"Київ, вулиця Хрещатик, 22","city":"Київ","street":"вулиця Хрещатик",
                 "houseNumber":"22","district":"Центр","latitude":50.4498465,"longitude":30.5230925}
                """);
    }

    @Test
    void carriesTheDoorAndTheNumberToCallWithCoordinatesAsStrings() throws Exception {
        GiftAddress destination =
                new GiftAddress("Київ, вулиця Хрещатик, 22", "42", "3", "5", "+380671234567");

        Map<String, Object> address = CartBuildingService.giftAddressArguments(place(), destination);

        assertThat(address)
                .containsEntry("addressType", "flat")
                .containsEntry("city", "Київ")
                .containsEntry("street", "вулиця Хрещатик")
                .containsEntry("house", "22")
                .containsEntry("district", "Центр")
                .containsEntry("flat", "42")
                .containsEntry("entrance", "3")
                .containsEntry("floor", "5")
                .containsEntry("phone", "+380671234567")
                .containsEntry("latitude", "50.4498465")
                .containsEntry("longitude", "30.5230925");
        assertThat(address.get("courrierComment").toString()).contains("Подарунок");
    }

    @Test
    void aBuildingWithNoApartmentIsAHouse() throws Exception {
        Map<String, Object> address = CartBuildingService.giftAddressArguments(
                place(), new GiftAddress("Київ, вулиця Хрещатик, 22", null, null, null, "+380671234567"));

        assertThat(address).containsEntry("addressType", "house").doesNotContainKey("flat");
    }

    @Test
    void noPhoneMeansNoPhoneKeyRatherThanAnEmptyOne() throws Exception {
        Map<String, Object> address = CartBuildingService.giftAddressArguments(
                place(), new GiftAddress("Київ, вулиця Хрещатик, 22", "42", null, null, null));

        assertThat(address).doesNotContainKey("phone");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftAddressArgumentsTest*'`
Expected: compilation failure — `GiftAddress` and `giftAddressArguments` do not exist.

- [ ] **Step 3: Add the model and the exception**

`GiftAddress.java`:

```java
package com.silporestockai.model;

/**
 * Where a gift goes, in the recipient's own words (task 81).
 *
 * <p>{@code addressText} is what {@code silpo_find_address} is asked to geocode — a street and a house number.
 * The rest is what geocoding cannot give back and a courier still needs: the apartment, the entrance, the floor
 * and a number to call. Probed live on 2026-09-10; {@code silpo_update_shopping_cart} keeps all four.
 */
public record GiftAddress(String addressText, String flat, String entrance, String floor, String phone) {

    /** The address alone, for the path where the sender typed one and nothing else is known. */
    public static GiftAddress of(String addressText, String phone) {
        return new GiftAddress(addressText, null, null, null, phone);
    }
}
```

`GiftDeliveryUnavailableException.java`:

```java
package com.silporestockai.exception;

/** Silpo delivers nothing to the address a gift was aimed at — a real answer, not a failure to hide. */
public class GiftDeliveryUnavailableException extends ApplicationException {

    public GiftDeliveryUnavailableException(String message) {
        super(message);
    }
}
```

Check `ApplicationException`'s constructor signature first and match it; if it takes `(String message)` only, the above compiles as written.

- [ ] **Step 4: Add the repoint and restore to `CartBuildingService`**

Add the tool constant beside the others:

```java
    private static final String TOOL_FIND_ADDRESS = "silpo_find_address";
```

Then, after `resolvePickupBranch`:

```java
    /**
     * Points this household's cart at somebody else's door (task 81), and hands back the delivery block it was
     * using so it can be put back later.
     *
     * <p>Order matters and is not a preference: a branch comes with the address, and moving the cart to another
     * branch invalidates every product already in it — probed live on 2026-09-10, three
     * {@code product.offer.not_found} validations for a three-line cart. So this runs before any product is
     * resolved, and the normal pipeline then searches the shelf that will actually be picked from.
     *
     * <p>{@code DeliveryHome} only. Nothing among the live server's forty tools names a recipient, so a friend
     * cannot be given an order to collect; a destination Silpo will not drive to is reported, not worked around.
     */
    public Map<String, Object> repointCartTo(UUID userId, GiftAddress destination) {
        CartContext context = getOrCreateCartContext(userId);
        JsonNode cart = call(userId, TOOL_CART_BY_ID, Map.of("shoppingCartId", context.cartId()));
        Map<String, Object> ownDelivery = deliveryBlockOf(cart);

        JsonNode found = call(userId, TOOL_FIND_ADDRESS, Map.of("address", destination.addressText()));
        JsonNode place = McpResponses.findArray(found, McpResponses.ADDRESSES).stream()
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("silpo_find_address matched nothing for a gift address of user {}", userId);
                    return new GiftDeliveryUnavailableException(
                            "Silpo could not place the gift address given by user " + userId);
                });
        BigDecimal latitude = requireNumber(place, McpResponses.LATITUDE, userId, "a gift address had no latitude");
        BigDecimal longitude = requireNumber(place, McpResponses.LONGITUDE, userId, "a gift address had no longitude");

        JsonNode types = call(userId, TOOL_DELIVERY_TYPES, Map.of("latitude", latitude, "longitude", longitude));
        String branchId = McpResponses.findArray(types, McpResponses.DELIVERY_TYPE_OPTIONS).stream()
                .filter(option -> McpResponses.findString(option, McpResponses.DELIVERY_TYPE)
                        .map(DELIVERY_HOME::equals)
                        .orElse(false))
                .findFirst()
                .flatMap(option -> McpResponses.findString(option, McpResponses.BRANCH_ID))
                .orElseThrow(() -> {
                    log.warn("no DeliveryHome option at {},{} for a gift from user {}", latitude, longitude, userId);
                    return new GiftDeliveryUnavailableException(
                            "Silpo offers no home delivery at the gift address for user " + userId);
                });

        String companyId = McpResponses.findArray(cart, McpResponses.SHIPMENTS).stream()
                .findFirst()
                .flatMap(shipment -> McpResponses.findString(shipment, McpResponses.COMPANY_ID))
                .or(() -> McpResponses.findString(cart, McpResponses.COMPANY_ID))
                .orElseThrow(() -> new CartBuildException("no companyId to ship a gift with for user " + userId));

        CartContext giftContext = new CartContext(context.cartId(), branchId, companyId, DELIVERY_HOME, null, null);
        OfferedSlot slot = firstDeliverableSlot(userId, giftContext);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("deliveryType", DELIVERY_HOME);
        Map<String, Object> timeslot = new LinkedHashMap<>();
        timeslot.put("start", slot.id());
        timeslot.put("end", slot.end() == null ? slot.id() : slot.end());
        changes.put("timeslot", timeslot);
        changes.put("address", giftAddressArguments(place, destination));
        changes.put("shipments", List.of(Map.of("companyId", companyId, "branchId", branchId)));
        changes.put("branchId", branchId);
        if (!updateCart(userId, context.cartId(), changes)) {
            throw new CartBuildException("Silpo declined to point cart " + context.cartId() + " at a gift address");
        }
        log.info("pointed cart {} at a gift address on branch {} for user {}", context.cartId(), branchId, userId);
        return ownDelivery;
    }

    /**
     * Puts the household's own delivery settings back. Best effort, like every other {@code updateCart} caller: a
     * refusal is worth a log line and a retry on the next build, never a failed order.
     */
    public boolean restoreOwnDelivery(UUID userId, String cartId, Map<String, Object> ownDelivery) {
        if (ownDelivery == null || ownDelivery.isEmpty()) {
            return false;
        }
        boolean restored = updateCart(userId, cartId, ownDelivery);
        log.info("restoring the household's own delivery on cart {} for user {}: {}", cartId, userId, restored);
        return restored;
    }

    /** The four fields {@code silpo_update_shopping_cart} demands on every call, as the cart currently has them. */
    private static Map<String, Object> deliveryBlockOf(JsonNode cart) {
        Map<String, Object> block = new LinkedHashMap<>();
        McpResponses.findString(cart, McpResponses.DELIVERY_TYPE).ifPresent(v -> block.put("deliveryType", v));
        McpResponses.findNode(cart, McpResponses.TIMESLOT)
                .filter(JsonNode::isObject)
                .ifPresent(node -> block.put("timeslot", MAPPER.convertValue(node, Map.class)));
        McpResponses.findNode(cart, McpResponses.ADDRESS)
                .filter(JsonNode::isObject)
                .ifPresent(node -> block.put("address", MAPPER.convertValue(node, Map.class)));
        List<Map<String, Object>> shipments = new ArrayList<>();
        for (JsonNode shipment : McpResponses.findArray(cart, McpResponses.SHIPMENTS)) {
            Map<String, Object> reduced = new LinkedHashMap<>();
            McpResponses.findString(shipment, McpResponses.COMPANY_ID).ifPresent(v -> reduced.put("companyId", v));
            McpResponses.findString(shipment, McpResponses.BRANCH_ID).ifPresent(v -> reduced.put("branchId", v));
            if (!reduced.isEmpty()) {
                shipments.add(reduced);
            }
        }
        if (!shipments.isEmpty()) {
            block.put("shipments", shipments);
        }
        return block;
    }

    /**
     * The address object for a gift. Coordinates go as strings because that is how the live cart returns them,
     * and {@code courrierComment} says out loud what this delivery is, so a courier at a stranger's door has some
     * idea why.
     */
    static Map<String, Object> giftAddressArguments(JsonNode place, GiftAddress destination) {
        Map<String, Object> address = new LinkedHashMap<>();
        boolean hasFlat = destination.flat() != null && !destination.flat().isBlank();
        address.put("addressType", hasFlat ? "flat" : "house");
        McpResponses.findNumber(place, McpResponses.LATITUDE)
                .ifPresent(v -> address.put("latitude", v.stripTrailingZeros().toPlainString()));
        McpResponses.findNumber(place, McpResponses.LONGITUDE)
                .ifPresent(v -> address.put("longitude", v.stripTrailingZeros().toPlainString()));
        McpResponses.findString(place, McpResponses.CITY).ifPresent(v -> address.put("city", v));
        McpResponses.findString(place, McpResponses.STREET).ifPresent(v -> address.put("street", v));
        McpResponses.findString(place, McpResponses.HOUSE).ifPresent(v -> address.put("house", v));
        McpResponses.findString(place, McpResponses.DISTRICT).ifPresent(v -> address.put("district", v));
        address.put("locality", destination.addressText());
        putIfFilled(address, "flat", destination.flat());
        putIfFilled(address, "entrance", destination.entrance());
        putIfFilled(address, "floor", destination.floor());
        putIfFilled(address, "phone", destination.phone());
        address.put("courrierComment", "Подарунок — телефонуйте отримувачу за номером у замовленні");
        return address;
    }

    private static void putIfFilled(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
```

And add the constant beside `DELIVERY_TYPES_WITH_A_BRANCH_ALREADY`:

```java
    /** The one delivery type a gift can use — see the task 81 design note on why the others cannot. */
    private static final String DELIVERY_HOME = "DeliveryHome";
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests '*GiftAddressArgumentsTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 6: Format and commit**

```bash
make format
git add -A
git commit -m "Point a cart at a gift address, and remember what it replaced"
```

---

### Task 5: Give the household its cart back, lazily

**Files:**
- Create: `src/main/java/com/silporestockai/service/GiftCartCustodyService.java`
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java`
- Test: `src/test/java/com/silporestockai/integration/GiftCartCustodyIntegrationTest.java`

**Interfaces:**
- Consumes: `GiftOrderRepository`, `CartBuildingService.restoreOwnDelivery`.
- Produces: `GiftCartCustodyService.releaseCartIfHeld(UUID userId)` returning `boolean` (whether anything was restored), and `GiftCartCustodyService.hold(GiftOrder order)`.

The restore cannot happen when the sender confirms: they pay on a Silpo web link that reads the live cart, so
putting the household's address back at that moment would deliver the gift to the sender. It happens on the
household's next ordinary cart build instead.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.GiftResolution;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.GiftCartCustodyService;
import com.silporestockai.service.UserAccountService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("the household gets its cart back before its next order, not before the sender has paid")
class GiftCartCustodyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private GiftCartCustodyService giftCartCustodyService;

    @Autowired
    private GiftOrderRepository giftOrderRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        giftOrderRepository.deleteAll();
        userRepository.deleteAll();
    }

    private UUID senderHolding(GiftOrderStatus status, Map<String, Object> ownDelivery) {
        var sender = userAccountService.findOrCreate(9401L);
        giftOrderRepository.save(GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername("olena")
                .status(status)
                .resolution(GiftResolution.DIRECT)
                .silpoCartId("cart-1")
                .ownDelivery(ownDelivery)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return sender.getId();
    }

    @Test
    void closesTheGiftRowOnceTheCartIsHandedBack() {
        UUID sender = senderHolding(GiftOrderStatus.CONFIRMED, Map.of("deliveryType", "DeliveryHome"));

        giftCartCustodyService.releaseCartIfHeld(sender);

        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getStatus)
                .isEqualTo(GiftOrderStatus.CANCELLED);
    }

    @Test
    void aGiftStillWaitingForAnAddressHoldsNothing() {
        UUID sender = senderHolding(GiftOrderStatus.AWAITING_ADDRESS, null);

        assertThat(giftCartCustodyService.releaseCartIfHeld(sender)).isFalse();
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getStatus)
                .isEqualTo(GiftOrderStatus.AWAITING_ADDRESS);
    }

    @Test
    void aHouseholdWithNoGiftInFlightIsUntouched() {
        var user = userAccountService.findOrCreate(9402L);

        assertThat(giftCartCustodyService.releaseCartIfHeld(user.getId())).isFalse();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftCartCustodyIntegrationTest*'`
Expected: compilation failure — `GiftCartCustodyService` does not exist.

- [ ] **Step 3: Write the service**

```java
package com.silporestockai.service;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.repository.GiftOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Custody of the household's single Silpo cart while a gift is using it (task 81).
 *
 * <p>An account has exactly one cart — {@code silpo_create_shopping_cart} is documented idempotent per user —
 * so a gift borrows the same one the weekly order uses. Handing it back cannot happen when the sender confirms:
 * checkout is a Silpo web link that reads the cart live, so restoring the household's own address at that moment
 * would send the gift to the sender. It happens on the household's next ordinary build instead, which is late
 * enough to be safe and early enough that nobody ever orders onto a friend's doorstep by accident.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftCartCustodyService {

    private static final List<GiftOrderStatus> HOLDING =
            List.of(GiftOrderStatus.CART_PRESENTED, GiftOrderStatus.CONFIRMED);

    private final GiftOrderRepository giftOrderRepository;
    private final CartBuildingService cartBuildingService;

    /** Marks this gift as the current holder of the cart. */
    public void hold(GiftOrder order) {
        order.setStatus(GiftOrderStatus.CART_PRESENTED);
        order.setUpdatedAt(Instant.now());
        giftOrderRepository.save(order);
    }

    /**
     * Puts the household's own delivery settings back if a gift is still holding them.
     *
     * @return whether anything was restored
     */
    public boolean releaseCartIfHeld(UUID userId) {
        Optional<GiftOrder> holder =
                giftOrderRepository.findFirstBySenderUserIdAndStatusInOrderByCreatedAtDesc(userId, HOLDING);
        if (holder.isEmpty() || !holder.get().holdsTheCart()) {
            return false;
        }
        GiftOrder order = holder.get();
        if (order.getSilpoCartId() != null) {
            cartBuildingService.restoreOwnDelivery(userId, order.getSilpoCartId(), order.getOwnDelivery());
        }
        // Closed either way. A Silpo refusal here would be repeated forever otherwise, and the next build reads
        // the cart's real state regardless of what this row believes.
        order.setStatus(GiftOrderStatus.CANCELLED);
        order.setUpdatedAt(Instant.now());
        giftOrderRepository.save(order);
        log.info("released the cart held by gift {} for user {}", order.getId(), userId);
        return true;
    }
}
```

- [ ] **Step 4: Hook it into the one door every ordinary build goes through**

`CartConfirmationService.present` is the single entry point for INITIAL, SCHEDULED_REORDER and AD_HOC carts.
Add the release as its first statement, before `cartBuildingService.buildCart`:

```java
        long chatId = user.getTelegramChatId();
        if (type != OrderType.GIFT) {
            // Task 81: a gift may still be holding this household's only cart. Give it back before building
            // anything, or the weekly order goes to whoever the last gift was for.
            giftCartCustodyService.releaseCartIfHeld(user.getId());
        }
```

Add `private final GiftCartCustodyService giftCartCustodyService;` to the constructor-injected fields. `OrderType.GIFT` arrives in Task 11; until then compile against `type != null` and change it in that task — no, do it properly: **complete Task 11's one-line enum addition first if the compiler complains**, then return here.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests '*GiftCartCustodyIntegrationTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 6: Format and commit**

```bash
make format
git add -A
git commit -m "Hand the cart back on the household's next order, not at checkout"
```

---

### Task 6: `OrderType.GIFT` and a cart message that names no address

**Files:**
- Modify: `src/main/java/com/silporestockai/model/OrderType.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/CartMessageService.java`
- Test: `src/test/java/com/silporestockai/service/telegram/GiftCartMessageTest.java`

**Interfaces:**
- Consumes: `CartSummary`, `OfferedSlot`, `CartBenefits`.
- Produces: `OrderType.GIFT`; `CartMessageService.giftCartText(CartSummary summary, OfferedSlot slot, String recipientLabel, CartBenefits benefits)` returning `String`.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.service.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartBenefits;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.OfferedSlot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the sender of a gift reads — which is everything except where it is going. */
@DisplayName("the cart a household is sending to somebody else")
class GiftCartMessageTest {

    private static final OfferedSlot SLOT =
            new OfferedSlot("2026-09-11T07:30:00Z", "2026-09-11T07:30:00Z", Instant.parse("2026-09-11T07:30:00Z"), "2026-09-11T09:00:00Z");

    private final CartMessageService service = new CartMessageService();

    private static CartSummary cart() {
        return new CartSummary(
                "cart-1",
                "2026-09-11T07:30:00Z",
                Instant.parse("2026-09-10T15:00:00Z"),
                List.of(new BasketItem("p-1", "Кава Lavazza", "шт", BigDecimal.ONE, new BigDecimal("249.00"))),
                new BigDecimal("899.00"),
                List.of(),
                BigDecimal.ZERO,
                false,
                "https://silpo.ua/checkout/cart-1",
                "silpo://checkout/cart-1",
                List.of());
    }

    @Test
    void namesTheFriendAndNeverTheAddress() {
        String text = service.giftCartText(cart(), SLOT, "@olena", CartBenefits.NONE);

        assertThat(text).contains("@olena").contains("Кава Lavazza");
        assertThat(text).doesNotContain("Хрещатик").doesNotContain("вулиц").doesNotContain("+380");
    }

    @Test
    void saysItIsAGiftSoTheConfirmButtonIsNotMistakenForTheWeeklyOrder() {
        assertThat(service.giftCartText(cart(), SLOT, "@olena", CartBenefits.NONE))
                .contains("Подарунок");
    }
}
```

Check `CartBenefits` for an existing empty constant; if there is none, build one with `new CartBenefits(List.of(), null, List.of())` in the test instead of `CartBenefits.NONE`.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftCartMessageTest*'`
Expected: FAIL — `giftCartText` does not exist.

- [ ] **Step 3: Add the enum constant**

In `OrderType.java`:

```java
    /**
     * A package this household is sending to somebody else's address (task 81). Never a baseline and never an
     * inventory signal: what a friend was sent says nothing about how this household eats.
     */
    GIFT
```

- [ ] **Step 4: Add the message**

In `CartMessageService`, beside `cartText`:

```java
    /**
     * The gift variant of {@link #cartText}. It names the friend and the window and stops there — the address is
     * the recipient's, and in two of the three ways it can be resolved the sender never gave it and must not be
     * shown it. The ordinary cart text already prints no address at all; this one only makes the reason explicit.
     */
    public String giftCartText(CartSummary summary, OfferedSlot slot, String recipientLabel, CartBenefits benefits) {
        String base = cartText(summary, slot, OrderType.GIFT, benefits);
        return "🎁 Подарунок для " + recipientLabel + "\n\n" + base
                + "\n\nАдресу я не показую — вона належить отримувачу.";
    }
```

If `cartText` switches on `OrderType` to pick a heading, add a `case GIFT` there that reads
`"Зібрав подарунок:"` rather than letting it fall into the weekly-order wording.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests '*GiftCartMessageTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 6: Run the whole suite — `OrderType` is switched on in several places**

Run: `./gradlew test`
Expected: PASS. Fix any non-exhaustive `switch (type)` the compiler flags by giving `GIFT` the same branch as `AD_HOC`, except where a baseline or an inventory trend is being written — there it must be excluded.

- [ ] **Step 7: Format and commit**

```bash
make format
git add -A
git commit -m "Add the GIFT order type and a cart message that names no address"
```

---

### Task 7: Resolving who and where — `GiftOrderService`

**Files:**
- Create: `src/main/java/com/silporestockai/service/GiftOrderService.java`
- Create: `src/main/java/com/silporestockai/service/telegram/GiftMessageService.java`
- Create: `src/main/resources/prompts/gift-request-system.txt`
- Create: `src/main/java/com/silporestockai/model/GiftRequest.java`
- Test: `src/test/java/com/silporestockai/integration/GiftResolutionIntegrationTest.java`

**Interfaces:**
- Consumes: `GiftOrderRepository`, `UserRepository`, `UserProfileRepository`, `ClaudeApiClient`, `TelegramOutboundService`, `ConversationStateService`, `CartConfirmationService`, `CartBuildingService`, `GiftCartCustodyService`, `AdHocOrderService`.
- Produces: `GiftOrderService.start(User sender, String sentence, OrderTrigger trigger)`;
  `GiftOrderService.handleRecipientReply(User recipient, TelegramIncomingUpdate incoming)`;
  `GiftOrderService.handleSenderReply(User sender, TelegramIncomingUpdate incoming)`;
  `GiftRequest(String recipientUsername, String address, String flat, String phone, String theme)`.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.GiftOrderService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("who a gift is for, and how its address is arrived at")
class GiftResolutionIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "555:stub-bot-token";
    private static final long SENDER_CHAT = 9501L;
    private static final long RECIPIENT_CHAT = 9502L;
    private static final StubTelegramServer TELEGRAM = startTelegram();

    @Autowired
    private GiftOrderService giftOrderService;

    @Autowired
    private GiftOrderRepository giftOrderRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        giftOrderRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void anUnknownNicknameIsSaidOutLoudRatherThanFailingQuietly() {
        var sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");

        giftOrderService.startForTest(sender, "@nobody", null, null, null, "щось до кави");

        assertThat(TELEGRAM.sentMessages()).isNotEmpty();
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText())
                .contains("@nobody")
                .contains("ще не користувався");
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(order -> order.getStatus())
                .isEqualTo(GiftOrderStatus.UNREACHABLE);
    }

    @Test
    void aKnownNicknameWithoutConsentIsAskedInTheirOwnChat() {
        var sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");
        userAccountService.findOrCreate(RECIPIENT_CHAT, "olena");

        giftOrderService.startForTest(sender, "@olena", null, null, null, "щось до кави");

        assertThat(TELEGRAM.sentMessages())
                .anySatisfy(message -> assertThat(message.path("chat_id").asLong()).isEqualTo(RECIPIENT_CHAT));
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(order -> order.getStatus())
                .isEqualTo(GiftOrderStatus.AWAITING_ADDRESS);
    }

    @Test
    void theSenderIsNeverToldTheStoredAddressOfAConsentingFriend() {
        var sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");
        var recipient = userAccountService.findOrCreate(RECIPIENT_CHAT, "olena");
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(recipient.getId())
                .onlyUaProducer(false)
                .giftDeliveryAddress("Київ, вулиця Хрещатик, 22")
                .giftDeliveryPhone("+380671234567")
                .giftAddressShareable(true)
                .build());

        giftOrderService.startForTest(sender, "@olena", null, null, null, "щось до кави");

        assertThat(TELEGRAM.sentMessages())
                .filteredOn(message -> message.path("chat_id").asLong() == SENDER_CHAT)
                .allSatisfy(message -> assertThat(message.path("text").asText())
                        .doesNotContain("Хрещатик")
                        .doesNotContain("+380671234567"));
        assertThat(TELEGRAM.sentMessages())
                .anySatisfy(message -> {
                    assertThat(message.path("chat_id").asLong()).isEqualTo(RECIPIENT_CHAT);
                    assertThat(message.path("text").asText()).contains("подарунок");
                });
    }
}
```

`startForTest` is a package-visible entry point that skips the Claude classification and takes the extracted
fields directly, so these three tests do not depend on a model call. Declare it on `GiftOrderService` as:

```java
    /** {@link #start} with the extraction already done — the classifier is task 9's concern, not this one's. */
    void startForTest(User sender, String recipient, String address, String flat, String phone, String theme) {
        resolve(sender, new GiftRequest(recipient, address, flat, phone, theme), null);
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftResolutionIntegrationTest*'`
Expected: compilation failure — `GiftOrderService` does not exist.

- [ ] **Step 3: Write the copy**

`GiftMessageService.java` — every user-facing string in one place, none of which may name an address:

```java
package com.silporestockai.service.telegram;

import org.springframework.stereotype.Service;

/**
 * What a gift sounds like in a chat (task 81).
 *
 * <p>Nothing here takes an address. Two of the three ways a gift's destination is arrived at are the
 * recipient's own information, and putting the address in this class at all would be one refactor away from
 * putting it on the sender's screen.
 */
@Service
public class GiftMessageService {

    /** Asking a friend who never opted in. Their chat, their choice, and their words are what resolves it. */
    public String askRecipientForAddress(String senderLabel, String theme) {
        return ("""
                🎁 %s хоче надіслати тобі подарунок через «Сільпо»%s.

                Куди привезти? Напиши одним повідомленням адресу, квартиру й телефон для кур'єра — \
                наприклад: «Київ, вулиця Хрещатик 22, кв. 42, +380671234567».

                Відправник твоєї адреси не побачить — я передаю її лише в замовлення. \
                Не хочеш — просто напиши «ні».""")
                .formatted(senderLabel, theme == null || theme.isBlank() ? "" : " — " + theme);
    }

    /** Telling a friend who did opt in. They consented to an address, not to this particular delivery. */
    public String tellRecipientAboutTheGift(String senderLabel, String slot) {
        return ("🎁 %s надсилає тобі подарунок через «Сільпо» — привезуть на твою збережену адресу, %s. "
                        + "Якщо цей час не підходить, скажи мені, і я передам.")
                .formatted(senderLabel, slot);
    }

    /** The sender, once somebody else's address is in hand. Deliberately says nothing about what it is. */
    public String addressInHand(String recipientLabel) {
        return "Адресу для %s маю — збираю кошик.".formatted(recipientLabel);
    }

    /** The sender, while a friend has not answered yet. */
    public String waitingOnTheRecipient(String recipientLabel) {
        return "Запитав у %s адресу. Щойно відповість — зберу кошик і покажу тобі.".formatted(recipientLabel);
    }

    /** The honest dead end: there is no chat to ask in, so the sender is told exactly that. */
    public String recipientUnreachable(String recipientLabel) {
        return ("%s ще не користувався ботом, тому не можу його спитати. "
                        + "Назви адресу сам, якщо знаєш — наприклад: «надішли подарунок на Київ, "
                        + "вулиця Хрещатик 22, кв. 42, +380671234567».")
                .formatted(recipientLabel);
    }

    /** Nobody answered inside the window. */
    public String requestExpired(String recipientLabel) {
        return ("%s поки не відповів про адресу, тож я поставив цей подарунок на паузу. "
                        + "Скажи ще раз, коли захочеш спробувати — або назви адресу сам.")
                .formatted(recipientLabel);
    }

    /** The sender typed an address but no number to call. */
    public String askSenderForPhone(String recipientLabel) {
        return ("Який телефон у %s? Кур'єр телефонує за номером із замовлення, і без нього дзвонитимуть тобі — "
                        + "а адреси ти не бачиш, тож підказати під'їзд не вийде. "
                        + "Якщо номера немає, напиши «не знаю».")
                .formatted(recipientLabel);
    }

    /** The sender chose to go without a number. Said plainly, once, rather than discovered at the door. */
    public String noPhoneWarning() {
        return "Добре, відправлю без номера отримувача — тоді кур'єр телефонуватиме тобі.";
    }

    /** Silpo does not drive there. */
    public String deliveryUnavailable() {
        return "«Сільпо» не доставляє за цією адресою — доставка додому туди недоступна. "
                + "Спробуй іншу адресу.";
    }

    /** The geocoder found nothing. */
    public String addressNotFound() {
        return "Не зміг знайти таку адресу в «Сільпо». Напиши точніше — місто, вулицю й номер будинку.";
    }
}
```

- [ ] **Step 4: Write `GiftRequest` and the prompt**

`GiftRequest.java`:

```java
package com.silporestockai.model;

/**
 * What a «надішли подарунок …» sentence turned out to be asking for (task 81).
 *
 * @param recipientUsername the {@code @nickname} named, without the {@code @}, or null when an address was
 *     given instead
 * @param address a street address the sender typed outright, or null
 * @param flat the apartment, entrance or floor if the sender mentioned one, or null
 * @param phone a number the sender gave for the recipient, or null
 * @param theme what to buy, in the sender's own words
 */
public record GiftRequest(String recipientUsername, String address, String flat, String phone, String theme) {}
```

`src/main/resources/prompts/gift-request-system.txt`:

```
Ти розбираєш прохання надіслати комусь подарунок продуктами через «Сільпо».
Відповідай лише структурованим об'єктом — без пояснень.

recipientUsername — Telegram-нік отримувача БЕЗ символа @ (наприклад «olena»), якщо в повідомленні є
@нік. Якщо ніка немає — null.
address — повна адреса доставки, якщо людина назвала її прямо: місто, вулиця, номер будинку
(«Київ, вулиця Хрещатик, 22»). Без квартири. Якщо адреси немає — null.
flat — квартира, під'їзд або поверх, якщо названі («кв. 42», «42, 3 під'їзд»). Інакше null.
phone — номер телефону отримувача, якщо названий, у форматі як написано. Інакше null.
theme — що саме купити, словами людини («щось до кави», «набір солодощів», «пляшка вина й сир»).
Якщо не сказано — «подарунковий набір».

Приклади:
«відправ подарунок @olena, щось до кави» → recipientUsername=olena, address=null, flat=null,
phone=null, theme=«щось до кави»
«замов другу на Київ, Хрещатик 22, кв. 42, +380671234567 набір солодощів» → recipientUsername=null,
address=«Київ, вулиця Хрещатик, 22», flat=«кв. 42», phone=«+380671234567», theme=«набір солодощів»
```

- [ ] **Step 5: Write `GiftOrderService`**

The service owns the four paths and nothing else; building carts stays with `CartConfirmationService`, and
turning a theme into lines stays with `AdHocOrderService`.

```java
package com.silporestockai.service;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.GiftOrder;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.GiftAddress;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.GiftRequest;
import com.silporestockai.model.GiftResolution;
import com.silporestockai.model.OrderTrigger;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.GiftMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * «Надішли подарунок другу» (task 81): the same ad-hoc order the rest of the app already builds, pointed at
 * somebody else's door.
 *
 * <p>Three ways to arrive at that door, and the order they are tried in is the order of how little the
 * recipient's privacy has to be spent: an address the sender already knows, an address the recipient has
 * already agreed to share, and — only then — asking the recipient directly. A friend who has never spoken to
 * the bot has no fourth path, and is said out loud rather than guessed at.
 */
@Slf4j
@Service
public class GiftOrderService {

    /** How long an unanswered request waits before the sender is told nobody replied. */
    static final Duration ANSWER_WINDOW = Duration.ofHours(24);

    static final String STEP_AWAITING_PHONE = "AWAITING_GIFT_PHONE";
    private static final String KEY_GIFT_ORDER = "giftOrderId";

    /** «кв. 42», «квартира 42», «42, 3 під'їзд, 5 поверх» — what a person actually types after a street. */
    private static final Pattern FLAT = Pattern.compile("кв\\.?\\s*(\\d+[а-яa-z]?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s()-]{8,}\\d");

    private final ClaudeApiClient claudeApiClient;
    private final GiftOrderRepository giftOrderRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final GiftMessageService giftMessageService;
    private final TelegramOutboundService telegramOutboundService;
    private final ConversationStateService conversationStateService;
    private final GiftCartBuilder giftCartBuilder;
    private final String systemPrompt;

    public GiftOrderService(
            ClaudeApiClient claudeApiClient,
            GiftOrderRepository giftOrderRepository,
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            GiftMessageService giftMessageService,
            TelegramOutboundService telegramOutboundService,
            ConversationStateService conversationStateService,
            GiftCartBuilder giftCartBuilder,
            @Value("classpath:prompts/gift-request-system.txt") Resource systemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.giftOrderRepository = giftOrderRepository;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.giftMessageService = giftMessageService;
        this.telegramOutboundService = telegramOutboundService;
        this.conversationStateService = conversationStateService;
        this.giftCartBuilder = giftCartBuilder;
        this.systemPrompt = read(systemPromptResource);
    }

    /** Task 31's entry point: a sentence classified as GIFT_ORDER. */
    public void start(User sender, String sentence, OrderTrigger trigger) {
        GiftRequest request;
        try {
            request = claudeApiClient.completeStructuredFast(systemPrompt, sentence, GiftRequest.class);
        } catch (RuntimeException e) {
            log.warn("could not read a gift request from user {}", sender.getId(), e);
            telegramOutboundService.sendMessage(
                    sender.getTelegramChatId(),
                    "Не зрозумів, кому надіслати. Напиши, наприклад: «надішли подарунок @нік, щось до кави».");
            return;
        }
        resolve(sender, request, trigger);
    }

    /** {@link #start} with the extraction already done — the classifier is task 9's concern, not this one's. */
    void startForTest(User sender, String recipient, String address, String flat, String phone, String theme) {
        resolve(sender, new GiftRequest(recipient, address, flat, phone, theme), null);
    }

    private void resolve(User sender, GiftRequest request, OrderTrigger trigger) {
        String theme = request == null || request.theme() == null || request.theme().isBlank()
                ? "подарунковий набір"
                : request.theme().trim();

        // (a) The sender typed an address. Nothing about anybody else's privacy is at stake here — they know it.
        if (request != null && request.address() != null && !request.address().isBlank()) {
            GiftOrder order = open(sender, null, GiftResolution.DIRECT, theme);
            order.setGiftAddressText(request.address().trim());
            order.setGiftFlat(request.flat());
            order.setGiftPhone(request.phone());
            order.setStatus(GiftOrderStatus.RESOLVED);
            order.setExpiresAt(null);
            giftOrderRepository.save(order);
            if (request.phone() == null || request.phone().isBlank()) {
                askSenderForPhone(sender, order);
                return;
            }
            giftCartBuilder.build(sender, order, trigger);
            return;
        }

        if (request == null || request.recipientUsername() == null || request.recipientUsername().isBlank()) {
            telegramOutboundService.sendMessage(
                    sender.getTelegramChatId(),
                    "Кому надіслати? Назви @нік друга або адресу — і що саме привезти.");
            return;
        }

        String username = request.recipientUsername().replace("@", "").strip().toLowerCase(Locale.ROOT);
        String label = "@" + username;
        Optional<User> recipient = userRepository.findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc(username);

        // (d) Never seen. There is no chat to ask in, so say so and offer the path that does work.
        if (recipient.isEmpty()) {
            GiftOrder order = open(sender, username, GiftResolution.ASKED, theme);
            order.setStatus(GiftOrderStatus.UNREACHABLE);
            order.setExpiresAt(null);
            giftOrderRepository.save(order);
            telegramOutboundService.sendMessage(sender.getTelegramChatId(), giftMessageService.recipientUnreachable(label));
            return;
        }

        User friend = recipient.get();
        Optional<UserProfile> profile = userProfileRepository.findByUserId(friend.getId());

        // (b) Already opted in: address and phone are both on file, so nothing has to be asked at send time.
        if (profile.map(UserProfile::acceptsGifts).orElse(false)) {
            UserProfile consented = profile.orElseThrow();
            GiftOrder order = open(sender, username, GiftResolution.CONSENTED, theme);
            order.setRecipientUserId(friend.getId());
            order.setRecipientChatId(friend.getTelegramChatId());
            order.setGiftAddressText(consented.getGiftDeliveryAddress());
            order.setGiftPhone(consented.getGiftDeliveryPhone());
            order.setStatus(GiftOrderStatus.RESOLVED);
            order.setExpiresAt(null);
            giftOrderRepository.save(order);
            telegramOutboundService.sendMessage(sender.getTelegramChatId(), giftMessageService.addressInHand(label));
            giftCartBuilder.build(sender, order, trigger);
            return;
        }

        // (c) Known but never opted in: ask them, in their own chat, and tell the sender to expect a wait.
        GiftOrder order = open(sender, username, GiftResolution.ASKED, theme);
        order.setRecipientUserId(friend.getId());
        order.setRecipientChatId(friend.getTelegramChatId());
        order.setStatus(GiftOrderStatus.AWAITING_ADDRESS);
        order.setExpiresAt(Instant.now().plus(ANSWER_WINDOW));
        giftOrderRepository.save(order);

        String senderLabel = sender.getTelegramUsername() == null ? "Хтось" : "@" + sender.getTelegramUsername();
        telegramOutboundService.sendMessage(
                friend.getTelegramChatId(), giftMessageService.askRecipientForAddress(senderLabel, theme));
        telegramOutboundService.sendMessage(
                sender.getTelegramChatId(), giftMessageService.waitingOnTheRecipient(label));
    }

    /** The recipient's own chat answering «куди привезти». */
    public void handleRecipientReply(User recipient, TelegramIncomingUpdate incoming) {
        if (!(incoming instanceof TelegramIncomingUpdate.Text text)) {
            telegramOutboundService.sendMessage(
                    incoming.chatId(), "Напиши адресу текстом, будь ласка — місто, вулицю, будинок, квартиру й телефон.");
            return;
        }
        Optional<GiftOrder> pending = giftOrderRepository.findFirstByRecipientChatIdAndStatusOrderByCreatedAtDesc(
                incoming.chatId(), GiftOrderStatus.AWAITING_ADDRESS);
        if (pending.isEmpty()) {
            conversationStateService.save(incoming.chatId(), ConversationFlow.NONE, null, Map.of());
            return;
        }
        GiftOrder order = pending.get();
        String answer = text.text().strip();
        if (answer.toLowerCase(Locale.ROOT).startsWith("ні")) {
            order.setStatus(GiftOrderStatus.CANCELLED);
            order.setUpdatedAt(Instant.now());
            giftOrderRepository.save(order);
            conversationStateService.save(incoming.chatId(), ConversationFlow.NONE, null, Map.of());
            telegramOutboundService.sendMessage(incoming.chatId(), "Добре, нічого не замовляю.");
            senderOf(order).ifPresent(sender -> telegramOutboundService.sendMessage(
                    sender.getTelegramChatId(),
                    "%s поки не хоче отримувати подарунок. Нічого не замовляв.".formatted(label(order))));
            return;
        }

        order.setGiftAddressText(withoutContactDetails(answer));
        order.setGiftFlat(flatIn(answer));
        order.setGiftPhone(phoneIn(answer));
        order.setStatus(GiftOrderStatus.RESOLVED);
        order.setExpiresAt(null);
        order.setUpdatedAt(Instant.now());
        giftOrderRepository.save(order);
        conversationStateService.save(incoming.chatId(), ConversationFlow.NONE, null, Map.of());
        telegramOutboundService.sendMessage(incoming.chatId(), "Записав, дякую. Передам у замовлення.");

        senderOf(order).ifPresent(sender -> {
            telegramOutboundService.sendMessage(
                    sender.getTelegramChatId(), giftMessageService.addressInHand(label(order)));
            giftCartBuilder.build(sender, order, null);
        });
    }

    /** The sender's own chat answering «який телефон у друга». */
    public void handleSenderReply(User sender, TelegramIncomingUpdate incoming) {
        if (!(incoming instanceof TelegramIncomingUpdate.Text text)) {
            telegramOutboundService.sendMessage(incoming.chatId(), "Напиши номер текстом або «не знаю».");
            return;
        }
        UUID orderId = UUID.fromString(String.valueOf(conversationStateService
                .load(incoming.chatId())
                .getContext()
                .get(KEY_GIFT_ORDER)));
        GiftOrder order = giftOrderRepository.findById(orderId).orElse(null);
        conversationStateService.save(incoming.chatId(), ConversationFlow.NONE, null, Map.of());
        if (order == null) {
            return;
        }
        String phone = phoneIn(text.text());
        if (phone == null) {
            telegramOutboundService.sendMessage(incoming.chatId(), giftMessageService.noPhoneWarning());
        } else {
            order.setGiftPhone(phone);
            order.setUpdatedAt(Instant.now());
            giftOrderRepository.save(order);
        }
        giftCartBuilder.build(sender, order, null);
    }

    private void askSenderForPhone(User sender, GiftOrder order) {
        conversationStateService.save(
                sender.getTelegramChatId(),
                ConversationFlow.GIFT_SENDER_DETAIL,
                STEP_AWAITING_PHONE,
                Map.of(KEY_GIFT_ORDER, order.getId().toString()));
        telegramOutboundService.sendMessage(
                sender.getTelegramChatId(), giftMessageService.askSenderForPhone(label(order)));
    }

    private GiftOrder open(User sender, String username, GiftResolution resolution, String theme) {
        return GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername(username)
                .status(GiftOrderStatus.AWAITING_ADDRESS)
                .resolution(resolution)
                .theme(theme)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private Optional<User> senderOf(GiftOrder order) {
        return userRepository.findById(order.getSenderUserId());
    }

    static String label(GiftOrder order) {
        return order.getRecipientUsername() == null ? "друга" : "@" + order.getRecipientUsername();
    }

    /** The street part of what somebody typed — the geocoder chokes on a phone number glued to an address. */
    static String withoutContactDetails(String answer) {
        String stripped = PHONE.matcher(answer).replaceAll(" ");
        stripped = FLAT.matcher(stripped).replaceAll(" ");
        return stripped.replaceAll("[,;]\\s*(?=[,;]|$)", "").replaceAll("\\s+", " ").strip();
    }

    static String flatIn(String answer) {
        Matcher matcher = FLAT.matcher(answer);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String phoneIn(String answer) {
        Matcher matcher = PHONE.matcher(answer);
        return matcher.find() ? matcher.group().replaceAll("[\\s()-]", "") : null;
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the gift request prompt", e);
        }
    }
}
```

`GiftCartBuilder` is Task 8. To keep this task testable on its own, create it now as a thin service whose
`build` logs and does nothing; Task 8 fills it in. Its three tests here never reach a cart.

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests '*GiftResolutionIntegrationTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 7: Format and commit**

```bash
make format
git add -A
git commit -m "Resolve a gift's destination three ways, and say so when there is no fourth"
```

---

### Task 8: Build the gift cart

**Files:**
- Create: `src/main/java/com/silporestockai/service/GiftCartBuilder.java` (replacing Task 7's stub)
- Modify: `src/main/java/com/silporestockai/service/CartConfirmationService.java`
- Test: `src/test/java/com/silporestockai/integration/GiftCartBuildIntegrationTest.java`

**Interfaces:**
- Consumes: `CartBuildingService.repointCartTo`, `AdHocOrderService.giftLinesFor`, `CartConfirmationService.presentGift`, `GiftCartCustodyService.hold`.
- Produces: `GiftCartBuilder.build(User sender, GiftOrder order, OrderTrigger trigger)`.

`GiftCartBuilder` is deliberately not named `…Service`: it is called only from `GiftOrderService` and holds no
Spring `@Service` semantics of its own. **Check `ArchitectureTest` first** — if the rule requires every bean in
`service` to end in `Service`, name it `GiftCartBuildingService` instead and use that name throughout.

- [ ] **Step 1: Write the failing test**

Drive it through `StubMcpServer`, asserting the *order* of calls — the whole design rests on the address being
set before products are searched.

```java
    @Test
    void pointsTheCartAtTheFriendBeforeAnythingIsSearchedFor() {
        // ... arrange a RESOLVED gift order and a stubbed MCP ...
        giftCartBuilder.build(sender, order, null);

        List<String> calls = MCP.toolCallsInOrder();
        assertThat(calls).containsSubsequence(
                "silpo_find_address",
                "silpo_get_available_delivery_types",
                "silpo_get_time_slots",
                "silpo_update_shopping_cart",
                "silpo_find_products_batch");
    }

    @Test
    void remembersTheHouseholdsOwnDeliveryBlockOnTheGiftRow() {
        giftCartBuilder.build(sender, order, null);

        assertThat(giftOrderRepository.findById(order.getId()).orElseThrow().getOwnDelivery())
                .containsKey("address");
    }
```

Read `src/test/java/com/silporestockai/support/StubMcpServer.java` first and use whatever recording accessor it
already exposes; add `toolCallsInOrder()` to it only if there is no equivalent.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftCartBuildIntegrationTest*'`
Expected: FAIL — the stub records no `silpo_find_address` call.

- [ ] **Step 3: Write the builder**

```java
package com.silporestockai.service;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.exception.GiftDeliveryUnavailableException;
import com.silporestockai.model.GiftAddress;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.OrderTrigger;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.service.telegram.GiftMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Turns a gift whose destination is known into a cart in front of the sender (task 81).
 *
 * <p>The address goes on the cart first and the products second, which is not a style choice: the branch
 * changes with the address, and every product added before the move is invalidated by it. Doing it this way
 * round also means the search runs against the shelf the friend's order will actually be picked from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftCartBuilder {

    private final CartBuildingService cartBuildingService;
    private final CartConfirmationService cartConfirmationService;
    private final AdHocOrderService adHocOrderService;
    private final GiftOrderRepository giftOrderRepository;
    private final GiftCartCustodyService giftCartCustodyService;
    private final GiftMessageService giftMessageService;
    private final TelegramOutboundService telegramOutboundService;

    public void build(User sender, GiftOrder order, OrderTrigger trigger) {
        long chatId = sender.getTelegramChatId();
        if (order.getGiftAddressText() == null || order.getGiftAddressText().isBlank()) {
            log.warn("gift {} has no address to build against", order.getId());
            return;
        }
        // A previous gift may still be holding this cart. Give that one back before borrowing it again.
        giftCartCustodyService.releaseCartIfHeld(sender.getId());

        GiftAddress destination = new GiftAddress(
                order.getGiftAddressText(), order.getGiftFlat(), null, null, order.getGiftPhone());
        Map<String, Object> ownDelivery;
        try {
            ownDelivery = cartBuildingService.repointCartTo(sender.getId(), destination);
        } catch (GiftDeliveryUnavailableException e) {
            log.warn("cannot deliver a gift for user {}: {}", sender.getId(), e.getMessage());
            fail(order);
            telegramOutboundService.sendMessage(chatId, giftMessageService.deliveryUnavailable());
            return;
        } catch (RuntimeException e) {
            log.error("could not point the cart at a gift address for user {}", sender.getId(), e);
            fail(order);
            telegramOutboundService.sendMessage(chatId, giftMessageService.addressNotFound());
            return;
        }
        order.setOwnDelivery(ownDelivery);
        order.setUpdatedAt(Instant.now());
        giftOrderRepository.save(order);

        List<ShoppingListItem> items = adHocOrderService.giftLinesFor(sender.getId(), order.getTheme());
        if (items.isEmpty()) {
            telegramOutboundService.sendMessage(
                    chatId,
                    "Не зрозумів, що покласти в подарунок на «%s». Напиши конкретніше.".formatted(order.getTheme()));
            giftCartCustodyService.releaseCartIfHeld(sender.getId());
            return;
        }
        boolean presented = cartConfirmationService.presentGift(sender, items, order, trigger);
        if (presented) {
            giftCartCustodyService.hold(order);
        } else {
            giftCartCustodyService.releaseCartIfHeld(sender.getId());
        }
    }

    private void fail(GiftOrder order) {
        order.setStatus(GiftOrderStatus.CANCELLED);
        order.setUpdatedAt(Instant.now());
        giftOrderRepository.save(order);
    }
}
```

- [ ] **Step 4: Expose the two things it leans on**

In `AdHocOrderService`, make the existing private `linesFor` reachable under an honest name:

```java
    /**
     * The gift's theme as shop lines (task 81). The same call the ad-hoc order makes — a gift package is an
     * ad-hoc order with a different destination, not a second kind of basket.
     */
    public List<ShoppingListItem> giftLinesFor(UUID userId, String theme) {
        return linesFor(userId, theme == null || theme.isBlank() ? "подарунковий набір" : theme);
    }
```

In `CartConfirmationService`, add the gift presentation beside the existing `present` overloads. It reuses the
whole body of `present` — extract the shared part rather than copying it — with two differences: the order is
saved with `OrderType.GIFT`, and the message comes from `cartMessageService.giftCartText(...)` with
`GiftOrderService.label(order)` as the recipient label.

```java
    /**
     * The gift variant (task 81): the same cart, presented without ever naming where it is going. The type is
     * what keeps it out of the baseline, and the message is what keeps the address off the sender's screen.
     */
    public boolean presentGift(User user, List<ShoppingListItem> items, GiftOrder gift, OrderTrigger trigger) {
        return present(user, items, OrderType.GIFT, false, trigger, MatchingHints.NONE, gift);
    }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests '*GiftCartBuildIntegrationTest*'`
Expected: PASS.

- [ ] **Step 6: Format and commit**

```bash
make format
git add -A
git commit -m "Build the gift cart against the friend's branch, address first"
```

---

### Task 9: The two intents

**Files:**
- Modify: `src/main/resources/prompts/intent-router-system.txt`
- Modify: `src/main/java/com/silporestockai/service/IntentRouterService.java`
- Create: `src/main/java/com/silporestockai/service/GiftConsentService.java`
- Test: `src/test/java/com/silporestockai/integration/GiftIntentIntegrationTest.java`

**Interfaces:**
- Consumes: `GiftOrderService.start`, `GiftConsentService.offer`, `GiftConsentService.revoke`.
- Produces: intents `GIFT_ORDER` and `GIFT_ADDRESS_CONSENT` on the private `IntentType` enum.

- [ ] **Step 1: Write the failing test**

Model the test on `IntentRouterIntegrationTest` — it already stubs Anthropic through `StubAnthropicServer`.
Assert that a stubbed `{"intent":"GIFT_ORDER","confidence":0.95,...}` reaches `GiftOrderService`, and that
`GIFT_ADDRESS_CONSENT` reaches `GiftConsentService`.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftIntentIntegrationTest*'`
Expected: FAIL — both classify as UNKNOWN.

- [ ] **Step 3: Extend the prompt**

Insert before the `HELP` entry in `intent-router-system.txt`:

```
- GIFT_ORDER — надіслати подарунок продуктами ІНШІЙ людині: на її адресу або за її @ніком.
  Приклади: "відправ подарунок @olena, щось до кави", "хочу зробити сюрприз, замов другу на Хрещатик 22
  набір солодощів", "надішли мамі продуктів на Львів, вулиця Січових Стрільців 12".
  Не плутати зі звичайним замовленням собі — тут є інша людина або чужа адреса.
- GIFT_ADDRESS_CONSENT — сам користувач хоче (або більше не хоче) отримувати подарунки від друзів за
  своїм ніком: залишити або прибрати свою адресу й телефон для цього.
  Приклади: "дозволь друзям надсилати мені подарунки на цю адресу", "хочу отримувати подарунки за ніком",
  "прибери мою адресу для подарунків", "більше не хочу отримувати подарунки".
```

- [ ] **Step 4: Wire the dispatch**

Add to `IntentType`: `GIFT_ORDER,` and `GIFT_ADDRESS_CONSENT,` before `HELP`.

Add to `dispatch`'s switch:

```java
            // Task 81. The sentence itself goes through: it carries the friend, the address and the theme, and
            // re-asking for what the person already typed is the failure this router exists to remove.
            case GIFT_ORDER -> giftOrderService.start(user, text, trigger);
            // One intent for both directions, like FILTER_UA_PRODUCER_ONLY: the sentence says which.
            case GIFT_ADDRESS_CONSENT -> giftConsentService.handleRequest(user, text);
```

Add both services to the constructor.

- [ ] **Step 5: Write `GiftConsentService`**

It owns the opt-in outside onboarding: asks for address and phone in one message, stores them, sets the flag,
and clears all three on a revoking sentence. Reuse `ConversationFlow.GIFT_CONSENT` (Task 10) and the same
`phoneIn` / `withoutContactDetails` helpers, made package-visible on `GiftOrderService` in Task 7.

- [ ] **Step 6: Run the tests, then the suite**

Run: `./gradlew test --tests '*GiftIntentIntegrationTest*'` then `./gradlew test`
Expected: PASS.

- [ ] **Step 7: Format and commit**

```bash
make format
git add -A
git commit -m "Classify a gift request and a gift-address opt-in"
```

---

### Task 10: Routing the two new conversations, and the onboarding opt-in

**Files:**
- Modify: `src/main/java/com/silporestockai/model/ConversationFlow.java`
- Modify: `src/main/java/com/silporestockai/model/OnboardingStep.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Modify: `src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java`
- Test: `src/test/java/com/silporestockai/integration/GiftOnboardingOptInIntegrationTest.java`

**Interfaces:**
- Consumes: `GiftOrderService.handleRecipientReply`, `GiftOrderService.handleSenderReply`, `GiftConsentService.handle`.
- Produces: `ConversationFlow.{GIFT_ADDRESS_REQUEST, GIFT_SENDER_DETAIL, GIFT_CONSENT}`; `OnboardingStep.{ASK_GIFT_OPT_IN, ASK_GIFT_ADDRESS}`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void theGiftSectionIsOfferedAtTheEndAndSkippingLeavesNothingStored() {
        // drive onboarding to ASK_BUDGET, answer it, expect the gift offer with two buttons
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText())
                .contains("Подарунки від друзів")
                .contains("за бажанням");

        // tap «Пропустити»
        onboardingFlowService.handle(user, new TelegramIncomingUpdate.ButtonTap(CHAT_ID, CHAT_ID, "q", "onb:gift:skip"));

        UserProfile stored = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(stored.getGiftDeliveryAddress()).isNull();
        assertThat(stored.getGiftAddressShareable()).isFalse();
    }

    @Test
    void leavingAnAddressAndAPhoneTurnsTheFlagOn() {
        // tap «Залишити адресу», then send "Київ, вулиця Хрещатик 22, кв. 42, +380671234567"
        UserProfile stored = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(stored.getGiftDeliveryAddress()).contains("Хрещатик");
        assertThat(stored.getGiftDeliveryPhone()).isEqualTo("+380671234567");
        assertThat(stored.getGiftAddressShareable()).isTrue();
    }
```

Model the driving on `OnboardingFlowIntegrationTest`, which already walks the fallback questions.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftOnboardingOptInIntegrationTest*'`
Expected: FAIL — onboarding finishes straight after the budget.

- [ ] **Step 3: Add the flows and steps**

In `ConversationFlow`:

```java
    /** A friend was asked, in their own chat, where a gift should go (task 81). */
    GIFT_ADDRESS_REQUEST,
    /** The sender was asked for the one detail their sentence left out — the recipient's phone (task 81). */
    GIFT_SENDER_DETAIL,
    /** Somebody is leaving, or clearing, the address friends may send gifts to (task 81). */
    GIFT_CONSENT
```

In `OnboardingStep`, before `DONE`:

```java
    /** Offering the optional gift-address section — buttons only, and «Пропустити» is a complete answer. */
    ASK_GIFT_OPT_IN,
    /** Collecting the address, apartment and phone friends' gifts would be delivered to. */
    ASK_GIFT_ADDRESS,
```

- [ ] **Step 4: Add the onboarding section**

In `OnboardingFlowService`, add the callbacks:

```java
    public static final String CALLBACK_GIFT_YES = "onb:gift:yes";
    public static final String CALLBACK_GIFT_SKIP = "onb:gift:skip";
```

Change `ASK_BUDGET`'s branch in `handleAnswer` from `finish(user, chatId, context)` to
`askNext(chatId, OnboardingStep.ASK_GIFT_OPT_IN, context, user)`, extend `following` with
`case ASK_BUDGET -> OnboardingStep.ASK_GIFT_OPT_IN;` and `case ASK_GIFT_OPT_IN -> OnboardingStep.DONE;`, and add
to `askNext`'s switch:

```java
            case ASK_GIFT_OPT_IN ->
                telegramOutboundService.sendMessageWithButtons(
                        chatId,
                        """
                        🎁 Подарунки від друзів — за бажанням

                        Якщо залишиш адресу й телефон, друзі зможуть замовити тобі подарунок у «Сільпо» \
                        просто за твоїм ніком — і я привезу його сюди, не питаючи тебе щоразу. \
                        Твоєї адреси ніхто з них не побачить.""",
                        List.of(
                                TelegramButton.callback("Залишити адресу", CALLBACK_GIFT_YES),
                                TelegramButton.callback("Пропустити", CALLBACK_GIFT_SKIP)));
            case ASK_GIFT_ADDRESS ->
                telegramOutboundService.sendMessage(
                        chatId,
                        "Напиши одним повідомленням адресу, квартиру й телефон — наприклад: "
                                + "«Київ, вулиця Хрещатик 22, кв. 42, +380671234567».");
```

In `handleButton`, add:

```java
        if (step == OnboardingStep.ASK_GIFT_OPT_IN && CALLBACK_GIFT_SKIP.equals(data)) {
            // Skipping is a complete answer, and the default one: the profile keeps its null address and its
            // false flag, exactly as every profile that existed before this section did.
            finish(user, chatId, context);
            return;
        }
        if (step == OnboardingStep.ASK_GIFT_OPT_IN && CALLBACK_GIFT_YES.equals(data)) {
            askNext(chatId, OnboardingStep.ASK_GIFT_ADDRESS, context, user);
            return;
        }
```

In `handleAnswer`, add:

```java
            case ASK_GIFT_ADDRESS -> {
                context.put(KEY_GIFT_ADDRESS, GiftOrderService.withoutContactDetails(answer));
                context.put(KEY_GIFT_FLAT, GiftOrderService.flatIn(answer));
                context.put(KEY_GIFT_PHONE, GiftOrderService.phoneIn(answer));
                finish(user, chatId, context);
            }
```

and in `finish`, before `userProfileRepository.save(profile)`:

```java
        // Task 81. Written only when the section was actually filled in — «Пропустити» reaches here with all
        // three keys absent, and must leave a profile that shares nothing.
        if (context.get(KEY_GIFT_ADDRESS) != null) {
            String flat = context.get(KEY_GIFT_FLAT) == null ? null : context.get(KEY_GIFT_FLAT).toString();
            String address = context.get(KEY_GIFT_ADDRESS).toString();
            profile.setGiftDeliveryAddress(flat == null ? address : address + ", кв. " + flat);
            profile.setGiftDeliveryPhone(
                    context.get(KEY_GIFT_PHONE) == null ? null : context.get(KEY_GIFT_PHONE).toString());
            profile.setGiftAddressShareable(true);
        }
```

with `private static final String KEY_GIFT_ADDRESS = "giftAddress";` and the two siblings beside the other keys.

- [ ] **Step 5: Route the three flows**

In `TelegramRoutingService.handle`, beside the other flow gates:

```java
        if (flow == ConversationFlow.GIFT_ADDRESS_REQUEST) {
            giftOrderService.handleRecipientReply(user, incoming);
            return;
        }
        if (flow == ConversationFlow.GIFT_SENDER_DETAIL) {
            giftOrderService.handleSenderReply(user, incoming);
            return;
        }
        if (flow == ConversationFlow.GIFT_CONSENT) {
            giftConsentService.handle(user, incoming);
            return;
        }
```

`GiftOrderService` must also set `ConversationFlow.GIFT_ADDRESS_REQUEST` on the recipient's chat when it asks
them — add that `conversationStateService.save(...)` call in Task 7's path (c) branch if it is not there.

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests '*GiftOnboardingOptInIntegrationTest*'` then `./gradlew test`
Expected: PASS.

- [ ] **Step 7: Format and commit**

```bash
make format
git add -A
git commit -m "Offer the gift address as an optional last step, and route both gift conversations"
```

---

### Task 11: Nobody answered

**Files:**
- Create: `src/main/java/com/silporestockai/job/GiftRequestExpiryScheduler.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/silporestockai/integration/GiftExpiryIntegrationTest.java`

**Interfaces:**
- Consumes: `GiftOrderRepository.findAllByStatusAndExpiresAtBefore`, `GiftMessageService.requestExpired`.
- Produces: `GiftRequestExpiryScheduler.sweep()`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void tellsTheSenderWhenNobodyAnswered() {
        // a gift_order in AWAITING_ADDRESS with expires_at in the past
        scheduler.sweep();

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("поки не відповів");
        assertThat(giftOrderRepository.findAll())
                .singleElement()
                .extracting(GiftOrder::getStatus)
                .isEqualTo(GiftOrderStatus.EXPIRED);
    }

    @Test
    void leavesARequestThatIsStillInsideItsWindowAlone() {
        // expires_at an hour from now
        scheduler.sweep();

        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*GiftExpiryIntegrationTest*'`
Expected: compilation failure — the scheduler does not exist.

- [ ] **Step 3: Write the scheduler**

Model it on `job/AdHocScheduleScheduler` — same `@Scheduled(cron = "${...}")` shape, same `Scheduler` suffix
(ArchUnit requires it), same delegation-only body. Add the cron property to `application.yml` under an existing
block with a sensible default (`0 */15 * * * *`), following the `${ENV_VAR:default}` idiom.

The sweep marks each row `EXPIRED`, clears `expires_at`, and sends the sender
`giftMessageService.requestExpired(GiftOrderService.label(order))`. It restores nothing: a request that never
got an address never touched the cart.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*GiftExpiryIntegrationTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 5: Format and commit**

```bash
make format
git add -A
git commit -m "Tell the sender when a friend never answered about the address"
```

---

### Task 12: A test that the address never leaks, and the docs

**Files:**
- Create: `src/test/java/com/silporestockai/unit/GiftAddressNeverLeaksTest.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/HelpContent.java`
- Modify: `docs/RUNBOOK.md`
- Modify: `docs/OVERNIGHT_SUMMARY.md`

- [ ] **Step 1: Write the leak guard**

Every public method on `GiftMessageService` returns a `String`. Call each one with an address-shaped argument
in every slot and assert none of it comes back out.

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.service.telegram.GiftMessageService;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sender may never see where a gift is going. In two of the three ways a destination is resolved the
 * address is the recipient's own, given on the understanding that it stays with the bot.
 *
 * <p>Written reflectively on purpose: a method added to {@code GiftMessageService} later is covered the day it
 * is added, which is the only version of this guard that survives contact with a future task.
 */
@DisplayName("nothing a gift sender reads carries an address")
class GiftAddressNeverLeaksTest {

    private static final String ADDRESS = "Київ, вулиця Хрещатик, 22";
    private static final String PHONE = "+380671234567";

    @Test
    void noMessageEchoesWhateverItWasHanded() throws Exception {
        GiftMessageService service = new GiftMessageService();
        for (Method method : GiftMessageService.class.getDeclaredMethods()) {
            if (!method.getReturnType().equals(String.class) || !method.canAccess(service)) {
                continue;
            }
            Object[] arguments = Arrays.stream(method.getParameterTypes())
                    .map(type -> type.equals(String.class) ? ADDRESS + ", " + PHONE : null)
                    .toArray();
            String produced = (String) method.invoke(service, arguments);
            assertThat(produced)
                    .as("%s must not echo an address", method.getName())
                    .doesNotContain("Хрещатик")
                    .doesNotContain(PHONE);
        }
    }
}
```

If this fails for `askRecipientForAddress` — whose `senderLabel` is legitimately interpolated — narrow the
assertion to the methods a *sender* reads, and say so in a comment naming the recipient-facing exceptions.

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests '*GiftAddressNeverLeaksTest*'`
Expected: PASS.

- [ ] **Step 3: Add the capability to the help text**

One line in `HelpContent.FULL`, in the same voice as its neighbours:

```
🎁 «Надішли подарунок @ніку, щось до кави» — зберу набір і привезу другу. Адреси його я тобі не покажу.
```

- [ ] **Step 4: Write the live-check section**

Append to `docs/RUNBOOK.md` under a new `### Task 81: send-as-gift` heading: a table of the three paths with
what to type and what to expect, the SQL to read the `gift_order` row
(`SELECT status, resolution, recipient_username, silpo_cart_id FROM gift_order ORDER BY created_at DESC;`), the
MCP call order to grep for in `logs/app.log`
(`silpo_find_address` → `silpo_get_available_delivery_types` → `silpo_get_time_slots` →
`silpo_update_shopping_cart` → `silpo_find_products_batch`), and the check that matters most: after the gift,
place an ordinary order and confirm from `silpo_get_shopping_cart_by_id` that the address came back.

Record honestly, in the same section, what was probed live on 2026-09-10 and what was not: that
`address.phone` is stored by the API but that no paid delivery has yet proved a courier dials it, and that
SelfPickup and NovaPoshta were examined at the schema level and found to carry no recipient identity.

- [ ] **Step 5: Add the session write-up**

One `# Session N` section in `docs/OVERNIGHT_SUMMARY.md`, following the existing format.

- [ ] **Step 6: Run the whole suite**

Run: `make test`
Expected: PASS, including `ArchitectureTest`.

- [ ] **Step 7: Format and commit**

```bash
make format
git add -A
git commit -m "Guard the gift address against leaking, and write down how to check task 81 by hand"
```

---

## Self-Review

**Spec coverage.** Live findings → Task 4 and the RUNBOOK entry in Task 12. `users.telegram_username` → Task 1.
Consent columns → Task 2. `gift_order` → Task 3. Repoint → Task 4. Lazy restore → Task 5. `OrderType.GIFT` and
non-disclosure in the cart message → Task 6. The four resolution paths → Task 7. Cart building order → Task 8.
Both intents → Task 9. Onboarding opt-in, revocation and routing → Tasks 9 and 10. The 24-hour window → Task 11.
The leak guard and the honest scope note → Task 12. No spec section is unclaimed.

**Placeholders.** Tasks 8, 9, 10 and 11 describe two test bodies in outline rather than in full, because each
has to be modelled on an existing test class (`StubMcpServer`, `IntentRouterIntegrationTest`,
`OnboardingFlowIntegrationTest`, `AdHocScheduleScheduler`) whose exact accessors must be read first. Each names
the file to read and the assertions to make. Everything else is complete code.

**Type consistency.** `repointCartTo` returns `Map<String, Object>`, which `GiftOrder.ownDelivery` holds and
`restoreOwnDelivery` takes back — same type in all three places. `giftAddressArguments` is package-private
static on `CartBuildingService` and the unit test lives in `com.silporestockai.unit`, a **different package** —
fix by moving `GiftAddressArgumentsTest` into `com.silporestockai.service`, beside the existing
`CartBuildingServiceVariantTest`. `GiftOrderService.label`, `withoutContactDetails`, `flatIn` and `phoneIn` are
package-private statics used from `OnboardingFlowService` (package `service.onboarding`) — they must be
`public static` instead. Both corrections are made here rather than left for the implementer to discover.
