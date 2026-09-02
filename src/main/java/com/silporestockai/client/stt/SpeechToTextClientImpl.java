package com.silporestockai.client.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.config.SttProperties;
import com.silporestockai.exception.SpeechToTextException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Transcription against an OpenAI-compatible {@code /v1/audio/transcriptions} endpoint.
 *
 * <p>Not a Feign client, unlike every other outbound HTTP call here. The endpoint takes
 * {@code multipart/form-data}, which Feign cannot express without an extra form-encoder dependency, and one
 * hand-built multipart body is a smaller thing to own than that dependency. The MCP transport is the other
 * deliberate exception, for the same kind of reason.
 *
 * <p>A blank key is not a startup failure: {@link #isConfigured()} answers false and the check-in flow asks the user
 * to type instead. The key is sent as a bearer token and never logged.
 */
@Slf4j
@Component
public class SpeechToTextClientImpl implements SpeechToTextClient {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final SttProperties properties;
    private final HttpClient http;

    public SpeechToTextClientImpl(SttProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newHttpClient();
        if (!properties.apiKeyConfigured()) {
            log.warn("STT_API_KEY is not set — voice check-ins will ask the user to type instead");
        }
    }

    @Override
    public boolean isConfigured() {
        return properties.apiKeyConfigured();
    }

    @Override
    public String transcribe(byte[] audio, String filename) {
        if (!isConfigured()) {
            throw new SpeechToTextException("no speech-to-text API key is configured");
        }
        String boundary = "komora-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.endpoint()))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(properties.timeout())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body(boundary, audio, filename)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // The body can carry the provider's error text but never the key, which lives only in the header.
                throw new SpeechToTextException(
                        "transcription answered %d: %s".formatted(response.statusCode(), response.body()));
            }
            JsonNode text = MAPPER.readTree(response.body()).path("text");
            if (text.isMissingNode() || text.asText().isBlank()) {
                throw new SpeechToTextException("transcription came back without any text");
            }
            log.info(
                    "transcribed {} bytes of audio into {} characters",
                    audio.length,
                    text.asText().length());
            return text.asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechToTextException("interrupted while transcribing a voice note", e);
        } catch (IOException e) {
            throw new SpeechToTextException("could not reach the transcription service", e);
        }
    }

    /** One file part and two text parts, in the order every OpenAI-compatible implementation accepts. */
    private byte[] body(String boundary, byte[] audio, String filename) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(audio);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            writeField(out, boundary, "model", properties.model());
            writeField(out, boundary, "language", properties.language());
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SpeechToTextException("could not assemble the transcription request", e);
        }
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
