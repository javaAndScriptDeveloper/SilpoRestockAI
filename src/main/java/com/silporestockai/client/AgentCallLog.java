package com.silporestockai.client;

import com.silporestockai.utils.SecretRedactor;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * The demo channel: one line per outbound agent call, formatted to be read off a screen recording.
 *
 * <p>This class exists to be a logback category. The console next to the chat is the only evidence a judge has that
 * Комора really calls «Сільпо» rather than replaying a script, so those lines cannot be one shade of grey among
 * Hibernate's — they get their own appenders, their own pattern and their own colour ({@code logback-spring.xml}), and
 * this class is what {@code additivity=false} is pinned to. A string category would have needed a manual
 * {@code LoggerFactory}, which this project forbids; a class gets one from {@code @Slf4j}.
 *
 * <p>Everything printed here is a <i>summary</i>. Arguments are counted, not dumped ({@code products=23 items}),
 * scalars are cut short, and both arguments and results pass through {@link SecretRedactor} — the bearer token lives
 * in a header rather than in any of these values, and this class is written so that it stays true even if that ever
 * changes (task 02).
 */
@Slf4j
public final class AgentCallLog {

    /** Wide enough for the longest live tool name ({@code silpo_add_or_update_cart_products}). */
    private static final int TOOL_WIDTH = 34;

    private static final int ARGS_WIDTH = 30;
    private static final int MAX_SCALAR = 24;
    private static final int MAX_RESULT = 44;

    private static final String LINE = "%s %-" + TOOL_WIDTH + "s %-" + ARGS_WIDTH + "s %7s  %s";

    private AgentCallLog() {}

    /** One MCP tool call: what was called, what it was asked for, how long it took, how it ended. */
    public static void mcpCall(String tool, Map<String, Object> arguments, String result, long millis, boolean ok) {
        emit(
                ok,
                String.format(
                        LINE,
                        "🔧",
                        tool,
                        summarizeArguments(arguments),
                        duration(millis),
                        (ok ? "✅ " : "❌ ") + clean(result, MAX_RESULT)));
    }

    /** A fresh MCP session — the handshake that every tool call below it hangs off. */
    public static void mcpSession(int toolCount) {
        log.info("🔗 Silpo MCP session opened — {} tools available", toolCount);
    }

    /** One Claude call, on the same channel: on a recording the agent's thinking and its acting read as one story. */
    public static void claudeCall(String call, String model, long millis, boolean ok) {
        emit(ok, String.format(LINE, "🧠", call, clean(model, ARGS_WIDTH), duration(millis), ok ? "✅" : "❌"));
    }

    /**
     * Success is INFO, failure is WARN — {@code %clr} in the console pattern colours by level, so a call that did not
     * work turns yellow on camera without the format needing to know anything about colour.
     */
    private static void emit(boolean ok, String line) {
        if (ok) {
            log.info("{}", line);
        } else {
            log.warn("{}", line);
        }
    }

    /**
     * Turns a call's arguments into something that fits on one line: a collection or map becomes its size, a scalar is
     * cut to {@link #MAX_SCALAR}. Deliberately shape-agnostic — no per-tool knowledge, so a tool this codebase has
     * never called still prints something sane.
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

    /**
     * What a tool answered, in three words or fewer. Prefers the first list in the structured content — for this
     * server that is the products, the slots, the orders — then a field count, then the raw text.
     *
     * <p>{@code structuredContent} is whatever the server sent, so it is typed as loosely here as it is on
     * {@link com.silporestockai.client.mcp.McpToolResponse}: a shape this codebase has never seen must degrade to a
     * short line, never to a stack trace on the demo channel.
     */
    public static String summarizeResult(Object structuredContent, String text) {
        if (structuredContent instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Object value : map.values()) {
                if (value instanceof Collection<?> collection) {
                    return collection.size() + " items";
                }
            }
            return map.size() + " fields";
        }
        if (structuredContent instanceof Collection<?> collection) {
            return collection.size() + " items";
        }
        String flat = clean(text, MAX_RESULT);
        return flat.isEmpty() ? "ok" : flat;
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
