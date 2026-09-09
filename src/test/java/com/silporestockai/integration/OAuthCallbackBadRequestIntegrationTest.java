package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.silporestockai.config.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * Found on the deployed host (task 59), the same way {@link WrongHttpMethodIntegrationTest} was: opening
 * {@code /auth/silpo/callback} with no query string answered 500 with a full stack trace at ERROR, because the
 * required {@code state} parameter is missing and nothing handled that.
 *
 * <p>Both OAuth callbacks are public by necessity — that is where Google and Silpo redirect — so a bare GET is
 * routine, from a scanner or from someone pasting the URL. 400 is the honest answer, and it belongs in the log at
 * DEBUG rather than as an incident.
 */
@DisplayName("a malformed OAuth callback request is a quiet 400, not a 500 with a stack trace")
class OAuthCallbackBadRequestIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void silpoCallbackWithoutStateIsABadRequest() throws Exception {
        assertQuietBadRequest(get("/auth/silpo/callback"));
    }

    @Test
    void googleCallbackWithoutStateIsABadRequest() throws Exception {
        assertQuietBadRequest(get("/auth/google/callback"));
    }

    @Test
    void anUnparseableUserIdIsABadRequest() throws Exception {
        // A UUID path that is present but nonsense takes the other route into the handler,
        // MethodArgumentTypeMismatchException rather than a missing parameter.
        assertQuietBadRequest(get("/auth/silpo/start").param("userId", "not-a-uuid"));
    }

    private void assertQuietBadRequest(RequestBuilder request) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(request).andExpect(status().isBadRequest());
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)))
                .isEmpty();
    }
}
