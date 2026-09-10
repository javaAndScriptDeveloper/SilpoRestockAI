package com.silporestockai.service;

import com.silporestockai.client.google.GoogleCalendarApiClient;
import com.silporestockai.config.GoogleCalendarProperties;
import com.silporestockai.entity.User;
import com.silporestockai.model.OrderConfirmedEvent;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Writes the delivery window into the user's calendar, when there is a calendar to write to.
 *
 * <p>Three early exits, and every one of them is a normal Tuesday: the deployment has no Google credentials, this
 * user never connected an account, or the slot carried no readable start time. None of them is an error, and none of
 * them may disturb an order that is already confirmed.
 *
 * <p>Asynchronous and swallowing, like the meal-plan hand-off: the event is published on a webhook thread, and a
 * calendar that is down is not a reason for a person to see a failure about groceries they already bought.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarIntegrationService {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final DateTimeFormatter RFC_3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final GoogleCalendarProperties properties;
    private final GoogleAuthService googleAuthService;
    private final GoogleCalendarApiClient calendarApiClient;
    private final TelegramOutboundService telegramOutboundService;

    /**
     * The «Пізніше» tap on the onboarding offer (task 71). Self-contained — it names nothing and needs no
     * {@code conversation_state} — so the routing layer dispatches it globally, like every other such tap.
     *
     * <p>{@code gcal:} rather than {@code cal:} because {@code CalendarViewService.CALLBACK_DAY_PREFIX} is already
     * {@code cal:}, and everything under it is read as a weekday name: live, a «Пізніше» tap answered «Не знайшов
     * цей день у поточному плані.»
     */
    public static final String CALLBACK_LATER = "gcal:later";

    /**
     * The proactive offer, made once during onboarding while the first list is on screen and no cart has been
     * confirmed yet (task 71).
     *
     * <p>Until now the feature was reachable only by a household that already knew it existed and said so. Somebody
     * could finish their entire first order without ever learning that a fully built integration was sitting there,
     * and the one delivery it would have been most useful for — the first — was the one it always missed.
     *
     * <p>Nothing here waits for anything. The connect button is a link into the same OAuth flow tasks 18 and 60
     * already own, which completes in a browser on its own schedule: before the cart is confirmed, after it, or
     * never. Whichever it is only decides whether this first delivery reaches the calendar, never whether the order
     * can be placed. Silent when there is nothing to offer — no Google credentials on the deployment, or a calendar
     * already connected — because an offer nobody can accept is worse than no offer.
     */
    public void offerDuringOnboarding(User user) {
        if (!googleAuthService.configured()) {
            log.debug("no Google credentials configured; not offering the calendar during onboarding");
            return;
        }
        if (googleAuthService.isConnected(user.getId())) {
            return;
        }
        try {
            telegramOutboundService.sendMessageWithButtons(
                    user.getTelegramChatId(),
                    "І ще одне, поки нічого не замовлено: хочеш, щоб доставки самі з'являлись у Google Календарі? "
                            + "Тоді й ця, перша, туди потрапить.",
                    List.of(
                            TelegramButton.link("Підключити", googleAuthService.buildAuthorizationUrl(user.getId())),
                            TelegramButton.callback("Пізніше", CALLBACK_LATER)));
        } catch (RuntimeException e) {
            // An optional offer, made at the end of a hand-off that has already delivered a plan and a list. A
            // failure here must not read as a first plan that went wrong.
            log.warn("could not offer the calendar to user {}: {}", user.getId(), e.getMessage());
        }
    }

    /** «Пізніше»: acknowledged in one line, with the way back in, and nothing else happens (task 71). */
    public void declineConnection(User user) {
        log.info("user {} declined the onboarding calendar offer", user.getId());
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                "Гаразд, без календаря. Захочеш пізніше — напиши «підключи гугл календар», це займе пів хвилини.");
    }

    /**
     * Offers the connection. Opt-in, and only ever opt-in: a calendar nobody connected is never touched. Reached by
     * «підключи гугл календар» through the intent router, or the typed {@code /calendar}.
     */
    public void offerConnection(User user) {
        long chatId = user.getTelegramChatId();
        if (!googleAuthService.configured()) {
            telegramOutboundService.sendMessage(chatId, "Календар зараз не налаштований на сервері.");
            return;
        }
        if (googleAuthService.isConnected(user.getId())) {
            telegramOutboundService.sendMessage(chatId, "Календар уже підключено — додаю туди слоти доставки.");
            return;
        }
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                "Підключи Google Календар — і я вноситиму туди вікна доставки.",
                List.of(TelegramButton.link(
                        "Підключити календар", googleAuthService.buildAuthorizationUrl(user.getId()))));
    }

    @Async("applicationTaskExecutor")
    @EventListener
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        createDeliveryEvent(event);
    }

    /** Runs on the caller's thread. Separated for the same reason the meal-plan hand-off is: a test can drive it. */
    public void createDeliveryEvent(OrderConfirmedEvent event) {
        if (!googleAuthService.configured()) {
            log.debug("no Google credentials configured; skipping the calendar event");
            return;
        }
        if (event.deliveryStartsAt() == null) {
            log.debug("order {} has no readable delivery time; skipping the calendar event", event.orderId());
            return;
        }
        googleAuthService
                .accessToken(event.userId())
                .ifPresentOrElse(
                        token -> insert(event, token),
                        () -> log.debug("user {} has no connected calendar", event.userId()));
    }

    private void insert(OrderConfirmedEvent event, String accessToken) {
        ZonedDateTime start = event.deliveryStartsAt().atZone(KYIV);
        ZonedDateTime end = start.plus(properties.eventDuration());
        GoogleCalendarApiClient.CalendarEvent calendarEvent = new GoogleCalendarApiClient.CalendarEvent(
                "Доставка «Сільпо»",
                "Замовлення на %d позицій. Слот: %s.\nID замовлення: %s"
                        .formatted(
                                event.itemCount(),
                                event.slotLabel() == null ? "—" : event.slotLabel(),
                                event.orderId()),
                new GoogleCalendarApiClient.EventDateTime(RFC_3339.format(start), KYIV.getId()),
                new GoogleCalendarApiClient.EventDateTime(RFC_3339.format(end), KYIV.getId()),
                new GoogleCalendarApiClient.Reminders(
                        false,
                        List.of(new GoogleCalendarApiClient.ReminderOverride(
                                "popup", properties.reminderMinutesBefore()))));
        try {
            GoogleCalendarApiClient.CreatedEvent created =
                    calendarApiClient.insertEvent("Bearer " + accessToken, properties.calendarId(), calendarEvent);
            log.info("created calendar event {} for order {}", created.id(), event.orderId());
        } catch (RuntimeException e) {
            // The groceries are ordered either way. A calendar that refuses is a log line, not a user-facing failure.
            log.warn("could not create a calendar event for order {}: {}", event.orderId(), e.getMessage());
        }
    }
}
