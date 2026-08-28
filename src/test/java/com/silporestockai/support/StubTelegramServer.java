package com.silporestockai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal Telegram Bot API over plain HTTP, enough to drive {@code TelegramOutboundService} in tests:
 * {@code sendMessage}, {@code answerCallbackQuery}, {@code setWebhook}, {@code getFile} and the separate
 * {@code /file/bot<token>/<path>} download host the real API uses.
 *
 * <p>Every request body is recorded so a test can assert what was sent rather than mock the call away.
 */
public final class StubTelegramServer implements AutoCloseable {

    /** Bytes served for any voice-note download. */
    public static final byte[] VOICE_BYTES = "stub-ogg-voice-payload".getBytes(StandardCharsets.UTF_8);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One {@code multipart/form-data} part: its {@code name} and the body up to the next boundary. */
    private static final Pattern MULTIPART_PART =
            Pattern.compile("name=\"([^\"]+)\"(?:\r?\n[^\r\n]+)*\r?\n\r?\n(.*?)\r?\n--", Pattern.DOTALL);

    private final HttpServer server;
    private final String botToken;
    private final List<JsonNode> sentMessages = new ArrayList<>();
    private final List<JsonNode> callbackAnswers = new ArrayList<>();
    private final List<JsonNode> setWebhookCalls = new ArrayList<>();

    public StubTelegramServer(String botToken) throws IOException {
        this.botToken = botToken;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /** Base URL to hand to {@code telegram.api-url}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized List<JsonNode> sentMessages() {
        return List.copyOf(sentMessages);
    }

    public synchronized List<JsonNode> callbackAnswers() {
        return List.copyOf(callbackAnswers);
    }

    public synchronized List<JsonNode> setWebhookCalls() {
        return List.copyOf(setWebhookCalls);
    }

    public synchronized void reset() {
        sentMessages.clear();
        callbackAnswers.clear();
        setWebhookCalls.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/file/bot" + botToken + "/")) {
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, VOICE_BYTES.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(VOICE_BYTES);
                }
                return;
            }

            // The SDK lowercases Bot API method names in the URL; Telegram accepts either casing.
            String method = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            byte[] rawBody = exchange.getRequestBody().readAllBytes();
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            JsonNode body = parseBody(rawBody, contentType);
            record(method, body);
            respond(exchange, resultFor(method, body));
        } finally {
            exchange.close();
        }
    }

    /**
     * Most Bot API methods are sent as JSON, but the ones that can carry a file — {@code setWebhook} with its optional
     * certificate — are always sent as {@code multipart/form-data}. Both shapes are flattened to one JSON object so
     * assertions do not have to care which the SDK chose.
     */
    private static JsonNode parseBody(byte[] rawBody, String contentType) throws IOException {
        if (rawBody.length == 0) {
            return MAPPER.createObjectNode();
        }
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            return MAPPER.readTree(rawBody);
        }
        ObjectNode parsed = MAPPER.createObjectNode();
        Matcher matcher = MULTIPART_PART.matcher(new String(rawBody, StandardCharsets.UTF_8));
        while (matcher.find()) {
            parsed.put(matcher.group(1), matcher.group(2));
        }
        return parsed;
    }

    private synchronized void record(String method, JsonNode body) {
        switch (method) {
            case "sendmessage" -> sentMessages.add(body);
            case "answercallbackquery" -> callbackAnswers.add(body);
            case "setwebhook" -> setWebhookCalls.add(body);
            default -> {
                // getFile and anything else needs no recording.
            }
        }
    }

    private Object resultFor(String method, JsonNode body) {
        return switch (method) {
            case "sendmessage" ->
                Map.of(
                        "message_id",
                        1,
                        "date",
                        1,
                        "chat",
                        Map.of("id", body.path("chat_id").asLong(), "type", "private"));
            case "getfile" -> Map.of("file_id", body.path("file_id").asText(), "file_path", "voice/stub.ogg");
            default -> Boolean.TRUE;
        };
    }

    private void respond(HttpExchange exchange, Object result) throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(Map.of("ok", true, "result", result));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
