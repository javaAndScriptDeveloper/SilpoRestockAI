# Demo-ready MCP call logging — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every MCP tool call (and every Claude call) prints as one short, colour-coded, instantly
recognisable console line, so the demo screencast's console window is the proof of agency on its own —
no grep, no explanation, no JSON dumps.

**Architecture:** One dedicated log channel — the class `com.silporestockai.client.AgentCallLog`, whose
class name *is* the logback category. It formats fixed-width single lines and compresses arguments
("products=23 items") instead of printing payloads. A new `logback-spring.xml` routes that one category
to two appenders with `additivity=false`: a console appender coloured by level through Spring Boot's own
`%clr` converter (INFO green = success, WARN yellow = failure) and a plain, ANSI-free
`logs/mcp-calls.log` for `tail -f` and for task 55 to parse. A new `demo` Spring profile quiets
everything else so those lines are the only bright thing on screen.

**Tech Stack:** Java 21, Spring Boot 4, Logback (`logback-spring.xml` + Boot's `defaults.xml`),
JUnit 5 + AssertJ, `ch.qos.logback.core.read.ListAppender` for asserting log output.

**Spec:** Notion task 58 — «Demo-ready MCP call logging: structured, readable console output for screen
recording» (https://app.notion.com/p/3d47227def1c81d384cfed84303f909e). Related: task 02 (tokens are
never logged), task 09 (every MCP call logged), task 55 (parses this log instead of its own collector).

## Global Constraints

- **Tokens never reach a log line.** Every argument and result summary goes through
  `SecretRedactor.redact` before it is formatted, and each field is length-capped. A regression here is
  unacceptable (task 02).
- **`@Slf4j` only** — no manual `LoggerFactory` (root `CLAUDE.md`). The dedicated category is therefore a
  dedicated *class*, not a string category name.
- **Spotless (palantir)** — `make format` before committing; CI runs `spotlessCheck`.
- **ArchUnit is enforced** — `AgentCallLog` is a plain utility (private constructor, static methods) in
  `client`, so no naming-suffix or injection rule applies to it.
- **`logs/mcp-calls.log` must stay ANSI-free** so it is greppable and parseable (task 55 acceptance).
- Stop the running app before `./gradlew test` — they share `build/classes`.

---

### Task 1: `AgentCallLog` — the demo log channel

**Files:**
- Create: `src/main/java/com/silporestockai/client/AgentCallLog.java`
- Test: `src/test/java/com/silporestockai/client/AgentCallLogTest.java`

**Interfaces:**
- Consumes: `com.silporestockai.utils.SecretRedactor.redact(String)`.
- Produces:
  - `AgentCallLog.mcpCall(String tool, Map<String, Object> arguments, String result, long millis, boolean ok)`
  - `AgentCallLog.mcpSession(int toolCount)`
  - `AgentCallLog.claudeCall(String call, String model, long millis, boolean ok)`
  - `AgentCallLog.summarizeArguments(Map<String, Object> arguments)` — package-private, tested directly.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/silporestockai/client/AgentCallLogTest.java`:

```java
package com.silporestockai.client;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
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
        List<String> many = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> "товар-" + i)
                .toList();

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
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew test --tests '*AgentCallLogTest'`
Expected: compilation failure — `AgentCallLog` does not exist.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/silporestockai/client/AgentCallLog.java`:

