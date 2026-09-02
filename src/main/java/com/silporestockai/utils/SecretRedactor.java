package com.silporestockai.utils;

import java.util.regex.Pattern;

/**
 * Scrubs secrets out of text before it reaches a log line.
 *
 * <p>Exists because turning on full request/response logging (Feign's {@code Logger.Level.FULL}, in particular) means
 * printing the literal wire content of every call — headers and bodies alike. That is exactly where an OAuth token
 * exchange, a bearer header or an API key would otherwise leak: {@code TokenResponse.toString()} hides them from a
 * log line we write ourselves, but it cannot hide from a logger that prints the raw HTTP frame instead of our object.
 *
 * <p>Pattern-based rather than field-by-field on purpose: it has to catch a secret in a header line, a JSON body and
 * a form-encoded body without knowing which shape it is looking at, and it is safer to over-redact a false positive
 * than to miss a real one.
 */
public final class SecretRedactor {

    private static final String MASK = "***";

    /** One thing to find, and how to replace exactly what was found while keeping the surrounding text readable. */
    private record Rule(Pattern pattern, String replacement) {
        /**
         * Not anchored to the start of a line: Feign prints one header per line, but the inbound request filter
         * joins every header into a single {@code "name: value; "} line, and a header name is never mid-word
         * anywhere else in these logs, so a plain match is both correct and shape-independent.
         */
        static Rule header(String name) {
            return new Rule(Pattern.compile("(?i)\\b(" + Pattern.quote(name) + ":\\s*)\\S+"), "$1" + MASK);
        }

        static Rule bearerHeader() {
            return new Rule(Pattern.compile("(?i)(Authorization:\\s*Bearer\\s+)\\S+"), "$1" + MASK);
        }

        /** {@code "field": "value"} — a JSON body. */
        static Rule jsonField(String name) {
            return new Rule(
                    Pattern.compile("(?i)(\"" + Pattern.quote(name) + "\"\\s*:\\s*\")[^\"]*(\")"), "$1" + MASK + "$2");
        }

        /** {@code field=value} — a form-encoded body, value ending at the next {@code &} or the end of the string. */
        static Rule formField(String name) {
            return new Rule(Pattern.compile("(?i)\\b(" + Pattern.quote(name) + "=)[^&\\s]*"), "$1" + MASK);
        }
    }

    /**
     * Every secret shape this application's outbound and inbound HTTP traffic can carry: bearer tokens, the
     * Respeecher and Telegram headers, and the OAuth exchange fields for both Silpo and Google — in both the JSON and
     * form-encoded bodies they can appear in.
     */
    private static final Rule[] RULES = {
        Rule.bearerHeader(),
        Rule.header("X-API-Key"),
        Rule.header("X-Telegram-Bot-Api-Secret-Token"),
        Rule.jsonField("access_token"),
        Rule.jsonField("refresh_token"),
        Rule.jsonField("client_secret"),
        Rule.jsonField("code_verifier"),
        Rule.jsonField("code"),
        Rule.formField("access_token"),
        Rule.formField("refresh_token"),
        Rule.formField("client_secret"),
        Rule.formField("code_verifier"),
        Rule.formField("code"),
    };

    private SecretRedactor() {}

    /** Redacts every recognised secret in {@code text}. Safe to call on text with none — it is returned unchanged. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = text;
        for (Rule rule : RULES) {
            redacted = rule.pattern().matcher(redacted).replaceAll(rule.replacement());
        }
        return redacted;
    }

    /** Keeps a log line readable when a body is unexpectedly large. */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…(truncated)";
    }
}
