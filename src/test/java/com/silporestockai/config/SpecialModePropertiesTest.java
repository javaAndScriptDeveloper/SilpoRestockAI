package com.silporestockai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SpecialModePropertiesTest {

    @Autowired
    private SpecialModeProperties properties;

    @Test
    void bindsFromApplicationYmlDefaults() {
        assertThat(properties.gastritisAcuteDuration()).isEqualTo(Duration.ofDays(3));
        assertThat(properties.gastritisDiet5Duration()).isEqualTo(Duration.ofDays(11));
        assertThat(properties.sweepCron()).isEqualTo("0 0 * * * *");
    }
}