```java
package com.silporestockai.client;

import com.silporestockai.utils.SecretRedactor;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * The demo channel: one line per outbound agent call, formatted to be read off a screen recording.
 *
 * <p>This class exists to be a logback category. The console next to the chat is the only evidence a judge has
 * that Комора really calls «Сільпо» rather than replaying a script, so those lines cannot be one shade of grey
 * among Hibernate's — they get their own appenders, their own pattern and their own colour
 * ({@code logback-spring.xml}), and this class is what {@code additivity=false} is pinned to. A string category
 * would have needed a manual {@code LoggerFactory}, which the project forbids; a class gets one from {@code @Slf4j}.
 *
 * <p>Everything printed here is a <i>summary</i>. Arguments are counted, not dumped ({@code products=23 items}),
 * scalars are cut short, and both arguments and results pass through {@link SecretRedactor} — the bearer token
 * lives in a header rather than in any of these values, and this class is written so that it stays true even if
 * that ever changes (task 02).
 */
@Slf4j
public final class AgentCallLog {

    /** Wide enough for the longest live tool name ({@code silpo_add_or_update_cart_products}). */
    private static final int TOOL_WIDTH = 34;

    private static final int ARGS_WIDTH = 30;
    private static final int MAX_SCALAR = 24;
    private static final int MAX_RESULT = 44;

    private AgentCallLog() {}

    /** One MCP tool call: what was called, what it was asked for, how long it took, how it ended. */
    public static void mcpCall(String tool, Map<String, Object> arguments, String result, long millis, boolean ok) {
        String line = "%s %-" + TOOL_WIDTH + "s %-" + ARGS_WIDTH + "s %7s  %s";
        line = String.format(
                line,
                ok ? "🔧" : "🔧",
                tool,
                summarizeArguments(arguments),
                duration(millis),
                (ok ? "✅ " : "❌ ") + clean(result, MAX_RESULT));
        if (ok) {
            log.info("{}", line);
        } else {
            log.warn("{}", line);
        }
    }

    /** A fresh MCP session — the handshake that the tool calls below it hang off. */
    public static void mcpSession(int toolCount) {
        log.info("🔗 Silpo MCP session opened — {} tools available", toolCount);
    }

    /** One Claude call, on the same channel: on a recording the agent's thinking and its acting read as one story. */
    public static void claudeCall(String call, String model, long millis, boolean ok) {
        String line = String.format(
                "🧠 %-" + TOOL_WIDTH + "s %-" + ARGS_WIDTH + "s %7s  %s",
                call,
                clean(model, ARGS_WIDTH),
                duration(millis),
                ok ? "✅" : "❌");
        if (ok) {
            log.info("{}", line);
        } else {
            log.warn("{}", line);
        }
    }

    /**
     * Turns a call's arguments into something that fits on one line: a collection or map becomes its size, a scalar
     * is cut to {@link #MAX_SCALAR}. Deliberately shape-agnostic — no per-tool knowledge, so a tool this codebase
     * has never called still prints something sane.
     */
    static String summarizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        return arguments.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + summarizeValue(entry.getValue()))
                .collect(Collectors.joining(" "));
    }

    private static String summarizeValue(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.size() + " items";
        }
        if (value instanceof Map<?, ?> map) {
            return map.size() + " fields";
        }
        if (value instanceof Object[] array) {
            return array.length + " items";
        }
        return clean(String.valueOf(value), MAX_SCALAR);
    }

    /** Redacts, flattens to one line and caps — in that order, so a secret cannot survive by being long. */
    private static String clean(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = SecretRedactor.redact(text).replaceAll("\\s+", " ").trim();
        return flat.length() <= maxLength ? flat : flat.substring(0, maxLength - 1) + "…";
    }

    private static String duration(long millis) {
        return millis < 1000 ? millis + "ms" : String.format("%.1fs", millis / 1000.0);
    }
}
```

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./gradlew test --tests '*AgentCallLogTest'`
Expected: 6 tests, all PASS.

- [ ] **Step 5: Format and commit**

```bash
make format
git add src/main/java/com/silporestockai/client/AgentCallLog.java \
        src/test/java/com/silporestockai/client/AgentCallLogTest.java
git commit -m "Give MCP calls a log channel a camera can read"
```

---

### Task 2: Feed the channel from the MCP and Claude clients

**Files:**
- Modify: `src/main/java/com/silporestockai/client/mcp/SilpoMcpClientImpl.java` (`callTool`, `callToolOnce`, `openSession`)
- Modify: `src/main/java/com/silporestockai/client/claude/ClaudeApiClientImpl.java` (`timed`)
- Test: `src/test/java/com/silporestockai/client/AgentCallLogWiringTest.java`

**Interfaces:**
- Consumes: `AgentCallLog.mcpCall/mcpSession/claudeCall` from Task 1;
  `McpToolResponse.text()`, `.structuredContent()`, `.isError()`.
- Produces: `SilpoMcpClientImpl` emits exactly one `AgentCallLog` line per `callTool`, including when the
  call throws.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/silporestockai/client/AgentCallLogWiringTest.java` — drives the real client against a
stub MCP server would be heavy; instead assert the summarisation of a tool response, which is the part
with judgement in it, and lock the "one line even when it throws" contract through the existing
integration test in Step 6.

