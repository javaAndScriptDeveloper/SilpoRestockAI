package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.silporestockai.config.RequestResponseLoggingFilter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Everything this application receives from outside is logged with its full body — the same idea as
 * {@code FeignConfig} for the outbound side — and Telegram's shared secret must never be one of the things printed.
 */
@DisplayName("inbound requests are logged in full, and the Telegram secret header never is")
class RequestResponseLoggingFilterIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET = "stub-webhook-secret";
    private static final long CHAT_ID = 13001L;

    @Autowired
    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @DynamicPropertySource
    static void webhookSecret(DynamicPropertyRegistry registry) {
        registry.add("telegram.webhook-secret", () -> SECRET);
    }

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    private List<String> debugLines() {
        logger.detachAppender(appender);
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.DEBUG)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void logsTheWebhookRequestAndResponseWithTheSecretHeaderRedacted() throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":1,"message":{"message_id":1,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "text":"привіт"}}""".formatted(CHAT_ID)))
                .andExpect(status().isOk());

        List<String> lines = debugLines();
        assertThat(lines).anyMatch(line -> line.contains("/telegram/webhook") && line.contains("привіт"));
        assertThat(lines).noneMatch(line -> line.contains(SECRET));
        assertThat(lines).anyMatch(line -> line.contains("***"));
    }

    @Test
    void doesNotLogHealthChecks() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health"));

        assertThat(debugLines()).isEmpty();
    }
}
