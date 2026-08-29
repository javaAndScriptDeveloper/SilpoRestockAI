package com.silporestockai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal Anthropic Messages API over plain HTTP, enough to drive {@code ClaudeApiClientImpl} in tests.
 *
 * <p>Answers {@code POST /v1/messages} with a single text content block whose text is whatever
 * {@link #respondWithText(String)} was last given. Statuses can be injected one call at a time so a test can script
 * "429 then 200" and assert the retry happened, and every request body is recorded so a test can assert what was
 * actually sent.
 */
public final class StubAnthropicServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();
    private final Deque<Integer> injectedStatuses = new ArrayDeque<>();
    /** Texts for the next responses, in order. Falls back to {@link #respondWithText} when empty. */
    private final Deque<String> scriptedTexts = new ArrayDeque<>();

    private final AtomicInteger callCount = new AtomicInteger();
    private volatile String responseText = "stub completion";

    public StubAnthropicServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/messages", this::handle);
        this.server.start();
    }

    /** Base URL to hand to {@code claude.base-url}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Text the next responses carry in their single content block. */
    public void respondWithText(String text) {
        this.responseText = text;
    }

    /** Scripts the next responses in order, for tests that need a bad answer followed by a good one. */
    public synchronized void respondWithTexts(String... texts) {
        scriptedTexts.addAll(List.of(texts));
    }

    /** Makes the next call answer with {@code status} and an Anthropic-shaped error body. */
    public synchronized void injectStatus(int status) {
        injectedStatuses.add(status);
    }

    public synchronized List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    public int callCount() {
        return callCount.get();
    }

    public synchronized void reset() {
        requests.clear();
        injectedStatuses.clear();
        scriptedTexts.clear();
        callCount.set(0);
        responseText = "stub completion";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            callCount.incrementAndGet();
            byte[] rawBody = exchange.getRequestBody().readAllBytes();
            record(rawBody.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(rawBody));

            Integer injected = nextInjectedStatus();
            if (injected != null) {
                respond(exchange, injected, errorBody(injected));
                return;
            }
            respond(exchange, 200, successBody(nextText()));
        } finally {
            exchange.close();
        }
    }

    private synchronized void record(JsonNode body) {
        requests.add(body);
    }

    private synchronized Integer nextInjectedStatus() {
        return injectedStatuses.poll();
    }

    private synchronized String nextText() {
        String scripted = scriptedTexts.poll();
        return scripted == null ? responseText : scripted;
    }

    private static String successBody(String text) {
        return """
                {"id":"msg_stub","type":"message","role":"assistant","model":"claude-sonnet-5",\
                "content":[{"type":"text","text":%s}],"stop_reason":"end_turn","stop_sequence":null,\
                "usage":{"input_tokens":10,"output_tokens":20}}""".formatted(quote(text));
    }

    private static String errorBody(int status) {
        String type = status == 429 ? "rate_limit_error" : status == 401 ? "authentication_error" : "api_error";
        return """
                {"type":"error","error":{"type":"%s","message":"stubbed %d"}}""".formatted(type, status);
    }

    /** JSON-quotes a string, which is how arbitrary model output gets embedded in the canned response. */
    private static String quote(String raw) {
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (IOException e) {
            throw new IllegalStateException("could not quote the stub response text", e);
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
