package com.silporestockai.service.telegram;

import com.silporestockai.config.TelegramProperties;
import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OrderTrigger;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.BlackoutModeService;
import com.silporestockai.service.CalendarIntegrationService;
import com.silporestockai.service.CalendarViewService;
import com.silporestockai.service.CartConfirmationService;
import com.silporestockai.service.CheckinFlowService;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.DishRequestService;
import com.silporestockai.service.FeedbackService;
import com.silporestockai.service.GiftConsentService;
import com.silporestockai.service.GiftOrderService;
import com.silporestockai.service.GroupEventService;
import com.silporestockai.service.IntentRouterService;
import com.silporestockai.service.MealPlanHandoffService;
import com.silporestockai.service.OrderHistoryService;
import com.silporestockai.service.PastOrderSeedService;
import com.silporestockai.service.ReorderConfirmationService;
import com.silporestockai.service.ScheduledTaskManagementService;
import com.silporestockai.service.ShoppingListBuilderService;
import com.silporestockai.service.SpecialModeService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.onboarding.OnboardingFlowService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Turns a Telegram update into one of the internal {@link TelegramIncomingUpdate} shapes and dispatches it.
 *
 * <p>This class and {@code TelegramWebhookController} are the only places that see the Telegram SDK. Everything
 * downstream receives records that carry no SDK types.
 *
 * <p>Dispatch is deliberately thin: the user row is resolved here, then — in this order — the onboarding gate, the
 * persistent-menu buttons and self-contained button taps (global navigation, always live), the active conversation
 * flow, the typed slash commands, and finally free text through {@code IntentRouterService}. No business logic
 * lives here; every branch is one call into the service that owns that capability.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramRoutingService {

    private final UserAccountService userAccountService;
    private final OnboardingFlowService onboardingFlowService;
    private final ConversationStateService conversationStateService;
    private final CartConfirmationService cartConfirmationService;
    private final CheckinFlowService checkinFlowService;
    private final ReorderConfirmationService reorderConfirmationService;
    private final CalendarIntegrationService calendarIntegrationService;
    private final BlackoutModeService blackoutModeService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final OrderHistoryService orderHistoryService;
    private final VoiceReplyService voiceReplyService;
    private final UserRepository userRepository;
    private final TelegramOutboundService telegramOutboundService;
    private final SpecialModeService specialModeService;
    private final IntentRouterService intentRouterService;
    private final CalendarViewService calendarViewService;
    private final TelegramFailureRecoveryService failureRecoveryService;
    private final ScheduledTaskManagementService scheduledTaskManagementService;
    private final FeedbackService feedbackService;
    private final PastOrderSeedService pastOrderSeedService;
    private final DishRequestService dishRequestService;
    private final MealPlanHandoffService mealPlanHandoffService;
    private final GroupEventService groupEventService;
    private final GiftOrderService giftOrderService;
    private final GiftConsentService giftConsentService;
    private final TelegramProperties telegramProperties;

    /**
     * Off the webhook thread on purpose. A fridge photo means a vision call — the slowest and most expensive kind
     * of call this application makes — and Telegram redelivers an update it does not get a fast response for.
     * Without this, a slow photo reply was the one path most likely to trigger that redelivery, and this codebase
     * tracks no update id anywhere: a redelivered update was a second full vision call for the same photo, silently.
     * The controller now gets its 200 back in milliseconds regardless of how long the actual work takes.
     */
    @Async("applicationTaskExecutor")
    public void route(Update update) {
        // A group chat is not a household (task 68): it gets no user row, no onboarding, no intent router. Split
        // off before any of that, so a group can never be mistaken for a person who has not filled in the form.
        Optional<TelegramIncomingUpdate> group = toGroupIncoming(update);
        if (group.isPresent()) {
            TelegramIncomingUpdate incoming = group.get();
            try {
                groupEventService.handle(incoming);
            } catch (RuntimeException e) {
                log.error("failed to handle a group update in chat {}", incoming.chatId(), e);
                // Live, a network blip mid-recalculation left the group with «тегни мене ще раз» — and a tag
                // while a round is open is chatter by design, so the advice pointed at a dead end. The hint names
                // the one thing that works in the state the round is actually in.
                telegramOutboundService.sendMessage(
                        incoming.chatId(), groupEventService.recoveryHint(incoming.chatId()));
            }
            return;
        }
        // Read off the raw update, before the SDK types are dropped: the internal shapes carry a Telegram user
        // id but never a name, and task 81 addresses a gift by exactly that name.
        String telegramUsername = usernameOf(update);
        toIncoming(update)
                .ifPresentOrElse(
                        incoming -> {
                            try {
                                handle(incoming, telegramUsername);
                            } catch (RuntimeException e) {
                                // This method runs @Async: nothing above it ever sees this exception, so
                                // without this catch it would be silently logged by Spring's default
                                // async-uncaught-exception handler and the chat would just go quiet
                                // (task 26). See TelegramFailureRecoveryService's own javadoc.
                                failureRecoveryService.recover(incoming.chatId(), e);
                            }
                        },
                        () -> log.debug("ignoring unsupported Telegram update"));
    }

    /**
     * Turns spoken replies on or off for this chat.
     *
     * <p>Two switches have to agree before anything is spoken: this one, and a configured Respeecher key. Neither
     * defaults to on — a voice note nobody asked for is an interruption.
     */
    private void toggleVoice(User user, long chatId) {
        if (!voiceReplyService.enabled()) {
            telegramOutboundService.sendMessage(chatId, "Голосові відповіді зараз не налаштовані на сервері.");
            return;
        }
        boolean turningOn = !user.isVoiceRepliesEnabled();
        user.setVoiceRepliesEnabled(turningOn);
        userRepository.save(user);
        telegramOutboundService.sendMessage(
                chatId,
                turningOn
                        ? "Тепер відповідатиму ще й голосом. Щоб вимкнути — надішли /voice ще раз."
                        : "Вимкнув голосові відповіді.");
    }

    /**
     * The group-chat shapes (task 68): the bot being added to or removed from a group, a text in a group, a tap on
     * one of the bot's group messages. Empty for anything from a private chat, which goes down the household path.
     */
    private Optional<TelegramIncomingUpdate> toGroupIncoming(Update update) {
        long botId = telegramProperties.botId();
        if (update.hasMyChatMember()) {
            ChatMemberUpdated change = update.getMyChatMember();
            Chat chat = change.getChat();
            if (chat == null || !TelegramIncomingUpdate.isGroupChatType(chat.getType())) {
                return Optional.empty();
            }
            String status = change.getNewChatMember() == null
                    ? ""
                    : String.valueOf(change.getNewChatMember().getStatus());
            if ("member".equals(status) || "administrator".equals(status) || "restricted".equals(status)) {
                return Optional.of(new TelegramIncomingUpdate.BotAddedToGroup(
                        chat.getId(),
                        chat.getTitle(),
                        change.getFrom() == null ? 0L : change.getFrom().getId(),
                        displayName(change.getFrom())));
            }
            if ("left".equals(status) || "kicked".equals(status)) {
                return Optional.of(new TelegramIncomingUpdate.BotRemovedFromGroup(chat.getId()));
            }
            return Optional.empty();
        }
        if (update.hasMessage()) {
            Message message = update.getMessage();
            Chat chat = message.getChat();
            if (chat == null || !TelegramIncomingUpdate.isGroupChatType(chat.getType())) {
                return Optional.empty();
            }
            long chatId = chat.getId();
            if (message.getNewChatMembers() != null
                    && botId > 0
                    && message.getNewChatMembers().stream().anyMatch(member -> member.getId() == botId)) {
                // Some clients send this service message with — or instead of — my_chat_member; the group
                // handler treats a second add of the same round as a no-op.
                return Optional.of(new TelegramIncomingUpdate.BotAddedToGroup(
                        chatId,
                        chat.getTitle(),
                        message.getFrom() == null ? 0L : message.getFrom().getId(),
                        displayName(message.getFrom())));
            }
            if (message.getLeftChatMember() != null
                    && botId > 0
                    && message.getLeftChatMember().getId() == botId) {
                return Optional.of(new TelegramIncomingUpdate.BotRemovedFromGroup(chatId));
            }
            if (!message.hasText()) {
                // Photos, voice, stickers, joins of other people: a group round is a text conversation. Claimed as
                // group traffic all the same, so nothing from a group ever reaches the household path.
                return Optional.of(new TelegramIncomingUpdate.GroupText(
                        chatId,
                        userIdOf(message.getFrom()),
                        displayName(message.getFrom()),
                        messageIdOf(message),
                        "",
                        null,
                        false,
                        null));
            }
            String text = message.getText();
            Integer replyToBot = null;
            Message repliedTo = message.getReplyToMessage();
            if (repliedTo != null
                    && repliedTo.getFrom() != null
                    && botId > 0
                    && repliedTo.getFrom().getId() == botId) {
                replyToBot = repliedTo.getMessageId();
            }
            Optional<String> username = telegramOutboundService.botUsername();
            boolean mentionsBot = false;
            String command = null;
            for (MessageEntity entity :
                    message.getEntities() == null ? List.<MessageEntity>of() : message.getEntities()) {
                String span = entitySpan(text, entity);
                if ("mention".equals(entity.getType())) {
                    mentionsBot |= username.isPresent() && span.equalsIgnoreCase("@" + username.get());
                } else if ("bot_command".equals(entity.getType()) && command == null) {
                    int at = span.indexOf('@');
                    String target = at < 0 ? null : span.substring(at + 1);
                    boolean forThisBot =
                            target == null || username.isPresent() && target.equalsIgnoreCase(username.get());
                    if (forThisBot) {
                        command = (at < 0 ? span : span.substring(0, at)).toLowerCase(java.util.Locale.ROOT);
                    }
                }
            }
            return Optional.of(new TelegramIncomingUpdate.GroupText(
                    chatId,
                    userIdOf(message.getFrom()),
                    displayName(message.getFrom()),
                    messageIdOf(message),
                    text,
                    replyToBot,
                    mentionsBot,
                    command));
        }
        if (update.hasCallbackQuery()) {
            CallbackQuery callback = update.getCallbackQuery();
            if (callback.getMessage() == null
                    || callback.getMessage().getChat() == null
                    || !TelegramIncomingUpdate.isGroupChatType(
                            callback.getMessage().getChat().getType())) {
                return Optional.empty();
            }
            return Optional.of(new TelegramIncomingUpdate.GroupButtonTap(
                    callback.getMessage().getChatId(),
                    userIdOf(callback.getFrom()),
                    displayName(callback.getFrom()),
                    callback.getId(),
                    callback.getData(),
                    callback.getMessage().getMessageId() == null
                            ? 0
                            : callback.getMessage().getMessageId()));
        }
        return Optional.empty();
    }

    /** Entity offsets are UTF-16 code units, which is exactly what {@link String#substring} counts. */
    private static String entitySpan(String text, MessageEntity entity) {
        if (entity.getOffset() == null || entity.getLength() == null) {
            return "";
        }
        int from = Math.max(0, Math.min(text.length(), entity.getOffset()));
        int to = Math.max(from, Math.min(text.length(), entity.getOffset() + entity.getLength()));
        return text.substring(from, to);
    }

    /** The {@code @nickname} on whichever part of the update carries a sender, without the {@code @}, or null. */
    private static String usernameOf(Update update) {
        org.telegram.telegrambots.meta.api.objects.User from = null;
        if (update.hasMessage()) {
            from = update.getMessage().getFrom();
        } else if (update.hasCallbackQuery()) {
            from = update.getCallbackQuery().getFrom();
        }
        return from == null ? null : from.getUserName();
    }

    private static long userIdOf(org.telegram.telegrambots.meta.api.objects.User user) {
        return user == null ? 0L : user.getId();
    }

    private static int messageIdOf(Message message) {
        return message.getMessageId() == null ? 0 : message.getMessageId();
    }

    /** {@code @username} when the person has one, else their name — what the group already calls them. */
    private static String displayName(org.telegram.telegrambots.meta.api.objects.User user) {
        if (user == null) {
            return "учасник";
        }
        if (user.getUserName() != null && !user.getUserName().isBlank()) {
            return "@" + user.getUserName();
        }
        String first = user.getFirstName() == null ? "" : user.getFirstName().strip();
        String last = user.getLastName() == null ? "" : user.getLastName().strip();
        String name = (first + " " + last).strip();
        return name.isEmpty() ? "учасник" : name;
    }

    private Optional<TelegramIncomingUpdate> toIncoming(Update update) {
        if (update.hasMessage()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            long userId = message.getFrom() == null ? 0L : message.getFrom().getId();
            if (message.hasText()) {
                return Optional.of(new TelegramIncomingUpdate.Text(chatId, userId, message.getText()));
            }
            if (message.hasPhoto()) {
                // Telegram sends the same picture in several sizes, smallest first. The model wants the pixels.
                var largest = message.getPhoto().getLast();
                return Optional.of(new TelegramIncomingUpdate.Photo(
                        chatId, userId, largest.getFileId(), "image/jpeg", message.getCaption()));
            }
            if (message.hasVoice()) {
                var voice = message.getVoice();
                int duration = voice.getDuration() == null ? 0 : voice.getDuration();
                return Optional.of(new TelegramIncomingUpdate.Voice(chatId, userId, voice.getFileId(), duration));
            }
            if (message.hasWebAppData()) {
                return Optional.of(new TelegramIncomingUpdate.WebAppData(
                        chatId, userId, message.getWebAppData().getData()));
            }
            return Optional.empty();
        }
        if (update.hasCallbackQuery()) {
            CallbackQuery callback = update.getCallbackQuery();
            if (callback.getMessage() == null) {
                return Optional.empty();
            }
            long userId = callback.getFrom() == null ? 0L : callback.getFrom().getId();
            return Optional.of(new TelegramIncomingUpdate.ButtonTap(
                    callback.getMessage().getChatId(), userId, callback.getId(), callback.getData()));
        }
        return Optional.empty();
    }

    /**
     * «Список» with nothing on the table asks «Що беремо на цей тиждень?» and then owned every sentence: typed
     * over it, «що їмо в середу?» became a shopping list built from those words. Same rule as the open check-in
     * prompt (task 53): the sentence is offered to the intent router first, a confident request wins and the
     * question steps aside, and only a sentence that is not a request — or is itself a list request — is the
     * description the question asked for.
     */
    private boolean aRequestWinsOverTheListQuestion(User user, long chatId, String text) {
        shoppingListBuilderService.stepAsideForARequest(chatId);
        if (intentRouterService.tryRoute(user, text, Set.of("LIST_VIEW", "LIST_MODIFY"))) {
            log.info("the list question in chat {} stepped aside for a request typed over it", chatId);
            return true;
        }
        shoppingListBuilderService.reopenQuestion(chatId);
        return false;
    }

    private void handle(TelegramIncomingUpdate incoming, String telegramUsername) {
        User user = userAccountService.findOrCreate(incoming.chatId(), telegramUsername);
        // Feedback (task 47) sits above the onboarding gate on purpose: somebody stuck on the first screen is
        // exactly who should be able to say so. The prompt snapshots and restores whatever flow it interrupts.
        if (incoming instanceof TelegramIncomingUpdate.Text feedback
                && matches(feedback.text(), "/feedback", MainMenuKeyboard.FEEDBACK)) {
            feedbackService.prompt(user);
            return;
        }
        ConversationFlow flow = conversationStateService.load(incoming.chatId()).getCurrentFlow();
        if (flow == ConversationFlow.FEEDBACK) {
            if (incoming instanceof TelegramIncomingUpdate.Text menu && MainMenuKeyboard.isButton(menu.text())) {
                // "Never mind" — the previous flow comes back and the button below does what it always does.
                feedbackService.abandonIfPending(incoming.chatId());
                flow = conversationStateService.load(incoming.chatId()).getCurrentFlow();
            } else {
                feedbackService.handle(user, incoming);
                return;
            }
        }
        if (!onboardingFlowService.isOnboarded(user.getId())) {
            onboardingFlowService.handle(user, incoming);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text start && matches(start.text(), "/start", "")) {
            // A second /start — after a cleared chat, a reinstall, or the failure-recovery message's own advice to
            // type it — used to fall through to the classifier and get "Не зовсім зрозумів" for the one word
            // every Telegram user knows. It is also the one message guaranteed to bring the keyboard back.
            // And it closes whatever question was open: live, /start typed mid mass-gain setup said «Я тут» and
            // the next sentence was still answered «Не зрозумів число» — the flow the person was escaping from
            // had kept the chat. /start is the way out; the failure-recovery message names it as such.
            conversationStateService.save(incoming.chatId(), ConversationFlow.NONE, null, Map.of());
            telegramOutboundService.sendMessageWithMainMenu(
                    incoming.chatId(), "Я тут. Кнопки внизу — або просто напиши, що потрібно.");
            return;
        }
        // The persistent menu is global navigation — always tappable, even mid-flow. Checked before any
        // flow-specific dispatch below so a tap can never be swallowed as free text by whichever conversation
        // happens to be active (a list-edit AI parse, a check-in answer, ...). None of the flow handlers below
        // reset conversation_state on their own, so interrupting one to navigate away and back leaves it
        // exactly where the user left it.
        if (incoming instanceof TelegramIncomingUpdate.Text list
                && matches(list.text(), "/list", MainMenuKeyboard.LIST)) {
            shoppingListBuilderService.showCurrentOrAsk(user);
            return;
        }
        // The fuller, management-view form of task 56's read (task 57): same service, same single history
        // call, more of what it found — the button is how a person checks a state they keep coming back to.
        if (incoming instanceof TelegramIncomingUpdate.Text orders
                && matches(orders.text(), "/orders", MainMenuKeyboard.ORDERS)) {
            orderHistoryService.showStatus(user, true);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text form
                && matches(form.text(), "/anketa", MainMenuKeyboard.FORM)) {
            onboardingFlowService.reopenForm(user);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text scheduled
                && matches(scheduled.text(), "/scheduled", MainMenuKeyboard.SCHEDULED)) {
            scheduledTaskManagementService.showPending(user);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text help
                && matches(help.text(), "/help", MainMenuKeyboard.HELP)) {
            intentRouterService.sendHelp(user);
            return;
        }
        // Same reasoning as the text buttons above, for button taps whose callback data is self-contained
        // (carries its own id, needs no conversation_state to interpret): a stuck flow must not be able to
        // swallow a tap on a message that flow didn't even send. cart:*/re:*/onb:* taps are correctly NOT
        // here — those rely on conversation_state to know which draft/order/step they refer to.
        if (incoming instanceof TelegramIncomingUpdate.ButtonTap tap
                && tap.data().startsWith("sched:")) {
            scheduledTaskManagementService.handleButtonTap(user, tap);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.ButtonTap tap
                && tap.data().startsWith(CalendarViewService.CALLBACK_DAY_PREFIX)) {
            telegramOutboundService.answerCallback(tap.callbackQueryId());
            calendarViewService.showDay(user, tap.data().substring(CalendarViewService.CALLBACK_DAY_PREFIX.length()));
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.ButtonTap tap
                && CalendarIntegrationService.CALLBACK_LATER.equals(tap.data())) {
            // Task 71's «Пізніше». Global, like every self-contained tap here: the offer is sent while the list
            // flow is the active one, and it must stay answerable after the household has moved on from it.
            telegramOutboundService.answerCallback(tap.callbackQueryId());
            calendarIntegrationService.declineConnection(user);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.ButtonTap tap
                && MealPlanHandoffService.CALLBACK_RETRY.equals(tap.data())) {
            telegramOutboundService.answerCallback(tap.callbackQueryId());
            mealPlanHandoffService.retry(user);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.ButtonTap tap
                && ShoppingListMessageService.isListCallback(tap.data())) {
            // The list keyboard is the one every household ends every flow looking at, and its taps carry
            // everything they need. Dispatching them here rather than from the LIST_BUILDING branch below is what
            // lets the list stop owning conversation_state at all while its buttons keep working.
            shoppingListBuilderService.handleButtonTap(user, tap);
            return;
        }

        if (flow == ConversationFlow.CART_CONFIRMATION) {
            cartConfirmationService.handle(user, incoming);
            return;
        }
        if (flow == ConversationFlow.CHECK_IN) {
            checkinFlowService.handle(user, incoming);
            return;
        }
        // Narrower than every other flow gate here on purpose: LIST_BUILDING is the one flow that stays set after
        // its conversation is over — a list on screen with a keyboard under it is not a question, and nothing
        // clears the state. Claiming every update in it meant the classifier was unreachable from the moment a
        // household saw their first weekly plan. Only the two steps that actually asked something claim input.
        if (flow == ConversationFlow.LIST_BUILDING && shoppingListBuilderService.awaitsAnAnswer(incoming.chatId())) {
            if (incoming instanceof TelegramIncomingUpdate.Text typed
                    && shoppingListBuilderService.awaitsFirstInput(incoming.chatId())
                    && aRequestWinsOverTheListQuestion(user, incoming.chatId(), typed.text())) {
                return;
            }
            shoppingListBuilderService.handle(user, incoming);
            return;
        }
        if (flow == ConversationFlow.REORDER_CONFIRMATION) {
            reorderConfirmationService.handle(user, incoming);
            return;
        }
        if (flow == ConversationFlow.SPECIAL_MODE_SETUP) {
            specialModeService.handle(user, incoming);
            return;
        }
        if (flow == ConversationFlow.PROFILE_REEDIT) {
            onboardingFlowService.handleReedit(user, incoming);
            return;
        }
        if (flow == ConversationFlow.SCHEDULED_TASK_EDIT) {
            scheduledTaskManagementService.handleEditReply(user, incoming);
            return;
        }
        if (flow == ConversationFlow.PAST_ORDER_PICK) {
            pastOrderSeedService.handle(user, incoming);
            return;
        }
        // Task 81. Three gift conversations, each owning its chat until it is answered: the friend being asked
        // where a parcel should go, the sender being asked for the friend's number, and somebody leaving or
        // clearing the address friends may use.
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
        if (flow == ConversationFlow.DISH_CONFIRM) {
            byte[] photo = incoming instanceof TelegramIncomingUpdate.Photo p
                    ? telegramOutboundService.downloadFile(p.fileId())
                    : null;
            dishRequestService.handle(user, incoming, photo);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text voice && matches(voice.text(), "/voice", "")) {
            toggleVoice(user, incoming.chatId());
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text reorder && matches(reorder.text(), "/reorder", "")) {
            reorderConfirmationService.startNow(user, OrderTrigger.of("REORDER", Instant.now()));
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text blackout && matches(blackout.text(), "/blackout", "")) {
            // Explicit only. Inferring an outage from a sentence and sending an unwanted order would land at the
            // worst possible moment, which is the one this mode exists for.
            telegramOutboundService.sendMessage(incoming.chatId(), "Збираю щось на поїсти без плити й холодильника.");
            blackoutModeService.buildBlackoutOrder(user, OrderTrigger.of("BLACKOUT", Instant.now()));
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text text && matches(text.text(), "/calendar", "")) {
            calendarIntegrationService.offerConnection(user);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text normal && matches(normal.text(), "/normal", "")) {
            specialModeService.cancel(user);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text uaOnly && matches(uaOnly.text(), "/uaonly", "")) {
            specialModeService.toggleUaOnly(user);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text masgain && matches(masgain.text(), "/masgain", "")) {
            specialModeService.startMassGainSetup(user);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.ButtonTap tap) {
            // A keyboard left over from a conversation that has already ended — a second tap on confirm, most
            // often. Acknowledge it so Telegram stops spinning and say nothing: answering a button nobody is
            // waiting on with small talk is worse than silence.
            telegramOutboundService.answerCallback(tap.callbackQueryId());
            log.debug("ignoring stale button tap {} in chat {}", tap.data(), tap.chatId());
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text freeText) {
            intentRouterService.route(user, freeText.text());
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Voice voice) {
            // The product brief puts text and voice on equal footing ("відповідає текстом або голосовим") — but
            // outside a check-in a voice note used to get "use the buttons below". It is the same request as the
            // typed sentence; transcribe it and route it as one.
            if (!intentRouterService.voiceSupported()) {
                telegramOutboundService.sendMessage(
                        incoming.chatId(), "Голосові поки не розбираю. Напиши, будь ласка, текстом.");
                return;
            }
            intentRouterService.routeVoice(user, telegramOutboundService.downloadFile(voice.fileId()));
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Photo photo && photo.hasCaption()) {
            // The caption says what the picture is for (task 36): «замов усе для цього» under a plated dish is
            // a dish-ingredients order; anything else keeps the photo on the list builder, as below.
            intentRouterService.routePhoto(
                    user, photo.caption(), telegramOutboundService.downloadFile(photo.fileId()), photo.mediaType());
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Photo) {
            // A photo with no conversation open is a fridge, a shelf or a receipt — exactly what the list builder
            // asks for when it opens. Build a list from it and show it; nothing is ordered without approval.
            shoppingListBuilderService.handle(user, incoming);
            return;
        }
        telegramOutboundService.sendMessageWithMainMenu(
                incoming.chatId(), "Скористайся кнопками нижче або напиши, що потрібно.");
    }

    /** A command matches whether it was typed as a slash command or tapped as its own main-menu button. */
    private static boolean matches(String text, String command, String buttonLabel) {
        String stripped = text.strip();
        return stripped.startsWith(command) || stripped.equals(buttonLabel);
    }
}