```java
package com.silporestockai.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.client.mcp.McpToolResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentCallLogWiringTest {

    @Test
    void aListOfProductsIsSummarizedAsACount() {
        McpToolResponse response = McpToolResponse.of(
                List.of("{\"items\":[]}"), Map.of("items", List.of(Map.of("id", 1), Map.of("id", 2))), false);

        assertThat(AgentCallLog.summarizeResult(response.structuredContent(), response.text()))
                .isEqualTo("2 items");
    }

    @Test
    void aResponseWithoutAListFallsBackToItsFieldCount() {
        McpToolResponse response = McpToolResponse.of(List.of("ok"), Map.of("cartId", "abc", "total", 799), false);

        assertThat(AgentCallLog.summarizeResult(response.structuredContent(), response.text()))
                .isEqualTo("2 fields");
    }

    @Test
    void aResponseWithNoStructureFallsBackToTrimmedText() {
        McpToolResponse response = McpToolResponse.of(List.of("cart cleared"), null, false);

        assertThat(AgentCallLog.summarizeResult(response.structuredContent(), response.text()))
                .isEqualTo("cart cleared");
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew test --tests '*AgentCallLogWiringTest'`
Expected: compilation failure — `AgentCallLog.summarizeResult` does not exist.

- [ ] **Step 3: Add `summarizeResult` to `AgentCallLog`**

Insert after `summarizeArguments` in `AgentCallLog`:

```java
    /**
     * What a tool answered, in three words or fewer. Prefers the first list in the structured content — for this
     * server that is the products, the slots, the orders — then a field count, then the raw text.
     */
    public static String summarizeResult(Map<String, Object> structuredContent, String text) {
        if (structuredContent != null && !structuredContent.isEmpty()) {
            for (Object value : structuredContent.values()) {
                if (value instanceof Collection<?> collection) {
                    return collection.size() + " items";
                }
            }
            return structuredContent.size() + " fields";
        }
        String flat = clean(text, MAX_RESULT);
        return flat.isEmpty() ? "ok" : flat;
    }
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew test --tests '*AgentCallLogWiringTest'`
Expected: 3 tests PASS.

- [ ] **Step 5: Wire the MCP client**

In `SilpoMcpClientImpl.callTool`, keep the existing `Timer.Sample`, add wall-clock timing and the demo line —
one line per call, including the throwing path:

```java
    @Override
    @Retry(name = "silpoMcp")
    public McpToolResponse callTool(String toolName, Map<String, Object> arguments, UUID userId) {
        log.debug("calling Silpo MCP tool {} for user {}", toolName, userId);
        // Task 54: timed here rather than off McpToolCalledEvent, which is published inside the lambda below and so
        // never fires when the call throws — a transport failure, a 401 the refresh dance could not fix, an exhausted
        // retry. Those are exactly the failures a reliability panel exists to show. @Retry proxies this method, so
        // each attempt is its own sample: the timer measures attempts, which is what makes Silpo's 429 backoff visible.
        Timer.Sample sample = Timer.start(meterRegistry);
        // Task 58: the same reasoning for the demo line — a failed call is the most interesting thing that can
        // happen on camera, so it gets a line too. Wall clock rather than the Micrometer sample because this one
        // has to be human-readable, not aggregated.
        long startedAt = System.nanoTime();
        String outcome = "error";
        try {
            McpToolResponse response = callToolOnce(toolName, arguments, userId);
            outcome = response.isError() ? "tool_error" : "success";
            AgentCallLog.mcpCall(
                    toolName,
                    arguments,
                    AgentCallLog.summarizeResult(response.structuredContent(), response.text()),
                    millisSince(startedAt),
                    !response.isError());
            return response;
        } catch (RuntimeException e) {
            AgentCallLog.mcpCall(toolName, arguments, failureOf(e), millisSince(startedAt), false);
            throw e;
        } finally {
            sample.stop(meterRegistry.timer(
                    MeterNames.MCP_CALL, MeterNames.TAG_TOOL, toolName, MeterNames.TAG_OUTCOME, outcome));
        }
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /** The demo line wants the shortest true statement about a failure, not a stack trace. */
    private static String failureOf(RuntimeException e) {
        if (e instanceof SilpoMcpRateLimitedException) {
            return "rate limited";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
```

Add the import `import com.silporestockai.client.AgentCallLog;` and, in `openSession`, next to the existing
`log.info("connected to Silpo MCP …")`, add:

```java
        AgentCallLog.mcpSession(tools.size());
```

- [ ] **Step 6: Wire the Claude client**

In `ClaudeApiClientImpl.timed`, add the wall clock and one line in the `finally`:

