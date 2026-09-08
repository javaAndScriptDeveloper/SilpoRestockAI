package com.silporestockai.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The one landing page both OAuth callbacks render.
 *
 * <p>A single template rather than a text block per controller: the Silpo connect and the Google Calendar connect are
 * the same product, and two copies of the CSS would let them drift apart the first time one is touched. The template
 * is read once at class-init — it is a few kilobytes on the classpath, and a per-request read would buy nothing.
 *
 * <p>The brand colour is task 27's verified {@code #FF8200}; the failure state deliberately swaps it for a neutral so
 * that a page nobody reads carefully still cannot be mistaken for a success.
 */
public final class OAuthCallbackPage {

    private static final String TEMPLATE = load();

    private static final String SUCCESS_ACCENT = "var(--silpo-primary)";
    private static final String FAILURE_ACCENT = "var(--neutral)";

    private OAuthCallbackPage() {}

    /** A finished connection: brand-orange glyph, a checkmark. */
    public static String success(String title, String message) {
        return render(SUCCESS_ACCENT, "✓", title, message);
    }

    /** A connection that did not happen: neutral glyph, a cross, and honest copy from the caller. */
    public static String failure(String title, String message) {
        return render(FAILURE_ACCENT, "✕", title, message);
    }

    private static String render(String accent, String glyph, String title, String message) {
        return TEMPLATE.replace("{{accent}}", accent)
                .replace("{{glyph}}", glyph)
                .replace("{{title}}", escape(title))
                .replace("{{message}}", escape(message));
    }

    /**
     * Everything interpolated today is a constant of ours, which is exactly why this is cheap to add now: the day
     * somebody reflects a query parameter into the copy, the page must not become an injection point.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String load() {
        try (InputStream stream = OAuthCallbackPage.class.getResourceAsStream("/oauth/callback.html")) {
            if (stream == null) {
                throw new IllegalStateException("/oauth/callback.html is missing from the classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the OAuth callback template", e);
        }
    }
}
