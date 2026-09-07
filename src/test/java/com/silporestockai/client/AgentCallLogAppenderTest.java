package com.silporestockai.client;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.status.Status;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The demo channel is a configuration promise, not only code: its own appenders, off the root logger, and a
 * plain-text file that stays greppable. A broken include or a renamed class would silently send these lines back into
 * the ordinary application log — where the whole point of them is lost — and nothing else in the suite would fail.
 *
 * <p>{@code logback-spring.xml} is read by Spring Boot's logging initialiser rather than by logback itself, so a
 * plain test never sees it. Configuring a throwaway {@link LoggerContext} from the same file is the cheap way to
 * assert on what it declares — and it also proves the file parses, includes and all, without booting the app.
 */
class AgentCallLogAppenderTest {

    private static LoggerContext context;
    private static List<Status> configurationErrors;

    @BeforeAll
    static void configureFromTheRealFile() throws Exception {
        context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(AgentCallLogAppenderTest.class.getClassLoader().getResource("logback-spring.xml"));
        configurationErrors = context.getStatusManager().getCopyOfStatusList().stream()
                .filter(status -> status.getLevel() == Status.ERROR)
                .toList();
    }

    private static Logger demoLogger() {
        return context.getLogger(AgentCallLog.class);
    }

    @Test
    void theConfigurationParsesCleanly() {
        assertThat(configurationErrors).isEmpty();
    }

    @Test
    void theDemoChannelHasItsOwnAppendersAndDoesNotFallThroughToRoot() {
        Logger logger = demoLogger();

        List<String> appenders = new ArrayList<>();
        logger.iteratorForAppenders().forEachRemaining(appender -> appenders.add(appender.getName()));

        assertThat(logger.isAdditive()).isFalse();
        assertThat(appenders).contains("DEMO_CONSOLE", "DEMO_FILE");
    }

    @Test
    void theDemoFileIsPlainTextSoItStaysGreppable() {
        FileAppender<?> file = (FileAppender<?>) demoLogger().getAppender("DEMO_FILE");

        assertThat(file).isNotNull();
        assertThat(file.getFile()).endsWith("mcp-calls.log");
        assertThat(((PatternLayoutEncoder) file.getEncoder()).getPattern()).doesNotContain("%clr");
    }

    @Test
    void theConsoleLineIsColouredByLevelSoAFailedCallLooksDifferent() {
        var console = demoLogger().getAppender("DEMO_CONSOLE");

        assertThat(console).isNotNull();
        assertThat(((PatternLayoutEncoder) ((ch.qos.logback.core.OutputStreamAppender<?>) console).getEncoder())
                        .getPattern())
                .contains("%clr(%m)");
    }
}
