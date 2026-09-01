package com.silporestockai.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal OpenAI-compatible {@code /v1/audio/transcriptions} endpoint, enough to drive
 * {@code SpeechToTextClientImpl} in tests.
 *
 * <p>Every request body is kept as a string so a test can assert the multipart actually carried the audio, the model
 * and the language, rather than trusting that it did.
 */
public final class StubSttServer implements AutoCloseable {

    private final HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> authorizationHeaders = new ArrayList<>();

    private volatile String transcript = "молоко ще є";
    private volatile int status = 200;

    public StubSttServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/audio/transcriptions", this::handle);
        this.server.start();
    }

    /** Full URL to hand to {@code stt.endpoint}. */
    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/audio/transcriptions";
    }

    public void respondWith(String transcript) {
        this.transcript = transcript;
    }

    /** Makes every following call answer with {@code status} instead of a transcript. */
    public void respondWithStatus(int status) {
        this.status = status;
    }

    public synchronized List<String> requestBodies() {
        return List.copyOf(requestBodies);
    }

    public synchronized List<String> authorizationHeaders() {
        return List.copyOf(authorizationHeaders);
    }

    public synchronized void reset() {
        requestBodies.clear();
        authorizationHeaders.clear();
        transcript = "молоко ще є";
        status = 200;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (this) {
                requestBodies.add(body);
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization != null) {
                    authorizationHeaders.add(authorization);
                }
            }
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            byte[] response = "{\"text\":\"%s\"}".formatted(transcript).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        } finally {
            exchange.close();
        }
    }
}
