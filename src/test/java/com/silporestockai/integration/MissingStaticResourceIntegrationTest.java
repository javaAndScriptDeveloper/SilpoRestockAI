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
 * A browser opening the OAuth callback page or the WebApp form asks for {@code /favicon.ico}. That is a 404, not an
 * incident — before this it was «Unhandled exception» plus a stack trace at ERROR, twice per onboarding, on the
 * very console the demo puts on camera.
 */
@DisplayName("a missing static resource is a quiet 404, not an ERROR with a stack trace")
class MissingStaticResourceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void faviconIsAFourOhFourWithoutAnErrorLog() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)))
                .isEmpty();
    }
}
