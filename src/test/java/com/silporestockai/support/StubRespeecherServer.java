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
 * Respeecher's Space API bytes endpoint, enough to drive {@code RespeecherTtsClient} in tests.
 *
 * <p>Answers with recognisable fake WAV bytes and records every request, so a test can assert what the bot actually
 * asked to have spoken — which is the interesting half, since the text is rewritten before it gets here.
 */
public final class StubRespeecherServer implements AutoCloseable {

    /** What the stub "synthesises". Recognisable on the other end of Telegram. */
    public static final byte[] AUDIO = "stub-wav-audio-payload".getBytes(StandardCharsets.UTF_8);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> apiKeys = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();

    private volatile int status = 200;

    public StubRespeecherServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/public/tts", this::handle);
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Makes synthesis refuse, so a test can prove the written message still stands on its own. */
    public void respondWithStatus(int status) {
        this.status = status;
    }

    /** The transcripts the bot asked to have spoken, in order. */
    public synchronized List<String> spokenTranscripts() {
        return requests.stream().map(node -> node.path("transcript").asText()).toList();
    }

    public synchronized List<String> apiKeys() {
        return List.copyOf(apiKeys);
    }

    /** Request paths, which carry the model — {@code /v1/public/tts/ua-rt/tts/bytes}. */
    public synchronized List<String> paths() {
        return List.copyOf(paths);
    }

    public synchronized void reset() {
        requests.clear();
        apiKeys.clear();
        paths.clear();
        status = 200;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (this) {
                requests.add(MAPPER.readTree(body));
                apiKeys.add(exchange.getRequestHeaders().getFirst("X-API-Key"));
                paths.add(exchange.getRequestURI().getPath());
            }
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, AUDIO.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(AUDIO);
            }
        } finally {
            exchange.close();
        }
    }
}
