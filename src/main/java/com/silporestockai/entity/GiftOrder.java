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
 * spans two — the sender asks in theirs, the recipient answers in theirs, minutes or hours apart and possibly on
 * different instances. Same shape and same reasoning as task 68's {@code group_event}.
 *
 * <p>{@code ownDelivery} is the household's own {@code deliveryType / timeslot / address / shipments}, read off
 * the cart before it was repointed. It is the only copy: this account has no saved Silpo delivery addresses to
 * fall back on, so losing this row would leave the household's cart pointed at a friend.
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

    /**
     * How this gift's recipient is named in the sender's chat.
     *
     * <p>A nickname when there is one, and «друга» when the sender gave an address instead — never anything
     * derived from where the parcel is going.
     */
    public String recipientLabel() {
        return recipientUsername == null ? "друга" : "@" + recipientUsername;
    }

    /**
     * Whether this gift is still holding the household's only cart.
     *
     * <p>The snapshot is the evidence, not the status. It exists from the moment the cart was repointed and
     * nowhere else, so anything that got that far is holding the cart — including a gift that never reached a
     * screen. Live on 2026-09-10 that was not a hypothetical: a ₴423 gift was refused by Silpo's ₴799 minimum
     * before it could be presented, and a status-based check left the household's cart pointed at a friend's
     * door with nothing on any path to put it back.
     */
    public boolean holdsTheCart() {
        return ownDelivery != null
                && !ownDelivery.isEmpty()
                && status != GiftOrderStatus.CANCELLED
                && status != GiftOrderStatus.EXPIRED
                && status != GiftOrderStatus.UNREACHABLE;
    }
}
