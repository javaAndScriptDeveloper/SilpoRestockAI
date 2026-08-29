package com.silporestockai.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @EnableAsync} is here for {@code MealPlanHandoffService}: meal plan generation must not run on the Telegram
 * webhook thread, which Telegram expects to answer within seconds.
 */
@Configuration
@EnableAsync
public class BaseConfig {

    /**
     * The clock every date-deriving service reads. Kyiv, not the JVM default: a plan's week has to start on the
     * household's Monday, not the server's.
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Europe/Kyiv"));
    }
}
