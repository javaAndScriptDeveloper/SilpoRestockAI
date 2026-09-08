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
import java.util.concurrent.atomic.AtomicInteger;
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
    /** Bodies of {@code sendAudio} and {@code sendDocument} calls — how a spoken reply leaves the application. */
    private final List<String> sentAudio = new ArrayList<>();

    private boolean rejectCallbackAnswers;
    private final AtomicInteger nextMessageId = new AtomicInteger();

    /** What {@code getMe} answers as the bot's username when a test does not configure one. */
    public static final String GET_ME_USERNAME = "stub_bot";

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

    /** The raw multipart bodies of any audio the bot sent; the payload is visible inside each one. */
    public synchronized List<String> sentAudio() {
        return List.copyOf(sentAudio);
    }

    /**
     * Makes {@code answerCallbackQuery} answer the way Telegram really does for a tap it has stopped waiting on:
     * {@code [400] query is too old and response timeout expired or query ID is invalid}. Callback queries expire
     * in about a minute, so this is the normal fate of any tap redelivered across a restart or a slow reply.
     */
    public synchronized void rejectCallbackAnswers() {
        this.rejectCallbackAnswers = true;
    }

    public synchronized void reset() {
        sentMessages.clear();
        callbackAnswers.clear();
        setWebhookCalls.clear();
        sentAudio.clear();
        rejectCallbackAnswers = false;
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
            if (method.equals("sendaudio") || method.equals("senddocument")) {
                // Audio is multipart with binary content; keep the raw body so a test can look for its payload.
                synchronized (this) {
                    sentAudio.add(new String(rawBody, StandardCharsets.UTF_8));
                }
            }
            record(method, body);
            if (method.equals("answercallbackquery") && rejectsCallbackAnswers()) {
                respondWithError(
                        exchange, "Bad Request: query is too old and response timeout expired or query ID is invalid");
                return;
            }
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
            // sendAudio and sendDocument answer with a Message too; without one the SDK treats the call as failed
            // and the caller's fallback fires, which would look like a bug in the caller rather than in the stub.
            // Ids count up like the real API's: a group round (task 68) tells a reply to its greeting from a reply
            // to its proposal by exactly this number.
            case "sendmessage", "sendaudio", "senddocument" ->
                Map.of(
                        "message_id",
                        nextMessageId.incrementAndGet(),
                        "date",
                        1,
                        "chat",
                        Map.of("id", body.path("chat_id").asLong(), "type", "private"));
            case "getfile" -> Map.of("file_id", body.path("file_id").asText(), "file_path", "voice/stub.ogg");
            case "getme" -> Map.of("id", botId(), "is_bot", true, "first_name", "Stub", "username", GET_ME_USERNAME);
            default -> Boolean.TRUE;
        };
    }

    /** The id of the last message this stub handed out — what the next reply-to-bot fixture should point at. */
    public int lastMessageId() {
        return nextMessageId.get();
    }

    private long botId() {
        int colon = botToken.indexOf(':');
        return colon <= 0 ? 0L : Long.parseLong(botToken.substring(0, colon));
    }

    private synchronized boolean rejectsCallbackAnswers() {
        return rejectCallbackAnswers;
    }

    private void respondWithError(HttpExchange exchange, String description) throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(Map.of("ok", false, "error_code", 400, "description", description));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(400, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
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
