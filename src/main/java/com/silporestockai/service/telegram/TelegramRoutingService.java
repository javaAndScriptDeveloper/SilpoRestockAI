package com.silporestockai.service.telegram;

import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.BlackoutModeService;
import com.silporestockai.service.CalendarIntegrationService;
import com.silporestockai.service.CalendarViewService;
import com.silporestockai.service.CartConfirmationService;
import com.silporestockai.service.CheckinFlowService;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.IntentRouterService;
import com.silporestockai.service.ReorderConfirmationService;
import com.silporestockai.service.ScheduledTaskManagementService;
import com.silporestockai.service.ShoppingListBuilderService;
import com.silporestockai.service.SpecialModeService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.onboarding.OnboardingFlowService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
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
    private final VoiceReplyService voiceReplyService;
    private final UserRepository userRepository;
    private final TelegramOutboundService telegramOutboundService;
    private final SpecialModeService specialModeService;
    private final IntentRouterService intentRouterService;
    private final CalendarViewService calendarViewService;
    private final TelegramFailureRecoveryService failureRecoveryService;
    private final ScheduledTaskManagementService scheduledTaskManagementService;

    /**
     * Off the webhook thread on purpose. A fridge photo means a vision call — the slowest and most expensive kind
     * of call this application makes — and Telegram redelivers an update it does not get a fast response for.
     * Without this, a slow photo reply was the one path most likely to trigger that redelivery, and this codebase
     * tracks no update id anywhere: a redelivered update was a second full vision call for the same photo, silently.
     * The controller now gets its 200 back in milliseconds regardless of how long the actual work takes.
     */
    @Async("applicationTaskExecutor")
    public void route(Update update) {
        toIncoming(update)
                .ifPresentOrElse(
                        incoming -> {
                            try {
                                handle(incoming);
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
                return Optional.of(new TelegramIncomingUpdate.Photo(chatId, userId, largest.getFileId(), "image/jpeg"));
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

    private void handle(TelegramIncomingUpdate incoming) {
        User user = userAccountService.findOrCreate(incoming.chatId());
        if (!onboardingFlowService.isOnboarded(user.getId())) {
            onboardingFlowService.handle(user, incoming);
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

        ConversationFlow flow = conversationStateService.load(incoming.chatId()).getCurrentFlow();
        if (flow == ConversationFlow.CART_CONFIRMATION) {
            cartConfirmationService.handle(user, incoming);
            return;
        }
        if (flow == ConversationFlow.CHECK_IN) {
            checkinFlowService.handle(user, incoming);
            return;
        }
        if (flow == ConversationFlow.LIST_BUILDING) {
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
        if (incoming instanceof TelegramIncomingUpdate.Text voice && matches(voice.text(), "/voice", "")) {
            toggleVoice(user, incoming.chatId());
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text reorder && matches(reorder.text(), "/reorder", "")) {
            reorderConfirmationService.startNow(user);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text blackout && matches(blackout.text(), "/blackout", "")) {
            // Explicit only. Inferring an outage from a sentence and sending an unwanted order would land at the
            // worst possible moment, which is the one this mode exists for.
            telegramOutboundService.sendMessage(incoming.chatId(), "Збираю щось на поїсти без плити й холодильника.");
            blackoutModeService.buildBlackoutOrder(user);
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
        telegramOutboundService.sendMessageWithMainMenu(
                incoming.chatId(), "Скористайся кнопками нижче або напиши, що потрібно.");
    }

    /** A command matches whether it was typed as a slash command or tapped as its own main-menu button. */
    private static boolean matches(String text, String command, String buttonLabel) {
        String stripped = text.strip();
        return stripped.startsWith(command) || stripped.equals(buttonLabel);
    }
}
