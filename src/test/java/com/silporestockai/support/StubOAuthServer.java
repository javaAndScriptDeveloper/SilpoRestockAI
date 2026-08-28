package com.silporestockai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** A stand-in for the Silpo authorization server's {@code /register} and {@code /token} endpoints. */
public final class StubOAuthServer implements AutoCloseable {

    public static final String ACCESS_TOKEN = "stub-issued-access-token";
    public static final String REFRESH_TOKEN = "stub-issued-refresh-token";
    public static final String CLIENT_ID = "stub-client-id";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private volatile Map<String, String> lastTokenForm = Map.of();

    public StubOAuthServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/token", this::handleToken);
        this.server.createContext("/register", this::handleRegister);
        this.server.start();
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The form fields of the most recent token request, so a test can assert PKCE and the resource indicator. */
    public Map<String, String> lastTokenForm() {
        return lastTokenForm;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        try {
            lastTokenForm = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    Map.of(
                            "access_token",
                            ACCESS_TOKEN,
                            "refresh_token",
                            REFRESH_TOKEN,
                            "token_type",
                            "Bearer",
                            "expires_in",
                            3600));
        } finally {
            exchange.close();
        }
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, Map.of("client_id", CLIENT_ID));
        } finally {
            exchange.close();
        }
    }

    private Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                form.put(
                        URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private void respond(HttpExchange exchange, Map<String, Object> payload) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
