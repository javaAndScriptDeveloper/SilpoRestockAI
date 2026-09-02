package com.silporestockai.config;

import com.silporestockai.utils.SecretRedactor;
import feign.Logger;
import feign.slf4j.Slf4jLogger;
import java.util.Arrays;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Activates Feign client scanning. Client interfaces (see {@code com.silporestockai.client}) are wrapped in a
 * Resilience4j circuit breaker via {@code spring.cloud.openfeign.circuitbreaker.enabled} in {@code application.yml}.
 *
 * <p>Also turns on full request/response logging for every Feign client — the OAuth exchanges, the calendar insert,
 * the Respeecher call — at DEBUG, which {@code com.silporestockai: DEBUG} in {@code application.yml} already enables.
 * {@link Logger.Level#FULL} prints headers and bodies verbatim, which is exactly where an access token or an API key
 * would otherwise reach a log line unredacted: {@code TokenResponse}'s own {@code toString()} hides them from a line
 * this codebase writes itself, but not from one that prints the wire content directly.
 * {@link RedactingFeignLogger} is the one place that has to catch it instead.
 */
@Configuration
@EnableFeignClients(basePackages = "com.silporestockai.client")
public class FeignConfig {

    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.FULL;
    }

    @Bean
    public Logger feignLogger() {
        return new RedactingFeignLogger();
    }

    /**
     * Redacts every line before it reaches the real logger, rather than trying to intercept the request and response
     * objects separately. {@link Slf4jLogger} assembles the method line, every header and the body into formatted
     * strings and funnels all of them through this one method before anything is printed — headers, the request URL
     * and both bodies included — so it is the one place that has to catch a secret, not three.
     *
     * <p>Spring Cloud OpenFeign shares this one bean across every client, so a plain {@code Slf4jLogger} would log
     * everything under {@code feign.Logger} — outside {@code com.silporestockai}, and therefore below the {@code
     * root: INFO} threshold in {@code application.yml} regardless of the DEBUG this package asks for. Naming it
     * explicitly is what puts it back under a logger this application actually controls; each line's own
     * {@code configKey} prefix (e.g. {@code SilpoOAuthApiClient#token}) still says which client it came from.
     */
    static final class RedactingFeignLogger extends Slf4jLogger {

        RedactingFeignLogger() {
            super("com.silporestockai.client.FeignHttp");
        }

        @Override
        protected void log(String configKey, String format, Object... args) {
            Object[] redacted = Arrays.stream(args)
                    .map(arg -> arg instanceof String text ? SecretRedactor.redact(text) : arg)
                    .toArray();
            super.log(configKey, SecretRedactor.redact(format), redacted);
        }
    }
}
