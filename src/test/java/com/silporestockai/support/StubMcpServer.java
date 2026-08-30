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
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal MCP server over Streamable HTTP, enough to drive {@code SilpoMcpClientImpl} in tests: the
 * {@code initialize} handshake with an {@code Mcp-Session-Id}, {@code notifications/initialized}, {@code tools/list}
 * and {@code tools/call}. A {@code GET} is answered with {@code 405}, which is what the real transport expects when a
 * server does not offer a standalone SSE stream.
 *
 * <p>Failure injection is per JSON-RPC method, so a test can make exactly {@code tools/call} answer {@code 429} or
 * {@code 401} once without disturbing the handshake that precedes it.
 */
public final class StubMcpServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final Map<String, Deque<Integer>> injectedStatuses = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();
    /** Canned JSON per tool name, returned as that tool's single text block. */
    private final Map<String, String> toolResponses = new ConcurrentHashMap<>();

    /** Names of the tools called, in order — this is what a sequence test asserts on. */
    private final List<String> calledTools = Collections.synchronizedList(new ArrayList<>());

    private final List<String> seenAuthorizationHeaders = new ArrayList<>();
    private final List<String> seenCookieHeaders = new ArrayList<>();
    private final List<String> toolNames;

    public StubMcpServer(List<String> toolNames) throws IOException {
        this.toolNames = List.copyOf(toolNames);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/mcp", this::handle);
        this.server.start();
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    /** Makes the next call to {@code method} answer with {@code status} instead of a result. */
    public void injectStatus(String method, int status) {
        injectedStatuses.computeIfAbsent(method, key -> new ArrayDeque<>()).add(status);
    }

    /** Makes {@code toolName} answer with {@code json}. A tool with no scripted answer keeps the old canned text. */
    public void respondToTool(String toolName, String json) {
        toolResponses.put(toolName, json);
    }

    /** Every {@code tools/call} the server saw, in order. */
    public List<String> calledTools() {
        return List.copyOf(calledTools);
    }

    /** Clears counters, injected statuses, scripted responses and recorded headers between tests. */
    public synchronized void reset() {
        injectedStatuses.clear();
        callCounts.clear();
        toolResponses.clear();
        calledTools.clear();
        seenAuthorizationHeaders.clear();
        seenCookieHeaders.clear();
    }

    public int callCount(String method) {
        return callCounts.getOrDefault(method, new AtomicInteger()).get();
    }

    public synchronized List<String> seenAuthorizationHeaders() {
        return List.copyOf(seenAuthorizationHeaders);
    }

    public synchronized List<String> seenCookieHeaders() {
        return List.copyOf(seenCookieHeaders);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                // The transport opens a background GET stream after the handshake; 405 tells it there is none.
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            recordHeaders(exchange);

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = MAPPER.readTree(body);
            String method = request.path("method").asText();
            callCounts.computeIfAbsent(method, key -> new AtomicInteger()).incrementAndGet();
            if ("tools/call".equals(method)) {
                calledTools.add(request.path("params").path("name").asText());
            }

            Deque<Integer> statuses = injectedStatuses.get(method);
            if (statuses != null && !statuses.isEmpty()) {
                exchange.sendResponseHeaders(statuses.poll(), -1);
                return;
            }

            if (method.startsWith("notifications/")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }

            respond(exchange, request.path("id"), resultFor(method, request));
        } finally {
            exchange.close();
        }
    }

    private synchronized void recordHeaders(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null) {
            seenAuthorizationHeaders.add(authorization);
        }
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            seenCookieHeaders.add(cookie);
        }
    }

    private Object resultFor(String method, JsonNode request) {
        return switch (method) {
            case "initialize" ->
                Map.of(
                        "protocolVersion",
                        "2025-06-18",
                        "capabilities",
                        Map.of("tools", Map.of("listChanged", false)),
                        "serverInfo",
                        Map.of("name", "stub-silpo-mcp", "version", "1.0"));
            case "tools/list" ->
                Map.of(
                        "tools",
                        toolNames.stream()
                                .map(name -> Map.of(
                                        "name",
                                        name,
                                        "description",
                                        name + " (stub)",
                                        "inputSchema",
                                        Map.of("type", "object", "properties", Map.of())))
                                .toList());
            case "tools/call" -> {
                String tool = request.path("params").path("name").asText();
                String json = toolResponses.getOrDefault(tool, "stub tool result");
                yield Map.of("content", List.of(Map.of("type", "text", "text", json)), "isError", false);
            }
            case "ping" -> Map.of();
            default -> Map.of();
        };
    }

    private void respond(HttpExchange exchange, JsonNode id, Object result) throws IOException {
        Map<String, Object> envelope =
                Map.of("jsonrpc", "2.0", "id", id.isNumber() ? id.asLong() : id.asText(), "result", result);
        byte[] payload = MAPPER.writeValueAsBytes(envelope);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Mcp-Session-Id", "stub-session");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
