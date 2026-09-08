package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.GroupEvent;
import com.silporestockai.entity.GroupEventApproval;
import com.silporestockai.entity.GroupEventItem;
import com.silporestockai.entity.GroupEventParticipant;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.GroupEventStatus;
import com.silporestockai.model.GroupProposal;
import com.silporestockai.model.GroupProposalLine;
import com.silporestockai.model.OrderConfirmedEvent;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.ParticipantPreference;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.GroupEventApprovalRepository;
import com.silporestockai.repository.GroupEventItemRepository;
import com.silporestockai.repository.GroupEventParticipantRepository;
import com.silporestockai.repository.GroupEventRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.GroupEventMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import com.silporestockai.utils.GroupEventSettings;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * A drinks round in a Telegram group (task 68): greet, collect, freeze, propose, approve, revise, hand over.
 *
 * <p>The round is a state machine on {@code group_event.status}, not on {@code conversation_state}: a group has no
 * single conversation, and the four tables are the only memory between two webhook calls. Nothing is held in a
 * field.
 *
 * <p>Three rules this class exists to keep. The bot reads only what is addressed to it — a reply to one of its
 * messages, a mention, a command. The vote's denominator is the set of people who had replied at the instant the
 * organizer tapped «всі відповіли», and never changes for that round. And a revision resets every approval,
 * including those of people who had agreed to the previous version — by bumping the version the approvals are
 * counted against, so the history of who agreed to what is kept.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupEventService {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final List<GroupEventStatus> OPEN =
            List.of(GroupEventStatus.COLLECTING_REPLIES, GroupEventStatus.PROPOSED, GroupEventStatus.APPROVED);
    private static final List<GroupEventStatus> BEFORE_AGREEMENT =
            List.of(GroupEventStatus.COLLECTING_REPLIES, GroupEventStatus.PROPOSED);
    /** A second «added» event for the same round inside this window is Telegram delivering it twice. */
    private static final Duration DUPLICATE_ADD_WINDOW = Duration.ofMinutes(2);

    private static final String CATEGORY = "Напої";

    /** Own mapper, as elsewhere in the app: Boot 4 carries both Jackson 2 and Jackson 3. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final GroupEventRepository eventRepository;
    private final GroupEventParticipantRepository participantRepository;
    private final GroupEventItemRepository itemRepository;
    private final GroupEventApprovalRepository approvalRepository;
    private final UserRepository userRepository;
    private final GroupProposalService groupProposalService;
    private final GroupEventMessageService messages;
    private final TelegramOutboundService telegramOutboundService;
    private final CartConfirmationService cartConfirmationService;
    private final SilpoAuthService silpoAuthService;

    /** Everything a group chat can send. */
    public void handle(TelegramIncomingUpdate incoming) {
        switch (incoming) {
            case TelegramIncomingUpdate.BotAddedToGroup added ->
                startRound(added.chatId(), added.chatTitle(), added.byTelegramUserId(), added.byDisplayName(), true);
            case TelegramIncomingUpdate.BotRemovedFromGroup removed -> cancelOpenRounds(removed.chatId());
            case TelegramIncomingUpdate.GroupText text -> onText(text);
            case TelegramIncomingUpdate.GroupButtonTap tap -> onTap(tap);
            default -> log.debug("ignoring a private-chat shape in the group handler: {}", incoming);
        }
    }

    // ---- starting a round ------------------------------------------------------------------------------------

    private void startRound(long chatId, String chatTitle, long organizerId, String organizerName, boolean fromAdd) {
        Optional<GroupEvent> open =
                eventRepository.findFirstByTelegramGroupChatIdAndStatusInOrderByCreatedAtDesc(chatId, BEFORE_AGREEMENT);
        if (fromAdd
                && open.isPresent()
                && open.get().getOrganizerTelegramUserId() == organizerId
                && open.get().getStatus() == GroupEventStatus.COLLECTING_REPLIES
                && open.get().getCreatedAt().isAfter(Instant.now().minus(DUPLICATE_ADD_WINDOW))) {
            log.debug("ignoring a repeated add of the bot to chat {} — the round is already open", chatId);
            return;
        }
        open.ifPresent(previous -> {
            previous.setStatus(GroupEventStatus.CANCELLED);
            eventRepository.save(previous);
        });
        GroupEvent event = GroupEvent.builder()
                .id(UUID.randomUUID())
                .telegramGroupChatId(chatId)
                .chatTitle(chatTitle)
                .organizerTelegramUserId(organizerId)
                .organizerDisplayName(organizerName)
                .organizerUserId(userRepository
                        .findByTelegramChatId(organizerId)
                        .map(User::getId)
                        .orElse(null))
                .status(GroupEventStatus.COLLECTING_REPLIES)
                .createdAt(Instant.now())
                .build();
        eventRepository.save(event);
        int greetingId = telegramOutboundService.sendMessageWithButtons(
                chatId, messages.greeting(organizerName), messages.greetingButtons(event.getId()));
        event.setGreetingMessageId(greetingId);
        eventRepository.save(event);
        log.info("opened group round {} in chat {} for organizer {}", event.getId(), chatId, organizerId);
    }

    private void cancelOpenRounds(long chatId) {
        eventRepository
                .findFirstByTelegramGroupChatIdAndStatusInOrderByCreatedAtDesc(chatId, BEFORE_AGREEMENT)
                .ifPresent(event -> {
                    event.setStatus(GroupEventStatus.CANCELLED);
                    eventRepository.save(event);
                    log.info("cancelled group round {}: the bot left chat {}", event.getId(), chatId);
                });
    }

    // ---- text ------------------------------------------------------------------------------------------------

    private void onText(TelegramIncomingUpdate.GroupText text) {
        if (!text.addressedToBot()) {
            // The rule that makes the bot bearable in a group: anything that is not a reply to one of its own
            // messages is never read, never parsed — a mention and a command included.
            log.debug("ignoring an unaddressed message in group {}", text.chatId());
            return;
        }
        String body =
                text.bodyWithoutAddress(telegramOutboundService.botUsername().orElse(null));
        Optional<GroupEvent> current =
                eventRepository.findFirstByTelegramGroupChatIdAndStatusInOrderByCreatedAtDesc(text.chatId(), OPEN);
        if (current.isEmpty()) {
            telegramOutboundService.sendReply(text.chatId(), text.messageId(), messages.noActiveRound());
            return;
        }
        GroupEvent event = current.get();
        if (event.getStatus() != GroupEventStatus.COLLECTING_REPLIES && text.repliesTo(event.getGreetingMessageId())) {
            // A reply to the greeting after the freeze: kept as a fact, acknowledged as late, never counted.
            upsertParticipant(event, text, body, false);
            telegramOutboundService.sendReply(
                    text.chatId(), text.messageId(), messages.lateReplyAck(text.displayName()));
            return;
        }
        switch (event.getStatus()) {
            case COLLECTING_REPLIES -> collect(event, text, body);
            case PROPOSED -> revise(event, text, body);
            case APPROVED -> {
                if (body.toLowerCase(Locale.ROOT).contains("кошик")) {
                    buildCart(event);
                } else {
                    telegramOutboundService.sendReply(
                            text.chatId(), text.messageId(), messages.alreadyAgreed(organizerName(event)));
                }
            }
            default -> log.debug("ignoring text for group round {} in status {}", event.getId(), event.getStatus());
        }
    }

    private void collect(GroupEvent event, TelegramIncomingUpdate.GroupText text, String body) {
        if (text.telegramUserId() == event.getOrganizerTelegramUserId()) {
            Optional<GroupEventSettings.Parsed> settings = GroupEventSettings.parse(body, LocalDate.now(KYIV));
            if (settings.isPresent()) {
                GroupEventSettings.Parsed parsed = settings.get();
                if (parsed.budget() != null) {
                    event.setBudget(parsed.budget());
                }
                if (parsed.eventTag() != null) {
                    event.setEventTag(parsed.eventTag());
                }
                if (parsed.eventDate() != null) {
                    event.setEventDate(parsed.eventDate());
                }
                eventRepository.save(event);
                telegramOutboundService.sendReply(text.chatId(), text.messageId(), messages.settingsAck(event));
                return;
            }
        }
        upsertParticipant(event, text, body, false);
        long count = participantRepository.findByGroupEventId(event.getId()).size();
        telegramOutboundService.sendReply(
                text.chatId(), text.messageId(), messages.replyAck(text.displayName(), count));
    }

    private void upsertParticipant(
            GroupEvent event, TelegramIncomingUpdate.GroupText text, String body, boolean counted) {
        String reply = body == null || body.isBlank() ? "." : body;
        GroupEventParticipant row = participantRepository
                .findByGroupEventIdAndTelegramUserId(event.getId(), text.telegramUserId())
                .orElseGet(() -> GroupEventParticipant.builder()
                        .id(UUID.randomUUID())
                        .groupEventId(event.getId())
                        .telegramUserId(text.telegramUserId())
                        .countedInDenominator(counted)
                        .build());
        row.setDisplayName(text.displayName());
        row.setRawReplyText(reply);
        row.setRepliedAt(Instant.now());
        participantRepository.save(row);
    }

    /**
     * A revision: note it, bump the version, regenerate. «спробуй ще» regenerates without a note. Only somebody in
     * the frozen set may revise (product decision): a vote that can be reset by a person who has no vote is not a
     * vote.
     */
    private void revise(GroupEvent event, TelegramIncomingUpdate.GroupText text, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        boolean counted = participantRepository
                .findByGroupEventIdAndTelegramUserId(event.getId(), text.telegramUserId())
                .map(GroupEventParticipant::isCountedInDenominator)
                .orElse(false);
        if (!counted) {
            telegramOutboundService.sendReply(text.chatId(), text.messageId(), messages.revisionNotCounted());
            return;
        }
        boolean retry = body.toLowerCase(Locale.ROOT).contains("спробуй ще") || event.getProposalJson() == null;
        if (!retry) {
            List<String> notes = new ArrayList<>(event.revisionNotesOrEmpty());
            notes.add(body);
            event.setRevisionNotes(notes);
            event.setProposalVersion(event.getProposalVersion() + 1);
            eventRepository.save(event);
            telegramOutboundService.sendReply(text.chatId(), text.messageId(), messages.revising(text.displayName()));
            log.info("group round {} revised to version {}: «{}»", event.getId(), event.getProposalVersion(), body);
        }
        proposeAndPost(event);
    }

    // ---- buttons ---------------------------------------------------------------------------------------------

    private void onTap(TelegramIncomingUpdate.GroupButtonTap tap) {
        String data = tap.data() == null ? "" : tap.data();
        if (data.startsWith(GroupEventMessageService.CALLBACK_FREEZE_PREFIX)) {
            freeze(tap, data.substring(GroupEventMessageService.CALLBACK_FREEZE_PREFIX.length()));
            return;
        }
        if (data.startsWith(GroupEventMessageService.CALLBACK_APPROVE_PREFIX)) {
            approve(tap, data.substring(GroupEventMessageService.CALLBACK_APPROVE_PREFIX.length()));
            return;
        }
        if (GroupEventMessageService.CALLBACK_NEW_ROUND.equals(data)) {
            telegramOutboundService.answerCallback(tap.callbackQueryId());
            startRound(tap.chatId(), null, tap.telegramUserId(), tap.displayName(), false);
            return;
        }
        telegramOutboundService.answerCallback(tap.callbackQueryId());
        log.debug("ignoring unknown group callback {} in chat {}", data, tap.chatId());
    }

    /** Only the organizer closes the round, and the denominator is whoever has replied at this instant. */
    private void freeze(TelegramIncomingUpdate.GroupButtonTap tap, String eventId) {
        Optional<GroupEvent> found = parse(eventId).flatMap(eventRepository::findById);
        if (found.isEmpty()) {
            telegramOutboundService.answerCallback(tap.callbackQueryId(), messages.noActiveRound());
            return;
        }
        GroupEvent event = found.get();
        if (event.getOrganizerTelegramUserId() != tap.telegramUserId()) {
            telegramOutboundService.answerCallback(tap.callbackQueryId(), messages.freezeNotOrganizer());
            return;
        }
        if (event.getStatus() != GroupEventStatus.COLLECTING_REPLIES) {
            telegramOutboundService.answerCallback(tap.callbackQueryId(), messages.alreadyClosed());
            return;
        }
        List<GroupEventParticipant> replied = participantRepository.findByGroupEventId(event.getId());
        if (replied.isEmpty()) {
            telegramOutboundService.answerCallback(tap.callbackQueryId(), messages.nobodyReplied());
            return;
        }
        Instant now = Instant.now();
        if (eventRepository.transitionToProposed(
                        event.getId(), GroupEventStatus.COLLECTING_REPLIES, GroupEventStatus.PROPOSED, now)
                == 0) {
            // A double tap, or two organizer taps in flight at once: the first one already froze the round.
            telegramOutboundService.answerCallback(tap.callbackQueryId(), messages.alreadyClosed());
            return;
        }
        telegramOutboundService.answerCallback(tap.callbackQueryId());
        for (GroupEventParticipant row : replied) {
            row.setCountedInDenominator(true);
        }
        participantRepository.saveAll(replied);
        event.setFrozenAt(now);
        event.setStatus(GroupEventStatus.PROPOSED);
        event.setProposalVersion(1);
        log.info("group round {} frozen with {} counted participants", event.getId(), replied.size());
        telegramOutboundService.sendMessage(tap.chatId(), messages.frozen(replied.size()));
        if (organizer(event)
                .filter(user -> silpoAuthService.isConnected(user.getId()))
                .isEmpty()) {
            telegramOutboundService.sendMessage(
                    tap.chatId(), messages.connectHint(organizerName(event), telegramOutboundService.botUsername()));
        }
        proposeAndPost(event);
    }

    private void approve(TelegramIncomingUpdate.GroupButtonTap tap, String payload) {
        int colon = payload.lastIndexOf(':');
        Optional<GroupEvent> found = colon < 0
                ? Optional.empty()
                : parse(payload.substring(0, colon)).flatMap(eventRepository::findById);
        if (found.isEmpty()) {
            telegramOutboundService.answerCallback(tap.callbackQueryId(), messages.noActiveRound());
            return;
        }
        GroupEvent event = found.get();
        int version;
        try {
            version = Integer.parseInt(payload.substring(colon + 1));
        } catch (NumberFormatException e) {
            telegramOutboundService.answerCallback(tap.callbackQueryId());
            return;
        }
        if (event.getStatus() != GroupEventStatus.PROPOSED) {
            telegramOutboundService.answerCallback(
                    tap.callbackQueryId(),
                    event.getStatus() == GroupEventStatus.APPROVED || event.getStatus() == GroupEventStatus.ORDERED
                            ? messages.alreadyAgreed(organizerName(event))
                            : messages.noActiveRound());
            return;
        }
        if (version != event.getProposalVersion()) {
            telegramOutboundService.answerCallback(tap.callbackQueryId(), messages.staleProposal());
            return;
        }
        boolean counted = participantRepository
                .findByGroupEventIdAndTelegramUserId(event.getId(), tap.telegramUserId())
                .map(GroupEventParticipant::isCountedInDenominator)
                .orElse(false);
        if (!counted) {
            telegramOutboundService.answerCallback(tap.callbackQueryId(), messages.notCounted());
            return;
        }
        long total = participantRepository.countByGroupEventIdAndCountedInDenominatorTrue(event.getId());
        if (approvalRepository.existsByGroupEventIdAndTelegramUserIdAndProposalVersion(
                event.getId(), tap.telegramUserId(), version)) {
            long approved = approvalRepository.countByGroupEventIdAndProposalVersion(event.getId(), version);
            telegramOutboundService.answerCallback(tap.callbackQueryId(), messages.alreadyApproved(approved, total));
            return;
        }
        approvalRepository.save(GroupEventApproval.builder()
                .id(UUID.randomUUID())
                .groupEventId(event.getId())
                .telegramUserId(tap.telegramUserId())
                .proposalVersion(version)
                .approvedAt(Instant.now())
                .build());
        long approved = approvalRepository.countByGroupEventIdAndProposalVersion(event.getId(), version);
        telegramOutboundService.answerCallback(tap.callbackQueryId(), messages.approvalToast(approved, total));
        log.info("group round {} version {}: {} of {} approved", event.getId(), version, approved, total);
        if (approved >= total) {
            consensus(event);
        }
    }

    // ---- the proposal ----------------------------------------------------------------------------------------

    private void proposeAndPost(GroupEvent event) {
        List<GroupEventParticipant> counted = participantRepository.findByGroupEventId(event.getId()).stream()
                .filter(GroupEventParticipant::isCountedInDenominator)
                .toList();
        Optional<User> organizer = organizer(event);
        if (organizer.isPresent() && event.getOrganizerUserId() == null) {
            event.setOrganizerUserId(organizer.get().getId());
        }
        GroupProposal proposal;
        try {
            proposal = groupProposalService.propose(event, counted, organizer);
        } catch (RuntimeException e) {
            log.error("could not propose for group round {}", event.getId(), e);
            telegramOutboundService.sendMessage(event.getTelegramGroupChatId(), messages.proposalFailed());
            return;
        }
        event.setProposalJson(MAPPER.convertValue(
                proposal, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        for (ParticipantPreference preference :
                proposal.preferences() == null ? List.<ParticipantPreference>of() : proposal.preferences()) {
            counted.stream()
                    .filter(row -> row.getTelegramUserId() == preference.telegramUserId())
                    .findFirst()
                    .ifPresent(row -> {
                        row.setPreferenceSummary(truncate(preference.preferenceSummary()));
                        participantRepository.save(row);
                    });
        }
        if (proposal.note() != null && !proposal.note().isBlank()) {
            // The model's reasoning is for the operator reading the log, not for the group reading a phone.
            log.info(
                    "group round {} version {} note: {}",
                    event.getId(),
                    proposal.version(),
                    proposal.note().strip());
        }
        int messageId = telegramOutboundService.sendMessageWithButtons(
                event.getTelegramGroupChatId(),
                messages.proposal(event, proposal, counted.size(), telegramOutboundService.botUsername()),
                messages.proposalButtons(event.getId(), proposal.version()));
        event.setProposalMessageId(messageId);
        eventRepository.save(event);
    }

    // ---- consensus -------------------------------------------------------------------------------------------

    private void consensus(GroupEvent event) {
        Instant now = Instant.now();
        if (eventRepository.transitionToApproved(
                        event.getId(), GroupEventStatus.PROPOSED, GroupEventStatus.APPROVED, now)
                == 0) {
            // Two final taps landed together; the other thread is already building the cart.
            log.info("group round {} consensus already handled", event.getId());
            return;
        }
        GroupProposal proposal = storedProposal(event);
        event.setStatus(GroupEventStatus.APPROVED);
        event.setApprovedAt(now);
        if (proposal != null) {
            for (GroupProposalLine line : proposal.lines()) {
                itemRepository.save(GroupEventItem.builder()
                        .id(UUID.randomUUID())
                        .groupEventId(event.getId())
                        .resolvedSilpoProductId(line.silpoProductId())
                        .productName(line.catalogName() == null ? line.requestedName() : line.catalogName())
                        .requestedName(line.requestedName())
                        .quantity(line.quantity())
                        .unit(line.unit())
                        .unitPrice(line.unitPrice())
                        .build());
            }
        }
        log.info("group round {} reached consensus", event.getId());
        buildCart(event);
    }

    /** The hand-over: the approved lines go through the organizer's ordinary cart confirmation, in private. */
    private void buildCart(GroupEvent event) {
        long chatId = event.getTelegramGroupChatId();
        Optional<String> username = telegramOutboundService.botUsername();
        Optional<User> organizer = organizer(event).filter(user -> silpoAuthService.isConnected(user.getId()));
        if (organizer.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, messages.connectHint(organizerName(event), username));
            return;
        }
        GroupProposal proposal = storedProposal(event);
        List<GroupProposalLine> lines = proposal == null ? List.of() : proposal.resolvedLines();
        if (lines.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, messages.nothingResolved(organizerName(event)));
            return;
        }
        List<ShoppingListItem> items = lines.stream()
                .map(line -> ShoppingListItem.builder()
                        .id(UUID.randomUUID())
                        .userId(organizer.get().getId())
                        .name(line.requestedName())
                        .quantity(line.quantity())
                        .unit(line.unit())
                        .category(CATEGORY)
                        .silpoProductId(line.silpoProductId())
                        .build())
                .toList();
        boolean presented = cartConfirmationService.present(organizer.get(), items, OrderType.AD_HOC);
        long headcount = participantRepository.countByGroupEventIdAndCountedInDenominatorTrue(event.getId());
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                presented
                        ? messages.consensus(proposal, organizerName(event), headcount)
                        : messages.cartFailed(organizerName(event)),
                messages.newRoundButtons());
    }

    /** The organizer confirmed in private: say so in the group and close the round. */
    @EventListener
    public void onOrderConfirmed(OrderConfirmedEvent confirmed) {
        try {
            eventRepository
                    .findFirstByOrganizerUserIdAndStatusOrderByApprovedAtDesc(
                            confirmed.userId(), GroupEventStatus.APPROVED)
                    .ifPresent(event -> {
                        event.setStatus(GroupEventStatus.ORDERED);
                        eventRepository.save(event);
                        telegramOutboundService.sendMessageWithButtons(
                                event.getTelegramGroupChatId(),
                                messages.ordered(organizerName(event)),
                                messages.newRoundButtons());
                        log.info("group round {} ordered by organizer {}", event.getId(), confirmed.userId());
                    });
        } catch (RuntimeException e) {
            // A group message is a courtesy; the order it reports on is already stored.
            log.warn("could not report a confirmed group order to its chat", e);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private Optional<User> organizer(GroupEvent event) {
        if (event.getOrganizerUserId() != null) {
            Optional<User> byId = userRepository.findById(event.getOrganizerUserId());
            if (byId.isPresent()) {
                return byId;
            }
        }
        // A private chat's id is the person's Telegram id, so the household row is found by the organizer's id.
        return userRepository.findByTelegramChatId(event.getOrganizerTelegramUserId());
    }

    private static String organizerName(GroupEvent event) {
        return event.getOrganizerDisplayName() == null
                        || event.getOrganizerDisplayName().isBlank()
                ? "організатор"
                : event.getOrganizerDisplayName();
    }

    private static GroupProposal storedProposal(GroupEvent event) {
        if (event.getProposalJson() == null) {
            return null;
        }
        return MAPPER.convertValue(event.getProposalJson(), GroupProposal.class);
    }

    private static Optional<UUID> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String truncate(String summary) {
        if (summary == null) {
            return null;
        }
        String stripped = summary.strip();
        return stripped.length() <= 256 ? stripped : stripped.substring(0, 256);
    }
}
