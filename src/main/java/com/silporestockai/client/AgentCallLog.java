package com.silporestockai.client;

import com.silporestockai.utils.SecretRedactor;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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

    /**
     * A hard ceiling on the arguments column. Live calls carry as many as five arguments — an offline-orders read
     * sends a branch id, a delivery type and both ends of a slot — and spelling all of them out pushed the duration
     * and the status off a 120-column terminal, which is the one thing this format exists to prevent. What does not
     * fit becomes «+N more»: the first arguments are the ones that say what the call was about.
     */
    private static final int ARGS_MAX = 46;

    private static final String LINE = "%s %-" + TOOL_WIDTH + "s %-" + ARGS_WIDTH + "s %7s  %s";

    /** A cart or order id is 36 characters of nothing anyone can read off a screen; its first block identifies it. */
    private static final Pattern UUID_ARGUMENT = Pattern.compile(
            "^([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);

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
        // Collections first, then scalars, alphabetically inside each group. Live, this is the difference between
        // «branchId=1edddb40… deliveryType=DeliveryHome +3 more» and «queries=16 items +3 more» for the same call:
        // what the agent asked for is the interesting half, and the branch id it asks every tool for is not.
        List<String> pairs = arguments.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .sorted(Comparator.comparing((Map.Entry<String, Object> entry) -> isPlural(entry.getValue()) ? 0 : 1)
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + "=" + summarizeValue(entry.getValue()))
                .toList();

        StringBuilder shown = new StringBuilder();
        int dropped = 0;
        for (String pair : pairs) {
            if (dropped > 0 || shown.length() + pair.length() + 1 > ARGS_MAX) {
                dropped++;
                continue;
            }
            if (!shown.isEmpty()) {
                shown.append(' ');
            }
            shown.append(pair);
        }
        if (dropped > 0) {
            // Never silently: an argument that is not on the line has to be visibly missing, or the line lies.
            shown.append(shown.isEmpty() ? "" : " ").append("+").append(dropped).append(" more");
        }
        return shown.toString();
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
                    return count(collection.size(), "item");
                }
            }
            return count(map.size(), "field");
        }
        if (structuredContent instanceof Collection<?> collection) {
            return count(collection.size(), "item");
        }
        String flat = clean(text, MAX_RESULT);
        return flat.isEmpty() ? "ok" : flat;
    }

    /** True for the arguments worth leading with: the ones that carry how much the agent asked for. */
    private static boolean isPlural(Object value) {
        return value instanceof Collection<?> || value instanceof Map<?, ?> || value instanceof Object[];
    }

    private static String summarizeValue(Object value) {
        if (value instanceof Collection<?> collection) {
            return count(collection.size(), "item");
        }
        if (value instanceof Map<?, ?> map) {
            return count(map.size(), "field");
        }
        if (value instanceof Object[] array) {
            return count(array.length, "item");
        }
        String scalar = String.valueOf(value);
        var uuid = UUID_ARGUMENT.matcher(scalar);
        return uuid.matches() ? uuid.group(1) + "…" : clean(scalar, MAX_SCALAR);
    }

    /** Redacts, flattens to one line and caps — in that order, so a secret cannot survive by being long. */
    private static String clean(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = SecretRedactor.redact(text).replaceAll("\\s+", " ").trim();
        return flat.length() <= maxLength ? flat : flat.substring(0, maxLength - 1) + "…";
    }

    /** «1 item», not «1 items» — the line is read by people, and a plural that is wrong is the kind of thing a
     * viewer's eye catches instead of the tool name. */
    private static String count(int size, String noun) {
        return size + " " + noun + (size == 1 ? "" : "s");
    }

    private static String duration(long millis) {
        return millis < 1000 ? millis + "ms" : String.format("%.1fs", millis / 1000.0);
    }
}
