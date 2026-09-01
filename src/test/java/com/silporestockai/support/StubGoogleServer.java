package com.silporestockai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Google's OAuth token endpoint and the one Calendar API call this application makes.
 *
 * <p>Every request is recorded so a test can assert what was actually sent — which grant was used, and what the
 * event said — rather than mocking the clients away.
 */
public final class StubGoogleServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<String> tokenRequests = new ArrayList<>();
    private final List<JsonNode> insertedEvents = new ArrayList<>();
    private final List<String> authorizationHeaders = new ArrayList<>();

    private volatile int eventsStatus = 200;

    public StubGoogleServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/token", this::handleToken);
        this.server.createContext("/calendars", this::handleEvents);
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String tokenEndpoint() {
        return baseUrl() + "/token";
    }

    /** Makes the events endpoint refuse, so a test can prove a confirmed order survives it. */
    public void failEvents(int status) {
        this.eventsStatus = status;
    }

    public synchronized List<String> tokenRequests() {
        return List.copyOf(tokenRequests);
    }

    public synchronized List<JsonNode> insertedEvents() {
        return List.copyOf(insertedEvents);
    }

    public synchronized List<String> authorizationHeaders() {
        return List.copyOf(authorizationHeaders);
    }

    public synchronized void reset() {
        tokenRequests.clear();
        insertedEvents.clear();
        authorizationHeaders.clear();
        eventsStatus = 200;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (this) {
                tokenRequests.add(body);
            }
            respond(exchange, 200, """
                    {"access_token":"stub-google-access","refresh_token":"stub-google-refresh",\
                    "token_type":"Bearer","expires_in":3600}""");
        } finally {
            exchange.close();
        }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (this) {
                authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                insertedEvents.add(MAPPER.readTree(body));
            }
            if (eventsStatus != 200) {
                exchange.sendResponseHeaders(eventsStatus, -1);
                return;
            }
            respond(exchange, 200, "{\"id\":\"evt-1\",\"htmlLink\":\"https://calendar.google.com/evt-1\"}");
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
