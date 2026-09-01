package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional Google Calendar integration.
 *
 * <p>Blank credentials are a supported state, not a broken one: the {@code /calendar} command says the feature is
 * off and every confirmation carries on exactly as before.
 *
 * @param clientId OAuth client id from the Google Cloud console; blank disables the whole integration
 * @param clientSecret OAuth client secret; a web-application client, so this one is real and stays server-side
 * @param redirectUri where Google sends the browser back, matching the console's registered URI
 * @param authorizationEndpoint Google's consent screen
 * @param tokenEndpoint Google's token endpoint
 * @param apiUrl Calendar API base URL
 * @param calendarId which calendar to write to; {@code primary} is the user's own
 * @param scope requested scope — only writing events, never reading them
 * @param eventDuration how long a delivery block lasts on the calendar; Silpo's slot end is not reliably returned
 */
@ConfigurationProperties(prefix = "google.calendar")
public record GoogleCalendarProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String authorizationEndpoint,
        String tokenEndpoint,
        String apiUrl,
        String calendarId,
        String scope,
        Duration eventDuration) {

    public boolean configured() {
        return clientId != null && !clientId.isBlank();
    }
}
