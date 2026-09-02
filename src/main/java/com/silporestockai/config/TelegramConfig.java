package com.silporestockai.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@link TelegramProperties} from {@code application.yml}. */
@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {

    /**
     * Recently seen Telegram {@code update_id}s, so a redelivered update is a no-op rather than a full reprocess.
     *
     * <p>A bean rather than a field the controller builds itself, specifically so a test can reach in and clear it:
     * {@code update_id} is unique and strictly increasing forever for a real bot, but this application's own test
     * fixtures reuse small ids like {@code 1, 2, 3} for readability across many test methods that share one Spring
     * context — entirely reasonable in a test, and exactly what a JVM-lifetime singleton cache would otherwise read
     * as a flood of redeliveries.
     */
    @Bean
    public Cache<Integer, Boolean> telegramUpdateDedupCache() {
        return Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(Duration.ofMinutes(15))
                .build();
    }
}