```java
    private <T> T timed(String callName, String model, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startedAt = System.nanoTime();
        String outcome = "success";
        try {
            return action.get();
        } catch (ClaudeRateLimitedException e) {
            outcome = "rate_limited";
            throw e;
        } catch (ClaudeUnavailableException e) {
            outcome = "unavailable";
            throw e;
        } catch (ClaudeStructuredOutputException e) {
            outcome = "structured";
            throw e;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            // Task 58: on the demo channel next to the MCP lines — a viewer sees the agent think, then act.
            AgentCallLog.claudeCall(
                    callName, model, (System.nanoTime() - startedAt) / 1_000_000, "success".equals(outcome));
            sample.stop(meterRegistry.timer(
                    MeterNames.CLAUDE_CALL,
                    MeterNames.TAG_CALL,
                    callName,
                    MeterNames.TAG_MODEL,
                    model,
                    MeterNames.TAG_OUTCOME,
                    outcome));
        }
    }
```

Add the import `import com.silporestockai.client.AgentCallLog;`.

- [ ] **Step 7: Run the client test suites**

Run: `./gradlew test --tests '*AgentCallLog*' --tests '*SilpoMcpClient*' --tests '*Claude*' --tests '*ArchitectureTest'`
Expected: all PASS. `ArchitectureTest` matters here — `AgentCallLog` is a new class in `client`.

- [ ] **Step 8: Format and commit**

```bash
make format
git add -A
git commit -m "Log every Silpo and Claude call on the demo channel"
```

---

### Task 3: Logback wiring — colour on the console, plain text in `logs/mcp-calls.log`

**Files:**
- Create: `src/main/resources/logback-spring.xml`
- Test: `src/test/java/com/silporestockai/client/AgentCallLogAppenderTest.java`

**Interfaces:**
- Consumes: the category `com.silporestockai.client.AgentCallLog` from Task 1.
- Produces: appenders `DEMO_CONSOLE` and `DEMO_FILE`; the file `logs/mcp-calls.log`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/silporestockai/client/AgentCallLogAppenderTest.java`:

```java
package com.silporestockai.client;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.FileAppender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The demo channel is a configuration promise, not only code: its own appenders, off the root logger, with a
 * plain-text file that task 55 can parse. A broken include or a renamed class silently sends these lines back
 * into the ordinary application log, where the whole point of them is lost.
 */
class AgentCallLogAppenderTest {

    @Test
    void theDemoChannelHasItsOwnAppendersAndDoesNotFallThroughToRoot() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(AgentCallLog.class);

        List<String> appenders = new ArrayList<>();
        logger.iteratorForAppenders().forEachRemaining(appender -> appenders.add(appender.getName()));

        assertThat(logger.isAdditive()).isFalse();
        assertThat(appenders).contains("DEMO_CONSOLE", "DEMO_FILE");
    }

    @Test
    void theDemoFileIsPlainTextSoItStaysGreppable() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(AgentCallLog.class);

        FileAppender<?> file = (FileAppender<?>) logger.getAppender("DEMO_FILE");

        assertThat(file).isNotNull();
        assertThat(file.getFile()).endsWith("mcp-calls.log");
        assertThat(file.getEncoder().toString()).doesNotContain("%clr");
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew test --tests '*AgentCallLogAppenderTest'`
Expected: FAIL — `logger.isAdditive()` is `true` and no `DEMO_CONSOLE` appender exists.

- [ ] **Step 3: Write `src/main/resources/logback-spring.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Task 58. The only reason this file exists: the demo channel (com.silporestockai.client.AgentCallLog) needs
  appenders of its own. Everything else — the root logger, the console pattern, `logging.level.*` from
  application.yml — stays exactly as Spring Boot configures it, which is what the two includes below bring in.

  Console lines are coloured through Boot's own %clr converter, which maps the level: INFO green for a call that
  worked, WARN yellow for one that did not. The file gets the same text without a single escape byte, so
  `tail -f logs/mcp-calls.log`, grep and task 55's parser all see plain lines.
-->
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>

    <appender name="DEMO_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%clr(%d{HH:mm:ss}){faint} %clr(%m){}%n</pattern>
        </encoder>
    </appender>

    <appender name="DEMO_FILE" class="ch.qos.logback.core.FileAppender">
        <file>${DEMO_LOG_FILE:-logs/mcp-calls.log}</file>
        <append>true</append>
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %m%n</pattern>
        </encoder>
    </appender>

    <logger name="com.silporestockai.client.AgentCallLog" level="INFO" additivity="false">
        <appender-ref ref="DEMO_CONSOLE"/>
        <appender-ref ref="DEMO_FILE"/>
    </logger>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew test --tests '*AgentCallLogAppenderTest'`
Expected: 2 tests PASS.

- [ ] **Step 5: Run the whole suite — a logback config touches every `@SpringBootTest`**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. (Stop `make run` first if it is up.)

- [ ] **Step 6: Format and commit**

```bash
make format
git add -A
git commit -m "Route the demo channel to its own console colour and log file"
```

---

### Task 4: The `demo` profile and the two make targets

**Files:**
- Create: `src/main/resources/application-demo.yml`
- Modify: `Makefile` (`.PHONY` line, new `demo` and `mcp-log` targets)
- Modify: `docs/RUNBOOK.md` (new `### Task 58:` section)
- Modify: `README.md` if it lists make targets (check first)

