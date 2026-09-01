package com.silporestockai.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableAsync} is here for {@code MealPlanHandoffService}: meal plan generation must not run on the Telegram
 * webhook thread, which Telegram expects to answer within seconds.
 *
 * <p>{@code @EnableScheduling} is here for {@code CheckinScheduler}, the one stage of the agent nobody triggers by
 * writing to it.
 */
@Configuration
@EnableAsync
@EnableScheduling
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
