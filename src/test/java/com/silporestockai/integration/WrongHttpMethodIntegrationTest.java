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

/**
 * The sibling of {@link MissingStaticResourceIntegrationTest}, found while checking what task 59's deployed host
 * exposes: {@code GET /telegram/webhook} — the first thing anyone who finds the domain tries — fell through to the
 * catch-all handler and came back 500 with a full stack trace at ERROR. The webhook path is public by necessity, so
 * that is a scanner's worth of fake incidents in the log and a status code that says the app is broken when it is not.
 */
@DisplayName("the wrong HTTP method is a quiet 405, not a 500 with a stack trace")
class WrongHttpMethodIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void gettingThePostOnlyWebhookIsAFourOhFiveWithoutAnErrorLog() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(get("/telegram/webhook")).andExpect(status().isMethodNotAllowed());
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)))
                .isEmpty();
    }
}
