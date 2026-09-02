package com.silporestockai.integration;

import com.github.benmanes.caffeine.cache.Cache;
import com.silporestockai.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests. Boots the full Spring context against a real PostgreSQL started by
 * Testcontainers and configures {@code MockMvc} for driving the web layer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    private Cache<Integer, Boolean> telegramUpdateDedupCache;

    /**
     * {@code TelegramWebhookController}'s redelivery guard is a JVM-lifetime singleton by design — real Telegram
     * {@code update_id}s never repeat, so that is exactly right in production. This test suite's own fixtures reuse
     * small ids like {@code 1, 2, 3} across many test methods sharing one Spring context, which the cache would
     * otherwise read as a flood of redeliveries. Clearing it here, once, is what keeps every test class free to keep
     * doing that without knowing this guard exists.
     */
    @BeforeEach
    void resetTelegramUpdateDedupCache() {
        telegramUpdateDedupCache.invalidateAll();
    }
}
