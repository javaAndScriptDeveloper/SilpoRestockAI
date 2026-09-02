package com.silporestockai.config;

import com.silporestockai.utils.SecretRedactor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs every request this application receives from outside — the Telegram webhook, the two OAuth callbacks — with
 * its full body, at DEBUG.
 *
 * <p>Symmetric with the outbound side: {@code FeignConfig} turns on full logging for everything this application
 * calls, and this is the equivalent for everything that calls it. The one thing they share is the reason it exists
 * at all — every incident so far ("what did Silpo actually send", "what did the model actually say") came down to
 * not having the raw wire content in front of us, and the fix was always to start logging it.
 *
 * <p>Redaction matters more here than outbound: Telegram sends its shared secret as a request header, and an OAuth
 * callback's query string carries the authorization code. {@link SecretRedactor} strips both before either reaches a
 * log line. Health checks and API docs are excluded — they carry nothing worth a full body dump and would only add
 * noise to a channel meant for the handful of endpoints that actually matter.
 */
@Slf4j
@Component
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_LENGTH = 4000;

    /** What the wrapper itself is allowed to buffer. Telegram updates and OAuth callbacks are JSON metadata, never
     * the megabytes a photo's actual bytes would be — Telegram sends those as a {@code file_id} to fetch separately. */
    private static final int MAX_CACHED_BODY_BYTES = 65536;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!log.isDebugEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, MAX_CACHED_BODY_BYTES);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        long startedAt = System.currentTimeMillis();
        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.debug(
                    "HTTP -> {} {} headers={} body={}",
                    request.getMethod(),
                    SecretRedactor.redact(requestUriWithQuery(request)),
                    redactedHeaders(wrappedRequest),
                    SecretRedactor.truncate(bodyOf(wrappedRequest.getContentAsByteArray()), MAX_BODY_LENGTH));
            log.debug(
                    "HTTP <- {} {} ({} ms) body={}",
                    request.getMethod(),
                    wrappedResponse.getStatus(),
                    elapsedMs,
                    SecretRedactor.truncate(bodyOf(wrappedResponse.getContentAsByteArray()), MAX_BODY_LENGTH));
            // ContentCachingResponseWrapper buffers the body instead of writing it; this is what actually sends it.
            wrappedResponse.copyBodyToResponse();
        }
    }

    private static String requestUriWithQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private static String redactedHeaders(HttpServletRequest request) {
        StringBuilder headers = new StringBuilder();
        request.getHeaderNames()
                .asIterator()
                .forEachRemaining(name -> headers.append(name)
                        .append(": ")
                        .append(request.getHeader(name))
                        .append("; "));
        return SecretRedactor.redact(headers.toString());
    }

    private static String bodyOf(byte[] content) {
        return content.length == 0 ? "" : new String(content, StandardCharsets.UTF_8);
    }
}
