package com.silporestockai.service;

import com.silporestockai.client.google.GoogleCalendarApiClient;
import com.silporestockai.config.GoogleCalendarProperties;
import com.silporestockai.model.OrderConfirmedEvent;
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
