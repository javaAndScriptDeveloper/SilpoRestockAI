package com.silporestockai.client;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AgentCallLogTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(AgentCallLog.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private String onlyLine() {
        assertThat(appender.list).hasSize(1);
        return appender.list.getFirst().getFormattedMessage();
    }

    @Test
    void oneCallIsOneLineCarryingToolArgumentsDurationAndResult() {
        AgentCallLog.mcpCall(
                "silpo_find_products_batch", Map.of("queries", List.of("хліб", "молоко")), "14 items", 1234, true);

        String line = onlyLine();
        assertThat(line).doesNotContain("\n");
        assertThat(line).contains("silpo_find_products_batch");
        assertThat(line).contains("queries=2 items");
        assertThat(line).contains("1.2s");
        assertThat(line).contains("14 items");
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void aListArgumentIsCountedNeverDumped() {
        List<String> many = IntStream.range(0, 40).mapToObj(i -> "товар-" + i).toList();

        AgentCallLog.mcpCall("silpo_add_or_update_cart_products", Map.of("products", many), "ok", 800, true);

        String line = onlyLine();
        assertThat(line).contains("products=40 items");
        assertThat(line).doesNotContain("товар-7");
        assertThat(line.length()).isLessThan(160);
    }

    @Test
    void aFailedCallIsLoggedAtWarnSoThePatternColoursItDifferently() {
        AgentCallLog.mcpCall("silpo_get_time_slots", Map.of(), "429 rate limited", 2100, false);

        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(onlyLine()).contains("429 rate limited");
    }

    @Test
    void aSecretInAnArgumentOrResultNeverReachesTheLine() {
        AgentCallLog.mcpCall(
                "silpo_whatever",
                Map.of("header", "Authorization: Bearer sk-live-must-not-appear"),
                "{\"access_token\": \"tok-must-not-appear\"}",
                10,
                true);

        String line = onlyLine();
        assertThat(line).doesNotContain("sk-live-must-not-appear");
        assertThat(line).doesNotContain("tok-must-not-appear");
        assertThat(line).contains("***");
    }

    @Test
    void aLongScalarArgumentIsTruncated() {
        String essay = "я".repeat(300);

        AgentCallLog.mcpCall("silpo_find_products", Map.of("query", essay), "ok", 10, true);

        assertThat(onlyLine().length()).isLessThan(160);
    }

    @Test
    void sessionAndClaudeLinesAreOnTheSameChannel() {
        AgentCallLog.mcpSession(40);
        AgentCallLog.claudeCall("completeStructured", "claude-sonnet-5", 31_400, true);

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("40");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("claude-sonnet-5");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("31.4s");
    }
}
