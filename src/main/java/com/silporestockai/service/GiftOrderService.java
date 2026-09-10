package com.silporestockai.service;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.GiftOrder;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
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
 * <p>Three ways to arrive at that door, tried in the order of how little of the recipient's privacy each spends:
 * an address the sender already knows, an address the recipient has already agreed to share, and — only then —
 * asking the recipient directly. A friend who has never spoken to the bot has no fourth path, and is said out
 * loud rather than guessed at, because a silent failure here looks exactly like a gift that was sent.
 */
@Slf4j
@Service
public class GiftOrderService {

    /** How long an unanswered request waits before the sender is told nobody replied. */
    static final Duration ANSWER_WINDOW = Duration.ofHours(24);

    static final String STEP_AWAITING_PHONE = "AWAITING_GIFT_PHONE";
    private static final String KEY_GIFT_ORDER = "giftOrderId";

    /** «кв. 42», «квартира 42», «кв.42а» — what a person actually types after a street. */
    private static final Pattern FLAT =
            Pattern.compile("кв(?:артира)?\\.?\\s*(\\d+[а-яa-z]?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s()-]{8,}\\d");

    /** The ways a person declines. Anything else in their chat is read as an address. */
    private static final Pattern DECLINES =
            Pattern.compile("^(ні|ні,|нi|no|не треба|не хочу|відмов)", Pattern.CASE_INSENSITIVE);

    private final ClaudeApiClient claudeApiClient;
    private final GiftOrderRepository giftOrderRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final GiftMessageService giftMessageService;
    private final TelegramOutboundService telegramOutboundService;
    private final ConversationStateService conversationStateService;
    private final GiftCartBuildingService giftCartBuildingService;
    private final String systemPrompt;

    public GiftOrderService(
            ClaudeApiClient claudeApiClient,
            GiftOrderRepository giftOrderRepository,
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            GiftMessageService giftMessageService,
            TelegramOutboundService telegramOutboundService,
            ConversationStateService conversationStateService,
            GiftCartBuildingService giftCartBuildingService,
            @Value("classpath:prompts/gift-request-system.txt") Resource systemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.giftOrderRepository = giftOrderRepository;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.giftMessageService = giftMessageService;
        this.telegramOutboundService = telegramOutboundService;
        this.conversationStateService = conversationStateService;
        this.giftCartBuildingService = giftCartBuildingService;
        this.systemPrompt = read(systemPromptResource);
    }

    /** Task 31's entry point: a sentence classified as GIFT_ORDER. */
    public void start(User sender, String sentence, OrderTrigger trigger) {
        GiftRequest request;
        try {
            request = claudeApiClient.completeStructuredFast(systemPrompt, sentence, GiftRequest.class);
        } catch (RuntimeException e) {
            log.warn("could not read a gift request from user {}", sender.getId(), e);
            telegramOutboundService.sendMessage(sender.getTelegramChatId(), giftMessageService.couldNotRead());
            return;
        }
        resolve(sender, request, trigger);
    }

    /**
     * {@link #start} with the reading already done.
     *
     * <p>The split is worth having on its own terms: turning a sentence into a {@link GiftRequest} is a model
     * call, and everything interesting about this service — which of the four paths a request takes, and what
     * each of the two chats is told — has nothing to do with it.
     */
    public void startFrom(User sender, GiftRequest request, OrderTrigger trigger) {
        resolve(sender, request, trigger);
    }

    private void resolve(User sender, GiftRequest raw, OrderTrigger trigger) {
        // The model has more than one way of writing «nothing», and one of them is a non-blank string.
        GiftRequest request = raw == null ? null : raw.cleaned();
        String theme =
                request == null || request.theme() == null || request.theme().isBlank()
                        ? "подарунковий набір"
                        : request.theme().trim();

        // (a) The sender typed an address. Nobody else's privacy is at stake — they already know where it goes.
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
            giftCartBuildingService.build(sender, order, trigger);
            return;
        }

        if (request == null
                || request.recipientUsername() == null
                || request.recipientUsername().isBlank()) {
            telegramOutboundService.sendMessage(sender.getTelegramChatId(), giftMessageService.whoIsItFor());
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
            telegramOutboundService.sendMessage(
                    sender.getTelegramChatId(), giftMessageService.recipientUnreachable(label));
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
            // They agreed to store an address, not to this delivery — and somebody has to be home for a courier.
            telegramOutboundService.sendMessage(
                    friend.getTelegramChatId(),
                    giftMessageService.tellRecipientAboutTheGift(senderLabel(sender), "найближчим часом"));
            giftCartBuildingService.build(sender, order, trigger);
            return;
        }

        // (c) Known but never opted in: ask them, in their own chat, and tell the sender to expect a wait.
        GiftOrder order = open(sender, username, GiftResolution.ASKED, theme);
        order.setRecipientUserId(friend.getId());
        order.setRecipientChatId(friend.getTelegramChatId());
        order.setStatus(GiftOrderStatus.AWAITING_ADDRESS);
        order.setExpiresAt(Instant.now().plus(ANSWER_WINDOW));
        giftOrderRepository.save(order);

