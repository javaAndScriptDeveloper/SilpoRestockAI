package com.silporestockai.client.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.silporestockai.config.SttProperties;
import com.silporestockai.exception.SpeechToTextException;
import com.silporestockai.support.StubSttServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("voice notes reach an OpenAI-compatible transcription endpoint, or nothing at all")
class SpeechToTextClientTest {

    private static final byte[] AUDIO = "ogg-opus-bytes".getBytes(StandardCharsets.UTF_8);
    private static final StubSttServer STUB = start();

    private static StubSttServer start() {
        try {
            return new StubSttServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the STT stub", e);
        }
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
    }

    private static SpeechToTextClient configured() {
        return new SpeechToTextClientImpl(
                new SttProperties("stub-stt-key", STUB.endpoint(), "whisper-1", "uk", Duration.ofSeconds(10)));
    }

    private static SpeechToTextClient withoutKey() {
        return new SpeechToTextClientImpl(
                new SttProperties("", STUB.endpoint(), "whisper-1", "uk", Duration.ofSeconds(10)));
    }

    @Test
    void sendsTheAudioTheModelAndTheLanguageAsOneMultipartBody() {
        String transcript = configured().transcribe(AUDIO, "checkin.ogg");

        assertThat(transcript).isEqualTo("молоко ще є");
        String body = STUB.requestBodies().getFirst();
        assertThat(body)
                .contains("name=\"file\"; filename=\"checkin.ogg\"")
                .contains("ogg-opus-bytes")
                .contains("name=\"model\"")
                .contains("whisper-1")
                .contains("name=\"language\"")
                .contains("uk");
        assertThat(STUB.authorizationHeaders()).containsExactly("Bearer stub-stt-key");
    }

    @Test
    void aBlankKeyIsNotAStartupFailureButIsNotUsableEither() {
        SpeechToTextClient client = withoutKey();

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.transcribe(AUDIO, "checkin.ogg"))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("no speech-to-text API key");
        // Nothing left the process: an unconfigured client must not reach the network at all.
        assertThat(STUB.requestBodies()).isEmpty();
    }

    @Test
    void surfacesARefusalRatherThanReturningEmptyText() {
        STUB.respondWithStatus(401);

        assertThatThrownBy(() -> configured().transcribe(AUDIO, "checkin.ogg"))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("401");
    }
}
