package com.silporestockai.client.google;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * The one Calendar API call this application makes: insert an event.
 *
 * <p>Reading the calendar is deliberately absent, and so is the scope for it. The agent writes a delivery block and
 * nothing else.
 */
@FeignClient(name = "googleCalendar", url = "${google.calendar.api-url}")
public interface GoogleCalendarApiClient {

    @PostMapping(value = "/calendars/{calendarId}/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    CreatedEvent insertEvent(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerToken,
            @PathVariable("calendarId") String calendarId,
            @RequestBody CalendarEvent event);

    /**
     * An event as the API takes it.
     *
     * @param summary the title a person sees in their calendar
     * @param description what was ordered, and the order id so a later task can find this event again
     * @param start when the delivery window opens
     * @param end a flat block after that; Silpo's slot end is not reliably returned
     */
    record CalendarEvent(String summary, String description, EventDateTime start, EventDateTime end) {}

    /** RFC 3339 timestamp plus its zone, which is what the API wants. */
    record EventDateTime(String dateTime, String timeZone) {}

    /** Only the fields worth logging come back modelled. */
    record CreatedEvent(String id, String htmlLink) {}

    /** Convenience for the single reminder-free shape this application creates. */
    static List<String> writeOnlyScopes() {
        return List.of("https://www.googleapis.com/auth/calendar.events");
    }
}