        conversationStateService.save(
                friend.getTelegramChatId(), ConversationFlow.GIFT_ADDRESS_REQUEST, null, Map.of());
        telegramOutboundService.sendMessage(
                friend.getTelegramChatId(), giftMessageService.askRecipientForAddress(senderLabel(sender), theme));
        telegramOutboundService.sendMessage(
                sender.getTelegramChatId(), giftMessageService.waitingOnTheRecipient(label));
    }

    /** The recipient's own chat answering «куди привезти». */
    public void handleRecipientReply(User recipient, TelegramIncomingUpdate incoming) {
        Optional<GiftOrder> pending = giftOrderRepository.findFirstByRecipientChatIdAndStatusOrderByCreatedAtDesc(
                incoming.chatId(), GiftOrderStatus.AWAITING_ADDRESS);
        if (pending.isEmpty()) {
            // The question is gone — expired, cancelled, or already answered on another device. Let the chat go
            // back to normal rather than holding it on a conversation that no longer exists.
            conversationStateService.save(incoming.chatId(), ConversationFlow.NONE, null, Map.of());
            return;
        }
        if (!(incoming instanceof TelegramIncomingUpdate.Text text)) {
            telegramOutboundService.sendMessage(
                    incoming.chatId(),
                    "Напиши адресу текстом, будь ласка — місто, вулицю, будинок, квартиру й телефон.");
            return;
        }
        GiftOrder order = pending.get();
        String answer = text.text().strip();
        if (DECLINES.matcher(answer).find()) {
            order.setStatus(GiftOrderStatus.CANCELLED);
            order.setUpdatedAt(Instant.now());
            giftOrderRepository.save(order);
            conversationStateService.save(incoming.chatId(), ConversationFlow.NONE, null, Map.of());
            telegramOutboundService.sendMessage(incoming.chatId(), "Добре, нічого не замовляю.");
            senderOf(order)
                    .ifPresent(sender -> telegramOutboundService.sendMessage(
                            sender.getTelegramChatId(), giftMessageService.recipientDeclined(label(order))));
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
            giftCartBuildingService.build(sender, order, null);
        });
    }

    /** The sender's own chat answering «який телефон у друга». */
    public void handleSenderReply(User sender, TelegramIncomingUpdate incoming) {
        if (!(incoming instanceof TelegramIncomingUpdate.Text text)) {
            telegramOutboundService.sendMessage(incoming.chatId(), "Напиши номер текстом або «не знаю».");
            return;
        }
        Object stored =
                conversationStateService.load(incoming.chatId()).getContext().get(KEY_GIFT_ORDER);
        conversationStateService.save(incoming.chatId(), ConversationFlow.NONE, null, Map.of());
        if (stored == null) {
            return;
        }
        GiftOrder order =
                giftOrderRepository.findById(UUID.fromString(stored.toString())).orElse(null);
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
        giftCartBuildingService.build(sender, order, null);
    }

    /**
     * Closes the requests nobody answered, and tells each sender so.
     *
     * <p>Nothing is restored here: a request that never got an address never touched the cart. What it did do is
     * leave somebody waiting on a friend who is not going to reply, and saying nothing would leave them
     * indefinitely unsure whether the present was on its way.
     */
    public void expireUnanswered() {
        for (GiftOrder order : giftOrderRepository.findAllByStatusAndExpiresAtBefore(
                GiftOrderStatus.AWAITING_ADDRESS, Instant.now())) {
            order.setStatus(GiftOrderStatus.EXPIRED);
            order.setExpiresAt(null);
            order.setUpdatedAt(Instant.now());
            giftOrderRepository.save(order);
            if (order.getRecipientChatId() != null) {
                conversationStateService.save(order.getRecipientChatId(), ConversationFlow.NONE, null, Map.of());
            }
            senderOf(order)
                    .ifPresent(sender -> telegramOutboundService.sendMessage(
                            sender.getTelegramChatId(), giftMessageService.requestExpired(label(order))));
            log.info("gift {} expired with no address from {}", order.getId(), label(order));
        }
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

    private static String senderLabel(User sender) {
        return sender.getTelegramUsername() == null ? "Хтось" : "@" + sender.getTelegramUsername();
    }

    private static String label(GiftOrder order) {
        return order.recipientLabel();
    }

    /**
     * The street part of what somebody typed.
     *
     * <p>{@code silpo_find_address} is a geocoder, and a phone number or an apartment glued onto the end of an
     * address is enough to stop it matching anything. Both are carried separately anyway — the cart's address
     * object has its own {@code flat} and {@code phone} fields.
     */
    public static String withoutContactDetails(String answer) {
        String stripped = PHONE.matcher(answer).replaceAll(" ");
        stripped = FLAT.matcher(stripped).replaceAll(" ");
        return stripped.replaceAll("[,;]\\s*(?=[,;]|$)", "")
                .replaceAll("\\s+", " ")
                .replaceAll("[,;\\s]+$", "")
                .strip();
    }

    /** The apartment in what somebody typed, or null. */
    public static String flatIn(String answer) {
        Matcher matcher = FLAT.matcher(answer);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** The phone number in what somebody typed, punctuation removed, or null. */
    public static String phoneIn(String answer) {
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
