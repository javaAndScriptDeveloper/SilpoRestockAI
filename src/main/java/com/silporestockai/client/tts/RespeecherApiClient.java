package com.silporestockai.client.tts;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Respeecher's Space API: one call, the non-streaming one.
 *
 * <p>SSE and WebSocket exist and are the right choice for a live voice agent. A chat reply is a few sentences that
 * are already written by the time we speak them, so the bytes endpoint is both simpler and sufficient.
 */
@FeignClient(name = "respeecher", url = "${respeecher.base-url}")
public interface RespeecherApiClient {

    /**
     * @param model {@code ua-rt} or {@code en-rt}; the model is part of the path
     * @return a WAV file, 16-bit LE PCM. {@code output_format} only controls the sample rate, so WAV is the only
     *     thing this API returns — which is why the Telegram side has to cope with it.
     */
    @PostMapping(value = "/v1/public/tts/{model}/tts/bytes", consumes = MediaType.APPLICATION_JSON_VALUE)
    byte[] synthesize(
            @RequestHeader("X-API-Key") String apiKey,
            @PathVariable("model") String model,
            @RequestBody SynthesisRequest request);

    /** @param transcript the text to narrate — at most about 5000 characters for this endpoint */
    record SynthesisRequest(String transcript, Voice voice) {

        public static SynthesisRequest of(String transcript, String voiceId) {
            return new SynthesisRequest(transcript, new Voice(voiceId));
        }
    }

    record Voice(String id) {}
}