**Interfaces:**
- Consumes: the `DEMO_LOG_FILE` property and the `AgentCallLog` category from Task 3.
- Produces: `make demo`, `make mcp-log`.

- [ ] **Step 1: Write `src/main/resources/application-demo.yml`**

```yaml
# Task 58. The recording profile: everything that is not the agent acting gets out of the way.
#
# The default profiles run com.silporestockai at DEBUG, which prints up to 2000 characters of every MCP
# response — correct when debugging, unreadable on camera. Here the ordinary application log is dimmed to a
# short, faint one-liner and the demo channel (AgentCallLog) is the only bright thing on the screen.
spring:
  output:
    ansi:
      # `make run` pipes stdout through tee, so Boot's DETECT would turn colour off exactly when it is wanted.
      enabled: always

logging:
  level:
    root: WARN
    com.silporestockai: INFO
    # The channel the console recording is about.
    com.silporestockai.client.AgentCallLog: INFO
  pattern:
    console: "%clr(%d{HH:mm:ss}){faint} %clr(%m){faint}%n"
```

- [ ] **Step 2: Add the make targets**

In `Makefile`, add `demo mcp-log` to the `.PHONY` list, and after the `dev` target:

```makefile
demo: ## Run in recording mode: quiet app log, colour-coded MCP/Claude lines (profile `demo`)
	@mkdir -p logs
	set -a; . ./$(ENV_FILE); set +a; SPRING_PROFILES_ACTIVE=demo ./gradlew bootRun 2>&1 | tee logs/app.log

mcp-log: ## tail -f the demo call log — the second window during a screen recording
	@mkdir -p logs
	@touch logs/mcp-calls.log
	@tail -f logs/mcp-calls.log
```

- [ ] **Step 3: Verify the profile parses and the app starts**

Run: `SPRING_PROFILES_ACTIVE=demo make dev` in one window, `make mcp-log` in another; stop after startup.
Expected: startup log is short and dim; `🔗 Silpo MCP session opened` appears on the first MCP-touching
request; `logs/mcp-calls.log` gets the same line without escape codes.

- [ ] **Step 4: Document it in `docs/RUNBOOK.md`**

Append a `### Task 58: the demo call log` section: what `make demo` and `make mcp-log` do, what a good
line looks like, and the reminder that `logs/mcp-calls.log` is append-only — truncate it (`: > logs/mcp-calls.log`)
before a take.

- [ ] **Step 5: Commit**

```bash
make format
git add -A
git commit -m "Add a recording profile that leaves only the agent on screen"
```

---

### Task 5: Live rehearsal of the demo script (steps 1–13) and the write-ups

**Files:**
- Modify: `docs/OVERNIGHT_SUMMARY.md` (new `# Session 9` section)
- Modify: Notion — task 58 status, demo-script step 6

- [ ] **Step 1: Start the app on the demo profile against the live Silpo MCP**

Per `komora-live-driving-without-telegram`: stop the tunnel supervisor first, run `make demo`, drive the
flow with synthetic webhook POSTs (`/start`, list, cart build, reorder, blackout, ad-hoc), so every step
of the demo script that touches MCP produces lines.

- [ ] **Step 2: Read the console with a director's eye, not a developer's**

Check: is every line one line at the recording's likely width (~120 cols)? Do the columns stay aligned
across tools with very different name lengths? Is the green/yellow difference obvious? Does anything
non-MCP still shout? Fix what looks bad — this is the acceptance criterion the task actually cares about.

- [ ] **Step 3: Grep the log for secrets**

Run: `grep -Ei 'bearer|access_token|refresh_token|eyJ' logs/mcp-calls.log logs/app.log`
Expected: no hits other than redacted `***` forms.

- [ ] **Step 4: Update Notion**

Task 58 → "In review" (the visual verdict is the user's), demo-script step 6 → describe the console
concretely and name `make demo` / `make mcp-log`, plus a changelog bullet.

- [ ] **Step 5: Write up the session and commit**

```bash
git add -A
git commit -m "Record what the demo console looks like after a live rehearsal"
```
